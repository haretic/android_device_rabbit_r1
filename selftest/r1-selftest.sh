#!/system/bin/sh
# SPDX-License-Identifier: Apache-2.0
#
# r1-selftest: Developer options > Self test. The page (problemreport/, SelfTestActivity) sets
# sys.r1.selftest.run to "<token>:<quick|full>"; init starts this as the shell user in the
# shell domain (r1-selftest.rc), as adb shell would run it, and it runs the suite the build is
# tested with (r1-smoke.sh) on the R1 itself. The page follows it in
# /data/misc/r1-selftest/<token>/:
#
#   status    "running <index> <total> <test>", then "done|cancelled <passed> <failed>
#             <skipped>" or "error <why>"
#   log.txt   the suite's lines: "== <test>", then PASS, FAIL, SKIP and RETRY verdicts
#   shots/    the screenshots and view dumps the tests took
#
# sys.r1.selftest.cancel set to the token stops it between tests. Whatever the tests changed
# in the settings they touch is put back after, and the page comes back to show the result.

# The page, another user, reads all it writes
umask 022
ROOT=/data/misc/r1-selftest
req=$(getprop sys.r1.selftest.run)
token=${req%%:*}
kind=${req#*:}
case "$token" in
	*[!a-z0-9]*|"") exit 1 ;;
esac
DIR=$ROOT/$token
STATUS=$DIR/status

# The quick check: the wheel through home, Settings, dialogs, the shade, the taskbar and the
# screen turned; about five minutes
QUICK="boot dialog shade settings home homepage power_volume rotation edge openwith subpage readorder"

mkdir -p $DIR/shots
chmod 755 $DIR $DIR/shots
# Only the last few runs are kept
ls -dt $ROOT/*/ 2>/dev/null | tail -n +6 | while read -r old; do rm -rf "$old"; done

case "$kind" in
	quick) tests=$QUICK ;;
	full) tests= ;;
	*) echo "error unknown kind $kind" > $STATUS; exit 1 ;;
esac

# The settings the tests change and put back themselves, put back once more whatever happens:
# the Lineage wheel settings, the screen's turn, and the tour
saved=$DIR/settings
content query --uri content://lineagesettings/secure --projection name:value 2>/dev/null \
	| sed -n 's/^Row: [0-9]* name=\(wheel_[a-z_]*\), value=\(.*\)$/\1 \2/p' > $saved
for s in accelerometer_rotation user_rotation; do
	echo "system $s $(settings get system $s)" >> $DIR/android
done
echo "secure r1_wheel_tour_done $(settings get secure r1_wheel_tour_done)" >> $DIR/android
echo "global stay_on_while_plugged_in $(settings get global stay_on_while_plugged_in)" >> $DIR/android
restore() {
	local name value
	while read -r name value; do
		content insert --uri content://lineagesettings/secure --bind name:s:$name \
			--bind "value:s:$(echo "$value" | sed 's/:/\\:/g')" >/dev/null 2>&1
	done < $saved
	while read -r table name value; do
		if [ "$value" = null ]; then
			settings delete $table $name >/dev/null 2>&1
		else
			settings put $table $name "$value"
		fi
	done < $DIR/android
	input keyevent 3
}

echo "running 0 0 starting" > $STATUS
chmod 644 $STATUS
# Its output through a pipe, as adb shell's is: the services the tests call (input, cmd)
# write their replies to it, and may not write this directory's files. mksh keeps its
# here-strings in TMPDIR.
mkdir -p $DIR/tmp
{
	R1_ONDEVICE=1 R1_SHOTS=$DIR/shots R1_STATUS=$STATUS R1_TOKEN=$token TMPDIR=$DIR/tmp \
		/system/bin/sh /system_ext/bin/r1-smoke.sh $tests
	echo $? > $DIR/rc
} 2>&1 | cat > $DIR/log.txt
rc=$(cat $DIR/rc 2>/dev/null)
rm -rf $DIR/tmp $DIR/rc
restore
chmod 644 $DIR/log.txt $DIR/shots/* 2>/dev/null

# By test: failed when its run failed and no retry passed it, skipped when it said so
counts=$(awk '
	/^== retry / { t = $3; retried[t] = 1; next }
	/^== cancelled/ { next }
	/^== / { t = $2; order[++n] = t; next }
	t == "" { next }
	/^RETRY FAIL / { refail[t] = 1; next }
	/^FAIL / { fail[t] = 1; next }
	/^SKIP / { skip[t] = 1; next }
	END {
		for (i = 1; i <= n; i++) {
			t = order[i]
			if (fail[t] && (refail[t] || !retried[t])) f++
			else if (skip[t]) s++
			else p++
		}
		print p + 0, f + 0, s + 0
	}' $DIR/log.txt)
if [ $rc = 3 ]; then
	echo "cancelled $counts" > $STATUS
elif grep -q '^== ' $DIR/log.txt; then
	echo "done $counts" > $STATUS
else
	echo "error the suite didn't run ($rc)" > $STATUS
fi
am start -n cat.kenny.r1.problemreport/.SelfTestActivity --es token $token >/dev/null 2>&1
