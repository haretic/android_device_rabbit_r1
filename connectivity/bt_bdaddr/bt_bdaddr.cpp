/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

// Pick the r1's Bluetooth address and hand it to device/mainline/common's
// bdaddr, which programs it through MGMT once btmtk_wmt reports the
// controller unconfigured. The CONSYS controller powers on with a ROM
// default address, not the unit's own; stock's HAL programmed the factory
// one from NVRAM on every power on, and btmtk_wmt now does the same.
//
// The address is the factory one in nvdata APCFG/APRDEB/BT_Addr. Without a
// usable record it is derived from the eMMC CID, so it still stays the same
// from boot to boot. NVRAM is never written.

#include <unistd.h>

#include <cstring>
#include <string>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/stringprintf.h>
#include <android-base/strings.h>
#include <openssl/sha.h>

static constexpr char kRecord[] = "/mnt/vendor/nvdata/APCFG/APRDEB/BT_Addr";
static constexpr char kCid[] = "/sys/block/mmcblk0/device/cid";
static constexpr char kBdaddr[] = "/vendor/bin/hw/bdaddr";
// ap_nvram_btradio_struct starts with the address, most significant byte first.
static constexpr size_t kMaxRecord = 4096;
// The controller's ROM default, also the NVRAM default the stock HAL rejected.
static constexpr unsigned char kRomDefault[6] = {0x00, 0x00, 0x46, 0x76, 0x63, 0x01};

static bool Usable(const unsigned char* a) {
    static const unsigned char kZero[6] = {};
    static const unsigned char kOnes[6] = {0xff, 0xff, 0xff, 0xff, 0xff, 0xff};
    return memcmp(a, kZero, 6) && memcmp(a, kOnes, 6) && memcmp(a, kRomDefault, 6) &&
           !(a[0] & 1);
}

static bool FromNvram(unsigned char* a) {
    std::string record;
    if (!android::base::ReadFileToString(kRecord, &record)) {
        PLOG(ERROR) << "no factory record " << kRecord;
        return false;
    }
    if (record.size() < 6 || record.size() > kMaxRecord) {
        LOG(ERROR) << "factory record is " << record.size() << " bytes, not using it";
        return false;
    }
    memcpy(a, record.data(), 6);
    if (!Usable(a)) {
        LOG(ERROR) << "factory record has no valid address";
        return false;
    }
    return true;
}

// A locally administered unicast address from a hash of the eMMC CID.
static bool FromCid(unsigned char* a) {
    std::string cid;
    if (!android::base::ReadFileToString(kCid, &cid)) {
        PLOG(ERROR) << "cannot read " << kCid;
        return false;
    }
    cid = android::base::Trim(cid);
    if (cid.size() != 32 || cid.find_first_not_of("0123456789abcdefABCDEF") != std::string::npos) {
        LOG(ERROR) << "unexpected eMMC CID format";
        return false;
    }
    std::string input = "haretic r1 bdaddr " + cid;
    unsigned char digest[SHA256_DIGEST_LENGTH];
    SHA256(reinterpret_cast<const unsigned char*>(input.data()), input.size(), digest);
    memcpy(a, digest, 6);
    a[0] = (a[0] & 0xfc) | 0x02;
    return true;
}

int main(int, char** argv) {
    android::base::InitLogging(argv);

    unsigned char a[6];
    const char* source;
    if (FromNvram(a)) {
        source = "factory NVRAM";
    } else if (FromCid(a)) {
        source = "eMMC CID";
    } else {
        // An unconfigured controller is never offered to the Bluetooth HAL:
        // configure the ROM default so that Bluetooth still comes up.
        memcpy(a, kRomDefault, 6);
        source = "controller's ROM default";
    }
    std::string addr = android::base::StringPrintf("%02x:%02x:%02x:%02x:%02x:%02x", a[0], a[1],
                                                   a[2], a[3], a[4], a[5]);
    LOG(INFO) << "Bluetooth address " << addr << " from the " << source;

    execl(kBdaddr, "bdaddr", addr.c_str(), nullptr);
    PLOG(ERROR) << "cannot run " << kBdaddr;
    return 1;
}
