// SPDX-License-Identifier: Apache-2.0
// Adapted from andr36oid UsbMode Services.cpp/main.cpp: tinyalsa PCM and HID.
#include <array>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <string>
#include <fcntl.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>
#include <sound/asound.h>
#include <cutils/properties.h>
#include <cutils/sockets.h>
#include <tinyalsa/asoundlib.h>

namespace {
std::string prop(const char* key) {
    char value[PROPERTY_VALUE_MAX]{};
    property_get(key, value, "");
    return value;
}
std::string text(const std::string& path) {
    std::ifstream in(path); std::string line; std::getline(in, line); return line;
}
void closeFd(int& fd) { if (fd >= 0) close(fd); fd = -1; }
bool again() { return errno == EAGAIN || errno == EINTR; }
int hid(const char* function) {
    unsigned maj, min; char tail;
    std::string dev = text(std::string("/config/usb_gadget/g1/functions/") + function + "/dev");
    if (sscanf(dev.c_str(), "%u:%u%c", &maj, &min, &tail) != 2 || min > 255) return -1;
    int fd = open(("/dev/hidg" + std::to_string(min)).c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
    struct stat st{};
    if (fd >= 0 && (fstat(fd, &st) || !S_ISCHR(st.st_mode) || major(st.st_rdev) != maj || minor(st.st_rdev) != min)) closeFd(fd);
    return fd;
}
int card() {
    std::ifstream in("/proc/asound/cards"); std::string line;
    while (std::getline(in, line)) {
        int n = -1;
        if (line.find("UAC2Gadget") != std::string::npos && sscanf(line.c_str(), " %d", &n) == 1 && n >= 0 && n < 256) return n;
    }
    return -1;
}

// Every connection must be system UID and present the current generation as its
// first seqpacket. Revocation drops clients and queues before another lease.
struct Channel {
    int server = -1, client = -1;
    bool ready = false;
    unsigned ticks = 0;
    explicit Channel(const char* name) {
        server = android_get_control_socket(name);
        if (server >= 0) {
            fcntl(server, F_SETFL, O_NONBLOCK);
            if (listen(server, 1)) closeFd(server);
        }
    }
    void reset() { closeFd(client); ready = false; ticks = 0; }
    void accept(const std::string& generation) {
        if (client < 0) {
            int fd = accept4(server, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
            if (fd < 0) return;
            ucred cred{}; socklen_t len = sizeof(cred);
            if (getsockopt(fd, SOL_SOCKET, SO_PEERCRED, &cred, &len) || cred.uid != 1000 || generation.empty()) { close(fd); return; }
            // Keep queued audio/input small enough that stalled readers cannot
            // retain seconds of old packets. Kernel doubles these limits.
            int buffer = 4096;
            setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buffer, sizeof(buffer));
            setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buffer, sizeof(buffer));
            client = fd; ready = false; ticks = 0;
        }
        if (!ready) {
            char data[64]{};
            ssize_t n = recv(client, data, sizeof(data), MSG_DONTWAIT | MSG_TRUNC);
            if (n < 0 && again() && ++ticks < 500) return;
            if (n > static_cast<ssize_t>(sizeof(data)) || n != static_cast<ssize_t>(generation.size()) || memcmp(data, generation.data(), generation.size())) { reset(); return; }
            ready = true;
        }
    }
};

struct Input {
    int keyboard = -1, mouse = -1;
    std::array<uint8_t, 9> pending{};
    size_t size = 0;
    bool neutral = true;
    void reset() {
        // Best effort releases before close, also used on client death.
        const uint8_t zero[8]{};
        if (keyboard >= 0) write(keyboard, zero, 8);
        if (mouse >= 0) write(mouse, zero, 4);
        closeFd(keyboard); closeFd(mouse); size = 0; neutral = true;
    }
    bool pump(Channel& channel) {
        if (keyboard < 0) keyboard = hid("hid.usbemu0");
        if (mouse < 0) mouse = hid("hid.usbemu1");
        if (keyboard < 0 || mouse < 0) return false;
        // Retry a neutral report until accepted after reconnect. No held key can
        // leak from a disconnected sender, even if the endpoint was busy.
        if (neutral) {
            const uint8_t zero[8]{};
            if (write(keyboard, zero, 8) != 8 || write(mouse, zero, 4) != 4) return true;
            neutral = false;
        }
        uint8_t leds[8]; read(keyboard, leds, sizeof(leds));
        if (!channel.ready) return true;
        if (!size) {
            ssize_t n = recv(channel.client, pending.data(), pending.size(), MSG_DONTWAIT | MSG_TRUNC);
            if (n < 0 && again()) return true;
            if (!((n == 9 && pending[0] == 1 && pending[2] == 0) ||
                  (n == 5 && pending[0] == 2 && (pending[1] & ~7) == 0))) return false;
            if (pending[0] == 1) {
                for (size_t i = 3; i < 9; ++i) if (pending[i] > 0x73) return false;
            } else {
                for (size_t i = 2; i < 5; ++i) if (pending[i] == 0x80) return false;
            }
            size = static_cast<size_t>(n);
        }
        int fd = pending[0] == 1 ? keyboard : mouse;
        ssize_t n = write(fd, pending.data() + 1, size - 1);
        if (n == static_cast<ssize_t>(size - 1)) size = 0;
        else if (n >= 0 || !again()) return false;
        return true;
    }
};

struct Audio {
    pcm* fromHost = nullptr;
    pcm* toHost = nullptr;
    mixer* controls = nullptr;
    std::array<int16_t, 384> capture{};
    struct Packet { std::array<int16_t, 384> samples{}; size_t frames = 0; };
    std::deque<Packet> queued;
    size_t offset = 0, frames = 0;
    float playbackGain = 1, captureGain = 1;
    unsigned tick = 0;
    void reset() {
        if (fromHost) pcm_close(fromHost);
        if (toHost) pcm_close(toHost);
        if (controls) mixer_close(controls);
        fromHost = toHost = nullptr; controls = nullptr; offset = frames = 0; tick = 0; queued.clear();
    }
    bool openCard() {
        int n = card(); if (n < 0) return false;
        // Runtime lookup is mandatory: never assume a physical card's index.
        pcm_config config{}; config.channels = 2; config.rate = 48000;
        config.period_size = 192; config.period_count = 4; config.format = PCM_FORMAT_S16_LE;
        config.start_threshold = 192;
        fromHost = pcm_open(n, 0, PCM_IN, &config);
        toHost = pcm_open(n, 0, PCM_OUT, &config);
        if (!fromHost || !toHost || !pcm_is_ready(fromHost) || !pcm_is_ready(toHost)) { reset(); return false; }
        fcntl(pcm_get_poll_fd(fromHost), F_SETFL, O_NONBLOCK);
        fcntl(pcm_get_poll_fd(toHost), F_SETFL, O_NONBLOCK);
        controls = mixer_open(n);
        if (pcm_prepare(toHost) || pcm_start(fromHost)) { reset(); return false; }
        return true;
    }
    float gain(const char* direction) {
        if (!controls) return 1;
        auto* sw = mixer_get_ctl_by_name(controls, (std::string("PCM ") + direction + " Switch").c_str());
        auto* vol = mixer_get_ctl_by_name(controls, (std::string("PCM ") + direction + " Volume").c_str());
        if (sw && mixer_ctl_get_value(sw, 0) == 0) return 0;
        int value = vol ? mixer_ctl_get_value(vol, 0) : 96;
        return value < 0 ? 0 : std::pow(10.f, (std::clamp(value, 0, 96) - 96) / 20.f);
    }
    static void scale(int16_t* data, size_t count, float gain) {
        for (size_t i = 0; i < count; ++i) data[i] = static_cast<int16_t>(data[i] * gain);
    }
    bool pump(Channel& channel) {
        if (!fromHost && !openCard()) return false;
        if (tick++ % 50 == 0) { playbackGain = gain("Capture"); captureGain = gain("Playback"); }
        std::array<int16_t, 384> block{};
        snd_xferi transfer{}; transfer.buf = block.data(); transfer.frames = 192;
        if (ioctl(pcm_get_poll_fd(fromHost), SNDRV_PCM_IOCTL_READI_FRAMES, &transfer) < 0) {
            if (errno == EPIPE || errno == ESTRPIPE) {
                // pcm_prepare caches prepared state; DROP resets that state
                // after a raw transfer ioctl reports an xrun.
                if (pcm_stop(fromHost) || pcm_start(fromHost)) return false;
            }
            else if (!again()) return false;
        } else if (transfer.result > 0 && channel.ready) {
            scale(block.data(), transfer.result * 2, playbackGain);
            // Discard old playback rather than blocking HID or gadget cleanup.
            ssize_t n = send(channel.client, block.data(), transfer.result * 4, MSG_DONTWAIT | MSG_NOSIGNAL);
            if (n < 0 && !again()) return false;
        }
        // Keep draining the client when the host is not recording. A bounded
        // four-period queue retains recent mic audio without blocking the app.
        if (channel.ready) for (unsigned i = 0; i < 8; ++i) {
            Packet packet;
            ssize_t n = recv(channel.client, packet.samples.data(), sizeof(packet.samples), MSG_DONTWAIT | MSG_TRUNC);
            if (n < 0 && again()) break;
            if (n <= 0 || n > static_cast<ssize_t>(sizeof(packet.samples)) || n % 4) return false;
            packet.frames = n / 4;
            scale(packet.samples.data(), packet.frames * 2, captureGain);
            if (queued.size() == 4) queued.pop_front();
            queued.push_back(packet);
        }
        if (!frames) {
            offset = 0;
            if (queued.empty()) { capture.fill(0); frames = 192; }
            else { capture = queued.front().samples; frames = queued.front().frames; queued.pop_front(); }
        }
        transfer = {}; transfer.buf = capture.data() + offset * 2; transfer.frames = frames;
        if (ioctl(pcm_get_poll_fd(toHost), SNDRV_PCM_IOCTL_WRITEI_FRAMES, &transfer) < 0) {
            if (errno == EPIPE || errno == ESTRPIPE) {
                if (pcm_stop(toHost) || pcm_prepare(toHost)) return false;
                frames = 0;
            }
            else if (!again()) return false;
        } else if (transfer.result > 0) { offset += transfer.result; frames -= transfer.result; }
        return true;
    }
};
} // namespace

int main() {
    Channel input("r1-usbemu-input"), audio("r1-usbemu-audio");
    if (input.server < 0 || audio.server < 0) return 1;
    Input hid; Audio pcm;
    std::string previous;
    for (;;) {
        std::string generation = prop("sys.r1.usbemu.st.generation");
        std::string functions = prop("sys.r1.usbemu.functions");
        if (generation.empty() || generation.size() > 10 || generation.find_first_not_of("0123456789") != std::string::npos
                || generation == "0" || functions.rfind(generation + ":", 0) != 0) generation.clear();
        if (generation != previous) { input.reset(); audio.reset(); hid.reset(); pcm.reset(); previous = generation; }
        bool keys = !generation.empty() && functions.find("hid.usbemu0") != std::string::npos;
        bool sound = !generation.empty() && functions.find("uac2.usbemu0") != std::string::npos;
        if (keys) {
            input.accept(generation);
            if (!hid.pump(input)) { input.reset(); hid.reset(); }
        } else { input.reset(); hid.reset(); }
        if (sound) {
            audio.accept(generation);
            if (!pcm.pump(audio)) { audio.reset(); pcm.reset(); }
        } else { audio.reset(); pcm.reset(); }
        poll(nullptr, 0, 2);
    }
}
