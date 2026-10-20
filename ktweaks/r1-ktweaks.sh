#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# r1-ktweaks: applies Settings > System > Kernel Tweaks. The page (system uid) sets
#   persist.sys.r1.kt.enabled   1 to use the tweaks, else everything is at the default
#   persist.sys.r1.kt.mem_sleep deep | s2idle
#   persist.sys.r1.kt.cores     1..8, the cores left online (the highest numbers go first)
#   persist.sys.r1.kt.trip      75 | 80 | 85 | 90, the CPU throttle temperature in degrees C
#   persist.sys.r1.kt.zram      compressed swap in MiB, 0 for none
#   persist.sys.r1.kt.oc        1 to overclock: boost on for the clusters below, whose top
#                               speed the power HAL then holds (OC_* hints, the Controller)
#   persist.sys.r1.kt.oc_big    2300 | 2400, the big cores' top speed in MHz
#   persist.sys.r1.kt.oc_little 1700 | 1800, the little cores'
# and then sys.r1.kt.apply to anything new. Unset means the default. Writes
# sys.r1.kt.result (ok, or the tweaks that could not be set) for the page.
#
# The overclock steps are offered only where this r1 passed the stress test (octest, started
# with sys.r1.kt.octest): persist.sys.r1.kt.oc_ok_big and oc_ok_little are the fastest steps
# that held, sys.r1.kt.octest_state is running, done or why it stopped.
#
# Two starts in a row that don't stay up two minutes after the tweaks went in switch them
# off (persist.sys.r1.kt.paused=1) until the page switches them on again.
#
#   r1-ktweaks.sh start    at post-fs-data: counts the start
#   r1-ktweaks.sh apply    once Android is up and after every change
#   r1-ktweaks.sh settle   two minutes later: the start was good
#   r1-ktweaks.sh octest   the overclock stress test

CPU=/sys/devices/system/cpu
BIG=$CPU/cpufreq/policy0
LITTLE=$CPU/cpufreq/policy4
BOOST=$CPU/cpufreq/boost
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

# Boost on for a cluster with an overclock step picked; the HAL holds the speed itself.
# A cluster's boost only switches while the global one is on, and that switches them all.
set_oc() {
	if [ -n "$1$2" ]; then
		[ "$(cat $BOOST)" = 1 ] || echo 1 > $BOOST 2>/dev/null || { fail oc; return; }
	else
		[ "$(cat $BOOST)" = 0 ] || echo 0 > $BOOST 2>/dev/null || fail oc
		return
	fi
	for p in "$BIG:$1" "$LITTLE:$2"; do
		policy=${p%%:*} mhz=${p#*:}
		[ -f $policy/boost ] || { [ -n "$mhz" ] && fail oc; continue; }
		if [ -n "$mhz" ]; then on=1; else on=0; fi
		[ "$(cat $policy/boost)" = "$on" ] || echo $on > $policy/boost 2>/dev/null || fail oc
	done
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
		oc_big="" oc_little=""
		if [ "$(prop oc)" = 1 ]; then
			oc_big=$(oc_step big) oc_little=$(oc_step little)
		fi
	else
		mem_sleep="" cores="" trip="" zram="" oc_big="" oc_little=""
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
	set_oc "$oc_big" "$oc_little"
	setprop sys.r1.kt.result "${failed:-ok}"
}

# The step picked for a cluster, if the stress test passed it
oc_step() {
	mhz=$(prop oc_$1) ok=$(prop oc_ok_$1)
	case "$mhz" in 2300|2400|1700|1800) ;; *) return ;; esac
	[ -n "$ok" ] && [ "$mhz" -le "$ok" ] && echo $mhz
}

# octest: every step, both clusters at once, at the speed for 60 s with all eight cores
# hashing the same file (framework.jar, from the page cache) and comparing. The board sensor's throttling (ap-ntc, from 45 °C)
# pauses for it; the chip's own trips stay, and over 90 °C the test stops. A step counts only
# if it held its speed most of the time.
octest_state() { setprop sys.r1.kt.octest_state "$1"; echo "r1-ktweaks octest: $1" > /dev/kmsg; }

octest() {
	blob=/system/framework/framework.jar
	ref=$(sha256sum $blob | cut -d' ' -f1)
	[ -n "$ref" ] || { octest_state "failed: can't read $blob"; return; }
	ntc=""
	for z in /sys/class/thermal/thermal_zone*; do
		[ "$(cat $z/type)" = ap-ntc-thermal ] && ntc=$z
	done
	gov_big=$(cat $BIG/scaling_governor) gov_little=$(cat $LITTLE/scaling_governor)
	max_big=$(cat $BIG/scaling_max_freq) max_little=$(cat $LITTLE/scaling_max_freq)
	boost=$(cat $BOOST) boost_big=$(cat $BIG/boost) boost_little=$(cat $LITTLE/boost)
	[ -n "$ntc" ] && echo disabled > $ntc/mode
	ok_big="" ok_little="" state=done
	for step in "2300 1700" "2400 1800"; do
		big=${step% *} little=${step#* }
		octest_state "running $big/$little MHz"
		echo 1 > $BOOST; echo 1 > $BIG/boost; echo 1 > $LITTLE/boost
		echo $((big * 1000)) > $BIG/scaling_max_freq
		echo $((little * 1000)) > $LITTLE/scaling_max_freq
		echo performance > $BIG/scaling_governor; echo performance > $LITTLE/scaling_governor
		end=$(( $(date +%s) + 60 ))
		workers=""
		for i in 0 1 2 3 4 5 6 7; do
			( while [ $(date +%s) -lt $end ]; do
				[ "$(sha256sum $blob | cut -d' ' -f1)" = "$ref" ] || exit 1
			done ) &
			workers="$workers $!"
		done
		samples=0 held=0 hot=""
		while [ $(date +%s) -lt $end ]; do
			sleep 2
			samples=$((samples + 1))
			[ "$(cat $BIG/scaling_cur_freq)" = $((big * 1000)) ] &&
				[ "$(cat $LITTLE/scaling_cur_freq)" = $((little * 1000)) ] && held=$((held + 1))
			for z in /sys/class/thermal/thermal_zone*; do
				[ "$(cat $z/temp 2>/dev/null || echo 0)" -gt 90000 ] && hot=1
			done
			[ -n "$hot" ] && break
		done
		bad=""
		for w in $workers; do
			[ -n "$hot" ] && kill $w 2>/dev/null
			wait $w || bad=1
		done
		if [ -n "$hot" ]; then state="stopped at $big/$little MHz: over 90 °C"; break; fi
		if [ -n "$bad" ]; then state="stopped at $big/$little MHz: wrong results"; break; fi
		if [ $((held * 10)) -lt $((samples * 8)) ]; then
			state="stopped at $big/$little MHz: too warm to hold the speed, let it cool"; break
		fi
		ok_big=$big ok_little=$little
	done
	echo $gov_big > $BIG/scaling_governor; echo $gov_little > $LITTLE/scaling_governor
	echo $max_big > $BIG/scaling_max_freq; echo $max_little > $LITTLE/scaling_max_freq
	echo $boost > $BOOST
	if [ "$boost" = 1 ]; then echo $boost_big > $BIG/boost; echo $boost_little > $LITTLE/boost; fi
	[ -n "$ntc" ] && echo enabled > $ntc/mode
	# Kept from an earlier pass if this one stopped sooner (a warmer r1, not a worse one)
	old_big=$(prop oc_ok_big) old_little=$(prop oc_ok_little)
	[ -n "$ok_big" ] && [ "$ok_big" -gt "${old_big:-0}" ] &&
		setprop persist.sys.r1.kt.oc_ok_big $ok_big
	[ -n "$ok_little" ] && [ "$ok_little" -gt "${old_little:-0}" ] &&
		setprop persist.sys.r1.kt.oc_ok_little $ok_little
	case "$state" in *"wrong results"*)
		# A step that gave wrong results is never offered, whatever passed before
		setprop persist.sys.r1.kt.oc_ok_big "$ok_big"
		setprop persist.sys.r1.kt.oc_ok_little "$ok_little" ;;
	esac
	octest_state "$state"
	apply
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
octest)
	octest
	;;
settle)
	sleep 120
	setprop persist.sys.r1.kt.tries 0
	;;
esac
