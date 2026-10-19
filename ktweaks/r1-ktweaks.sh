#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# r1-ktweaks: applies Settings > System > Kernel Tweaks. The page (system uid) sets
#   persist.sys.r1.kt.enabled   1 to use the tweaks, else everything is at the default
#   persist.sys.r1.kt.mem_sleep deep | s2idle
#   persist.sys.r1.kt.cores     1..8, the cores left online (the highest numbers go first)
#   persist.sys.r1.kt.trip      75 | 80 | 85 | 90, the CPU throttle temperature in degrees C
#   persist.sys.r1.kt.zram      compressed swap in MiB, 0 for none
# and then sys.r1.kt.apply to anything new. Unset means the default. Writes
# sys.r1.kt.result (ok, or the tweaks that could not be set) for the page.
#
# Two starts in a row that don't stay up two minutes after the tweaks went in switch them
# off (persist.sys.r1.kt.paused=1) until the page switches them on again.
#
#   r1-ktweaks.sh start    at post-fs-data: counts the start
#   r1-ktweaks.sh apply    once Android is up and after every change
#   r1-ktweaks.sh settle   two minutes later: the start was good

CPU=/sys/devices/system/cpu
ZRAM=/sys/block/zram0
# The CPU passive trip in the device tree
DEFAULT_TRIP=85000

prop() { getprop "persist.sys.r1.kt.$1"; }

enabled() {
	[ "$(prop enabled)" = 1 ] && [ "$(prop paused)" != 1 ]
}

failed=""
fail() { failed="${failed:+$failed,}$1"; }

set_mem_sleep() {
	echo "$1" > /sys/power/mem_sleep 2>/dev/null || fail mem_sleep
}

set_cores() {
	n=$1
	for c in 1 2 3 4 5 6 7; do
		if [ "$c" -lt "$n" ]; then on=1; else on=0; fi
		if [ "$(cat $CPU/cpu$c/online)" != "$on" ]; then
			echo $on > $CPU/cpu$c/online 2>/dev/null || fail cores
		fi
	done
}

set_trip() {
	for z in /sys/class/thermal/thermal_zone*; do
		case "$(cat $z/type)" in
		cpu-big-thermal|cpu-little-thermal)
			echo "$1" > $z/trip_point_0_temp 2>/dev/null || fail trip ;;
		esac
	done
}

set_zram() {
	bytes=$1
	[ "$(cat $ZRAM/disksize)" = "$bytes" ] && return
	# Moves what is swapped out back into RAM first, so it can take a moment
	swapoff /dev/block/zram0 2>/dev/null
	echo 1 > $ZRAM/reset || { fail zram; return; }
	[ "$bytes" = 0 ] && return
	echo "$bytes" > $ZRAM/disksize && mkswap /dev/block/zram0 >/dev/null &&
		swapon /dev/block/zram0 || fail zram
}

apply() {
	# The default size, taken before the first change of this start: in bytes to put it back
	# exactly, in MiB for the page
	if [ -z "$(getprop sys.r1.kt.default_zram_bytes)" ]; then
		setprop sys.r1.kt.default_zram_bytes $(cat $ZRAM/disksize)
		setprop sys.r1.kt.default_zram $(( $(cat $ZRAM/disksize) / 1048576 ))
	fi

	if enabled; then
		mem_sleep=$(prop mem_sleep) cores=$(prop cores) trip=$(prop trip) zram=$(prop zram)
	else
		mem_sleep="" cores="" trip="" zram=""
	fi
	case "$mem_sleep" in deep|s2idle) ;; *) mem_sleep=deep ;; esac
	case "$cores" in [1-8]) ;; *) cores=8 ;; esac
	case "$trip" in 75|80|85|90) trip=$(( trip * 1000 )) ;; *) trip=$DEFAULT_TRIP ;; esac
	case "$zram" in
	''|*[!0-9]*) zram=$(getprop sys.r1.kt.default_zram_bytes) ;;
	*) zram=$(( zram * 1048576 )) ;;
	esac

	set_mem_sleep "$mem_sleep"
	set_cores "$cores"
	set_trip "$trip"
	set_zram "$zram"
	setprop sys.r1.kt.result "${failed:-ok}"
}

case "$1" in
start)
	enabled || exit 0
	tries=$(( $(prop tries) + 1 ))
	setprop persist.sys.r1.kt.tries $tries
	if [ $tries -gt 2 ]; then
		setprop persist.sys.r1.kt.paused 1
		setprop persist.sys.r1.kt.tries 0
		echo "r1-ktweaks: two starts in a row failed, tweaks paused" > /dev/kmsg
	fi
	;;
apply)
	apply
	;;
settle)
	sleep 120
	setprop persist.sys.r1.kt.tries 0
	;;
esac
