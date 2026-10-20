#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# r1-problem-report: the part of Settings > System > Problem report that the page can't do
# itself. The page runs as the system user in system_app, which may not read the kernel log,
# tombstones or ANR traces; this runs as system with the log group in its own domain
# (sepolicy/system_ext/r1_problem_report.te) and writes only to /data/misc/r1-problem-report.
# The page adds what it collects itself, takes personal things out of everything and saves
# the .zip (problemreport/src).
#
#   r1-problem-report.sh check     once Android is up: what the last start left behind, for
#                                  the page, in sys.r1.report.last_start:
#                                  "<panic|reset|normal> <kept|none>"
#   r1-problem-report.sh collect   when the page sets sys.r1.report.collect, after init
#                                  copied the logs of the last start to pstore/ (pstore can
#                                  only be read by a few system domains, init among them, see
#                                  r1-problem-report.rc)
#
# collect writes work/ and reports in sys.r1.report.status: "running <step> <steps> <name>"
# for steps 2 to 4 of the page's 8, then "collected", or "error <why>".

DIR=/data/misc/r1-problem-report
WORK=$DIR/work
PSTORE=$DIR/pstore
STEPS=8

status() { setprop sys.r1.report.status "$*"; }

step() { status "running $1 $STEPS $2"; }

# cap <bytes> : passes at most this much, so one runaway log can't fill /data
cap() { head -c "$1"; }

# run <file> <timeout s> <command...> : appends a command and its output, never hangs
run() {
	local f=$1 t=$2
	shift 2
	{
		echo "\$ $*"
		timeout "$t" "$@" 2>&1 </dev/null
	} | cap 8388608 >>"$WORK/$f"
	echo >>"$WORK/$f"
}

# show <file> <path...> : appends files with their names
show() {
	local f=$1 p
	shift
	for p in "$@"; do
		[ -f "$p" ] && [ -r "$p" ] || continue
		echo "== $p" >>"$WORK/$f"
		cap 1048576 <"$p" >>"$WORK/$f" 2>&1
		echo >>"$WORK/$f"
	done
}

# bootstat's verdict on how the last start ended, from the bootloader's reason and the
# last kernel log (it reads pstore, we may not)
how_it_ended() {
	case "$(getprop sys.boot.reason)" in
	kernel_panic*) echo panic ;;
	watchdog*) echo reset ;;
	*) echo normal ;;
	esac
}

check() {
	local kept=none f
	# The kernel log of the last start: ramoops (LK puts ramoops.* on the command line), or
	# pstore_blk on expdb when r1-expdb set that up instead
	for f in /sys/fs/pstore/console-*; do
		[ -e "$f" ] && kept=kept
	done
	setprop sys.r1.report.last_start "$(how_it_ended) $kept"
}

collect() {
	local f
	umask 007
	rm -rf "$WORK"
	if ! mkdir -p "$WORK"; then
		status "error cant_write"
		exit 1
	fi

	step 2 logcat
	run logcat.txt 60 logcat -d -b all -v threadtime

	step 3 kernel
	run kernel.txt 10 dmesg -S
	{
		echo "Boot reason (bootstat): $(getprop sys.boot.reason)"
		echo "How the last start ended: $(how_it_ended)"
		echo
		echo "Kept from the last start in /sys/fs/pstore (names, sizes and times):"
		ls -l /sys/fs/pstore 2>&1
		echo
		# The kernel's crash records, "Panic#1 Part1" or "Oops#1 Part1". The slots are
		# reused from the first at every start, so a record can be older than the last start.
		for f in "$PSTORE"/dmesg-*; do
			[ -s "$f" ] && echo "${f##*/}: $(head -n 1 "$f")"
		done
	} >"$WORK/last-start.txt" 2>&1
	{
		echo "SELinux denials in this start's kernel and Android logs:"
		grep -h 'avc: *denied' "$WORK/kernel.txt" "$WORK/logcat.txt"
		echo
		echo "SELinux denials in the last start's kernel log:"
		for f in "$PSTORE"/console-*; do
			[ -s "$f" ] && grep -h 'avc: *denied' "$f"
		done
	} 2>/dev/null | cap 2097152 >"$WORK/selinux-denials.txt"

	step 4 crashes
	run crashes.txt 5 ls -l /data/tombstones /data/anr
	# The newest few: the text tombstones (not their .pb twins) and ANR traces
	for f in $(ls -t /data/tombstones/tombstone_* 2>/dev/null | grep -v '\.pb$' | head -n 3) \
		$(ls -t /data/anr/* 2>/dev/null | head -n 2); do
		show crashes.txt "$f"
	done

	status collected
}

case "$1" in
check)
	check
	;;
collect)
	collect
	;;
esac
