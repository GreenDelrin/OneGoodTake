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

// bInterfaceSubClass value for an audio streaming interface.
static const int AUDIO_STREAMING = 2;
// bInterfaceProtocol values for the audio streaming interfaces of each class revision.
static const int PROTO_UAC1 = 0x00;
static const int PROTO_UAC2 = 0x20;
// Class-specific descriptor types.
static const int CS_INTERFACE = 0x24;
static const int CS_ENDPOINT = 0x25;
// Class-specific descriptor subtypes.
static const int SUB_AS_GENERAL = 0x01;
static const int SUB_FORMAT_TYPE = 0x02;
// UAC2 clock source: control selector for sampling frequency.
static const int CS_SAM_FREQ_CONTROL = 0x01;
static const int CN_CUR = 0x01;
static const int CN_RANGE = 0x02;

static std::string fmt(const char* f, ...) {
    char b[512];
    va_list ap;
    va_start(ap, f);
    vsnprintf(b, sizeof b, f, ap);
    va_end(ap);
    return b;
}

static int u24(const uint8_t* p) {
    return p[0] | p[1] << 8 | p[2] << 16;
}

/**
 * Reads the alternating settings of every audio streaming interface.
 *
 * UAC2 keeps the channel count in the class-specific AS interface descriptor and the sample frame
 * size in the Format Type I descriptor, while UAC1 puts the channel count, the frame size and the
 * list of supported rates all in the Format Type I descriptor. Both layouts are parsed so a
 * class-compliant device of either revision can be captured.
 */
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
            if (curClass == 1 && curSub == AUDIO_STREAMING && curProto != PROTO_UAC2 && curProto != PROTO_UAC1)
                log += fmt("NOTE: interface %d is audio streaming with protocol 0x%02x (neither UAC1 nor UAC2)\n", p[2], curProto);
        } else if (type == CS_INTERFACE && curClass == 1) {  // class-specific interface
            uint8_t sub = p[2];
            if (curSub == 1 && sub == 0x0A && len >= 8) {  // UAC2 clock source
                log += fmt("clock source id=%d attrs=0x%02x\n", p[3], p[4]);
                if (out.clockId < 0 && (p[4] & 3) != 0) out.clockId = p[3];
            } else if (curSub == AUDIO_STREAMING && curProto == PROTO_UAC2) {
                if (sub == SUB_AS_GENERAL && len >= 16) {
                    cur.channels = p[10];
                    cur.uac = 2;
                    haveGeneral = true;
                } else if (sub == SUB_FORMAT_TYPE && len >= 6 && p[3] == 1) {
                    cur.subslot = p[4];   // bSubslotSize: bytes per sample
                    cur.bits = p[5];      // bBitResolution
                    haveFmt = true;
                }
            } else if (curSub == AUDIO_STREAMING && curProto == PROTO_UAC1) {
                if (sub == SUB_AS_GENERAL && len >= 7) {
                    cur.uac = 1;
                    if ((p[5] | p[6] << 8) != 1)
                        log += fmt("NOTE: interface %d declares format tag 0x%04x, not PCM\n", cur.iface, p[5] | p[6] << 8);
                    haveGeneral = true;
                } else if (sub == SUB_FORMAT_TYPE && len >= 8 && p[3] == 1) {
                    cur.channels = p[4];   // bNrChannels
                    cur.subslot = p[5];    // bSubframeSize: bytes per sample
                    cur.bits = p[6];       // bBitResolution
                    int rateCount = p[7];  // bSamFreqType: 0 means a continuous range
                    if (rateCount == 0) {
                        if (len >= 14) {
                            int minRate = u24(p + 8), maxRate = u24(p + 11);
                            if (minRate > 0 && minRate <= maxRate) {
                                cur.rates.push_back(minRate);
                                if (maxRate != minRate) cur.rates.push_back(maxRate);
                            }
                        }
                    } else {
                        for (int k = 0; k < rateCount && 8 + 3 * k + 2 < len; k++) {
                            int r = u24(p + 8 + 3 * k);
                            if (r > 0) cur.rates.push_back(r);
                        }
                    }
                    haveFmt = true;
                }
            }
        } else if (type == 5 && curClass == 1 && curSub == AUDIO_STREAMING && len >= 7) {  // endpoint
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
        } else if (type == CS_ENDPOINT && curClass == 1 && curSub == AUDIO_STREAMING && len >= 7) {
            // UAC1 keeps the sampling-frequency control flag on the endpoint, right after the
            // endpoint descriptor that was just collected.
            if (!out.alts.empty() && (p[3] & 1)) out.alts.back().freqControl = true;
        }
        i += len;
    }
    return true;
}

static bool altSupportsRate(const AltInfo& a, int rate) {
    for (int r : a.rates)
        if (r == rate) return true;
    return false;
}

/** The rate to stream at on an alt setting that declares its own rates (UAC1). */
static int pickAltRate(const std::vector<int>& rates, int preferred) {
    if (rates.empty()) return preferred;
    for (int r : rates)
        if (r == preferred) return r;
    // 48 kHz is the app's default, so fall back to 44.1 kHz for an interface that cannot run at 48.
    for (int r : rates)
        if (r == 44100) return r;
    for (int r : rates)
        if (r == 48000) return r;
    return rates.front();
}

const AltInfo* chooseAlt(const ParsedDesc& pd, int preferredRate, int minChannels) {
    const AltInfo* best = nullptr;
    int bestScore = -1;
    const int floor_ = minChannels < 1 ? 1 : minChannels;
    for (const auto& a : pd.alts) {
        if (a.subslot < 2 || a.subslot > 4 || a.maxPacket <= 0) continue;
        if (a.channels < floor_) continue;
        int score = 0;
        if (preferredRate > 0 && altSupportsRate(a, preferredRate)) score += 100;
        // Two channels back the app's Input 1 / Input 2 mapping and cost less bandwidth than the
        // wider alt setting the same interface may also expose.
        score += a.channels == 2 ? 40 : (a.channels == 1 ? 30 : 10);
        score += a.uac == 2 ? 4 : 0;
        score += a.alt > 0 ? 2 : 0;  // prefer a streaming, non-zero-bandwidth setting
        if (score > bestScore) {
            bestScore = score;
            best = &a;
        }
    }
    return best;
}

int captureInterface(const uint8_t* d, size_t n) {
    ParsedDesc pd;
    std::string log;
    parse(d, n, pd, log);
    const AltInfo* alt = chooseAlt(pd, 0, 1);
    return alt ? alt->iface : -1;
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

/** UAC2 clock source: the rate the device is running at right now. */
static bool readClockRate(int fd, uint16_t idx, uint32_t& hz) {
    uint8_t got[4] = {0};
    if (controlXfer(fd, 0xA1, 0x01, (uint16_t)((CS_SAM_FREQ_CONTROL << 8) | CN_CUR), idx, got, 4) != 4) return false;
    hz = (uint32_t)got[0] | (uint32_t)got[1] << 8 | (uint32_t)got[2] << 16 | (uint32_t)got[3] << 24;
    return hz >= 8000 && hz <= 384000;
}

static bool writeClockRate(int fd, uint16_t idx, uint32_t hz) {
    uint8_t le[4] = {(uint8_t)hz, (uint8_t)(hz >> 8), (uint8_t)(hz >> 16), (uint8_t)(hz >> 24)};
    return controlXfer(fd, 0x21, 0x01, (uint16_t)((CS_SAM_FREQ_CONTROL << 8) | CN_CUR), idx, le, 4) >= 0;
}

/** UAC2 clock source: the range of rates it accepts, when it advertises one. */
static bool readClockRange(int fd, uint16_t idx, uint32_t& minHz, uint32_t& maxHz, uint32_t& res) {
    uint8_t got[12] = {0};
    if (controlXfer(fd, 0xA1, 0x01, (uint16_t)((CS_SAM_FREQ_CONTROL << 8) | CN_RANGE), idx, got, 12) < 12) return false;
    auto word = [&](int o) {
        return (uint32_t)got[o] | (uint32_t)got[o + 1] << 8 | (uint32_t)got[o + 2] << 16 | (uint32_t)got[o + 3] << 24;
    };
    minHz = word(0);
    maxHz = word(4);
    res = word(8);
    return minHz >= 8000 && maxHz <= 384000 && minHz <= maxHz;
}

/** The closest rate to the preference that the advertised range accepts. */
static uint32_t snapToRange(uint32_t preferred, uint32_t minHz, uint32_t maxHz, uint32_t res) {
    if (preferred < minHz) return minHz;
    if (preferred > maxHz) return maxHz;
    if (res == 0) return preferred;
    return minHz + ((preferred - minHz) / res) * res;
}

/** UAC1 rate selection goes through the endpoint's sampling frequency control. */
static void setUac1Rate(int fd, const AltInfo& alt, int rate, std::string& log) {
    uint8_t le[3] = {(uint8_t)rate, (uint8_t)(rate >> 8), (uint8_t)(rate >> 16)};
    uint16_t idx = (uint16_t)((alt.iface << 8) | alt.ep);
    int r = controlXfer(fd, 0x22, 0x01, (uint16_t)(CS_SAM_FREQ_CONTROL << 8), idx, le, 3);
    log += fmt("set endpoint rate %d -> %s\n", rate, r < 0 ? strerror(errno) : "ok");
}

std::string Capture::start(int fd, const uint8_t* desc, size_t len, int sampleRate, int minChannels) {
    stop();
    fd_ = fd;
    ParsedDesc pd;
    std::string log;
    parse(desc, len, pd, log);
    for (auto& a : pd.alts) {
        std::string rates;
        for (int r : a.rates) rates += (rates.empty() ? "" : "/") + std::to_string(r);
        log += fmt("capture alt: iface=%d alt=%d uac=%d ch=%d subslot=%d bits=%d ep=0x%02x maxPkt=%d interval=%d usage=%d rates=%s\n",
                   a.iface, a.alt, a.uac, a.channels, a.subslot, a.bits, a.ep, a.maxPacket, a.interval, a.usage,
                   rates.empty() ? "n/a" : rates.c_str());
    }
    const int requested = sampleRate > 0 ? sampleRate : 48000;
    const bool haveClock = pd.clockId >= 0 && pd.acIface >= 0;
    uint32_t negotiated = (uint32_t)requested;
    if (haveClock) {
        const uint16_t idx = (uint16_t)((pd.clockId << 8) | pd.acIface);
        uint32_t current = 0;
        const bool haveCurrent = readClockRate(fd_, idx, current);
        uint32_t minHz = 0, maxHz = 0, res = 0;
        const bool haveRange = readClockRange(fd_, idx, minHz, maxHz, res);
        const uint32_t target = haveRange ? snapToRange((uint32_t)requested, minHz, maxHz, res) : (uint32_t)requested;
        const bool set = writeClockRate(fd_, idx, target);
        uint32_t after = 0;
        const bool haveAfter = readClockRate(fd_, idx, after);
        negotiated = haveAfter ? after : (haveRange ? target : (haveCurrent ? current : (uint32_t)requested));
        log += fmt("clock %d: current=%u range=[%u..%u step %u] want=%d set=%s device now=%u\n",
                   pd.clockId, haveCurrent ? current : 0, haveRange ? minHz : 0, haveRange ? maxHz : 0,
                   haveRange ? res : 0, requested, set ? "ok" : "refused",
                   haveAfter ? after : (haveCurrent ? current : 0));
    }
    const int rateForChoice = haveClock ? (int)negotiated : requested;
    const AltInfo* a = chooseAlt(pd, rateForChoice, minChannels);
    if (!a && minChannels > 1) a = chooseAlt(pd, rateForChoice, 1);
    if (!a)
        return "ERROR no class-compliant UAC capture alt setting found (device may be vendor mode, "
               "or it may not be in class-compliant mode)\n" + log;
    alt_ = *a;
    nch_ = alt_.channels;
    rate_ = rateForChoice;
    if (!haveClock && !alt_.rates.empty()) rate_ = pickAltRate(alt_.rates, requested);
    frameBytes_ = nch_ * alt_.subslot;

    usbdevfs_setinterface si{(unsigned)alt_.iface, (unsigned)alt_.alt};
    if (ioctl(fd_, USBDEVFS_SETINTERFACE, &si) < 0)
        return fmt("ERROR SETINTERFACE failed errno=%d (%s) - was the interface claimed?\n", errno, strerror(errno)) + log;

    if (!haveClock) {
        if (alt_.freqControl) setUac1Rate(fd_, alt_, rate_, log);
        else if (!alt_.rates.empty()) log += fmt("device is fixed at %d Hz\n", rate_);
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
    return fmt("OK streaming UAC%d %d ch, %d-bit (subslot %d), %d Hz\n", alt_.uac, nch_, alt_.bits, alt_.subslot, rate_) + log;
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
