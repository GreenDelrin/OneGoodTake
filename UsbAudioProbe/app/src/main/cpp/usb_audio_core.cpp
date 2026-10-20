#include "usb_audio_core.h"

#include <android/log.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#define TAG "UsbAudio"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace uac {

static std::string fmt(const char* f, ...) {
    char b[512];
    va_list ap;
    va_start(ap, f);
    vsnprintf(b, sizeof b, f, ap);
    va_end(ap);
    return b;
}

bool parse(const uint8_t* d, size_t n, ParsedDesc& out, std::string& log) {
    int curClass = -1, curSub = -1, curProto = 0;
    AltInfo cur;
    bool haveGeneral = false, haveFmt = false;
    size_t i = 0;
    while (i + 2 <= n) {
        uint8_t len = d[i], type = d[i + 1];
        if (len < 2 || i + len > n) break;
        const uint8_t* p = d + i;
        if (type == 4 && len >= 9) {  // interface descriptor
            cur = AltInfo();
            cur.iface = p[2];
            cur.alt = p[3];
            curClass = p[5];
            curSub = p[6];
            curProto = p[7];
            haveGeneral = haveFmt = false;
            if (curClass == 1 && curSub == 1) out.acIface = p[2];
            if (curClass == 1 && curSub == 2 && curProto != 0x20 && p[3] == 0)
                log += fmt("NOTE: interface %d is audio streaming with protocol 0x%02x (not UAC2)\n", p[2], curProto);
        } else if (type == 0x24 && curClass == 1) {  // class-specific
            uint8_t sub = p[2];
            if (curSub == 1 && sub == 0x0A && len >= 8) {  // clock source
                log += fmt("clock source id=%d attrs=0x%02x\n", p[3], p[4]);
                if (out.clockId < 0 && (p[4] & 3) != 0) out.clockId = p[3];
            } else if (curSub == 2 && curProto == 0x20) {
                if (sub == 1 && len >= 16) {
                    cur.channels = p[10];
                    haveGeneral = true;
                } else if (sub == 2 && len >= 6 && p[3] == 1) {
                    cur.subslot = p[4];
                    cur.bits = p[5];
                    haveFmt = true;
                }
            }
        } else if (type == 5 && curClass == 1 && curSub == 2 && len >= 7) {  // endpoint
            uint8_t addr = p[2], attr = p[3];
            int usage = (attr >> 4) & 3;
            if ((attr & 3) == 1 && (addr & 0x80) && haveGeneral && haveFmt && usage != 1) {
                int mp = p[4] | (p[5] << 8);
                cur.ep = addr;
                cur.maxPacket = (mp & 0x7ff) * (1 + ((mp >> 11) & 3));
                cur.interval = p[6];
                cur.usage = usage;
                out.alts.push_back(cur);
            }
        }
        i += len;
    }
    return true;
}

const AltInfo* chooseAlt(const ParsedDesc& pd) {
    const AltInfo* best = nullptr;
    for (const auto& a : pd.alts) {
        if (a.subslot < 2 || a.subslot > 4 || a.maxPacket <= 0) continue;
        if (!best || a.channels > best->channels ||
            (a.channels == best->channels && a.bits > best->bits))
            best = &a;
    }
    return best;
}

static inline float toFloat(const uint8_t* p, int subslot) {
    switch (subslot) {
        case 2: return (int16_t)(p[0] | (p[1] << 8)) / 32768.0f;
        case 3: {
            uint32_t u = (uint32_t)p[0] << 8 | (uint32_t)p[1] << 16 | (uint32_t)p[2] << 24;
            return (int32_t)u / 2147483648.0f;
        }
        case 4: {
            uint32_t u = (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24;
            return (int32_t)u / 2147483648.0f;
        }
    }
    return 0.f;
}

static int controlXfer(int fd, uint8_t rt, uint8_t req, uint16_t val, uint16_t idx, void* data, uint16_t len) {
    usbdevfs_ctrltransfer ct{};
    ct.bRequestType = rt;
    ct.bRequest = req;
    ct.wValue = val;
    ct.wIndex = idx;
    ct.wLength = len;
    ct.timeout = 1000;
    ct.data = data;
    return ioctl(fd, USBDEVFS_CONTROL, &ct);
}

std::string Capture::start(int fd, const uint8_t* desc, size_t len, int sampleRate) {
    stop();
    fd_ = fd;
    ParsedDesc pd;
    std::string log;
    parse(desc, len, pd, log);
    for (auto& a : pd.alts)
        log += fmt("capture alt: iface=%d alt=%d ch=%d subslot=%d bits=%d ep=0x%02x maxPkt=%d interval=%d usage=%d\n",
                   a.iface, a.alt, a.channels, a.subslot, a.bits, a.ep, a.maxPacket, a.interval, a.usage);
    const AltInfo* a = chooseAlt(pd);
    if (!a) return "ERROR no UAC2 capture alt setting found (device may be UAC1, vendor mode, or not in class-compliant mode)\n" + log;
    alt_ = *a;
    nch_ = alt_.channels;
    rate_ = sampleRate;
    frameBytes_ = nch_ * alt_.subslot;

    usbdevfs_setinterface si{(unsigned)alt_.iface, (unsigned)alt_.alt};
    if (ioctl(fd_, USBDEVFS_SETINTERFACE, &si) < 0)
        return fmt("ERROR SETINTERFACE failed errno=%d (%s) - was the interface claimed?\n", errno, strerror(errno)) + log;

    if (pd.clockId >= 0 && pd.acIface >= 0) {
        uint32_t hz = (uint32_t)sampleRate;
        uint8_t le[4] = {(uint8_t)hz, (uint8_t)(hz >> 8), (uint8_t)(hz >> 16), (uint8_t)(hz >> 24)};
        uint16_t idx = (uint16_t)((pd.clockId << 8) | pd.acIface);
        int r = controlXfer(fd_, 0x21, 0x01, 0x0100, idx, le, 4);
        log += fmt("set sample rate %d -> %s\n", sampleRate, r < 0 ? strerror(errno) : "ok");
        uint8_t got[4] = {0};
        if (controlXfer(fd_, 0xA1, 0x01, 0x0100, idx, got, 4) == 4) {
            uint32_t g = got[0] | got[1] << 8 | got[2] << 16 | (uint32_t)got[3] << 24;
            log += fmt("device reports rate %u Hz\n", g);
        }
    } else {
        log += "WARNING: no clock source found, sample rate not set\n";
    }

    cap_ = (uint64_t)rate_ * 4 * nch_;  // 4 seconds of buffer
    ring_.assign(cap_, 0.f);
    wpos_ = rpos_ = 0;
    peaks_.assign(nch_, 0.f);
    frames_ = overruns_ = errors_ = 0;

    const int nUrbs = 8;
    size_t urbSize = sizeof(usbdevfs_urb) + pktsPerUrb_ * sizeof(usbdevfs_iso_packet_desc);
    for (int k = 0; k < nUrbs; k++) {
        auto* u = (usbdevfs_urb*)calloc(1, urbSize);
        auto* b = (uint8_t*)calloc(1, (size_t)pktsPerUrb_ * alt_.maxPacket);
        u->type = USBDEVFS_URB_TYPE_ISO;
        u->endpoint = (unsigned char)alt_.ep;
        u->flags = USBDEVFS_URB_ISO_ASAP;
        u->buffer = b;
        u->buffer_length = pktsPerUrb_ * alt_.maxPacket;
        u->number_of_packets = pktsPerUrb_;
        u->usercontext = u;
        urbs_.push_back(u);
        bufs_.push_back(b);
        if (!submit(u)) {
            std::string e = fmt("ERROR SUBMITURB failed errno=%d (%s)\n", errno, strerror(errno));
            stop();
            return e + log;
        }
    }
    running_ = true;
    th_ = std::thread(&Capture::run, this);
    return fmt("OK streaming %d ch, %d-bit (subslot %d), %d Hz\n", nch_, alt_.bits, alt_.subslot, rate_) + log;
}

bool Capture::submit(usbdevfs_urb* u) {
    for (int i = 0; i < pktsPerUrb_; i++) {
        u->iso_frame_desc[i].length = alt_.maxPacket;
        u->iso_frame_desc[i].actual_length = 0;
        u->iso_frame_desc[i].status = 0;
    }
    u->error_count = 0;
    u->status = 0;
    return ioctl(fd_, USBDEVFS_SUBMITURB, u) == 0;
}

void Capture::handleUrb(usbdevfs_urb* u) {
    conv_.clear();
    std::vector<float> lp(nch_, 0.f);
    const uint8_t* base = (const uint8_t*)u->buffer;
    for (int i = 0; i < u->number_of_packets; i++) {
        if (u->iso_frame_desc[i].status != 0) { errors_++; continue; }
        unsigned act = u->iso_frame_desc[i].actual_length;
        const uint8_t* p = base + (size_t)i * alt_.maxPacket;
        unsigned frames = act / frameBytes_;
        for (unsigned f = 0; f < frames; f++) {
            for (int c = 0; c < nch_; c++) {
                float v = toFloat(p, alt_.subslot);
                p += alt_.subslot;
                conv_.push_back(v);
                float av = std::fabs(v);
                if (av > lp[c]) lp[c] = av;
            }
        }
        frames_ += frames;
    }
    {
        std::lock_guard<std::mutex> g(peakMu_);
        for (int c = 0; c < nch_; c++) peaks_[c] = std::max(peaks_[c], lp[c]);
    }
    uint64_t n = conv_.size(), w = wpos_.load(std::memory_order_relaxed), r = rpos_.load(std::memory_order_acquire);
    if (cap_ - (w - r) < n) { overruns_++; return; }
    for (uint64_t k = 0; k < n; k++) ring_[(w + k) % cap_] = conv_[k];
    wpos_.store(w + n, std::memory_order_release);
}

void Capture::run() {
    while (running_) {
        pollfd pfd{fd_, POLLOUT, 0};
        int r = poll(&pfd, 1, 100);
        if (r <= 0) continue;
        for (;;) {
            usbdevfs_urb* u = nullptr;
            if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &u) < 0) {
                if (errno == EAGAIN) break;
                errors_++;
                if (errno == ENODEV) running_ = false;
                break;
            }
            handleUrb(u);
            if (running_ && !submit(u)) { errors_++; LOGE("resubmit failed errno=%d", errno); running_ = false; }
        }
    }
}

void Capture::stop() {
    running_ = false;
    if (th_.joinable()) th_.join();
    if (fd_ >= 0 && !urbs_.empty()) {
        for (auto* u : urbs_) ioctl(fd_, USBDEVFS_DISCARDURB, u);
        size_t reaped = 0;  // reap discarded URBs before freeing them
        for (int tries = 0; tries < 50 && reaped < urbs_.size(); tries++) {
            usbdevfs_urb* u = nullptr;
            if (ioctl(fd_, USBDEVFS_REAPURBNDELAY, &u) == 0) reaped++;
            else usleep(10000);
        }
        usbdevfs_setinterface si{(unsigned)alt_.iface, 0};
        ioctl(fd_, USBDEVFS_SETINTERFACE, &si);
    }
    for (auto* u : urbs_) free(u);
    for (auto* b : bufs_) free(b);
    urbs_.clear();
    bufs_.clear();
    fd_ = -1;
}

int Capture::read(float* dst, int maxFrames) {
    if (nch_ == 0) return 0;
    uint64_t w = wpos_.load(std::memory_order_acquire), r = rpos_.load(std::memory_order_relaxed);
    uint64_t frames = std::min<uint64_t>((w - r) / nch_, (uint64_t)maxFrames);
    uint64_t n = frames * nch_;
    for (uint64_t k = 0; k < n; k++) dst[k] = ring_[(r + k) % cap_];
    rpos_.store(r + n, std::memory_order_release);
    return (int)frames;
}

void Capture::takePeaks(float* out, int n) {
    std::lock_guard<std::mutex> g(peakMu_);
    for (int c = 0; c < n; c++) {
        bool ok = c < (int)peaks_.size();
        out[c] = ok ? peaks_[c] : 0.f;
        if (ok) peaks_[c] = 0.f;
    }
}

void Capture::stats(long long out[3]) {
    out[0] = frames_;
    out[1] = overruns_;
    out[2] = errors_;
}

}  // namespace uac
