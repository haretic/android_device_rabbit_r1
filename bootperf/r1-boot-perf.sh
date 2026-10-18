#!/vendor/bin/sh
# SPDX-License-Identifier: Apache-2.0
# Run both CPU clusters at full speed while Android boots, unless the battery is
# below 10 %. init.r1.rc puts schedutil back once sys.boot_completed=1.
BAT=/sys/class/power_supply/mt6357-battery
cap=$(cat $BAT/capacity 2>/dev/null)
uv=$(cat $BAT/voltage_now 2>/dev/null)
case "$cap" in
    ''|*[!0-9]*)
        # No capacity yet: ~3.6 V under boot load is about 10 % on this cell.
        case "$uv" in ''|*[!0-9]*) ok=0 ;; *) [ "$uv" -ge 3600000 ] && ok=1 || ok=0 ;; esac
        why="voltage ${uv:-?} uV" ;;
    *)
        [ "$cap" -ge 10 ] && ok=1 || ok=0
        why="capacity $cap %" ;;
esac
if [ "$ok" = 1 ]; then
    for p in /sys/devices/system/cpu/cpufreq/policy*; do
        echo performance > $p/scaling_governor
    done
    echo "r1-boot-perf: performance governor for boot ($why)" > /dev/kmsg
else
    echo "r1-boot-perf: battery low or unknown ($why), keeping schedutil" > /dev/kmsg
fi
