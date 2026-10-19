/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Hand the factory Wi-Fi NVRAM record to gen4m before Wi-Fi powers on, the
// job MediaTek's wlan_assistant does. Without it gen4m logs "Nvram not
// available" and makes up a MAC (00:08:22 + the boot tick) on every boot, so
// each boot is a new DHCP client. The record also carries the RF calibration.
//
// wmt_chrdev_wifi takes "WR-BUF:NVRAM" followed by the record in one write.

#include <fcntl.h>
#include <unistd.h>

#include <cstring>
#include <string>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/unique_fd.h>

static constexpr char kRecord[] = "/mnt/vendor/nvdata/APCFG/APRDEB/WIFI";
static constexpr char kDevice[] = "/dev/wmtWifi";
static constexpr char kPrefix[] = "WR-BUF:NVRAM";
// WIFI_CFG_PARAM_STRUCT: u2Part1OwnVersion, u2Part1PeerVersion, aucMacAddress[6].
static constexpr size_t kMacOffset = 4;
// MAX_CFG_FILE_WIFI_REC_SIZE in gen4m.
static constexpr size_t kMaxRecord = 8192;

int main(int, char** argv) {
    android::base::InitLogging(argv, android::base::KernelLogger);

    std::string record;
    if (!android::base::ReadFileToString(kRecord, &record)) {
        PLOG(ERROR) << "no factory record " << kRecord << ", gen4m will make up a MAC";
        return 1;
    }
    if (record.size() < kMacOffset + 6 || record.size() > kMaxRecord) {
        LOG(ERROR) << "factory record is " << record.size() << " bytes, not loading it";
        return 1;
    }
    const auto* mac = reinterpret_cast<const unsigned char*>(record.data() + kMacOffset);
    static const unsigned char kZero[6] = {};
    if (!memcmp(mac, kZero, 6) || (mac[0] & 1)) {
        LOG(ERROR) << "factory record has no valid MAC, not loading it";
        return 1;
    }

    std::string buf = kPrefix + record;
    android::base::unique_fd fd(open(kDevice, O_WRONLY | O_CLOEXEC));
    if (fd < 0 || write(fd.get(), buf.data(), buf.size()) != static_cast<ssize_t>(buf.size())) {
        PLOG(ERROR) << "writing the record to " << kDevice << " failed";
        return 1;
    }
    LOG(INFO) << android::base::StringPrintf(
            "loaded %zu byte factory record, MAC %02x:%02x:%02x:%02x:%02x:%02x", record.size(),
            mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
    return 0;
}
