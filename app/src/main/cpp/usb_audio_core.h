// Minimal USB Audio Class capture driver for Android using usbdevfs (no libusb).
// Handles class-compliant UAC2 and UAC1 interfaces with 16/24/32-bit integer PCM.
#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct usbdevfs_urb;

namespace uac {

struct AltInfo {
    int iface = 0, alt = 0, channels = 0, subslot = 0, bits = 0;
    int ep = 0, maxPacket = 0, interval = 1, usage = 0;
    int uac = 0;                 // 1 when the alt setting is UAC1, 2 when it is UAC2
    bool freqControl = false;    // UAC1 endpoint accepts SET_CUR sampling frequency
    std::vector<int> rates;      // rates the alt setting declares (UAC1); empty for UAC2
};

struct ParsedDesc {
    int acIface = -1;
    int clockId = -1;
    std::vector<AltInfo> alts;  // capture (IN, isochronous) alt settings
};

bool parse(const uint8_t* d, size_t n, ParsedDesc& out, std::string& log);
// Picks the capture alt setting to stream: prefers one that runs at preferredRate, then two
// channels, then a streaming (non-zero bandwidth) setting. minChannels is the caller's floor.
const AltInfo* chooseAlt(const ParsedDesc& pd, int preferredRate, int minChannels);
int captureInterface(const uint8_t* d, size_t n);

class Capture {
public:
    ~Capture() { stop(); }
    // sampleRate is a preference: the rate the device actually runs at is negotiated from its
    // clock source and reported back through sampleRate(). minChannels steers the alt choice.
    // Returns "OK ..." on success, "ERROR ..." otherwise. Both include a descriptor report.
    std::string start(int fd, const uint8_t* desc, size_t len, int sampleRate, int minChannels);
    void stop();
    int channels() const { return nch_; }
    int sampleRate() const { return rate_; }
    int read(float* dst, int maxFrames);          // interleaved floats, returns frames
    void takePeaks(float* out, int n);            // peak since last call, then reset
    void stats(long long out[3]);                 // frames, overruns, usb errors

private:
    void run();
    void handleUrb(usbdevfs_urb* u);
    bool submit(usbdevfs_urb* u);

    int fd_ = -1, nch_ = 0, rate_ = 0, frameBytes_ = 0;
    AltInfo alt_;
    std::atomic<bool> running_{false};
    std::thread th_;
    std::vector<usbdevfs_urb*> urbs_;
    std::vector<uint8_t*> bufs_;
    int pktsPerUrb_ = 16;

    std::vector<float> ring_;
    uint64_t cap_ = 0;
    std::atomic<uint64_t> wpos_{0}, rpos_{0};
    std::mutex peakMu_;
    std::vector<float> peaks_;
    std::atomic<long long> frames_{0}, overruns_{0}, errors_{0};
    std::vector<float> conv_;
};

}  // namespace uac
