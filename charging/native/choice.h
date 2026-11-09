// SPDX-License-Identifier: Apache-2.0
#pragma once
#include <string_view>

namespace r1 {
inline constexpr char kProperty[] = "persist.sys.r1.charge_limit";
inline constexpr char kLimit[] = "/sys/devices/platform/charging-policy/charge_control_limit";

// Return -1 for malformed input. Automatic means the stock pack's 1 C, never unlimited.
inline int CurrentLimit(std::string_view choice) {
    if (choice == "auto") return 1000000;
    if (choice == "500") return 500000;
    if (choice == "600") return 600000;
    if (choice == "700") return 700000;
    if (choice == "800") return 800000;
    if (choice == "900") return 900000;
    if (choice == "1000") return 1000000;
    // Faster than the stock pack's 1 C: Battery details asks for a confirmation first
    if (choice == "1200") return 1200000;
    if (choice == "1500") return 1500000;
    if (choice == "2000") return 2000000;
    // Unsafe for the stock pack, for larger cells fitted by hand with a PMIC heat sink
    if (choice == "2500") return 2500000;
    if (choice == "3000") return 3000000;
    return -1;
}
}  // namespace r1
