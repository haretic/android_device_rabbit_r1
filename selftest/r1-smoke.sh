#!/bin/bash
# r1-smoke.sh [test...]: automated checks on the R1 after a flash. Screenshots and view dumps go
# to /rabbitr1/out/shots/smoke-<test>-*.png|.txt, a verdict line per check to stdout.
# Every device call is bounded; nothing here needs a person at the device.
#
# From a host it drives the R1 over adb as root (bash). On the R1 itself it is the self test
# (r1-selftest.sh sets R1_ONDEVICE): the same calls run here, as the shell user, under mksh;
# the wheel turns through r1-wheeld, as the shell may not write input devices, and the few
# tests that need root to read another app's data say SKIP.
set -u
SHOTS=${R1_SHOTS:-/rabbitr1/out/shots}
if [ -n "${R1_ONDEVICE:-}" ]; then
	adb() { :; }
	sh() { timeout 60 /system/bin/sh -c "$*"; }
	shot() { timeout 30 screencap -p > $SHOTS/smoke-$1.png; }
	rawshot() { timeout 30 screencap > $SHOTS/smoke-$1.raw; }
	wheel() {
		local way=down
		[ "$1" = up ] && way=up
		timeout 20 r1-wheeld inject $way ${2:-1}
	}
	# One step, as a command line for sh to run among others
	step_cmd() { echo "r1-wheeld inject $1 1"; }
else
	# R1SH runs "adb ..." against the R1 (as over ssh to the host it is plugged into)
	R=${R1SH:-/tmp/r1sh}
	adb() { timeout 40 $R "adb $*"; }
	sh() { timeout 60 $R "adb shell '$*'"; }
	shot() { timeout 30 $R "adb exec-out screencap -p" > $SHOTS/smoke-$1.png; }
	rawshot() { timeout 30 $R "adb exec-out screencap" > $SHOTS/smoke-$1.raw; }
	# A wheel step through the wheel's own node: r1-wheeld has it grabbed and takes it as real
	wheel() {
		local code=108 n=${2:-1}
		[ "$1" = up ] && code=103
		local cmds=""
		for _ in $(seq $n); do
			cmds="$cmds sendevent /dev/input/event3 1 $code 1; sendevent /dev/input/event3 0 0 0; sendevent /dev/input/event3 1 $code 0; sendevent /dev/input/event3 0 0 0; sleep 0.15;"
		done
		sh "$cmds"
	}
	step_cmd() {
		local code=108
		[ "$1" = up ] && code=103
		echo "sendevent /dev/input/event3 1 $code 1; sendevent /dev/input/event3 0 0 0; sendevent /dev/input/event3 1 $code 0; sendevent /dev/input/event3 0 0 0"
	}
fi
# Never reuse a stale hierarchy when uiautomator times out waiting for a live meter to idle.
# The tiny shell-only dex helper uses the system's existing accessibility dumper, taking a
# current snapshot without an idle wait. It installs no APK. Source/rebuild notes: SmokeUiDump.java.
ui_fast=${R1_UI_FAST:-0}
ui_tmp=/data/local/tmp/r1-smoke-ui-$$
ui_fast_dump() {
	local encoded
	encoded='UEsDBBQAAAAIAAAAIQD6LxSHWQkAAGwQAAALAAAAY2xhc3Nlcy5kZXh9mGtsHFcVx8+dfe/OetZre+1sE2e9G4jbxq/Yjtdx2zR14tTBTkKcOE1StYx3r+1pZmc2M7N+tLRKQUorkEolWlqo+MCjFAQSSBWqhChfmkoVSAVVQsoHKlqJqAJUofIBVXwJ/3tn1t4Q6Do/n3PPfZ17zplZ31T5RnJ4dJIu3Iy9+wdz+oUfvfPvay89eXY+/GntOzc++vkhM0NUJ6KNxbEsBZ8fa0QPk2/Pgw8YUTfkgwoR/tHDIaI+SBMSXeSFia6niF6AXEwQnQc62ARfBlfAV8FV8DXwHPgm+D74DXgX3ACfAi1JNA4eAjXwNHgR/AJcA9fBP0AG+90NjoFHgA2+Dl4Fb4Dfgz+mfL/+BD4AfwEfgb+Dm6BTJboTzIAvgQ3wGngTvA1uggNpokPgGFgA58El4IINcAVcBd8A3wY/AK+DN8FvwXvgffBX8E8QaSPqAkUwCWbAWbAM6mADXAHPgufAK+CH4Kfgl+DX4G3wDvgdQAooAhASwpEILhO2IXQR0khIL7UDkdwO0Am6QI78nPaAHUGe7wAlsBfcCe4Cd4N95O/T/IQDmUn4e7OgHQ0Qn3ig92BMLPCvAD0R+Nmf8McIfTjhj1Vb5qaDudHgDE27OEs5mJsNxiSCs92f8P3vDMbvCXSx7+cC/UHonw/0nhb9VMI/d1cwtz/QM4EPuZYz7gj0JFpFGesIHSTxbLTJWCjwbCKQp2W82qU9DC/HAzkVyFNSKvRFmcd2OiP3CNO9UqbofilVOixlmo5ImaOjUsZpTsbFXz8RSBHrE1JG6CEZY9+vFLJ4SMa5kyZljH17GjNHpIzTojyPRveQqJ8MnZXxT9J9MvYxWpBxZzQtYx6nGSm76JiUUTpJ/juiPcit+BTA8yhGV/PbTP7gOYn7tVTPdMhdM7BGMPsN2B9noiYPUFmrs75PstnRPpMVWZiyoRlNC9ULIfqErEwEM5JaWWuHHg70EGqrqCUwW6F6IYNT7NXy2ihdRio1UrXSWI6sQhKequHSeDf0nYi6Gs5Hxqh0AH3DKj0aVSOlA+gbLtDRaD4y/396VJR/NsomYqsdiV4af6lAk/FeysWrdLmQp5cpHx+Q+8ZJjecTx6l00F/l5aQaL036qzyUUOPXYzHW/+dy/AblI0NyRoTUSD72AJUm/RmNhBprzliBnk88SKW7crSEIM+nkiEx5sWkGrmcySLLamJPphtZVRNiPOZirWG5bozE3EGpi37pEaLxE1KTpSk/Gr+Cnk/NUqmYo/mEItd+Q1EVsWaS1JRY83Qqr3yBSlO+d3tTqlKa8L07HVOV3UoSGWlHBpJKPrJ/60RWJi1l3zk2Nf7EMt68kZSV6UVFqul8Wxm7d9HHlEyX02cxf7e0972ffWT043kSJ1NIxXrnMS5F58V6hV30KiXlqf2IXZB9mzil6HuNkjHRJ06951w39ZxWFeGjqViZqKgXpaz0YKcY9BTLIZsjjEXKrI1KI/7JNIaKGfZPdhI1WmSoLGWXrKw+2suup1Ks/xXf2rtl9ds7t9ppVtofrKe0roeRWhyVuhsjNVmp4r3BGKPjTLyPxXNyQxFVriCr/8Jz8Sj6L2cYnpv+i2nKsmy2HG6nvunREnwLhWkmHGbi6ZiWTwSeBSUdvK47wnEaP9wFu3jPq+FyWNvS94cTTZ2uY/v+vy2FGc6axrOZw+75UB/i2o23hMqsTKccZ2XafAk7g8zDY6FnZF9P0BcXnkofLmd2yDek/wlhTkK+c0I4Pb4Lkb8QxnbT+QgGYbG2Z55hXZpx7b0Pe7914dXv3T9gfMh6NMN4i7VrrFO7CQzWm76XZTqVJ17f+ZVpxb6qdFx9S3lkF8UxXWmbUtxdfSyrDfb2zhrvsb3awL3Be9D/zlJp+7tLkfZu2e4O7LnArlJOtpv0ELuTovcYluHdR7lp3bJsr+Dwmr3GC7ZZLVQbtTqxWVJm54gdJzZHyhxac3MUmsOv9NxCzb7EzxpHMG6K8nNV3VwzLg3JdXTPsK2hM6uOve5OUdvcY/qaPmTYQzOGyaeoa6t9yjEsb8FzuF6bonbfbOrWytC0qbvu1khpOrpR4XWx7hTtaTHPmiZf0c3Dzkqjxi2vZVTf7aMW4BlvGdLROsTy+Ap3pijbYjy59BiveLfa4K9hrUzRjttsDzQMs/rfSyxsuh6v3WpDZLhevfV8Mlr6kgjQ7hazw5dNuDA0bVuu5zQqnu3cunVzwIzBzapIxO1d89xbtcV2J+yCXvEMZHjdsKr2esGxbY80mEW2C+uO4XncojwMbqNetx2P+4XAnUJNLkI9Jy3UR8OrN7zCMtKJkrncMBxepUxLQQwKJyjXYjlYsIJd+il85vypo8QWSVlERS0eB6ixC6RcmKPsxdvLoOPi/0jJLcZmTu64+BnH79GtqmMb1UG9Xh88axxueHZNVirlmj0rjl5fNSru4CkblUkDTfuq7lTXdYcPVg23buqbg0d8Oa9bOormmGkv6SZ1NoevGXy9OYSi2I5bVSpX7Npgc0TD0P3tbWewYmPhw5UKd11jyTANb/OEXeWz1rJ9RIaexj9zZutJFla5aZ5zxI4OxSq2ZSEAFK1yk3uckvC+aQvL5zsjfp+TtXDGFk8nRZFO3XQpxB0HjQ3D9VwKQ3oUW7adE3qNU3yFe9N6ww00kSRqE9p2jcoeWZKUgjaLDt2qYHnZ8CgB6eeFkluqSzHocot2KKd0B6rHnTObde7vcJrrZjOuqaC9YDzOqUs0UMyz1mFZ4P6Z/DHB+4jSaCwYtbrJ5RYamrdUQRwpX9TNBrw0rDVULqTrB8Xk1oq3SuGabliUsvj61oFidfEOMy2KuCbndYp7tl+MFFmTa8WkOLlMbIPYJn2XPfXUkfITxSW9cglVUTxYrPKN4r4iUlw3TOnIQA3plx1LjRV0reruQGWVVy65jZpbPLiM9PB9xZphDeh1o3hwdHxf0V3VB0YwZXSJV8YmDiwtLS9PlMeGJyaGx6COjFR0fWyY6+WRieXKUnlZx6pr3HGxGSZNDu4fHNk/UOVrxSdJCbFzO1lPTF6Wnr4SfiHBkj9LsMQ1LfjeuBT8/Ust3ztCPqtt33Fa7c07uPgOat7DQ7R9Fw/T9n1c3MOad/Iobd/LWcGfI+7moUAXf3ezjH9vEvcbpeDvJe7u4UKLr4Eu/m4PZbZ9jQb254MLptDF/xn8B1BLAQIUAxQAAAAIAAAAIQD6LxSHWQkAAGwQAAALAAAAAAAAAAAAAACAAQAAAABjbGFzc2VzLmRleFBLBQYAAAAAAQABADkAAACCCQAAAAA='
	sh "[ -s $ui_tmp.jar ] || (echo $encoded | base64 -d > $ui_tmp.jar); rm -f $ui_tmp.xml; if CLASSPATH=$ui_tmp.jar:/system/framework/uiautomator.jar app_process /system/bin SmokeUiDump $ui_tmp.xml >/dev/null 2>$ui_tmp.err && [ -s $ui_tmp.xml ]; then cat $ui_tmp.xml; else rm -f $ui_tmp.xml; cat $ui_tmp.err >&2; exit 1; fi" > $SHOTS/smoke-$1.xml
}
ui() {
	if [ "$ui_fast" = 1 ]; then ui_fast_dump "$1"; return; fi
	sh "rm -f $ui_tmp.xml; if uiautomator dump $ui_tmp.xml >$ui_tmp.err 2>&1 && [ -s $ui_tmp.xml ]; then cat $ui_tmp.xml; else rm -f $ui_tmp.xml; cat $ui_tmp.err >&2; fi" > $SHOTS/smoke-$1.xml
	if ! grep -q '<hierarchy' $SHOTS/smoke-$1.xml; then
		echo "UI dump: no fresh idle snapshot; using live accessibility snapshot" >&2
		ui_fast=1
		ui_fast_dump "$1"
	fi
}
ui_cleanup() { sh "rm -f $ui_tmp.jar $ui_tmp.xml $ui_tmp.err"; }
# A view dump's nodes, a line each: depth, then focused, package, class, resource-id, bounds as
# "l t r b", text and content-desc, tab-separated. awk, as the R1 has it and no python
xnodes() {
	awk 'BEGIN { RS = "<"; OFS = "\t" }
		function attr(name,   m) {
			if (!match($0, " " name "=\"[^\"]*\"")) return ""
			m = substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
			gsub(/&quot;/, "\"", m); gsub(/&apos;/, "\047", m); gsub(/&lt;/, "<", m)
			gsub(/&gt;/, ">", m); gsub(/&amp;/, "\\&", m)
			return m
		}
		/^node / {
			b = attr("bounds"); gsub(/[^0-9]+/, " ", b); sub(/^ /, "", b); sub(/ $/, "", b)
			print depth, attr("focused"), attr("package"), attr("class"), attr("resource-id"), b,
				attr("text"), attr("content-desc")
			if ($0 !~ /\/>[ \t\r\n]*$/) depth++
		}
		/^\/node>/ { depth-- }' $SHOTS/smoke-$1.xml
}
# The focused node's line (see xnodes) marked "self", then its views inside marked "in" and
# the views round it "out", the nearest first: the marker is field 1, the node's fields follow
xfocus() {
	xnodes $1 | awk -F '\t' -v OFS='\t' '
		!found && $2 == "true" { found = 1; d = $1; print "self", $0; for (i = d - 1; i >= 0; i--) print "out", up[i]; next }
		found == 1 && $1 > d { print "in", $0; next }
		found == 1 { exit }
		{ up[$1] = $0 }'
}
# Whether two rawshots differ in the box from (x0, y0) to (x1, y1), the far edges out. A raw
# frame is a header (width first), then four bytes a pixel; cmp, as the R1 has no python
shots_differ() {
	local a=$SHOTS/smoke-$1.raw b=$SHOTS/smoke-$2.raw w h head
	w=$(od -An -tu4 -N4 $a | tr -d ' '); h=$(od -An -tu4 -j4 -N4 $a | tr -d ' ')
	head=$(($(wc -c < $a) - w * h * 4))
	cmp -l $a $b 2>/dev/null | awk -v w=$w -v head=$head -v x0=$3 -v y0=$4 -v x1=$5 -v y1=$6 '
		{ i = int(($1 - 1 - head) / 4); x = i % w; y = int(i / w)
		  if (i >= 0 && x >= x0 && x < x1 && y >= y0 && y < y1) { found = 1; exit } }
		END { exit !found }'
}
# The screen's turn back as it was. A view dump's uiautomator, done, puts back the turn it
# found, locked, a moment later: on the R1 that lands after these writes, so they go again
# until they hold (from a host adb's own delay lets it land first)
rotation_back() {
	local i
	for i in 1 2 3 4 5; do
		sh "settings put system user_rotation $1; settings put system accelerometer_rotation $2"
		sleep 1.5
		[ "$(dev "settings get system accelerometer_rotation")" = "$2" ] && \
			[ "$(dev "settings get system user_rotation")" = "$1" ] && return 0
	done
	echo "the screen's turn didn't stay as it was ($1, $2)"
}
# The screen locked to rotation $1. Auto-rotate off makes the window manager write the
# rotation it is at into user_rotation, which can land after a user_rotation written just
# behind it; its own lock sets both at once. The display follows once an app that lets it
# turn is in front: the launcher, auto-rotate off, keeps the rotation it has (NOSENSOR).
turn() {
	local i
	sh "cmd window user-rotation lock $1"
	for i in 1 2 3 4 5 6; do
		[ "$(dev "settings get system user_rotation")" = "$1" ] && \
			[ "$(dev "settings get system accelerometer_rotation")" = 0 ] && return 0
		sleep 0.5
	done
	echo "the screen didn't lock to rotation $1"
}
# A field of the focused node (2 focused ... 8 content-desc, see xnodes)
focus_field() { xfocus $1 | head -1 | cut -f$(($2 + 1)); }
key() { sh "input keyevent $*"; }
# Lineage secure settings: lget name, lset name value (empty clears)
lget() { dev "content query --uri content://lineagesettings/secure --projection name:value" | grep "name=$1," | sed 's/.*value=//'; }
# content splits a binding at every colon: those in the value go escaped
lset() { local v; v=$(printf '%s' "$2" | sed 's/:/\\\\:/g'); sh "content insert --uri content://lineagesettings/secure --bind name:s:$1 --bind value:s:$v"; }
ok() { echo "${retry:+RETRY }PASS $*"; }
# A test that reads another app's data, which only the host's root may: skipped on the R1
host_only() {
	[ -z "${R1_ONDEVICE:-}" ] && return 0
	echo "SKIP $cur: needs a host with root"
	return 1
}
bad() { echo "${retry:+RETRY }FAIL $*"; [ -n "${retry:-}" ] || failed="$failed $cur"; }
# The browser tests drive Jelly's WebView; builds with Cromite in its place have no Jelly
has_jelly() {
	dev "pm path org.lineageos.jelly" | grep -q package: && return 0
	echo "SKIP $cur: no Jelly, Cromite is the browser"
	return 1
}
failed=; retry=; cur=
dev() { sh "$*" | tr -d '\r'; }

# Preserve the crash buffer across log clears. A per-run baseline excludes older entries;
# the ledger deduplicates snapshots and excludes only adbd's documented restart abort.
crash_entries() {
	awk 'BEGIN { OFS="\t" }
		{ gsub(/\r/, ""); gsub(/\t/, " "); line=$0; name="" }
		/AndroidRuntime:[ ]*Process: / {
			name=line; sub(/^.*AndroidRuntime:[ ]*Process: */, "", name); sub(/, PID:.*/, "", name)
			lines[++n]=line; names[n]=name
		}
		/Fatal signal/ {
			pid=""
			if (match(line, /pid[ ]+[0-9]+[ ]+\([^)]*\)/)) {
				part=substr(line,RSTART,RLENGTH)
				pid=part; sub(/^pid[ ]+/,"",pid); sub(/[ ].*$/,"",pid)
				name=part; sub(/^.*\(/,"",name); sub(/\)$/, "", name)
			} else name="<unknown native process>"
			lines[++n]=line; names[n]=name
			if (pid!="") native[pid]=n
			abort[n]=(line ~ /Fatal signal[ ]+6[ ]+\(SIGABRT\)/)
		}
		# Match the tombstone to its fatal-signal event, not every crash named adbd.
		/DEBUG[ ]*: pid:[ ]*[0-9]+,/ {
			pid=line; sub(/^.*DEBUG[ ]*: pid:[ ]*/,"",pid); sub(/,.*/,"",pid)
			record[$3]=native[pid]
		}
		/DEBUG[ ]*: Abort message: / {
			message=line; sub(/^.*Abort message: /,"",message)
			e=record[$3]; quote=sprintf("%c",39)
			if (e && names[e]=="adbd" && abort[e] && message==quote "terminating" quote) benign[e]=1
		}
		END { for(i=1;i<=n;i++) if(!benign[i]) print lines[i],names[i] }'
}
crash_read() {
	if ! sh 'logcat -b crash -d -v threadtime' > "$crash_prefix-raw.txt"; then
		crash_failed=1
		echo 'Crash tally: could not read the crash buffer' >&2
		return 1
	fi
	if ! crash_entries < "$crash_prefix-raw.txt" > "$crash_prefix-current.tsv"; then
		crash_failed=1; return 1
	fi
}
crash_init() {
	crash_prefix="$SHOTS/smoke-crash-$$"
	crash_failed=0; crash_ready=1
	if ! mkdir -p "$SHOTS" || ! { : > "$crash_prefix-seen.txt" && : > "$crash_prefix-events.tsv"; }; then
		crash_failed=1; return 1
	fi
	# Baseline before the first input; boot's existing historical check is unchanged.
	if crash_read; then
		cut -f1 "$crash_prefix-current.tsv" > "$crash_prefix-seen.txt" || { crash_failed=1; return 1; }
	fi
}
crash_collect() {
	[ "${crash_ready:-0}" = 1 ] || return 0
	crash_read || return 1
	if ! awk -F '\t' 'FILENAME==ARGV[1] { seen[$0]=1; next }
		!($1 in seen) { print; seen[$1]=1 }' "$crash_prefix-seen.txt" "$crash_prefix-current.tsv" > "$crash_prefix-new.tsv"; then
		crash_failed=1; return 1
	fi
	cat "$crash_prefix-new.tsv" >> "$crash_prefix-events.tsv" || { crash_failed=1; return 1; }
	cut -f1 "$crash_prefix-new.tsv" >> "$crash_prefix-seen.txt" || { crash_failed=1; return 1; }
	awk -F '\t' '{ print "App crash: " $2 }' "$crash_prefix-new.tsv"
}
logcat_clear() {
	# Record first, and do not clear anything if the record could not be obtained.
	crash_collect || return 1
	# Leaving crash intact also closes the read/clear race for a newly arriving crash.
	sh 'logcat -b main -b system -c'
}
crash_final() {
	[ "${crash_ready:-0}" = 1 ] || return 0
	local total names
	crash_collect || :
	total=$(awk 'END { print NR+0 }' "$crash_prefix-events.tsv")
	names=$(awk -F '\t' '!seen[$2]++ { text=text sep $2; sep=", " } END { print text }' "$crash_prefix-events.tsv")
	if [ "${crash_failed:-0}" != 0 ]; then
		bad "no app crashed during the run: crash tally incomplete${names:+; observed $names}"
		return 1
	fi
	if [ "$total" = 0 ]; then ok 'no app crashed during the run'
	else bad "no app crashed during the run: $total entries ($names)"; return 1; fi
}
runner_cleanup() {
	local result=$1
	bottomstrip_end
	stress_cancel_end; pageprefs_end; synth_loop_end; r2_resources_end; app_abort; toolbox_silent_end; powerhold_settings_end; sweep_end
	sh "settings put global stay_on_while_plugged_in ${stay:-0}"
	ui_cleanup
	retry=; cur=crashes
	if ! crash_final; then [ "$result" != 0 ] || result=1; fi
	exit "$result"
}

t_boot() {
	[ "$(dev getprop sys.boot_completed)" = 1 ] && ok boot || bad boot
	dev getprop ro.build.date
	local crashes
	# Apps and native processes: a shell tool that throws (a screen dump cut short) names no process
	crashes=$(dev "logcat -b crash -d -v threadtime" | crash_entries | awk 'END { print NR+0 }')
	[ "${crashes:-0}" = 0 ] && ok "no crashes" || { bad "crashes: $crashes"; dev "logcat -b crash -d | grep -A6 -e AndroidRuntime:.Process: -e Fatal.signal | head -60"; }
	local denials
	denials=$(dev "logcat -d | grep -c avc:.*denied")
	echo "selinux denials: $denials"
	dev "logcat -d | grep avc:.*denied | sed s/.*avc:/avc:/ | cut -d\" \" -f1-12 | sort | uniq -c | sort -rn | head -10"
}

t_dialog() {
	# The wheel's dialog buttons back on, then an alert dialog
	lset wheel_dialogs 1
	key 224; sleep 1
	sh "wm dismiss-keyguard"; sleep 1
	# Bluetooth's "make visible" question: an alert dialog, which names the device too
	sh "am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE"
	sleep 3; shot dialog; ui dialog
	grep -qE 'resource-id="android:id/wheel_dialog_buttons"|button1' $SHOTS/smoke-dialog.xml && ok "dialog shows" || bad "dialog shows"
	[ "$(dev "logcat -b crash -d | grep -c application-specific")" = 0 ] && ok "no setTag crash" || bad "setTag crash"
	key 4
}

t_pin() {
	local after unlock
	after=$(dev "settings get secure lock_screen_lock_after_timeout")
	unlock=$(lget wheel_unlock)
	# The drum, not the dial the bouncer opens on by default
	lset wheel_unlock 0
	sh "locksettings set-pin 1234; settings put secure lock_screen_lock_after_timeout 0"
	key 223; sleep 3; key 224; sleep 2; key 82; sleep 2
	ui pin; shot pin
	grep -q 'wheel_pin_drum' $SHOTS/smoke-pin.xml && ok "pin drum in view tree" || bad "pin drum in view tree"
	# Unlock with the PIN as a user would (keys reach the drum as digits), then drop it
	sh "input text 1234; input keyevent 66"; sleep 2
	ui pin-after
	grep -q 'wheel_pin_drum' $SHOTS/smoke-pin-after.xml && bad "PIN unlocks" || ok "PIN unlocks"
	sh "locksettings clear --old 1234"
	sh "wm dismiss-keyguard; settings put secure lock_screen_lock_after_timeout $after"
	lset wheel_unlock "$unlock"
}

# Whether the keyguard is up
locked() { dev "dumpsys window" | grep -qE 'mDreamingLockscreen=true|isKeyguardShowing=true|mShowingLockscreen=true'; }

t_passthrough() {
	# A container an app focuses, as Files' whole window or the Dialer's box round its one
	# button, is no stop: the first step lands on something in it, not the container
	local a f w h
	for a in com.android.documentsui/.LauncherActivity com.android.dialer/.main.impl.MainActivity; do
		key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
		sh "am start -W -S -n $a >/dev/null 2>&1"; sleep 3.5
		wheel down 1 >/dev/null; sleep 0.8; ui passthrough; shot passthrough-${a%%/*}
		f=$(focused_in passthrough)
		w=$(echo "$f" | awk '{print $4 - $2}'); h=$(echo "$f" | awk '{print $5 - $3}')
		if [ -n "$f" ] && [ $((w * h)) -lt $((480 * 640 / 3)) ]; then
			ok "${a%%/*}: the first step lands in the container ($f)"
		else
			bad "${a%%/*}: the first step stopped on '${f:-nothing}', a container"
		fi
	done
	key 3
}

# A bouncer can expose its stock PIN view while its wheel pad is being prepared.
# Do not send a combination or its first press until the actual pad has settled.
dial_pad_signature() {
	awk -v kind="$2" 'BEGIN {RS="<"}
		function attr(name, m) {
			if(!match($0," " name "=\"[^\"]*\""))return ""
			return substr($0,RSTART+length(name)+3,RLENGTH-length(name)-4)
		}
		function rect(b) {gsub(/[^0-9-]+/," ",b);sub(/^ /,"",b);sub(/ $/,"",b);return b}
		function visible(b,min, a) {
			split(b,a," ");return a[1]>=left && a[2]>=top && a[3]<=right && a[4]<=bottom && a[3]-a[1]>=min && a[4]-a[2]>=min
		}
		/^node / {
			pkg=attr("package");id=attr("resource-id");b=rect(attr("bounds"))
			if(++nodes==1){split(b,s," ");left=s[1];top=s[2];right=s[3];bottom=s[4];root=pkg=="com.android.systemui" && right>left && bottom>top}
			if(pkg!="com.android.systemui")next
			if(id=="com.android.systemui:id/wheel_pin_touch_keypad" && visible(b,1) && attr("enabled")=="true" && attr("clickable")=="true")controls=1
			if(id~/^com\.android\.systemui:id\/key[0-9]$/ && visible(b,1))stock=1
			if(id=="com.android.systemui:id/wheel_pin_" kind && visible(b,24) && attr("class")=="android.view.View" && attr("enabled")=="true" && attr("focusable")=="true" && attr("clickable")=="true")pad=b
			if(id=="com.android.systemui:id/wheel_pin_" (kind=="dial"?"drum":"dial") && visible(b,1))other=1
		}
		END{if(root && controls && pad!="" && !stock && !other)print kind "|" left " " top " " right " " bottom "|" pad}' "$SHOTS/smoke-$1.xml"
}
dial_wait_pad() {
	local kind=$1 snap=$2 i signature last= end=$(( $(date +%s) + 10 ))
	for i in $(seq 1 30);do
		ui "$snap"
		signature=$(dial_pad_signature "$snap" "$kind")
		if [ -n "$signature" ] && locked;then
			[ "$signature" != "$last" ] || return 0
			last=$signature
		else last=;fi
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .2
	done
	return 1
}
dial_failure() {
	bad "$2"
	ui "$1-failed";shot "$1-failed"
	dev 'dumpsys window' > "$SHOTS/smoke-$1-window.txt"
	dev 'dumpsys power' > "$SHOTS/smoke-$1-power.txt"
	dev 'dumpsys input' > "$SHOTS/smoke-$1-input.txt"
	dev 'logcat -d -t 1500 -v threadtime' > "$SHOTS/smoke-$1-events.log"
	{ lget wheel_ui;lget wheel_lockscreen;lget wheel_unlock; } > "$SHOTS/smoke-$1-wheel-settings.txt"
}

t_dial() {
	# The combination dial: wrong/right PINs, then one press before any turn to use the drum.
	local after unlock ui_fast=1
	after=$(dev "settings get secure lock_screen_lock_after_timeout")
	unlock=$(lget wheel_unlock)
	lset wheel_unlock 1
	sh "locksettings set-pin 3141; settings put secure lock_screen_lock_after_timeout 0"
	key 223; sleep 3; key 224; sleep 2; key 82
	if dial_wait_pad dial dial;then
		shot dial;ok "the bouncer opens on the combination dial"
		# A wrong combination, 3 1 4 2
		wheel down 3 >/dev/null; wheel up 1 >/dev/null; wheel down 4 >/dev/null; wheel up 2 >/dev/null
		shot dial-turned; side_press; sleep 2.5
		locked && ok "a wrong combination keeps the lock" || bad "a wrong combination unlocked"
		# The right one
		wheel down 3 >/dev/null; wheel up 1 >/dev/null; wheel down 4 >/dev/null; wheel up 1 >/dev/null
		ui dial-right
		side_press; sleep 2.5
		if locked;then
			bad "the combination 3 1 4 1 left the lock up (see $SHOTS/smoke-dial-right.xml, dial-fail.log)"
			dev "logcat -d -t 300" | grep -iE 'keyguard|bouncer|wheel|lockout|LockSettings' > "$SHOTS/dial-fail.log"
		else ok "the combination unlocks";fi
		# Capture the second opening before input: no wheel step or extra press may
		# manufacture readiness for this press-before-turn assertion.
		key 223; sleep 3; key 224; sleep 2; key 82
		if dial_wait_pad dial dial-before-press;then
			side_press
			if dial_wait_pad drum dial-drum;then
				shot dial-drum;ok "a press before any turn picks the digits on the drum"
			else
				dial_failure dial-drum 'one press from a verified ready dial did not show the drum'
			fi
		else
			dial_failure dial-before-press 'TEST setup: second bouncer did not present a stable ready combination dial; no press sent'
		fi
	else
		dial_failure dial 'TEST setup: first bouncer did not present a stable ready combination dial; no combination sent'
	fi
	# Always remove the temporary PIN and restore settings, including failed setup.
	key 4; sleep 1
	sh "locksettings clear --old 3141"
	key 223; sleep 2; key 224; sleep 1
	sh "wm dismiss-keyguard; settings put secure lock_screen_lock_after_timeout $after"; sleep 1
	locked && bad "the lock screen stayed up after the dial test" || ok "the dial test leaves the device unlocked"
	lset wheel_unlock "$unlock"
}

t_shade() {
	key 224; key 3; sleep 1
	sh "cmd statusbar expand-notifications"; sleep 2; shot shade
	sh "cmd statusbar expand-settings"; sleep 2; shot qs
	wheel down 3; sleep 1; shot qs-wheel
	sh "cmd statusbar collapse"
}

t_settings() {
	key 224
	sh "am start -W -n com.android.settings/.Settings"; sleep 2
	shot settings-top
	key 20; key 20; key 20; sleep 1; shot settings-ring
	ui settings
	grep -q 'search_action_bar' $SHOTS/smoke-settings.xml && echo "search bar node present (check it is collapsed)"
	key 4; key 3
}

t_home() {
	key 224; sh "wm dismiss-keyguard"; key 3; sleep 2; shot home
	ui home
	grep -q 'wheel_home_tabs' $SHOTS/smoke-home.xml && ok "home tabs" || bad "home tabs (list profile on?)"
	# The wheel goes through the list, not to the taskbar: a row has the focus, the bar is shut
	wheel down 1; sleep 1; ui home-wheel
	echo "focused: $(focus_field home-wheel 3) $(focus_field home-wheel 7)"
	[ "$(focus_field home-wheel 3)" = com.android.launcher3 ] && ok "wheel moves through home" || bad "wheel on home went elsewhere"
	# The tabs: stepped up to, then the side button for the next one
	wheel up 6; sleep 0.8
	side_press
	sleep 1.2; shot home-tabs-focus; ui home-tab
	# The next tab, named after its first app: any tab but Home
	tab=$(grep -o 'wheel_home_tabs"[^>]*' $SHOTS/smoke-home-tab.xml | grep -o 'content-desc="[^"]*"')
	[ -n "$tab" ] && [ "$tab" != 'content-desc="Home"' ] && ok "side button: next tab ($tab)" || bad "side button left the tab ($tab)"
	sh "input keyevent 3"
}

t_power_volume() {
	# The app under the power menu in volume mode: the wheel must move the menu, not the volume.
	# The side button's hold never opens the menu; IWindowManager.showGlobalActions (code 119) does
	local saved before after
	saved=$(lget wheel_app_modes)
	lset wheel_app_modes net.sourceforge.opencamera=volume
	key 224
	sh "monkey -p net.sourceforge.opencamera -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"; sleep 2
	before=$(dev "cmd media_session volume --stream 3 --get | grep -o \"volume is [0-9]*\"")
	sh "service call window 119 >/dev/null"; sleep 2; shot power
	wheel down 2; sleep 1; shot power-wheel
	after=$(dev "cmd media_session volume --stream 3 --get | grep -o \"volume is [0-9]*\"")
	[ "$before" = "$after" ] && ok "power menu keeps volume ($before)" || bad "power menu changed volume: $before -> $after"
	key 4; sleep 1
	lset wheel_app_modes "$saved"
	key 3
}

t_touch() {
	# Touch zones on Settings: a step down taps the bottom of the screen
	local saved i window ready=0
	saved=$(lget wheel_app_modes)
	lset wheel_app_modes com.android.settings=touch:500:300:500:800
	key 224
	# A warm Settings task can still have Settings Intelligence Search above its root.
	# The mode is package-specific: never count a step sent to that other package.
	sh "am start -W -S -f 0x10008000 -n com.android.settings/.Settings"
	for i in $(seq 1 20); do
		window=$(focus_win)
		case "$window" in *" com.android.settings/"*) ready=1; break;; esac
		sleep .25
	done
	if [ "$ready" != 1 ]; then
		ui touch; shot touch; bad "touch: Settings did not become foreground ($window)"
		lset wheel_app_modes "$saved"; key 3; return
	fi
	# The touches r1-wheel-touch sends for one step down, read while the step goes in
	sh "getevent -c 12 /dev/input/event6 > /sdcard/touch.txt 2>&1 & sleep 0.5; $(step_cmd down); sleep 1; kill %1 2>/dev/null"
	local n
	n=$(dev "grep -c . /sdcard/touch.txt")
	if [ "${n:-0}" -gt 4 ]; then ok "touch zone tapped ($n events)"
	else
		bad "touch zone tapped ($n events)"
		printf 'touch foreground: '; focus_win
		printf 'touch app modes: '; lget wheel_app_modes
		ui touch
	fi
	dev "cat /sdcard/touch.txt" | head -12
	shot touch
	lset wheel_app_modes "$saved"
	key 4; key 3
}

t_pinch() {
	# Pinch zoom on Settings: a step up puts two fingers down on r1-wheel-touch and spreads them
	local saved n
	saved=$(lget wheel_app_modes)
	lset wheel_app_modes com.android.settings=pinch
	key 224
	sh "am start -W -n com.android.settings/.Settings"; sleep 2
	sh "getevent -lc 80 /dev/input/event6 > /sdcard/pinch.txt 2>&1 & sleep 0.5; $(step_cmd up); sleep 1.5; kill %1 2>/dev/null"
	n=$(dev "grep -c ABS_MT_TRACKING_ID /sdcard/pinch.txt")
	dev "grep -q \"ABS_MT_SLOT *00000001\" /sdcard/pinch.txt" && [ "${n:-0}" -ge 4 ] \
		&& ok "pinch zoom puts two fingers down and lifts them ($n tracking events)" \
		|| bad "pinch zoom sent no two-finger pinch ($n tracking events)"
	lset wheel_app_modes "$saved"
	key 4; key 3
}

t_homepage() {
	# The wheel on a home page past the first goes down that page's rows only, not along the
	# rows of the pages beside it off the screen
	local names text i
	key 224; key 3; sleep 1.5; key 3; sleep 1.5
	sh "input swipe 400 400 60 400 250"; sleep 1.5
	ui homepage0
	names=$(grep -o 'text="[^"]*"' $SHOTS/smoke-homepage0.xml | sed 's/text="\(.*\)"/\1/' | grep -v '^$' | sort -u)
	for i in 1 2 3 4 5; do
		wheel down 1 >/dev/null; sleep 0.8; ui homepage$i
		text=$(grep -o '<node[^>]*focused="true"[^>]*>' $SHOTS/smoke-homepage$i.xml | head -1 | sed -n 's/.*text="\([^"]*\)".*/\1/p')
		[ -z "$text" ] && continue
		if ! grep -qxF "$text" <<<"$names"; then
			shot homepage-off; bad "home step $i left the page for '$text', not on it ($(tr '\n' ',' <<<"$names"))"; key 3; return
		fi
	done
	ok "home steps stay on the page in sight ($(tr '\n' ',' <<<"$names"))"
	key 3
}

t_overlay() {
	host_only || return
	local was_on=0
	dev "grep enabled /data/data/cat.kenny.r1.perfoverlay/shared_prefs/*.xml" | grep -q true && was_on=1
	sh "setprop log.tag.PerfOverlay DEBUG"
	logcat_clear || return
	sh "am start -W -n com.android.settings/.Settings"; sleep 1
	sh "am start-foreground-service -n cat.kenny.r1.perfoverlay/.HudService"; sleep 6
	shot overlay
	dev "logcat -d -s PerfOverlay | tail -5"
	dev "logcat -d -s PerfOverlay | grep -q 'fps: task'" && ok "task fps" || echo "check: no 'fps: task' line"
	# Leave it as it was: off unless the user had it on
	if [ $was_on = 0 ]; then
		sh "am stopservice -n cat.kenny.r1.perfoverlay/.HudService >/dev/null; am force-stop cat.kenny.r1.perfoverlay; sed -i -e /enabled/s/true/false/ /data/data/cat.kenny.r1.perfoverlay/shared_prefs/*.xml"
	fi
	key 3
}

if [ -z "${R1_ONDEVICE:-}" ]; then
	timeout 30 $R "adb root" >/dev/null 2>&1; sleep 3; timeout 30 $R "adb wait-for-device" >/dev/null
fi
t_slider() {
	# Settings > Sound: the wheel to Media volume, a press grabs it, steps set it, a press lets go
	local before after
	key 224
	sh "am start -W -a android.settings.SOUND_SETTINGS >/dev/null"; sleep 2
	before=$(dev "cmd media_session volume --stream 3 --get | grep -o \"volume is [0-9]*\"")
	# Step until the Media volume row has the focus: where the first step lands varies
	local i
	for i in 1 2 3 4 5 6; do
		key 20; sleep 0.5; ui slider
		xfocus slider | grep -v '^out' | cut -f8 | grep -qx 'Media volume' && break
	done
	shot slider-focus
	# Grabbed, up lowers it (down, the clockwise turn, raises): lowered, so a full volume moves
	key 23; sleep 0.5; key 19; key 19; sleep 0.5; shot slider-grabbed; key 23; sleep 1
	after=$(dev "cmd media_session volume --stream 3 --get | grep -o \"volume is [0-9]*\"")
	[ "$before" != "$after" ] && ok "slider grab moved media volume ($before -> $after)" || bad "slider grab left media volume at $before"
	sh "cmd media_session volume --stream 3 --set ${before##* }" >/dev/null
	key 4; key 3
}

t_zones() {
	# The touch zones picker over Settings: a tap for up, one for down, Save writes the mode
	local saved got
	saved=$(lget wheel_app_modes)
	lset wheel_app_modes ""
	key 224
	sh "am start -W -n com.android.settings/.Settings >/dev/null"; sleep 1
	sh "am start -W -n cat.kenny.r1.wheel.settings/.TouchZonesActivity --es package com.android.settings >/dev/null"; sleep 2
	sh "input tap 240 160"; sleep 0.5; sh "input tap 240 480"; sleep 0.5; shot zones
	ui zones
	local save
	save=$(grep -o 'text="Save"[^>]*bounds="[^"]*"' $SHOTS/smoke-zones.xml | grep -o 'bounds="[^"]*"' | grep -o '[0-9][0-9]*' | tr '\n' ' ')
	if [ -n "$save" ]; then
		set -- $save
		sh "input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))"; sleep 1
	fi
	got=$(lget wheel_app_modes)
	case "$got" in
	*com.android.settings=touch:500:250:500:750*) ok "zones saved ($got)" ;;
	*com.android.settings=touch:*) ok "zones saved, near ($got)" ;;
	*) bad "zones not saved ($got)" ;;
	esac
	lset wheel_app_modes "$saved"
	key 4; key 3
}

t_reach() {
	# Battery details opens on rows that take no focus: the wheel scrolls on to the settings below
	key 224
	sh "am start -W -n cat.kenny.r1.batterydetails/.BatteryDetailsActivity >/dev/null"
	# Its window up, not the last test's page still going
	for i in 1 2 3 4 5 6 7 8; do
		focus_win | grep -q batterydetails && break
		sleep 1
	done
	sleep 1
	wheel down 10; sleep 1; shot reach; ui reach
	local cls; cls=$(focus_field reach 4)
	echo "focused: ${cls:-nothing} $(focus_field reach 5)"
	case "$cls" in
		""|*RecyclerView) bad "wheel stuck on the list" ;;
		*) ok "wheel reaches rows below the screen" ;;
	esac
	key 4; key 3
}

t_wheeltune() {
	# Kernel Tweaks' wheel rows reach the wheel driver, and its defaults come back after
	local d; d=$(dev "ls -d /sys/bus/i2c/drivers/och1970/*-000d 2>/dev/null | head -1")
	if [ -z "$d" ] || [ "$(dev "[ -f $d/step_deg ] && echo y")" != y ]; then
		echo "SKIP wheeltune: this kernel has no wheel tuning"; return
	fi
	local on fast step got
	on=$(dev getprop persist.sys.r1.kt.enabled)
	fast=$(dev getprop persist.sys.r1.kt.wheel_poll_fast)
	step=$(dev getprop persist.sys.r1.kt.wheel_step)
	sh "setprop persist.sys.r1.kt.enabled 1; setprop persist.sys.r1.kt.wheel_poll_fast 16; setprop persist.sys.r1.kt.wheel_step 30; setprop sys.r1.kt.apply t$(date +%s%N)"
	# The tweaks service takes a little over three seconds
	sleep 6
	got="$(dev cat $d/poll_fast_ms) $(dev cat $d/step_deg) $(dev getprop sys.r1.kt.result)"
	[ "$got" = "16 30 ok" ] && ok "wheel tweaks reach the driver ($got)" \
		|| bad "wheel tweaks didn't reach the driver (poll, step, result: $got)"
	sh "setprop persist.sys.r1.kt.wheel_poll_fast \"$fast\"; setprop persist.sys.r1.kt.wheel_step \"$step\"; setprop persist.sys.r1.kt.enabled \"$on\"; setprop sys.r1.kt.apply t$(date +%s%N)"
	sleep 6
	got="$(dev cat $d/poll_fast_ms) $(dev cat $d/step_deg)"
	case "$fast$step$on" in
	""|1) [ "$got" = "10 20" ] && ok "wheel back at its defaults" \
		|| bad "wheel not back at its defaults ($got)" ;;
	esac
}

t_locktap() {
	# A finger's tap on the lock screen's padlock unlocks, as the side button's press on it does
	local at
	key 223; sleep 3; key 224; sleep 2.5
	if ! locked; then echo "SKIP locktap: no lock screen after the screen went off"; key 3; return; fi
	ui locktap
	at=$(grep -oE 'resource-id="com.android.systemui:id/device_entry_icon_view"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' $SHOTS/smoke-locktap.xml | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ' | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
	if [ -z "$at" ]; then shot locktap; bad "no padlock on the lock screen"; sh "wm dismiss-keyguard"; key 3; return; fi
	sh "input tap $at"; sleep 2.5
	locked && { shot locktap; bad "a tap on the padlock ($at) left the lock screen up"; } \
		|| ok "a tap on the padlock unlocks"
	sh "wm dismiss-keyguard"; key 3
}

t_volte() {
	# VoLTE: the IMS service is the one Telephony binds, its voice feature is up, and the five
	# daemons between rild and the modem's IMS stack run. Registration itself needs a SIM on a
	# network with VoLTE, so it is reported, not judged
	dev "pm list features" | grep -q 'feature:android.hardware.telephony.ims$' \
		&& ok "the device declares IMS" || bad "no android.hardware.telephony.ims feature"
	local got s down=
	got=$(dev "cmd phone ims get-ims-service -s 0 -d")
	[ "$got" = com.mediatek.ims ] && ok "Telephony's IMS service is com.mediatek.ims" \
		|| bad "Telephony's IMS service is '$got'"
	if [ "$(dev getprop init.svc.vendor.ril-daemon-mtk)" != running ]; then
		echo "SKIP volte: the modem is not up"; return
	fi
	for s in volte_md_status volte_imsm_93 vendor.volte_stack vendor.volte_ua vendor.volte_imcb; do
		[ "$(dev getprop init.svc.$s)" = running ] || down="$down $s"
	done
	[ -z "$down" ] && ok "the IMS daemons run" || bad "IMS daemons not running:$down"
	for s in 1 2 3 4 5 6; do
		dev "service list" | grep -q 'mtkIms: ' && break; sleep 5
	done
	dev "service list" | grep -q 'mtkIms: ' && ok "the IMS service is up" || bad "the IMS service did not come up"
	got=$(dev "logcat -d -b crash" | grep -c 'Process: com.mediatek.ims')
	[ "$got" = 0 ] && ok "the IMS service has not crashed" || bad "the IMS service crashed $got times"
	echo "INFO volte: sim $(dev getprop gsm.sim.state), ims registered: $(dev "dumpsys telephony.registry" | grep -m1 -o 'mImsRegistered=[a-z]*' || echo unknown)"
}

t_wheelsens() {
	# Sensitivity (Settings > Wheel > Wheel feel) sets the wheel driver's step by the wheel and
	# by touch, with the Kernel Tweaks off, and Standard leaves the setting unset
	local d; d=$(dev "ls -d /sys/bus/i2c/drivers/och1970/*-000d 2>/dev/null | head -1")
	if [ -z "$d" ]; then echo "SKIP wheelsens: this kernel has no wheel tuning"; return; fi
	local on step got i
	on=$(dev getprop persist.sys.r1.kt.enabled); step=$(dev getprop persist.sys.r1.kt.wheel_step)
	sh "setprop persist.sys.r1.kt.enabled \"\"; setprop persist.sys.r1.kt.wheel_step \"\"; setprop sys.r1.kt.apply t$(date +%s%N)"
	key 224; sh "wm dismiss-keyguard"
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 1
	app_focus sens-entry 'Wheel feel' || return
	side_press; sleep .8
	app_focus sens-row 'Sensitivity' || return
	shot sens-row
	grep -q 'text="Standard - 18 steps a turn"' $SHOTS/smoke-sens-row.xml \
		&& ok "Sensitivity shows Standard when unset" || bad "Sensitivity does not show Standard when unset"
	side_press; sleep .8
	# The list opens on Standard; a step down is Low, 24 degrees a step
	wheel down 1 >/dev/null; sleep .5; side_press
	for i in 1 2 3 4 5 6 7 8; do sleep 1; got=$(dev cat $d/step_deg 2>/dev/null); [ "$got" = 24 ] && break; done
	got="$got $(dev getprop persist.sys.r1.kt.wheel_step)"
	[ "$got" = "24 24" ] && ok "Sensitivity set by the wheel reaches the wheel with the tweaks off ($got)" \
		|| bad "Sensitivity set by the wheel did not reach the wheel (step, setting: $got)"
	ui sens-low
	grep -q 'text="Low - 15 steps a turn"' $SHOTS/smoke-sens-low.xml \
		&& ok "the row shows the sensitivity set" || bad "the row does not show Low"
	# By touch: the row, then Standard
	tap_text sens-low Sensitivity; sleep 1; ui sens-list
	tap_text sens-list 'Standard - 18 steps a turn' || bad "no Standard in the list of sensitivities"
	for i in 1 2 3 4 5 6 7 8; do sleep 1; got=$(dev cat $d/step_deg 2>/dev/null); [ "$got" = 20 ] && break; done
	got="$got [$(dev getprop persist.sys.r1.kt.wheel_step)]"
	[ "$got" = "20 []" ] && ok "Standard by touch puts the wheel back and unsets the setting" \
		|| bad "Standard by touch did not put the wheel back (step, setting: $got)"
	key 4; key 4; key 3
	sh "setprop persist.sys.r1.kt.wheel_step \"$step\"; setprop persist.sys.r1.kt.enabled \"$on\"; setprop sys.r1.kt.apply t$(date +%s%N)"
	sleep 5
}

t_allapps() {
	# All apps opened from home: the wheel's first step is on an app in the list, not on home
	key 224; key 3; sleep 1; wheel down 1; sleep 0.5
	sh "input swipe 240 560 240 120 300"; sleep 1.5
	key 224; wheel down 1; sleep 0.8; shot allapps; ui allapps
	echo "focused: $(focus_field allapps 7)"
	xfocus allapps | grep '^out' | cut -f6 | grep -q apps_list_view && ok "all apps takes the focus" || bad "focus left behind all apps"
	key 3
}

# Each home's apps, one line each: list or stock
homes() {
	local db
	for db in launcher_r1_list_1 launcher_4_by_5; do
		dev "sqlite3 /data/data/com.android.launcher3/databases/$db.db \"select container, intent from favorites where container in (-100, -101) and itemType = 0 order by intent\"" | sed "s/^/$db /"
	done
}

t_stock() {
	# The whole-system switch: wheel UI off is stock (overlays off, no wheel dialogs), on again
	# The homes' databases only the host's root reads
	[ -z "${R1_ONDEVICE:-}" ] && homes > $SHOTS/homes-before.txt
	lset wheel_ui 0; sleep 6
	dev "cmd overlay list android" | grep -q "\[x\] com.rabbit.r1.overlay.wheel" && bad "stock: wheel overlays still on" || ok "stock: wheel overlays off"
	key 224; sh "wm dismiss-keyguard"; sleep 1
	sh "am start -a android.bluetooth.adapter.action.REQUEST_DISCOVERABLE >/dev/null"; sleep 3; ui stock-dialog; shot stock-dialog
	grep -q 'wheel_dialog_buttons' $SHOTS/smoke-stock-dialog.xml && bad "stock: dialog still listed" || ok "stock: stock dialog"
	key 4; key 4
	key 3; sleep 3
	lset wheel_ui 1; sleep 6
	key 3; sleep 3
	# Each home kept as it was left: nothing doubled or dropped by the switch
	if [ -z "${R1_ONDEVICE:-}" ]; then
		homes > $SHOTS/homes-after.txt
		diff -q $SHOTS/homes-before.txt $SHOTS/homes-after.txt >/dev/null && ok "homes kept as they were" || bad "homes changed switching ($(diff $SHOTS/homes-before.txt $SHOTS/homes-after.txt | grep -c '^[<>]') lines)"
	fi
	[ "$(dev "cmd overlay list android" | grep -c "\[x\] com.rabbit.r1.overlay.wheel")" = 2 ] && ok "wheel UI back on" || bad "wheel overlays not back"
	[ "$(dev "logcat -b crash -d -v threadtime" | crash_entries | awk 'END { print NR+0 }')" = 0 ] && ok "no crashes switching" || bad "crash switching"
	key 3
}

t_compose() {
	# Settings > Apps > All apps, a Compose page: the wheel reaches an app row, not the whole list
	sh "input keyevent 224; wm dismiss-keyguard; am start -W -a android.settings.APPLICATION_SETTINGS >/dev/null"; sleep 2
	wheel down 3; sleep 1; ui compose; shot compose
	local b; b=$(focus_field compose 6)
	echo "focused: ${b:-nothing} $(focused_text compose)"
	# a row: less than half the screen tall
	echo "$b" | awk 'NF == 4 && $4 - $2 < 320 && $2 > 95 { ok = 1 } END { exit !ok }' \
		&& ok "wheel reaches rows on Compose pages" || bad "wheel stuck on a Compose page"
	key 4; key 3
}

t_appinfo() {
	# App info, a Compose page that scrolls under its bar: the wheel gets past the back button
	sh "input keyevent 224; wm dismiss-keyguard; am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:net.sourceforge.opencamera >/dev/null"; sleep 2
	wheel down 3; sleep 1; ui appinfo; shot appinfo
	local b; b=$(focus_field appinfo 6)
	echo "focused: ${b:-nothing}"
	echo "$b" | awk 'NF == 4 && $2 > 120 { ok = 1 } END { exit !ok }' \
		&& ok "wheel gets into App info" || bad "wheel stuck on App info's bar"
	key 4; key 3
}

if [ -n "${R1_ONDEVICE:-}" ]; then
	side_press() { timeout 10 r1-wheeld inject side press; }
	side_hold() { timeout 10 r1-wheeld inject side hold; }
	side_double() { timeout 10 r1-wheeld inject side double; }
	side_triple() { timeout 10 r1-wheeld inject side triple; }
else
	side_press() { sh "sendevent /dev/input/event0 1 116 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 116 0; sendevent /dev/input/event0 0 0 0"; }
	side_hold() { sh "sendevent /dev/input/event0 1 116 1; sendevent /dev/input/event0 0 0 0; sleep 1.2; sendevent /dev/input/event0 1 116 0; sendevent /dev/input/event0 0 0 0"; }
	# Each press one write (down, up): an exec costs ~80 ms on the device, and the policy counts
	# a double press only if the second down comes within 300 ms of the first
	SIDE_TAP=AAAAAAAAAAAAAAAAAAAAAAEAdAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAdAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
	side_double() { sh "t=/data/local/tmp/side-tap.bin; [ -s \$t ] || echo $SIDE_TAP | base64 -d > \$t; cat \$t > /dev/input/event0; sleep 0.1; cat \$t > /dev/input/event0"; }
	side_triple() { sh "t=/data/local/tmp/side-tap.bin; [ -s \$t ] || echo $SIDE_TAP | base64 -d > \$t; cat \$t > /dev/input/event0; sleep 0.1; cat \$t > /dev/input/event0; sleep 0.1; cat \$t > /dev/input/event0"; }
fi
# Read the whole dump: grep -m1 closed Binder's output early and produced Broken pipe noise.
focus_win() { dev 'dumpsys window | awk "/mCurrentFocus/ && !seen {print; seen=1}"'; }
t_apptune() {
	# An app's own wheel feel (the taskbar's Wheel mode, This app's wheel) is that app's alone:
	# Settings scrolls the natural way while the general setting is off, and its rows are there
	local modes tuning natural a b
	modes=$(lget wheel_app_modes); tuning=$(lget wheel_app_tuning); natural=$(lget wheel_scroll_natural)
	lset wheel_app_modes com.android.settings=scroll; lset wheel_scroll_natural 0
	lset wheel_app_tuning com.android.settings=natural:1
	key 224; sh "wm dismiss-keyguard"
	sh "am start -W -S -n com.android.settings/.Settings >/dev/null"; sleep 2.5
	a=$(scroll_drag)
	lset wheel_app_tuning com.android.settings=divide:2; sleep 1
	# Two clicks a step: one click drags nothing
	b=$(scroll_drag)
	[ "$a" = down ] && ok "an app's natural scrolling is its own (a step down drags $a)" \
		|| bad "an app's natural scrolling did not reach the wheel (a step down drags $a)"
	[ "$b" = none ] && ok "with two clicks a step, one click is no step" \
		|| bad "with two clicks a step, one click still dragged ($b)"
	wheel down 1 >/dev/null; sleep 1
	# The rows, in the taskbar's Wheel mode list
	local timeout i d; timeout=$(lget wheel_taskbar_timeout); lset wheel_taskbar_timeout 0
	side_double; sleep 1.2
	for i in $(seq 1 12); do
		ui apptune; d=$(focus_field apptune 8)
		[ "$d" = "Wheel mode" ] && break
		wheel down 1; sleep 0.5
	done
	side_press; sleep 1.2
	# Down the modes to the row under them: the focus is on it when its bounds hold its title's
	local on=""
	for i in $(seq 1 20); do
		ui apptune-list
		on=$(awk -v q="This app(.|&apos;)s wheel" '
			{ n = split($0, nodes, "<node ")
			  for (i = 2; i <= n; i++) {
				if (!match(nodes[i], /bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"/)) continue
				b = substr(nodes[i], RSTART + 9, RLENGTH - 11); gsub(/\]\[/, ",", b); split(b, c, ",")
				if (nodes[i] ~ /focused="true"/) { top = c[2]; bottom = c[4] }
				if (nodes[i] ~ ("text=\"" q "\"")) y = c[2]
			  } }
			END { if (y != "" && top != "" && y >= top && y <= bottom) print "yes" }' $SHOTS/smoke-apptune-list.xml)
		[ -n "$on" ] && break
		wheel down 1; sleep 0.4
	done
	if [ -n "$on" ]; then
		side_press; sleep 1; ui apptune-open; shot apptune-open
		grep -q 'text="Clicks per step"' $SHOTS/smoke-apptune-open.xml \
			&& ok "This app's wheel opens its rows" || bad "This app's wheel opened no rows"
		grep -q 'Every 2nd click, this app only' $SHOTS/smoke-apptune-open.xml \
			&& ok "the app's own value is said to be its own" || bad "the app's own value is not shown"
	else
		shot apptune-list; bad "Wheel mode has no row for this app's wheel (focus: $d)"
	fi
	lset wheel_taskbar_timeout "$timeout"
	lset wheel_app_tuning "$tuning"; lset wheel_app_modes "$modes"; lset wheel_scroll_natural "${natural:-0}"
	key 4; key 4; key 3
}

# The text of the row a dialog's list has selected, or of the button with the focus
picked() { ui $1; grep -oE '<node[^>]*(selected|focused)="true"[^>]*>' $SHOTS/smoke-$1.xml | grep -oE ' text="[^"&]+' | tail -1 | cut -c8-; }

t_shortlist() {
	# A dialog's short list over its buttons, as an app's own wheel feel in Taskbar and wheel:
	# opened by touch, the wheel's first step is in the list, and from the buttons it goes back up
	local tuning a b c
	tuning=$(lget wheel_app_tuning); lset wheel_app_tuning com.android.calculator2=divide:2
	key 224; sh "wm dismiss-keyguard"
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null 2>&1"; sleep 2.5
	ui shortlist; tap_text shortlist "The wheel in each app"; sleep 2.5; ui shortlist
	tap_text shortlist "Calculator"; sleep 2; ui shortlist; tap_text shortlist "This app[^\"]*s wheel"; sleep 1.5
	wheel down 1 >/dev/null; sleep 0.7; a=$(picked shortlist-a)
	wheel down 4 >/dev/null; sleep 1; b=$(picked shortlist-b)
	wheel up 2 >/dev/null; sleep 0.8; c=$(picked shortlist-c)
	case "$a" in "Reverse direction"|"Clicks per step") ok "the first step is in the dialog's short list ($a)" ;;
	*) shot shortlist-a; bad "the first step left the dialog's short list (on: ${a:-nothing})" ;; esac
	case "$b" in Done|Reset) case "$c" in Done|Reset|"") shot shortlist-c; bad "no way back up from the dialog's buttons (on: ${c:-nothing})" ;;
		*) ok "from the buttons the wheel goes back up into the list ($c)" ;; esac ;;
	*) bad "the wheel did not reach the dialog's buttons (on: ${b:-nothing})" ;; esac
	key 4; sleep 0.5; key 4; key 4; key 3
	lset wheel_app_tuning "$tuning"
}

# Taps the middle of the first node whose text is $2 in the dump smoke-$1.xml
tap_text() {
	local at
	at=$(grep -oE "text=\"$2\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]" $SHOTS/smoke-$1.xml | head -1 | grep -oE '[0-9]+' | tail -4 | tr '\n' ' ' | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
	[ -n "$at" ] && sh "input tap $at"
}

asleep() { dev "dumpsys power" | grep -qE 'mWakefulness=(Asleep|Dozing)'; }

t_triple() {
	# Three presses of the side button turn the screen off, once set to
	local saved; saved=$(lget wheel_side_button_triple)
	lset wheel_side_button_triple 7
	key 224; sh "wm dismiss-keyguard"; key 3; sleep 1.5
	side_triple; sleep 2
	asleep && ok "a triple press turns the screen off" || { shot triple; bad "a triple press left the screen on"; }
	key 224; sh "wm dismiss-keyguard"; sleep 1
	# With no use for a third press the double press acts at once: the bar is open within the
	# interval a third press would have been waited for
	lset wheel_side_button_triple 0; sleep 0.5
	side_triple; sleep 1.5
	asleep && bad "a triple press set to nothing turned the screen off" || ok "a triple press set to nothing does nothing"
	key 224; sh "wm dismiss-keyguard"; key 4; key 3
	lset wheel_side_button_triple "${saved:-0}"
}

t_wakedouble() {
	# Double press to wake: one press leaves the screen off, two wake it
	local saved; saved=$(lget wheel_side_button_wake_double)
	lset wheel_side_button_wake_double 1
	key 224; sh "wm dismiss-keyguard"; key 3; sleep 1; key 223; sleep 2.5
	side_press; sleep 1.5
	asleep && ok "one press leaves the screen off where two wake it" || bad "one press woke the screen where it takes two"
	key 223; sleep 2
	side_double; sleep 2
	asleep && bad "a double press left the screen off" || ok "a double press wakes the screen"
	lset wheel_side_button_wake_double "${saved:-0}"
	key 224; sh "wm dismiss-keyguard"; sleep 1
	dev "dumpsys window" | grep -m1 mCurrentFocus | grep -qi camera && { key 4; bad "the waking double press opened the camera"; }
	key 3
}

# The way one wheel step down drags the page in scroll mode: up, down or none, by the first and
# the last height of r1-wheel-touch's finger
scroll_drag() {
	sh "getevent -c 60 /dev/input/event6 > /sdcard/drag.txt 2>&1 & sleep 0.5; $(step_cmd down); sleep 1.5; kill %1 2>/dev/null"
	local a b
	a=$(dev "cat /sdcard/drag.txt" | awk '$2 == "0036" { print $3; exit }')
	b=$(dev "cat /sdcard/drag.txt" | awk '$2 == "0036" { v = $3 } END { print v }')
	if [ -z "$a" ] || [ "$a" = "$b" ]; then echo none
	elif [ $((16#$b)) -lt $((16#$a)) ]; then echo up
	else echo down; fi
}

t_natural() {
	# Natural scrolling turns scroll mode's drag round: the page moves the way the wheel turns
	local modes natural a b
	modes=$(lget wheel_app_modes); natural=$(lget wheel_scroll_natural)
	lset wheel_app_modes com.android.settings=scroll; lset wheel_scroll_natural 0
	key 224; sh "wm dismiss-keyguard"
	sh "am start -W -S -n com.android.settings/.Settings >/dev/null"; sleep 2.5
	a=$(scroll_drag)
	lset wheel_scroll_natural 1; sleep 1
	b=$(scroll_drag)
	lset wheel_scroll_natural "${natural:-0}"; lset wheel_app_modes "$modes"
	if [ "$a" = up ] && [ "$b" = down ]; then
		ok "natural scrolling drags the page the wheel's way (a step down: $a, then $b)"
	else
		bad "natural scrolling did not turn the drag round (a step down: $a, then $b)"
	fi
	key 4; key 3
}
t_tour() {
	# Home opens the tour the first time; each page waits for its control
	sh "settings delete secure r1_wheel_tour_done >/dev/null; am force-stop com.android.launcher3; input keyevent 224; wm dismiss-keyguard; input keyevent 3"; sleep 3
	focus_win | grep -q TourActivity && ok "home opens the tour" || { bad "no tour ($(focus_win))"; sh "settings put secure r1_wheel_tour_done 1"; return; }
	shot tour-1
	side_press; sleep 1.5; shot tour-2
	wheel down 4; sleep 1.5; shot tour-3
	side_press; sleep 1.8; shot tour-4
	side_hold; sleep 1.8; shot tour-5
	focus_win | grep -q TourActivity && ok "tour still up at the slider page" || bad "hold left the tour"
	# The slider: a press grabs it, a turn sets it, a press lets go
	side_press; sleep 0.8; wheel down 2; sleep 0.5; side_press; sleep 1.8; shot tour-6
	# The double press opens the taskbar; the hold shuts it, the tour moves on
	side_double; sleep 1; focus_win | grep -q WheelTaskbar && tb=1 || tb=0; shot tour-taskbar
	[ $tb = 1 ] && ok "double press opens the taskbar over the tour" || bad "no taskbar ($(focus_win))"
	# A second double press shuts it
	side_double; sleep 2; shot tour-7
	focus_win | grep -q WheelTaskbar && bad "second double press left the taskbar open" || ok "second double press shuts the taskbar"
	# Back: the taskbar opened and shut, then Back
	side_double; sleep 1; side_double; sleep 1; key 4; sleep 1.8; shot tour-8
	# Power options told of, the status bar's double tap for sleep told of, the help page opened
	# and left
	side_press; sleep 1.5; shot tour-sleep; side_press; sleep 1.5; shot tour-help; side_press; sleep 2.5
	focus_win | grep -q WheelActivity && ok "the tour opens the help page" || bad "no help page from the tour ($(focus_win))"
	key 4; sleep 2; shot tour-9
	focus_win | grep -q TourActivity && ok "back from help, back in the tour" || bad "help left the tour ($(focus_win))"
	side_press; sleep 1.5
	focus_win | grep -q TourActivity && bad "tour did not finish ($(focus_win))" || ok "tour finished"
	sh "am force-stop com.android.launcher3; input keyevent 3"; sleep 2
	focus_win | grep -q TourActivity && bad "tour came back" || ok "tour shows once"
}

# The focused view's class and bounds from a dump: "ImageButton 18 33 83 98"
focused_in() { grep -o '<node[^>]*focused="true"[^>]*>' $SHOTS/smoke-$1.xml | head -1 | sed -n 's/.*class="[a-z.]*\.\([A-Za-z]*\)".*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4 \5/p'; }
t_rotation() {
	# The wheel's keys mean next and previous whichever way the screen is turned
	local input; input=$(dev "dumpsys input")
	for d in r1-wheel och1970-wheel; do
		echo "$input" | grep -A30 "Device [0-9]*: $d\$" | grep -m1 OrientationAware | grep -q false \
			&& ok "$d keys not turned with the screen" || bad "$d keys turn with the screen"
	done
	local auto user; auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	for r in 0 1; do
		turn $r; sleep 1
		key 224; sh "wm dismiss-keyguard"
		sh "am start -W -S -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null"; sleep 2
		# The first step is onto the page, past the back arrow; every step on goes down the list,
		# never back up to the toolbar nor sideways along it
		local top=0 stuck=0 i f
		for i in $(seq 15); do
			wheel down 1 >/dev/null; sleep 0.3; ui rot$r-$i; f=$(focused_in rot$r-$i)
			case "$f" in
				ImageButton*|Button*|"") stuck=$i; break ;;
			esac
		done
		shot rot$r
		[ $stuck = 0 ] && ok "rotation $r: 15 steps down Developer options stay in the list" \
			|| bad "rotation $r: step $stuck focused '${f:-nothing}'"
	done
	rotation_back "${user:-0}" "${auto:-1}"
	key 4; key 3
}

# The centre of the node named $2 in dump $1, as "x y"
centre_of() { grep -o "content-desc=\"$2\"[^>]*bounds=\"[^\"]*\"" $SHOTS/smoke-$1.xml | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'; }
t_edge() {
	# The taskbar hugs the edge the wheel is on as the screen turns: the top a quarter turn
	# left, the bottom a quarter turn right, its buttons along it in a row: Power, then its
	# settings at the end
	local auto user edge r home set hx hy sx sy
	auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	edge=$(lget wheel_taskbar_edge)
	lset wheel_taskbar_edge 0
	# Open through the view dump, which takes longer than the bar's 3 s
	local timeout; timeout=$(lget wheel_taskbar_timeout)
	lset wheel_taskbar_timeout 0
	for r in 1 3; do
		turn $r; sleep 1
		key 224; sh "wm dismiss-keyguard"
		sh "am start -W -S -a android.settings.SETTINGS >/dev/null"; sleep 2
		side_double; sleep 1.5; ui edge$r; shot edge$r
		home=$(centre_of edge$r Power); set=$(centre_of edge$r "Taskbar settings")
		read hx hy <<<"$home"; read sx sy <<<"$set"
		if [ -z "$home" ] || [ -z "$set" ]; then
			bad "rotation $r: no taskbar buttons ($(focus_win))"
		elif [ $r = 1 ] && [ $hy -lt 160 ] && [ $sy -lt 160 ] && [ $sx -gt $hx ]; then
			ok "rotation 1: the taskbar along the top, beside the wheel"
		elif [ $r = 3 ] && [ $hy -gt 320 ] && [ $sy -gt 320 ] && [ $sx -gt $hx ]; then
			ok "rotation 3: the taskbar along the bottom, beside the wheel"
		else
			bad "rotation $r: taskbar Power at $home, its settings at $set"
		fi
		key 4; sleep 1
	done
	lset wheel_taskbar_edge "${edge:-0}"
	lset wheel_taskbar_timeout "${timeout:-3}"
	rotation_back "${user:-0}" "${auto:-1}"
	key 4; key 3
}
t_turned() {
	# Turned right, the wheel on the bottom, its way is turned round with the screen: a turn
	# towards the screen's start, up as the device stands, goes on down the list
	local auto user top=0 stuck=0 i f
	auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	turn 3; sleep 1
	key 224; sh "wm dismiss-keyguard"
	sh "am start -W -S -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS >/dev/null"; sleep 2
	for i in $(seq 10); do
		wheel up 1 >/dev/null; sleep 0.3; ui turned-$i; f=$(focused_in turned-$i)
		case "$f" in
			ImageButton*|Button*|"") stuck=$i; break ;;
		esac
	done
	shot turned
	[ $stuck = 0 ] && ok "rotation 3: 10 steps the turned way go down Developer options" \
		|| bad "rotation 3: step $stuck focused '${f:-nothing}'"
	rotation_back "${user:-0}" "${auto:-1}"
	key 4; key 3
}
t_openwith() {
	# "Open with", as the Files app brings it up for an APK: the wheel goes through its
	# buttons and on to the other app, no taskbar over it
	local id f0 f1 f2 f3
	sh "cp \$(pm path org.lineageos.glimpse | sed s/package://) /sdcard/Download/r1-smoke-open.apk; am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/r1-smoke-open.apk >/dev/null"; sleep 2
	id=$(dev "content query --uri content://media/external/downloads --projection _id:_display_name" | grep 'r1-smoke-open.apk' | sed -n 's/.*_id=\([0-9]*\).*/\1/p' | tail -1)
	[ -n "$id" ] || { bad "no media id for the test APK"; return; }
	key 224; sh "wm dismiss-keyguard; am start -a android.intent.action.VIEW -t application/vnd.android.package-archive -d content://media/external/downloads/$id >/dev/null"; sleep 2.5
	# With one app to open it, the installer, there is no question to ask
	focus_win | grep -q ResolverActivity || { echo "SKIP $cur: one app opens an APK, no Open with"; sh "rm -f /sdcard/Download/r1-smoke-open.apk"; key 4; key 3; return; }
	# Where it starts: nothing after a touch, Just once after keys
	ui openwith0; f0=$(grep -o 'resource-id="[^"]*"[^>]*focused="true"' $SHOTS/smoke-openwith0.xml | head -1 | cut -d'"' -f2)
	wheel down 1 >/dev/null; sleep 0.8; ui openwith1; f1=$(grep -o 'resource-id="[^"]*"[^>]*focused="true"' $SHOTS/smoke-openwith1.xml | head -1 | cut -d'"' -f2)
	wheel down 1 >/dev/null; sleep 0.8; ui openwith2; f2=$(grep -o 'resource-id="[^"]*"[^>]*focused="true"' $SHOTS/smoke-openwith2.xml | head -1 | cut -d'"' -f2)
	wheel down 1 >/dev/null; sleep 0.8; ui openwith3; f3=$(grep -o 'resource-id="[^"]*"[^>]*focused="true"' $SHOTS/smoke-openwith3.xml | head -1 | cut -d'"' -f2)
	shot openwith
	focus_win | grep -q WheelTaskbar && bad "the wheel opened the taskbar over Open with" \
		|| { grep -qE 'button_once .*button_always .*resolver_list' <<<"$f0 $f1 $f2 $f3 " \
			&& ok "the wheel goes Just once, Always, the other app in Open with" \
			|| bad "Open with focused '$f0', then '$f1' '$f2' '$f3'"; }
	key 4; sh "rm -f /sdcard/Download/r1-smoke-open.apk"; key 3
}
t_ime() {
	has_jelly || return 0
	# Under the wheel a page with a text field opens with the field focused, not the keyboard up:
	# it comes as the field is pressed
	# In app mode, where the wheel moves through the browser and the side button presses
	local saved; saved=$(lget wheel_app_modes)
	lset wheel_app_modes org.lineageos.jelly=app
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.5
	sh "am start -W -S -n org.lineageos.jelly/.MainActivity >/dev/null 2>&1"; sleep 3
	shot ime-open
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && bad "browser opened with the keyboard up" \
		|| ok "browser opens without the keyboard"
	side_press; sleep 1.5; shot ime-press
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && ok "side button on the address brings the keyboard" \
		|| bad "side button on the address left the keyboard down"
	# A step off the field puts the keyboard away: the wheel goes through the page, not typing.
	# Two, as the browser's address comes all selected and the first step only drops that
	wheel down 2 >/dev/null; sleep 1.5; shot ime-step
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && bad "a step off the address left the keyboard up" \
		|| ok "a step off the address puts the keyboard away"
	lset wheel_app_modes "$saved"
	key 4; key 4; key 3
	# Nor in the taskbar mode, where the browser opens as if touched
	sh "am start -W -S -n org.lineageos.jelly/.MainActivity >/dev/null 2>&1"; sleep 3
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && bad "taskbar-mode browser opened with the keyboard up" \
		|| ok "taskbar-mode browser opens without the keyboard"
	key 4; key 3
}

t_kbheight() {
	has_jelly || return 0
	# The keyboard leaves the app over half the portrait screen: its top below y=330 of 640
	# (it was 245, with a band under it for a Back the side button's hold already is)
	local saved top; saved=$(lget wheel_app_modes)
	lset wheel_app_modes org.lineageos.jelly=app
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.5
	sh "am start -W -S -n org.lineageos.jelly/.MainActivity >/dev/null 2>&1"; sleep 3
	side_press; sleep 2; shot kbheight
	top=$(dev "dumpsys window" | awk '/type=ime frame=/ { sub(/.*frame=\[[0-9]+,/, ""); sub(/\].*/, ""); print; exit }')
	if [ -n "$top" ] && [ "$top" -ge 330 ] && [ "$top" -lt 640 ]; then
		ok "the keyboard leaves the app the screen above y=$top"
	else
		bad "the keyboard's top is at y=${top:-nowhere}, not below 330"
	fi
	lset wheel_app_modes "$saved"
	key 4; key 4; key 3
}

# Keep automatic Power dwell separate from testing the side-button hold. A dump or host
# transport can take longer than the ten-second dwell and open the menu before the hold.
# The EXIT cleanup also restores these settings if a device/host call is interrupted.
powerhold_settings_end() {
	[ "${powerhold_settings_active:-0}" = 1 ] || return 0
	lset wheel_taskbar_timeout "$powerhold_saved_timeout"
	lset wheel_taskbar_power_linger "$powerhold_saved_linger"
	powerhold_settings_active=0
}

t_powerhold() {
	# The taskbar: a hold on Power opens its options at once; each list starts with Back, which
	# shuts it; a swipe out towards the bar's edge shuts a list, then the bar
	local i d
	key 224; sh "wm dismiss-keyguard"; turn 0
	# Open through the view dumps, which take longer than the bar's 3 s
	powerhold_saved_timeout=$(lget wheel_taskbar_timeout)
	powerhold_saved_linger=$(lget wheel_taskbar_power_linger)
	powerhold_settings_active=1
	lset wheel_taskbar_timeout 0
	lset wheel_taskbar_power_linger 0
	key 3; sleep 1; side_double; sleep 1.2
	for i in $(seq 1 12); do
		ui powerhold; d=$(focus_field powerhold 8)
		[ "$d" = Power ] && break
		wheel down 1; sleep 0.5
	done
	if [ "$d" != Power ]; then
		bad "no wheel stop on the taskbar's Power (${d:-nothing})"
		powerhold_settings_end; key 4; key 3; rotation_back 0 1; return
	fi
	side_hold; sleep 1.2; ui powerhold-menu; shot powerhold-menu
	local menu_ok=1
	grep -q 'text="Power off"' $SHOTS/smoke-powerhold-menu.xml && ok "a hold on Power opens its options" \
		|| { bad "a hold on Power opened no options"; menu_ok=0; }
	grep -q 'text="Back"' $SHOTS/smoke-powerhold-menu.xml && ok "Power's options start with Back" \
		|| { bad "Power's options have no Back"; menu_ok=0; }
	# Without a confirmed list, do not send a press to whichever taskbar item is now focused.
	if [ "$menu_ok" = 0 ]; then
		key 3; powerhold_settings_end; rotation_back 0 1; return
	fi
	wheel up 1; sleep 0.5; side_press; sleep 1; ui powerhold-back
	if ! grep -q 'text="Power off"' $SHOTS/smoke-powerhold-back.xml && focus_win | grep -q WheelTaskbar; then
		ok "Back shuts the list, the bar stays"
	else
		bad "Back left the list open or shut the bar ($(focus_win))"
	fi
	# A swipe out on the list shuts it, then one on the bar shuts the bar
	side_hold; sleep 1.2
	sh "input swipe 200 320 330 320 150"; sleep 1; ui powerhold-swipe
	if ! grep -q 'text="Power off"' $SHOTS/smoke-powerhold-swipe.xml && focus_win | grep -q WheelTaskbar; then
		ok "a swipe out shuts the list, the bar stays"
	else
		shot powerhold-swipe; bad "a swipe out left the list open or shut the bar ($(focus_win))"
	fi
	sh "input swipe 420 320 479 320 150"; sleep 1.5
	focus_win | grep -q WheelTaskbar && { shot powerhold-swipebar; bad "a swipe out on the bar left it open"; } \
		|| ok "a swipe out on the bar shuts it"
	powerhold_settings_end
	key 3; rotation_back 0 1
}

t_subpage() {
	# A page opened inside the window, as Wheel settings' subpages are, starts on the page too:
	# never on its back arrow
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 3 >/dev/null; sleep 0.5; side_press; sleep 2; ui subpage
	local f; f=$(focused_in subpage)
	case "$f" in
		ImageButton*|"") bad "subpage opened on '${f:-nothing}'" ;;
		*) ok "subpage opens on its first row ($f)" ;;
	esac
	key 4; key 4
}

firststop_pages() {
	# Pages open with the focus on the page, past the toolbar's back arrow and its other
	# buttons (Internet's refresh)
	key 3; sleep 1; wheel down 1 >/dev/null
	local p f
	for p in DISPLAY_SETTINGS WIFI_SETTINGS APPLICATION_DEVELOPMENT_SETTINGS SOUND_SETTINGS; do
		sh "am start -W -S -a android.settings.$p >/dev/null"; sleep 2.5; ui fs-$p; f=$(focused_in fs-$p)
		case "$f" in
			ImageButton*|"") bad "$p opened on '${f:-nothing}'" ;;
			*) ok "$p opens on the page ($f)" ;;
		esac
	done
	key 3
}

t_landshade() {
	# Turned, the wheel's shade stays one column in tabs, the wheel walking one list: never the
	# split shade with the controls beside the notifications
	local auto user; auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	turn 1; sleep 1
	key 224; sh "wm dismiss-keyguard; am start -W -S -a android.settings.DISPLAY_SETTINGS >/dev/null"; sleep 2
	sh "cmd statusbar expand-notifications"; sleep 1.5; ui landshade; shot landshade
	if grep -q 'text="Display brightness"' $SHOTS/smoke-landshade.xml && grep -q 'USB debugging' $SHOTS/smoke-landshade.xml; then
		bad "turned shade split: controls beside the notifications"
	else
		ok "turned shade is one column"
	fi
	sh "cmd statusbar collapse"; rotation_back "${user:-0}" "${auto:-1}"
	key 3
}

t_landpower() {
	# Turned, the power menu is the wheel's list as upright: one action a row, Restart naming
	# its targets, not the stock grid of round buttons
	local auto user; auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	turn 1; sleep 1
	key 224; sh "wm dismiss-keyguard; am start -W -S -a android.settings.DISPLAY_SETTINGS >/dev/null"; sleep 2
	sh "service call window 119 >/dev/null"; sleep 2; ui landpower; shot landpower
	# The list's actions stand one under the other; the grid's lie side by side
	[ -n "$(xnodes landpower | awk -F '\t' '$7 == "Power off" { split($6, a, " ") } $7 == "Restart" { split($6, b, " ") } END { if (a[1] != "" && a[1] == b[1] && b[2] > a[2]) print "list" }')" ] \
		&& ok "turned power menu is the list" \
		|| bad "turned power menu is the grid"
	key 4; sleep 1
	rotation_back "${user:-0}" "${auto:-1}"
	key 3
}

t_noring() {
	# An app the wheel opens the taskbar in shows no focus ring: the wheel can't move it there.
	# The wheel used on home first, as a wheel user comes to an app
	local saved; saved=$(lget wheel_app_modes)
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.5
	# Contacts set to the taskbar, as an app with no wheel mode of its own is, its notification
	# prompt answered so it opens on its page
	lset wheel_app_modes com.android.contacts=taskbar
	sh "pm grant com.android.contacts android.permission.POST_NOTIFICATIONS 2>/dev/null; am start -W -S -n com.android.contacts/.activities.PeopleActivity >/dev/null"; sleep 2.5; shot noring
	dev "dumpsys window" | grep -m1 -o 'mInTouchMode=[a-z]*' | grep -q true \
		&& ok "taskbar-mode app opens without a focus ring" || bad "taskbar-mode app shows a focus ring"
	lset wheel_app_modes "$saved"
	# Then on to an app the wheel moves through, untouched: the wheel is still the one in use
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	dev "dumpsys window" | grep -m1 -o 'mInTouchMode=[a-z]*' | grep -q false \
		&& ok "untouched, a wheel app after it is back out of touch mode" \
		|| bad "touch mode from the taskbar-mode app stuck"
	key 4; key 3
}

t_outslider() {
	# The output switcher's volume slider (a Material slider, not a seek bar) takes the side
	# button's grab like the others: press on it, turn, and the media volume moves
	vol() { dev "cmd media_session volume --stream 3 --get 2>&1" | grep -o 'volume is [0-9]*' | grep -o '[0-9]*'; }
	dev "cmd media_session volume --stream 3 --set 10 >/dev/null 2>&1"
	key 3; sleep 1; wheel down 1 >/dev/null
	sh "am start -W -S -a android.settings.SOUND_SETTINGS >/dev/null"; sleep 2.5
	side_press; sleep 1.5; wheel down 1 >/dev/null; sleep 0.5; side_press; sleep 0.8
	wheel down 3 >/dev/null; sleep 1; shot outslider
	local after; after=$(vol)
	[ "${after:-10}" != 10 ] && ok "output switcher slider takes the wheel (10 to $after)" \
		|| bad "output switcher slider ignored the wheel"
	dev "cmd media_session volume --stream 3 --set 20 >/dev/null 2>&1"
	key 4; sleep 0.5; key 4; key 3
}

t_choicelist() {
	# Named destinations survive the new Help/Tour rows without changing a choice.
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 1
	app_focus choice-entry 'Wheel feel' || return
	side_press; sleep .5
	app_focus choice-entry 'How the highlight moves' || return
	side_press; sleep .5; shot choice0; rawshot choice0
	wheel down 1 >/dev/null; sleep .4; shot choice1; rawshot choice1
	shots_differ choice0 choice1 0 60 10000 10000 && ok "a wheel step shows in a dialog's list of choices" \
		|| bad "a wheel step in a dialog's list of choices shows nothing"
	key 4; key 4; key 4
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 1
	app_focus choiceset-entry 'Side button' || return
	side_press; sleep .5
	app_focus choiceset-entry 'Side button hold' || return
	side_press; sleep .5; ui choiceset
	local sel set
	sel=$(grep -o '<node [^>]*selected="true"[^>]*>' "$SHOTS/smoke-choiceset.xml" | grep -o 'text="[^"]*"' | head -1)
	set=$(grep -o '<node [^>]*checked="true"[^>]*>' "$SHOTS/smoke-choiceset.xml" | grep -o 'text="[^"]*"' | head -1)
	[ -n "$set" ] && [ "$sel" = "$set" ] && ok "a list of choices opens on the one set ($set)" \
		|| bad "a list of choices opened on ${sel:-nothing}, not on ${set:-?}"
	key 4; key 4; key 4
}

t_title() {
	# Back from a page opened in place, the title is the page's again, not the one left
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 4 >/dev/null; sleep 0.6; side_press; sleep 2; shot title0; rawshot title0
	side_press; sleep 2; key 4; sleep 1.5; shot title1; rawshot title1
	! shots_differ title0 title1 80 30 480 80 && ok "back from a page, the title is the page's again" \
		|| bad "back from a page, the title is the one left"
	key 4; key 4
}

t_appdefaults() {
	# Stock apps that work with the highlight default to it; the others open the taskbar
	sh "am start -W -S -n com.android.calculator2/.Calculator >/dev/null"; sleep 2.5
	wheel down 1 >/dev/null; sleep 0.8
	dev "dumpsys window" | grep -m1 'mCurrentFocus' | grep -q calculator2 \
		&& ok "Calculator: the wheel moves the highlight" || bad "Calculator: the wheel left the app"
	# From AC, where the first step lands, along its row, not down its column: its keys are in a
	# scroller
	wheel down 1 >/dev/null; sleep 0.8; ui calcrow
	case "$(focus_field calcrow 5)" in
		*/paren) ok "Calculator: from AC the wheel goes along the row" ;;
		*) bad "Calculator: from AC the wheel went to $(focus_field calcrow 5)" ;;
	esac
	key 4; key 3
}

t_wifidialog() {
	# Wi-Fi's Add network, its fields in an app's own scroller: a step off the network name puts
	# the keyboard away, as anywhere, and goes on to the button beside it, along the row
	sh "am start -W -a com.android.settings.WIFI_DIALOG >/dev/null 2>&1"; sleep 3
	side_press; sleep 1.5
	wheel down 1 >/dev/null; sleep 1.5; ui wifistep; shot wifistep
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && bad "Add network: a step off the name left the keyboard up" \
		|| ok "Add network: a step off the name puts the keyboard away"
	[ "$(focus_field wifistep 5)" = com.android.settings:id/ssid ] \
		&& bad "Add network: the step stayed on the name" || ok "Add network: the step goes on from the name"
	key 4; key 4; key 3
}

t_plaintext() {
	# Text made focusable only to be read, as Notification history's turned off, is no stop:
	# past the switch the wheel scrolls, the focus kept on the switch
	# History off for its text, then back as it was
	local saved present n at stuck=; saved=$(dev "settings get secure notification_history_enabled")
	present=$(dev 'settings list secure | grep -c ^notification_history_enabled=')
	sh "settings put secure notification_history_enabled 0"
	sh "am start -W -S -a android.settings.NOTIFICATION_HISTORY >/dev/null 2>&1"; sleep 3
	# Each step, as at the page's end, where the scroller would take the step itself
	for n in 1 2 3 4; do
		wheel down 1 >/dev/null; sleep 0.8; ui plaintext
		at=$(focus_field plaintext 5)
		case "$at" in *history_off*|*history_toggled*) stuck="$stuck $n" ;; esac
	done
	shot plaintext
	[ -z "$stuck" ] && ok "Notification history: the wheel steps over its text" \
		|| bad "Notification history: the wheel stopped on its text (steps$stuck)"
	if [ "$present" = 0 ]; then sh "settings delete secure notification_history_enabled >/dev/null"
	else sh "settings put secure notification_history_enabled $saved"; fi
	key 4; key 3
}

t_timepicker() {
	# In the image's own wheel mode for the app, whatever this r1's owner picked for it
	local modes; modes=$(lget wheel_app_modes)
	lset wheel_app_modes "cat.kenny.r1.smoke=app"; sleep 0.5
	timepicker_in_stock_mode
	lset wheel_app_modes "$modes"
}

timepicker_in_stock_mode() {
	# The alarm's time picker: the side button grabs the hour and the wheel sets it, the hour
	# kept when the focus moves on, where the wheel only stepped past the touch clock face
	local i h0 h1 at
	sh "am start -W -S -n com.android.deskclock/.HandleApiCalls -a android.intent.action.SHOW_ALARMS >/dev/null 2>&1"; sleep 3
	for i in 1 2 3 4 5 6 7 8; do
		wheel down 1 >/dev/null; sleep 0.6; ui timepicker; at=$(focus_field timepicker 5)
		case "$at" in *:id/fab*) break ;; esac
	done
	case "$at" in
		*:id/fab*) ;;
		*) bad "time picker: the wheel never reached the add button, last on '$at'"; key 3; return ;;
	esac
	side_press; sleep 2; ui timepicker
	h0=$(focus_field timepicker 7); at=$(focus_field timepicker 5)
	# A press is the window's only once no second press can follow it: the turn waits for it
	side_press; sleep 0.6; wheel down 1 >/dev/null; sleep 0.8; side_press; sleep 0.6; wheel down 1 >/dev/null; sleep 0.8
	ui timepicker; shot timepicker
	h1=$(xnodes timepicker | awk -F '\t' '$5 ~ /:id\/hours$/ { print $7; exit }')
	case "$h0" in ''|*[!0-9]*) h0=x ;; esac
	case "$h1" in ''|*[!0-9]*) h1=x ;; esac
	if [ "$h0" = x ] || [ "$h1" = x ]; then
		bad "time picker: no hour to read, focus on '$at' ('$h0', '$h1')"
	elif [ $((10#$h1)) -eq $(((10#$h0 + 1) % 24)) ] || { [ $((10#$h0)) -eq 12 ] && [ $((10#$h1)) -eq 1 ]; }; then
		ok "time picker: the wheel sets the grabbed hour, kept as the focus moves on"
	else
		bad "time picker: the hour went $h0 to $h1, not one on"
	fi
	key 4; key 4; key 3
}

t_dialpad() {
	# The Dialer's empty number field: a step goes down onto the keypad, not onto the empty
	# speed dial hidden under it, which left the wheel stuck with nothing in sight
	sh "am start -W -S -a android.intent.action.DIAL >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.8; ui dialpad; shot dialpad
	case "$(focus_field dialpad 5)" in
		*:id/one|*:id/two|*:id/three) ok "Dialer: from the number field the wheel goes onto the keypad" ;;
		*) bad "Dialer: from the number field the wheel went to $(focus_field dialpad 5)" ;;
	esac
	key 4; key 4; key 3
}

t_offcamera() {
	# From the screen off, the side button's double press opens the camera, as a phone's does
	key 223; sleep 2
	side_double; sleep 3; shot offcamera
	dev "dumpsys window" | grep -m1 'mCurrentFocus' | grep -q opencamera \
		&& ok "double press from the screen off opens the camera" \
		|| bad "double press from the screen off left the camera shut"
	key 4; sleep 1; key 224; sh "wm dismiss-keyguard"; key 3
}

t_readorder() {
	# In the image's own wheel mode for the app, whatever this r1's owner picked for it
	local modes; modes=$(lget wheel_app_modes)
	lset wheel_app_modes "cat.kenny.r1.smoke=app"; sleep 0.5
	readorder_in_stock_mode
	lset wheel_app_modes "$modes"
}

readorder_in_stock_mode() {
	# The wheel goes as text reads: on the clock's timer keypad a step goes along the row of
	# digits, not down the first column only
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	# HandleApiCalls opens the timer tab; DeskClock itself opens the tab used last
	sh "am start -W -S -n com.android.deskclock/.HandleApiCalls -a android.intent.action.SHOW_TIMERS >/dev/null 2>&1"; sleep 3
	local a b at bt al bl
	wheel down 1 >/dev/null; sleep 0.6; ui read0; a=$(focused_in read0)
	wheel down 1 >/dev/null; sleep 0.6; ui read1; b=$(focused_in read1)
	shot readorder
	at=$(echo "$a" | awk '{print $3}'); bt=$(echo "$b" | awk '{print $3}')
	al=$(echo "$a" | awk '{print $2}'); bl=$(echo "$b" | awk '{print $2}')
	[ -n "$a" ] && [ -n "$b" ] && [ "$at" = "$bt" ] && [ "$bl" -gt "$al" ] \
		&& ok "a step goes along the keypad's row ($a to $b)" \
		|| bad "a step left the keypad's row ('${a:-nothing}' to '${b:-nothing}')"
	key 4; key 3
}

t_alongrow() {
	# With nothing below, a step down goes on along the row, as from Recorder's button to its
	# settings beside it
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -S -n org.lineageos.recorder/.RecorderActivity >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.6; ui row0; local a b; a=$(focused_in row0)
	wheel down 1 >/dev/null; sleep 0.6; ui row1; b=$(focused_in row1)
	[ -n "$b" ] && [ "$a" != "$b" ] && ok "a step down with nothing below goes along the row ($a to $b)" \
		|| bad "a step down with nothing below stayed on '${a:-nothing}'"
	# and back up it, onto the taller button beside
	wheel up 1 >/dev/null; sleep 0.6; ui row2; local c; c=$(focused_in row2)
	[ -n "$c" ] && [ "$c" != "$b" ] && ok "a step up with nothing above goes back along the row ($b to $c)" \
		|| bad "a step up with nothing above stayed on '${b:-nothing}'"
	key 4; key 3
}

t_emptyfirst() {
	# The default Dialer case explicitly selects Favourites, independent of Recents left open.
	local app f
	for app in com.android.messaging/.ui.conversationlist.ConversationListActivity \
			com.android.dialer/.main.impl.MainActivity; do
		sweep_has "${app%%/*}" || continue
		sweep_begin "${app%%/*}" || continue
		case "$app" in
			com.android.dialer/*)
				sweep_dialer_tab emptyfirst speed_dial_tab
				if ! xnodes emptyfirst | cut -f5 | grep -q 'id/empty_list_view_action$'; then
					echo "SKIP Dialer Favourites: not empty or empty action is unavailable"; sweep_end; continue
				fi ;;
			*) sh "am start -W -S -n $app >/dev/null 2>&1"; sleep 4; ui emptyfirst ;;
		esac
		f=$(focused_in emptyfirst)
		case "$f" in
			Button*) ok "${app%%/*}: the first focus is a button, not the empty list ($f)" ;;
			*) shot emptyfirst; bad "${app%%/*}: the first focus is '${f:-nothing}', not a button" ;;
		esac
		sweep_end
	done
	key 3
}

t_composefirst() {
	# A Compose page focused as a whole, as Settings' All apps: its first row has the first focus,
	# with the ring, before the wheel turns
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -S -a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS >/dev/null 2>&1"; sleep 4
	ui composefirst; local f top; f=$(focused_in composefirst); top=$(echo "$f" | awk '{print $3}')
	if [ -n "$top" ] && [ "$top" -ge 100 ]; then
		ok "All apps has its first row focused on opening ($f)"
	else
		shot composefirst; bad "All apps opened with '${f:-nothing}' focused, not its first row"
	fi
	key 4; key 3
}

t_toolbar() {
	has_jelly || return 0
	# A toolbar that keeps a keyboard's arrows in it, as the browser's: past its row the wheel
	# goes on into the page. In app mode, where the wheel moves through the browser
	local saved; saved=$(lget wheel_app_modes)
	lset wheel_app_modes org.lineageos.jelly=app
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -n org.lineageos.jelly/.MainActivity >/dev/null"; sleep 3
	wheel down 3 >/dev/null; sleep 0.8; ui toolbar; shot toolbar
	# In the page: the WebView itself, or a link or button inside it, which a newer WebView
	# reports as the focus
	local f wtop; f=$(focused_in toolbar)
	wtop=$(grep -o '<node[^>]*class="android.webkit.WebView"[^>]*>' $SHOTS/smoke-toolbar.xml | head -1 \
		| sed -n 's/.*bounds="\[[0-9]*,\([0-9]*\)\].*/\1/p')
	case "$f" in
		WebView*) ok "the wheel goes on past the browser's toolbar into the page" ;;
		?*) if [ -n "$wtop" ] && [ "$(echo "$f" | awk '{print $3}')" -ge "$wtop" ]; then
			ok "the wheel goes on past the browser's toolbar into the page ($f)"
		else
			bad "the wheel stayed in the browser's toolbar, on '$f'"
		fi ;;
		*) bad "the wheel stayed in the browser's toolbar, on 'nothing'" ;;
	esac
	lset wheel_app_modes "$saved"
	key 4; key 3
}

t_applist() {
	# A Compose page, as Settings' app list: the first step lands on its first row, past the
	# top bar's back arrow
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -a android.settings.APPLICATION_SETTINGS >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.8; ui applist; shot applist
	local f top; f=$(focused_in applist); top=$(echo "$f" | awk '{print $3}')
	if [ -n "$top" ] && [ "$top" -ge 100 ]; then
		ok "the app list's first step lands on its first row ($f)"
	else
		bad "the app list's first step went to '${f:-nothing}', not its first row"
	fi
	key 4; key 3
}

t_overlist() {
	# In the image's own wheel mode for the app, whatever this r1's owner picked for it
	local modes; modes=$(lget wheel_app_modes)
	lset wheel_app_modes "cat.kenny.r1.smoke=app"; sleep 0.5
	overlist_in_stock_mode
	lset wheel_app_modes "$modes"
}

overlist_in_stock_mode() {
	# An empty list with the focus, as the music player's: the step goes onto the button
	# floating over it, not past it to the tabs below
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -S -n org.lineageos.twelve/.MainActivity >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.8; ui overlist; shot overlist
	local f top; f=$(focused_in overlist); top=$(echo "$f" | awk '{print $3}')
	case "$f" in
		Button*) if [ "$top" -lt 537 ]; then ok "the step from an empty list lands on the button over it ($f)"; else bad "the step from an empty list went to '$f', not the button over it"; fi ;;
		*) bad "the step from an empty list went to '${f:-nothing}', not the button over it" ;;
	esac
	key 4; key 3
}

t_actionbar() {
	# An app's action bar keeps a keyboard's arrows out on a touchscreen: steps up from the page,
	# as Messaging's, still reach its menu
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -S -n com.android.messaging/.ui.conversationlist.ConversationListActivity >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.6; wheel up 4 >/dev/null; sleep 0.8; ui actionbar; shot actionbar
	local f bottom; f=$(focused_in actionbar); bottom=$(echo "$f" | awk '{print $5}')
	if [ -n "$bottom" ] && [ "$bottom" -le 100 ]; then
		ok "steps up reach Messaging's action bar ($f)"
	else
		bad "steps up stayed below Messaging's action bar, on '${f:-nothing}'"
	fi
	key 4; key 3
}

t_knobs() {
	# AudioFX's knobs, drawn views of their own: steps down past the Reverb switch reach them,
	# the first of the row as text reads, then the one beside it: none left out
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "am start -W -S -n org.lineageos.audiofx/.activity.ActivityMusic >/dev/null 2>&1"; sleep 3
	local f="" top="" i
	for i in 1 2 3 4 5; do
		wheel down 1 >/dev/null; sleep 0.6; ui knobs; f=$(focused_in knobs); top=$(echo "$f" | awk '{print $3}')
		[ -n "$top" ] && [ "$top" -ge 473 ] && break
	done
	shot knobs
	if [ -n "$top" ] && [ "$top" -ge 473 ]; then
		ok "steps down reach AudioFX's knobs ($f)"
	else
		bad "steps down stayed above AudioFX's knobs, on '${f:-nothing}'"
	fi
	local g; wheel down 1 >/dev/null; sleep 0.6; ui knobs2; g=$(focused_in knobs2)
	if [ -n "$top" ] && [ -n "$g" ] && [ "$(echo "$f" | awk '{print $2}')" -lt "$(echo "$g" | awk '{print $2}')" ] \
		&& [ "$(echo "$g" | awk '{print $3}')" -ge 473 ]; then
		ok "the wheel goes along the knobs from the first ($g)"
	else
		bad "the wheel left a knob out, from '$f' to '${g:-nothing}'"
	fi
	key 4; key 3
}

t_homemenu() {
	# The side button's hold on a home tile opens its menu, the tile staying where it is, and
	# the wheel goes through the menu: no drag hanging the icon where the last touch was
	key 3; sleep 1; key 3; sleep 1.5; wheel down 1 >/dev/null; sleep 0.6
	side_hold; sleep 1.8; wheel down 1 >/dev/null; sleep 0.8; ui homemenu; shot homemenu
	# The focus in the menu, level with its App info row
	local f menu; f=$(focused_in homemenu)
	menu=$(grep -o 'text="App info"[^>]*bounds="\[[0-9]*,' $SHOTS/smoke-homemenu.xml | grep -o '\[[0-9]*' | tr -d '[')
	if [ -n "$f" ] && [ -n "$menu" ] && [ "$(echo "$f" | awk '{print $2}')" = "$menu" ]; then
		ok "a hold on a home tile opens its menu, the wheel in it ($f)"
	else
		bad "a hold on a home tile left no menu with the wheel in it ('${f:-nothing}')"
	fi
	key 4; sleep 0.5; key 3
}

# The centre of the node with this text, or nothing: node_at NAME TEXT (^TEXT: the start of its
# description)
node_at() {
	xnodes $1 | awk -F '\t' -v want="$2" '
		$7 == want || $8 == want || want ~ /^\^/ && index($8, substr(want, 2)) == 1 {
			split($6, b, " "); print int((b[1] + b[3]) / 2), int((b[2] + b[4]) / 2); exit }'
}
# The text of the focused node, or of the first text in it
focused_text() {
	xfocus $1 | awk -F '\t' '$1 != "out" && ($8 != "" || $9 != "") { print ($8 != "" ? $8 : $9); exit }'
}

t_folder() {
	host_only || return
	# The stock grid's folders: one made by dropping an app on another, in the dock, where the
	# page shrinking for the drag leaves it in place, is named, the wheel goes from its first app
	# to the one beside, and closing it hands the wheel back to it. The grid home's database is
	# put back as it was after.
	local db=/data/data/com.android.launcher3/databases/launcher_4_by_5.db
	sh "content insert --uri content://lineagesettings/secure --bind name:s:wheel_home --bind value:s:0"
	sh "am force-stop com.android.launcher3; cp $db /data/local/tmp/smoke-grid.db"
	# The grid home settled: its dock where it was a second before
	local from to i was=
	for i in 1 2 3 4 5 6 7 8; do
		key 3; sleep 1.5; ui folder0
		from=$(node_at folder0 Messaging); to=$(node_at folder0 Telephone)
		[ -n "$from" ] && [ -n "$to" ] && [ "$was" = "$from $to" ] && break
		was="$from $to"
	done
	if [ -z "$from" ] || [ -z "$to" ]; then
		echo "SKIP $cur: this grid home has no Messaging and Telephone to make a folder of"
	else
		# Picked up past the long press's menu, then dropped on Telephone
		local x=${from% *} y=${from#* }
		sh "input motionevent DOWN $from; sleep 1.2; for d in 8 16 24 32 40; do input motionevent MOVE $x \$(($y - d)); sleep 0.05; done"; sleep 1
		sh "input motionevent MOVE $to; sleep 1.5; input motionevent UP $to"; sleep 2.5
		ui folder2
		# The dock shows no labels: the folder's name is in its description
		if [ -z "$(node_at folder2 '^Folder: Folder,')" ]; then
			shot folder-made; bad "dropping Messaging on Telephone made no folder named Folder"
		else
			sh "input tap $(node_at folder2 '^Folder: Folder,')"; sleep 1.5
			wheel down 1 >/dev/null; sleep 0.7; ui folder3
			local first second
			first=$(focused_text folder3)
			wheel down 1 >/dev/null; sleep 0.7; ui folder4; second=$(focused_text folder4)
			shot folder-open
			case "$first-$second" in
				Telephone-Messaging|Messaging-Telephone) ok "the folder's first step lands on its first app, the next beside it ($first, $second)" ;;
				*) bad "the folder's steps went to '$first' then '$second', not its two apps in a row" ;;
			esac
			key 4; sleep 1.2; ui folder5
			case "$(focused_text folder5)" in "Folder: Folder,"*) true ;; *) false ;; esac && ok "closing the folder hands the wheel back to it" \
				|| bad "closing the folder left the wheel on '$(focused_text folder5)', not the folder"
		fi
	fi
	sh "am force-stop com.android.launcher3; cp /data/local/tmp/smoke-grid.db $db; rm /data/local/tmp/smoke-grid.db"
	sh "content insert --uri content://lineagesettings/secure --bind name:s:wheel_home --bind value:s:1"
	key 3; sleep 2
}

t_gallery() {
	# Gallery's photos take the highlight: steps go through them, not past the grid to its tabs
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
	sh "monkey -p org.lineageos.glimpse -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"; sleep 3
	wheel down 1 >/dev/null; sleep 0.8; ui gallery; shot gallery
	local f; f=$(focused_in gallery)
	case "$f" in
		ConstraintLayout*|ViewGroup*) ok "Gallery's photos take the highlight from the first step ($f)" ;;
		*) bad "Gallery's highlight went to '${f:-nothing}', not a photo" ;;
	esac
	# On through the rows, past the last one laid out: every step stays on a photo, none falls
	# back to the top bar while the list brings the next rows in
	# and each photo it lands on comes whole into sight, none left under the tabs' edge
	local i tall h
	tall=$(echo "$f" | awk '{print $5 - $3}')
	for i in $(seq 15); do
		wheel down 1 >/dev/null; sleep 0.9; ui gallery-$i; f=$(focused_in gallery-$i)
		# A dump taken as the list brings rows in can miss the focus: a focus truly lost is
		# still lost a second on
		[ -n "$f" ] || { sleep 1; ui gallery-$i; f=$(focused_in gallery-$i); }
		case "$f" in
			ConstraintLayout*|ViewGroup*) ;;
			*) # Kept for the cause, under names a rerun does not write over: the dump before the
			   # step, the one after and the screen
			   shot gallery-lost; cp $SHOTS/smoke-gallery-$i.xml $SHOTS/smoke-gallery-lost.xml
			   cp $SHOTS/smoke-gallery-$((i - 1)).xml $SHOTS/smoke-gallery-lost-before.xml 2>/dev/null
			   bad "Gallery's step $((i + 1)) left the photos for '${f:-nothing}'"; key 4; key 3; return ;;
		esac
		h=$(echo "$f" | awk '{print $5 - $3}')
		if [ -n "$tall" ] && [ "$h" -lt $((tall - 4)) ]; then
			shot gallery-cut; bad "Gallery's step $((i + 1)) left its photo partly out of sight ($f)"; key 4; key 3; return
		fi
	done
	ok "Gallery's sixteen steps all land on photos in sight ($f)"
	key 4; key 3
}

t_listadjust() {
	# Settings > Display: a press on Peak refresh rate (a list; Screen timeout is a page of its
	# own) grabs it, a step turns to the next choice in place, a press sets it, no list opened,
	# and the same back; a press without turning opens the list
	local before after back i
	key 224
	sh "am start -W -a android.settings.DISPLAY_SETTINGS >/dev/null"; sleep 2
	for i in $(seq 1 24); do
		key 20; sleep 0.4; ui listadjust
		xfocus listadjust | grep -v '^out' | cut -f8 | grep -qx 'Peak refresh rate' && break
	done
	if ! xfocus listadjust | grep -v '^out' | cut -f8 | grep -qx 'Peak refresh rate'; then
		shot listadjust-none; bad "no wheel stop on Peak refresh rate"; key 4; key 3; return
	fi
	before=$(xfocus listadjust | grep '^in' | cut -f8 | grep -v -e '^$' -e '^Peak refresh rate$' | head -1)
	key 23; sleep 0.6; key 20; sleep 0.6; shot listadjust-turned; key 23; sleep 1
	ui listadjust-set
	after=$(xfocus listadjust-set | grep '^in' | cut -f8 | grep -v -e '^$' -e '^Peak refresh rate$' | head -1)
	if focus_win | grep -q 'com.android.settings' && [ -n "$after" ] && [ "$before" != "$after" ]; then
		ok "a list's press and step set Peak refresh rate in place ($before -> $after)"
	else
		bad "a list's press and step left Peak refresh rate at ${after:-nothing} ($(focus_win))"
	fi
	key 23; sleep 0.6; key 19; sleep 0.6; key 23; sleep 1; ui listadjust-back
	back=$(xfocus listadjust-back | grep '^in' | cut -f8 | grep -v -e '^$' -e '^Peak refresh rate$' | head -1)
	[ "$back" = "$before" ] && ok "a list's press and step back set it back ($back)" \
		|| bad "a list's press and step back left it at ${back:-nothing}, not $before"
	key 23; sleep 0.4; key 23; sleep 1; ui listadjust-opened
	if grep -q 'select_dialog_listview\|CheckedTextView' $SHOTS/smoke-listadjust-opened.xml; then
		ok "a list's press without turning opens its choices"
	else
		shot listadjust-opened; bad "a list's press without turning opened nothing"
	fi
	key 4; key 4; key 3
}

t_boxstep() {
	# Dialer: the step down from the search bar goes onto its voice button, not past it
	local id
	key 224
	sh "am start -W -n com.android.dialer/.main.impl.MainActivity >/dev/null"; sleep 2
	wheel up 12; sleep 0.6; ui boxstep-bar
	id=$(focus_field boxstep-bar 5)
	if [ "${id##*/}" != search_view_container ]; then
		shot boxstep-bar; bad "Dialer's wheel didn't reach the search bar (${id:-none})"; key 3; return
	fi
	wheel down 1; sleep 0.6; ui boxstep-voice
	id=$(focus_field boxstep-voice 5)
	[ "${id##*/}" = voice_search_button ] && ok "Dialer's search bar steps on to its voice button" \
		|| { shot boxstep-voice; bad "Dialer's step from the search bar went to ${id:-nothing}"; }
	key 3
}

t_scrollsight() {
	# Notification history: steps that scroll the page past its switch leave no focus out of sight
	local b top bottom
	key 224
	sh "am start -W -a android.settings.NOTIFICATION_HISTORY >/dev/null"; sleep 1.5
	wheel down 5; sleep 0.6; ui scrollsight
	b=$(focus_field scrollsight 6)
	top=$(echo "$b" | cut -d' ' -f2); bottom=$(echo "$b" | cut -d' ' -f4)
	# A line of the page's text the scroller went on to is in sight whole, however low
	if [ -z "$b" ] || [ $((bottom - top)) -ge 40 ] || focus_field scrollsight 4 | grep -q 'TextView$'; then
		ok "the history page's scroll left the focus in sight (${b:-on the page})"
	else
		shot scrollsight; bad "the history page's scroll left the focus out of sight ($b)"
	fi
	key 4; key 3
}

# The texts on the screen, a line each
texts() { ui $1; xnodes $1 | cut -f7 | grep -v '^$'; }

# The tools take the side button's hold themselves: on a stopped stopwatch it resets, and only
# with nothing on it does it go back. Without the window feature's permission the system kept
# the hold as its Back and "hold to reset" left the tool.
t_toolhold() {
	key 224
	sh "am start -W --activity-clear-task -n cat.kenny.r1.toolbox/.StopwatchActivity"; sleep 1.5
	# From whatever it was left in: stopped with time on it
	texts toolhold-0 | grep -qx 'running.*' && { side_press; sleep 1; }
	texts toolhold-0 | grep -q '^0:00.0$' && { side_press; sleep 1.5; side_press; sleep 1.5; }
	if texts toolhold-1 | grep -q 'hold to reset'; then ok "stopwatch stopped with time on it"
	else bad "stopwatch not stopped with time on it"; fi
	side_hold; sleep 2.5
	if focus_win | grep -q StopwatchActivity && texts toolhold-2 | grep -q '^0:00.0$'; then
		ok "a hold resets the stopwatch and stays in it"
	else bad "a hold did not reset the stopwatch: $(focus_win)"; fi
	side_hold; sleep 2.5
	if focus_win | grep -q StopwatchActivity; then bad "a hold with nothing on it did not go back"
	else ok "a hold with nothing on it goes back"; fi
	key 3
}

# The wheel's tools by finger: a slide turns, a tap presses, a resting finger holds
t_tooltouch() {
	key 224
	sh "am start -W --activity-clear-task -n cat.kenny.r1.toolbox/.MetronomeActivity"; sleep 1.5
	local a b
	a=$(texts tooltouch-0 | grep -m1 -E '^[0-9]+$')
	sh "input swipe 200 300 200 420 300"; sleep 1
	b=$(texts tooltouch-1 | grep -m1 -E '^[0-9]+$')
	if [ -n "$a" ] && [ "$a" != "$b" ]; then ok "a slide turns the metronome ($a to $b)"
	else bad "a slide did not turn the metronome ($a, $b)"; fi
	sh "input swipe 200 420 200 300 300"; sleep 1
	b=$(texts tooltouch-2 | grep -m1 -E '^[0-9]+$')
	[ -n "$a" ] && [ "$a" = "$b" ] && ok "sliding back gives the same tempo" || echo "tempo now $b, was $a"
	key 3
	sh "am start -W --activity-clear-task -n cat.kenny.r1.toolbox/.StopwatchActivity"; sleep 1.5
	texts tooltouch-3 | grep -q '^0:00.0$' || { sh "input swipe 200 300 200 300 900"; sleep 1.5; }
	sh "input tap 200 300"; sleep 1.5; sh "input tap 200 300"; sleep 1.5
	if texts tooltouch-4 | grep -q 'hold to reset'; then ok "taps start and stop the stopwatch"
	else bad "taps did not start and stop the stopwatch"; fi
	sh "input swipe 200 300 200 300 900"; sleep 1.5
	if focus_win | grep -q StopwatchActivity && texts tooltouch-5 | grep -q '^0:00.0$'; then
		ok "a resting finger resets the stopwatch"
	else bad "a resting finger did not reset the stopwatch"; fi
	sh "input swipe 200 300 200 300 900"; sleep 1.5
	if focus_win | grep -q StopwatchActivity; then ok "a resting finger never leaves the tool"
	else bad "a resting finger left the tool"; fi
	key 3
}

# The travel router's own network: its row opens the name and password, both filled in
t_routername() {
	key 224
	sh "am start -W -n cat.kenny.r1.router/.RouterActivity"; sleep 2
	ui routername-0
	local b
	b=$(xnodes routername-0 | awk -F'\t' '$5 ~ /id\/own$/ {print $6; exit}')
	if [ -z "$b" ]; then bad "no row for the r1's own network"; key 3; return; fi
	sh "input tap $(echo $b | awk '{print int(($1 + $3) / 2), int(($2 + $4) / 2)}')"; sleep 2
	ui routername-1; shot routername
	local n
	n=$(xnodes routername-1 | awk -F'\t' '$4 ~ /EditText/ && length($7) > 0' | wc -l)
	if [ "$n" -ge 2 ]; then ok "the network's name and password open, filled in"
	else bad "name and password dialog missing or empty ($n fields)"; fi
	key 4; sleep 1; key 3
}

# Settings checks use the apps' own wheel handling, independent of a user's app overrides.
# Keep these local to each test: nothing is cleared, and every choice is put back by the UI.
app_begin() {
	app_land=0
	app_package=${1%%/*}; app_lost=0
	app_modes=$(lget wheel_app_modes)
	app_tuning=$(lget wheel_app_tuning)
	app_restore_modes=$app_modes; app_restore_tuning=$app_tuning; app_active=1
	lset wheel_app_modes "${1%%/*}=app"
	lset wheel_app_tuning ""
	sh "am start -W --activity-clear-task -n $1"; sleep 1.5
	if ! focus_win | grep -Fq "${1%%/*}/"; then
		bad "$cur: activity did not open ($1)"
		return 1
	fi
}
# app_* tests use local saved modes; retain an EXIT-safe copy for timeout/cancellation.
app_abort() {
	stress_cancel_end; pageprefs_end; synth_loop_end; r2_resources_end
	[ "${app_active:-0}" = 1 ] || return 0
	key 3; r2_after_home
	toolbox_silent_end
	if [ "${app_land:-0}" = 1 ]; then rotation_back "$app_rotation" "$app_auto"; fi
	lset wheel_app_modes "$app_restore_modes"
	lset wheel_app_tuning "$app_restore_tuning"
	app_active=0
}
app_end() {
	stress_cancel_end; pageprefs_end; synth_loop_end; r2_resources_end
	key 3; r2_after_home
	toolbox_silent_end
	if [ "${app_land:-0}" = 1 ]; then rotation_back "$app_rotation" "$app_auto"; fi
	lset wheel_app_modes "$app_modes"
	lset wheel_app_tuning "$app_tuning"
	app_active=0
}
# Find a row by wheel, with bounded travel both ways for lists that do not wrap. A title
# inside the focused row counts too; USB Devices puts the title at the start of its description.
app_focus() {
	local way i was now
	for way in down up; do
		was=
		for i in $(seq 1 24); do
			if [ -n "${app_focus_activity:-}" ] && ! r2_activity "$app_focus_activity"; then
				bad "$cur: expected $app_focus_activity before focus travel ($(focus_win))"; return 1
			fi
			app_window_ok "$1" || return 1
			ui "$1"
			if [ -n "${app_focus_activity:-}" ] && ! r2_activity "$app_focus_activity"; then
				bad "$cur: expected $app_focus_activity before focus travel ($(focus_win))"; return 1
			fi
			if xfocus "$1" | awk -F '\t' -v want="$2" '
				$1 == "self" && $5 ~ /(RecyclerView|NestedScrollView|ScrollView|ListView)$/ { container = 1 }
				$1 != "out" && ($8 == want || $9 == want || index($9, want " - ") == 1) { found = 1 }
				END { exit !(found && !container) }'; then return 0; fi
			now="$(focused_in "$1") $(focused_text "$1")"
			[ -n "$now" ] && [ "$now" = "$was" ] && break
			was=$now
			wheel "$way" 1 >/dev/null; sleep 0.4
		done
	done
	shot "$1-missing"; bad "$cur: no wheel stop on $2"
	return 1
}
app_window_ok() {
	[ "${app_active:-0}" = 1 ] && [ -n "${app_package:-}" ] || return 0
	local window i
	# Finishing/reopening an activity can briefly focus Launcher between app windows.
	# Wait without any input; a persistent loss still fails and captures its evidence.
	for i in 1 2 3 4 5 6 7 8; do
		window=$(focus_win)
		case "$window" in *" $app_package/"*) return 0 ;; esac
		sleep .15
	done
	app_lost=1
	ui "$1-lost-window"; shot "$1-lost-window"
	bad "$cur: lost $app_package window (${window:-no focus})"
	return 1
}
# Texts and checkable state of the nearest clickable row containing a title. Parsing nodes
# rather than grepping the whole screen keeps repeated On/Off and Normal labels unambiguous.
app_row() {
	awk -v want="$2" 'BEGIN { RS = "<" }
		function attr(name,   m) {
			if (!match($0, " " name "=\"[^\"]*\"")) return ""
			m = substr($0, RSTART + length(name) + 3, RLENGTH - length(name) - 4)
			gsub(/&quot;/, "\"", m); gsub(/&apos;/, "\047", m)
			gsub(/&lt;/, "<", m); gsub(/&gt;/, ">", m); gsub(/&amp;/, "\\&", m)
			return m
		}
		/^node / {
			n++; level[n] = depth; label[n] = attr("text"); desc[n] = attr("content-desc")
			check[n] = attr("checkable") == "true" ? "checked=" attr("checked") : ""
			parent[n] = depth > 0 ? path[depth - 1] : 0; path[depth] = n
			click[n] = attr("clickable") == "true"
			if (!hit && (label[n] == want || desc[n] == want || index(desc[n], want " - ") == 1)) hit = n
			if ($0 !~ /\/>[ \t\r\n]*$/) depth++
		}
		/^\/node>/ { depth-- }
		END {
			if (!hit) exit
			row = hit; while (row && !click[row]) row = parent[row]
			if (!row) exit
			for (i = row; i <= n && (i == row || level[i] > level[row]); i++) {
				if (label[i] != "" && label[i] != want) print label[i]
				if (check[i] != "") print check[i]
				# A content description can replace USB Devices child texts in a dump.
				if (index(desc[i], want " - ") == 1) print substr(desc[i], length(want) + 4)
			}
		}' "$SHOTS/smoke-$1.xml"
}
app_value() {
	app_row "$1" "$2" | awk -v choices="$3" 'BEGIN { n = split(choices, a, "|") }
		{ for (i = 1; i <= n; i++) if ($0 == a[i]) { print; exit } }'
}
app_next() {
	awk -v choices="$1" -v now="$2" 'BEGIN {
		n = split(choices, a, "|"); for (i = 1; i <= n; i++) if (a[i] == now) { print a[i % n + 1]; exit }
	}'
}
app_tap() {
	local at
	app_window_ok "$1" || return 1
	tap_text "$1" "$2" && return 0
	at=$(node_at "$1" "^$2")
	[ -n "$at" ] && sh "input tap $at"
}
# One press, the same row tapped, then enough taps to return to the recorded value. Choices
# below are transcribed from each app's strings.xml, in the order its code cycles them.
app_cycle() {
	local before after expected i count optional=${4:-}
	wheel down 1 >/dev/null; sleep 0.3
	app_focus "$1" "$2" || return
	before=$(app_value "$1" "$2" "$3")
	if [ -z "$before" ]; then bad "$cur: $2 has no known visible value"; return; fi
	app_window_ok "$1" || return 1
	side_press; sleep 1; ui "$1"
	app_window_ok "$1" || return 1
	after=$(app_value "$1" "$2" "$3"); expected=$(app_next "$3" "$before")
	# An optional last choice (Port's Webcam) is absent on hardware without that role.
	if [ -n "$optional" ] && [ "$expected" = "$optional" ] \
		&& [ "$after" = "$(app_next "$3" "$optional")" ]; then expected=$after; fi
	[ "$after" = "$expected" ] && ok "$cur: wheel and press change $2 ($before -> $after)" \
		|| bad "$cur: wheel and press on $2 gave ${after:-nothing}, expected $expected"
	expected=$(app_next "$3" "$after")
	app_tap "$1" "$2"; sleep 0.8; ui "$1"
	after=$(app_value "$1" "$2" "$3")
	if [ -n "$optional" ] && [ "$expected" = "$optional" ] \
		&& [ "$after" = "$(app_next "$3" "$optional")" ]; then expected=$after; fi
	[ -n "$expected" ] && [ "$after" = "$expected" ] && ok "$cur: touch changes $2 ($after)" \
		|| bad "$cur: touch on $2 gave ${after:-nothing}, expected ${expected:-a known value}"
	count=$(echo "$3" | awk -F '|' '{ print NF }')
	for i in $(seq 1 "$count"); do
		[ "$after" = "$before" ] && break
		app_tap "$1" "$2"; sleep 0.6; ui "$1"
		after=$(app_value "$1" "$2" "$3")
	done
	[ "$after" = "$before" ] && ok "$cur: restored $2 ($before)" \
		|| bad "$cur: could not restore $2 to $before"
}
app_title() {
	if texts "$1" | grep -Fxq "$2"; then ok "$cur: shows $2"
	else bad "$cur: missing $2"; return 1; fi
}
app_id_text() { xnodes "$1" | awk -F '\t' -v id="$2" '$5 ~ ("id/" id "$") { print $7; exit }'; }
app_tap_id() {
	local at
	at=$(xnodes "$1" | awk -F '\t' -v id="$2" '$5 ~ ("id/" id "$") {
		split($6, b, " "); print int((b[1] + b[3]) / 2), int((b[2] + b[4]) / 2); exit }')
	[ -n "$at" ] && sh "input tap $at"
}
app_min_target() {
	local dpi minimum bounds
	dpi=$(dev "wm density" | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad "$cur: cannot read display density"; return ;; esac
	minimum=$((48 * dpi / 160))
	bounds=$(xnodes "$1" | awk -F '\t' -v id="$2" '$5 ~ ("id/" id "$") { print $6; exit }')
	if echo "$bounds" | awk -v minimum="$minimum" 'NF == 4 && $3 - $1 >= minimum && $4 - $2 >= minimum { found = 1 } END { exit !found }'; then
		ok "$cur: $2 has a visible 48dp target"
	else bad "$cur: $2 is missing or smaller than 48dp ($bounds)"; fi
}
app_landscape() {
	if [ "${app_land:-0}" = 0 ]; then
		app_rotation=$(dev "settings get system user_rotation")
		app_auto=$(dev "settings get system accelerometer_rotation")
		app_land=1
	fi
	turn 1; sleep 1.5; ui "$1"
	if xnodes "$1" | head -1 | awk -F '\t' '{ split($6, b, " "); found = b[3] - b[1] > b[4] - b[2] } END { exit !found }'; then
		ok "$cur: landscape page"
	else bad "$cur: page did not turn to landscape"; return 1; fi
}
app_pad_targets() {
	local dpi bounds
	dpi=$(dev "wm density" | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad "$cur: no pad density"; return ;; esac
	bounds=$(xnodes "$1" | awk -F '\t' '$5 ~ /id\/pads$/ { print $6; exit }')
	if echo "$bounds" | awk -v d="$dpi" 'NF == 4 {
		gap = 8 * d / 160; minimum = 48 * d / 160
		w=$3-$1; h=$4-$2; cols=2*w>=3*h?3:2; rows=int((6+cols-1)/cols)
		found = (w-(cols-1)*gap)/cols >= minimum && (h-(rows-1)*gap)/rows >= minimum
	} END { exit !found }'; then ok "$cur: every landscape pad is at least 48dp"
	else bad "$cur: landscape pads are missing or too small ($bounds)"; fi
}
app_sight() {
	local bounds root dpi minimum
	app_focus "$1" "$2" || return
	bounds=$(focus_field "$1" 6); root=$(xnodes "$1" | head -1 | cut -f6)
	dpi=$(dev "wm density" | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad "$cur: no density for landscape check"; return ;; esac
	minimum=$((${3:-48} * dpi / 160))
	if echo "$bounds" | awk -v root="$root" -v minimum="$minimum" 'BEGIN { split(root, r, " ") }
		NF == 4 && $1 >= r[1] && $2 >= r[2] && $3 <= r[3] && $4 <= r[4] && $4 - $2 >= minimum { found = 1 }
		END { exit !found }'; then ok "$cur: landscape wheel reaches $2 in sight"
	else bad "$cur: landscape focus on $2 is clipped ($bounds in $root)"; fi
}
synth_mode() {
	case "$(app_id_text "$1" setting)" in
		Filter*) echo Filter ;; Pitch*) echo Pitch ;; Stutter*) echo Stutter ;; Echo*) echo Echo ;;
		*' scale') echo Scale ;; Preset*|'Your own setup') echo Preset ;;
		Play*|'Record a loop'|'Cancel recording'|'Close the loop'|'Add to the loop'|'Done adding'|'Clear the loop') echo Play ;;
	esac
}

t_synthmodes() {
	local app_modes app_tuning before before_mode label i bounds mode expected seen=
	toolbox_silent_begin || return
	app_begin cat.kenny.r1.synth/.SynthActivity || { app_end; return; }
	app_title synthmodes Synth || { app_end; return; }
	ui synthmodes; before=$(app_id_text synthmodes hint)
	before_mode=$(synth_mode synthmodes)
	[ -n "$before" ] || { bad 'synthmodes: missing mode hint'; app_end; return; }
	# The setting label changes with every mode; the hint disambiguates a custom Preset.
	for i in 1 2 3 4 5 6 7; do
		ui synthmodes
		label=$(app_id_text synthmodes setting)
		mode=$(synth_mode synthmodes); seen="$seen $mode"
		expected=$(app_next 'Filter|Pitch|Stutter|Echo|Scale|Preset|Play' "$mode")
		case "$label" in
			Filter*|Pitch*|Stutter*|Echo*|*' scale'|Preset*|'Your own setup'|Play*|'Record a loop'|'Cancel recording'|'Close the loop'|'Add to the loop'|'Done adding'|'Clear the loop') ok "synthmodes: visible setting $label" ;;
			*) bad "synthmodes: unexpected setting ${label:-nothing}" ;;
		esac
		case "$label" in
			Play*|'Record a loop'|'Cancel recording'|'Close the loop'|'Add to the loop'|'Done adding'|'Clear the loop')
				# A retained Play selection can now be on the loop; anchor at pad zero.
				synth_pad_tap synthmodes 0; ui synthmodes
				wheel down 1 >/dev/null; sleep 0.5; ui synthmodes-play
				[ "$(app_id_text synthmodes-play setting)" = 'Play Chirp' ] \
					&& ok "synthmodes: wheel picks Chirp" || bad "synthmodes: wheel did not pick Chirp"
				side_press; sleep 0.6
				# First pad by touch, derived from the PadsView bounds, two columns, three rows.
				bounds=$(xnodes synthmodes-play | awk -F '\t' '$5 ~ /id\/pads$/ { print $6; exit }')
				if [ -n "$bounds" ]; then
					sh "input tap $(echo "$bounds" | awk '{ print int($1 + ($3 - $1) / 4), int($2 + ($4 - $2) / 6) }')"
					sleep 0.5; ui synthmodes-play
					[ "$(app_id_text synthmodes-play setting)" = 'Play Grain' ] \
						&& ok "synthmodes: touch picks Grain" || bad "synthmodes: touch did not pick Grain"
				else bad "synthmodes: pads have no bounds"; fi
				app_title synthmodes-play Knobs
				app_min_target synthmodes-play knobs
				app_tap_id synthmodes-play knobs; sleep 0.5; ui synthmodes-play
				[ "$(synth_mode synthmodes-play)" = Filter ] && ok 'synthmodes: Knobs touch exits Play' \
					|| bad 'synthmodes: Knobs touch did not leave Play'
				# Return to Play to check the wheel exit independently.
				for expected in 1 2 3 4 5 6; do
					ui synthmodes-play; app_tap_id synthmodes-play setting; sleep 0.4
				done
				expected=Filter
				# Down now passes the loop action (and Clear for a retained take) before Filter.
				wheel down 5 >/dev/null; sleep 0.5
				for expected in 1 2 3; do
					ui synthmodes-play; [ "$(synth_mode synthmodes-play)" = Play ] || break
					wheel down 1 >/dev/null; sleep 0.4
				done
				expected=Filter ;;
			*) side_press; sleep 0.6 ;;
		esac
		ui synthmodes
		[ -n "$expected" ] && [ "$(synth_mode synthmodes)" = "$expected" ] \
			&& ok "synthmodes: wheel/button advances $mode to $expected" || bad "synthmodes: wrong mode after $mode"
	done
	for mode in Filter Pitch Stutter Echo Scale Preset Play; do
		case " $seen " in *" $mode "*) ok "synthmodes: visited $mode" ;; *) bad "synthmodes: missed $mode" ;; esac
	done
	# Seven taps on the setting label traverse all seven modes and return to the same one.
	for i in 1 2 3 4 5 6 7; do
		ui synthmodes; mode=$(synth_mode synthmodes)
		expected=$(app_next 'Filter|Pitch|Stutter|Echo|Scale|Preset|Play' "$mode")
		app_tap_id synthmodes setting; sleep 0.5; ui synthmodes
		[ -n "$expected" ] && [ "$(synth_mode synthmodes)" = "$expected" ] && ok "synthmodes: touch advances $mode to $expected" \
			|| bad "synthmodes: touch left the same mode"
	done
	# The same exit must stay visible in the short 640x480 orientation.
	for i in 1 2 3 4 5 6 7; do
		ui synthmodes
		[ "$(synth_mode synthmodes)" = Play ] && break
		app_tap_id synthmodes setting; sleep 0.4
	done
	app_landscape synthmodes-land
	app_title synthmodes-land Knobs
	app_min_target synthmodes-land knobs
	app_pad_targets synthmodes-land
	app_tap_id synthmodes-land knobs; sleep 0.5; ui synthmodes
	[ "$(synth_mode synthmodes)" = Filter ] && ok 'synthmodes: landscape Knobs exits Play' \
		|| bad 'synthmodes: landscape Knobs did not exit Play'
	# Restore the initial mode without changing any knob, scale or preset value.
	for i in 1 2 3 4 5 6 7; do
		ui synthmodes
		[ "$(synth_mode synthmodes)" = "$before_mode" ] && break
		app_tap_id synthmodes setting; sleep 0.4
	done
	[ "$(synth_mode synthmodes)" = "$before_mode" ] && ok "synthmodes: restored initial mode" \
		|| bad "synthmodes: could not restore initial mode"
	shot synthmodes; app_end
}

# A failed dumpsys is not evidence that a service is off.
app_service_state() {
	local result
	result=$(dev "dumpsys activity services $1")
	case "$result" in
		*'Permission Denial'*|*'Error'*|*"Can't find"*) echo unknown ;;
		*ServiceRecord*) echo on ;;
		*'ACTIVITY MANAGER SERVICES'*) echo off ;;
		*) echo unknown ;;
	esac
}

# The PeopleScroll has no id: use the immediate parent of id/people, only if it cannot scroll.
walkie_people_gesture() {
	local dpi at direction=$2
	dpi=$(dev "wm density" | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad 'walkieset: no display density for people slide'; return 1 ;; esac
	at=$(awk -v dpi="$dpi" -v way="$direction" 'BEGIN { RS="<" }
		function attr(s, key,   t) {
			if (!match(s, " " key "=\"[^\"]*\"")) return ""
			return substr(s, RSTART+length(key)+3, RLENGTH-length(key)-4)
		}
		/^node / {
			if (attr($0,"resource-id") ~ /id\/people$/) {
				s=up[d-1]
				if (attr(s,"class") !~ /Scroll/ || attr(s,"scrollable") != "false") exit
				b=attr(s,"bounds"); gsub(/[^0-9]+/," ",b); split(b,v," ")
				step=int(28*dpi/160); x=int((v[1]+v[3])/2); y=int((v[2]+v[4])/2)
				if (v[3]<=v[1] || v[4]-v[2]<2*step+8) exit
				end=y+(way=="down" ? step : -step)
				print x,y,x,end; exit
			}
			up[d]=$0; if ($0 !~ /\/>[ \t\r\n]*$/) d++
		}
		/^\/node>/ { d-- }' "$SHOTS/smoke-$1.xml")
	if [ -z "$at" ]; then echo 'SKIP walkieset people slide: needs a visible, non-scrolling people column'; return 1; fi
	# Less than the hold delay; movement cancels the pending talk/replay press.
	sh "input swipe $at 120"; sleep 0.4
}

walkie_people_check() {
	local before after channel channels way volume i
	channels='Everyone|Kitchen|Workshop|Den|Garden|Office|Upstairs|Outside'
	ui walkieset-people
	if [ "$(app_service_state cat.kenny.r1.walkie)" != on ]; then
		echo 'SKIP walkieset people slide: radio is not running (microphone permission needed)'; return
	fi
	before=$(xnodes walkieset-people | awk -F '\t' '$5 ~ /id\/drum$/ { print $8; exit }')
	channel=$(app_id_text walkieset-people channel)
	case "$before" in
		'Volume '*)
			volume=${before#Volume }; case "$volume" in ''|*[!0-9]*) bad 'walkieset: invalid drum volume'; return ;; esac
			if ! dev 'cmd media_session volume --stream 3 --get' | grep -Fq "volume is $volume in range"; then
				echo 'SKIP walkieset people volume: cannot verify media-volume restore access'; return
			fi
			way=down; [ "$volume" = 0 ] && way=up ;;
		*)
			if [ "$before" != "$channel" ] || ! echo "$channels" | tr '|' '\n' | grep -Fxq "$channel"; then
				echo 'SKIP walkieset people slide: drum is not on a channel or live volume'; return
			fi
			way=down; [ "$channel" = Outside ] && way=up ;;
	esac
	walkie_people_gesture walkieset-people "$way" || return
	ui walkieset-people
	after=$(xnodes walkieset-people | awk -F '\t' '$5 ~ /id\/drum$/ { print $8; exit }')
	if [ -n "${volume:-}" ]; then
		case "$after" in
			'Volume '*) [ "$after" != "$before" ] && ok 'walkieset: slide over people changes live volume' || bad 'walkieset: people swallowed volume slide' ;;
			*) echo 'SKIP walkieset people volume: speaker stopped during gesture' ;;
		esac
		sh "cmd media_session volume --stream 3 --set $volume"
		dev 'cmd media_session volume --stream 3 --get' | grep -Fq "volume is $volume in range" \
			&& ok 'walkieset: restored media volume' || bad 'walkieset: media volume restoration not verified'
	else
		[ "$(app_id_text walkieset-people channel)" != "$channel" ] \
			&& ok 'walkieset: slide over people changes channel' || bad 'walkieset: people swallowed channel slide'
		# Restore by the current named channel, even if the gesture emitted several steps.
		for i in 1 2 3 4 5 6 7 8; do
			[ "$(app_id_text walkieset-people channel)" = "$channel" ] && break
			if [ "$way" = down ]; then wheel up 1 >/dev/null; else wheel down 1 >/dev/null; fi
			sleep 0.2; ui walkieset-people
		done
		[ "$(app_id_text walkieset-people channel)" = "$channel" ] \
			&& ok 'walkieset: restored channel after people slide' || bad 'walkieset: could not restore channel'
		echo 'SKIP walkieset people volume: needs another person speaking; channel slide tested'
	fi
}

t_walkieset() {
	local app_modes app_tuning was_on channel point
	toolbox_silent_begin || return
	was_on=$(dev "dumpsys activity services cat.kenny.r1.walkie | grep -c ServiceRecord")
	[ "$was_on" != 0 ] || walkieset_stop_on_cleanup=1
	app_begin cat.kenny.r1.walkie/.WalkieActivity || { app_end; return; }
	app_title walkieset Walkie-Talkie
	walkie_people_check
	# Test a held drum only on an empty channel, so no other person receives test audio.
	ui walkieset
	if xnodes walkieset | cut -f7 | grep -Fxq 'Nobody else is on this channel yet.' \
		&& ! xnodes walkieset | cut -f7 | grep -Fq 'Walkie-Talkie needs the microphone'; then
		channel=$(app_id_text walkieset channel)
		point=$(xnodes walkieset | awk -F '\t' '$5 ~ /id\/drum$/ { split($6, b, " "); print int((b[1] + b[3]) / 2), int((b[2] + b[4]) / 2); exit }')
		if [ -n "$point" ]; then
			rawshot walkieset-drum-before
			sh "input motionevent DOWN $point; sleep 0.7"
			rawshot walkieset-drum-held
			sh "input motionevent UP $point"; sleep 0.6; ui walkieset
			if shots_differ walkieset-drum-before walkieset-drum-held 40 130 200 240 \
				&& [ "$(app_id_text walkieset channel)" = "$channel" ]; then
				ok 'walkieset: still drum hold talks and release keeps the channel'
			else bad 'walkieset: drum hold did not change the talking screen or picked a channel'; fi
		else bad 'walkieset: drum has no touch bounds'; fi
	else echo 'SKIP walkieset drum talk: needs Wi-Fi, microphone permission and an empty channel'; fi
	# Enter by touch so channel, microphone permission and service state need no changes.
	ui walkieset; app_tap_id walkieset settings_button; sleep 1
	if ! app_title walkieset 'Walkie-Talkie settings'; then
		app_end; [ "$was_on" = 0 ] && sh "am force-stop cat.kenny.r1.walkie"; return
	fi
	app_cycle walkieset 'Talk sounds' 'On|Off'
	app_cycle walkieset 'Message chime' 'Off|On'
	app_cycle walkieset 'Play with the page closed' 'On|Off'
	app_cycle walkieset 'Stay on after leaving' 'On|Off'
	app_landscape walkieset-land
	app_sight walkieset-land 'Talk sounds' 64
	app_sight walkieset-land 'Stay on after leaving' 64
	shot walkieset; app_end
	# End a radio started solely by this test, after restoring its settings. Force-stop keeps
	# the preferences and does not need shell access to the app's private service component.
	[ "$was_on" = 0 ] && sh "am force-stop cat.kenny.r1.walkie"
}

# Metronome playback checks must remain silent, including early return or cancellation.
toolbox_silent_end() {
	[ "${toolbox_silent_active:-0}" = 1 ] || return 0
	# MetronomeActivity.onPause stops and joins its speaker before volume is restored.
	key 3; sleep 0.5
	if [ "${walkieset_stop_on_cleanup:-0}" = 1 ]; then
		# A radio started only by walkieset must stop before its saved media volume returns.
		sh 'am force-stop cat.kenny.r1.walkie'; walkieset_stop_on_cleanup=0
	fi
	if [ "$toolbox_saved_volume" != 0 ]; then
		sh "cmd media_session volume --stream 3 --set $toolbox_saved_volume" >/dev/null
	fi
	dev 'cmd media_session volume --stream 3 --get' | grep -Fq "volume is $toolbox_saved_volume in range" \
		|| bad 'toolboxset: could not restore media volume'
	toolbox_silent_active=0
}
toolbox_silent_begin() {
	toolbox_saved_volume=$(dev 'cmd media_session volume --stream 3 --get' | sed -n 's/.*volume is \([0-9][0-9]*\) in range.*/\1/p' | tail -1)
	case "$toolbox_saved_volume" in ''|*[!0-9]*) echo 'SKIP toolboxset: cannot save media volume for silent playback'; return 1 ;; esac
	toolbox_silent_active=1
	[ "$toolbox_saved_volume" = 0 ] || sh 'cmd media_session volume --stream 3 --set 0' >/dev/null
	if ! dev 'cmd media_session volume --stream 3 --get' | grep -Fq 'volume is 0 in range'; then
		bad 'toolboxset: cannot silence media before playback'; toolbox_silent_end; return 1
	fi
}

t_toolboxset() {
	# texts/app_title run in pipelines; select live snapshots in this scope so a fallback
	# chosen in a subshell is not lost before the next continuously animated tool dump.
	local ui_fast=1
	local app_modes app_tuning before after way i bar bar_choices
	toolbox_silent_begin || return
	bar_choices='beats a minute, 4 to the bar|beats a minute, 3 to the bar|beats a minute, 2 to the bar|beats a minute, 6 to the bar|beats a minute, every beat alike'
	app_begin cat.kenny.r1.toolbox/.ToolboxActivity || { app_end; return; }
	app_title toolboxset Toolbox
	if ! app_focus toolboxset Settings; then app_end; return; fi
	side_press; sleep 1
	app_title toolboxset 'Toolbox settings' || { app_end; return; }
	app_cycle toolboxset 'Keep the screen on' 'While a tool plays or runs|While a tool is open'
	[ "${app_lost:-0}" = 0 ] || { app_end; return; }
	app_cycle toolboxset 'Metronome sound' 'Click|Wood block|Beep'
	[ "${app_lost:-0}" = 0 ] || { app_end; return; }
	app_cycle toolboxset 'Tone loudness' 'Quiet|Medium|Loud'
	[ "${app_lost:-0}" = 0 ] || { app_end; return; }
	app_cycle toolboxset 'Tone wave' 'Sine|Triangle|Square'
	[ "${app_lost:-0}" = 0 ] || { app_end; return; }
	app_cycle toolboxset 'Buzz at dead level' 'Off|On'
	[ "${app_lost:-0}" = 0 ] || { app_end; return; }
	shot toolboxset; key 4; sleep 0.7
	# The end keys use the same tempo steps as the wheel. Open through the exported list,
	# since its child activities are private to the app on an on-device run.
	if app_focus toolboxset Metronome; then
		side_press; sleep 1; ui toolboxset-tempo
		before=$(app_id_text toolboxset-tempo number)
		bar=$(app_id_text toolboxset-tempo line)
		app_min_target toolboxset-tempo end_up
		app_min_target toolboxset-tempo end_down
		case "$before" in
			''|*[!0-9]*) bad "toolboxset: metronome has no tempo" ;;
			*)
				way=down; [ "$before" -ge 240 ] && way=up
				wheel "$way" 1 >/dev/null; sleep 0.5; ui toolboxset-tempo
				after=$(app_id_text toolboxset-tempo number)
				[ "$after" != "$before" ] && ok "toolboxset: wheel changes tempo" || bad "toolboxset: wheel left tempo unchanged"
				if [ "$way" = down ]; then app_tap_id toolboxset-tempo end_up
				else app_tap_id toolboxset-tempo end_down; fi
				sleep 0.5; ui toolboxset-tempo
				[ "$(app_id_text toolboxset-tempo number)" = "$before" ] && ok "toolboxset: touch end key restores tempo" \
					|| bad "toolboxset: end key did not restore tempo"
				# Try either key a bounded number of times if a failed assertion left a value changed.
				for i in 1 2 3; do
					after=$(app_id_text toolboxset-tempo number)
					[ "$after" = "$before" ] && break
					case "$after" in ''|*[!0-9]*) break ;; esac
					if [ "$after" -gt "$before" ]; then app_tap_id toolboxset-tempo end_up
					else app_tap_id toolboxset-tempo end_down; fi
					sleep 0.5; ui toolboxset-tempo
				done ;;
		esac
		# A stopped hold leaves the tool. Reopen before testing the tap and running hold.
		app_title toolboxset-tempo 'Stopped.'
		side_hold; sleep 0.6; ui toolboxset-back
		if ! app_title toolboxset-back Tuner || ! app_focus toolboxset-back Metronome; then app_end; return; fi
		side_press; sleep 0.6; ui toolboxset-bar
		app_title toolboxset-bar 'Stopped.'
		app_tap_id toolboxset-bar line; sleep 0.5; ui toolboxset-bar
		after=$(app_id_text toolboxset-bar line)
		[ -n "$after" ] && [ "$after" = "$(app_next "$bar_choices" "$bar")" ] \
			&& ok 'toolboxset: touch changes the stopped bar' || bad 'toolboxset: touch did not change the stopped bar'
		side_press; sleep 0.5
		if app_title toolboxset-bar 'Press to stop. Wheel sets tempo. Hold or tap the beats to change the bar.'; then
			side_hold; sleep 0.5; ui toolboxset-bar
			if focus_win | grep -Eq 'cat\.kenny\.r1\.toolbox/(cat\.kenny\.r1\.toolbox)?\.MetronomeActivity[ }]' \
				&& [ "$(app_id_text toolboxset-bar line)" = "$(app_next "$bar_choices" "$after")" ]; then
				ok 'toolboxset: running hold changes the bar and stays in Metronome'
				after=$(app_id_text toolboxset-bar line)
				app_tap_id toolboxset-bar line; sleep 0.4; ui toolboxset-bar
				[ "$(app_id_text toolboxset-bar line)" = "$(app_next "$bar_choices" "$after")" ] \
					&& ok 'toolboxset: touch changes the running bar' || bad 'toolboxset: touch did not change the running bar'
			else bad 'toolboxset: running hold left Metronome or did not change the bar'; fi
			# Latch regression: after hold then bar tap, ONE press must stop (no focus wheel/retry).
			# Stop only on this tool; never send a press to a page reached by a broken hold.
			if focus_win | grep -Eq 'cat\.kenny\.r1\.toolbox/(cat\.kenny\.r1\.toolbox)?\.MetronomeActivity[ }]'; then side_press; sleep 0.5; fi
		fi
		if ! focus_win | grep -Eq 'cat\.kenny\.r1\.toolbox/(cat\.kenny\.r1\.toolbox)?\.MetronomeActivity[ }]'; then app_end; return; fi
		sleep 0.4; ui toolboxset-bar
		app_title toolboxset-bar 'Stopped.'
		# Restore by actual UI state, including a failed first transition.
		for i in 1 2 3 4 5; do
			[ "$(app_id_text toolboxset-bar line)" = "$bar" ] && break
			app_tap_id toolboxset-bar line; sleep 0.4; ui toolboxset-bar
		done
		[ "$(app_id_text toolboxset-bar line)" = "$bar" ] && ok 'toolboxset: restored beats to the bar' \
			|| bad 'toolboxset: could not restore beats to the bar'
		app_landscape toolboxset-land
		app_min_target toolboxset-land line
		app_min_target toolboxset-land end_up
		app_min_target toolboxset-land end_down
		key 4; sleep 0.6
	fi
	# If an earlier launch failed, still establish the orientation before checking the list.
	app_landscape toolboxset-land
	app_sight toolboxset-land Settings 64
	if app_focus toolboxset Tally; then
		side_press; sleep 0.7; ui toolboxset-tally
		app_min_target toolboxset-tally tally_less
		app_min_target toolboxset-tally tally_more
		# Leave the count and its lap/rate history intact; the shared turnKey action is
		# exercised by the tempo test above.
	fi
	app_end
}

# Latest app regressions: preference journal and loop cleanup run before leaving the app,
# including the runner's EXIT path. Only named appearance rows enter this journal.
pageprefs_begin() { pageprefs_component=$1; pageprefs_saved=; pageprefs_active=1; }
pageprefs_remember() {
	local value tab
	tab=$(printf '\t')
	app_focus "$1" "$2" || return 1
	value=$(app_value "$1" "$2" "$3")
	[ -n "$value" ] || { bad "$cur: cannot save $2"; return 1; }
	pageprefs_saved="${pageprefs_saved}${pageprefs_saved:+
}$2${tab}$3${tab}$value"
}
pageprefs_set() {
	local snap=$1 label=$2 choices=$3 wanted=$4 i now count
	count=$(echo "$choices" | awk -F '|' '{print NF}')
	for i in $(seq 1 "$count"); do
		app_focus "$snap" "$label" || return 1
		now=$(app_value "$snap" "$label" "$choices")
		[ "$now" = "$wanted" ] && return 0
		[ -n "$now" ] || return 1
		app_tap "$snap" "$label" || return 1
		sleep 0.4
	done
	ui "$snap"
	[ "$(app_value "$snap" "$label" "$choices")" = "$wanted" ]
}
pageprefs_end() {
	[ "${pageprefs_active:-0}" = 1 ] || return 0
	local label choices value result=0 tab
	tab=$(printf '\t')
	# Each component is an offline settings page, never an action-bearing app home.
	case "$pageprefs_component" in
		cat.kenny.r1.phone/.PhoneSettings) sh "am start -W -f 0x10008000 -n $pageprefs_component >/dev/null" ;;
		cat.kenny.r1.usbemu/.UsbActivity) : ;; # Settings is in this activity; keep its Back stack.
		*) sh "am start -W -n $pageprefs_component >/dev/null" ;;
	esac
	sleep 0.5
	while IFS="$tab" read -r label choices value; do
		[ -n "$label" ] || continue
		# adb shell otherwise consumes the following journal rows from this here-document.
		if pageprefs_set pageprefs-restore "$label" "$choices" "$value" </dev/null; then
			ok "$cur: restored original $label ($value)"
		else bad "$cur: could not restore original $label ($value)"; result=1; fi
	done <<EOF_PREFS
$pageprefs_saved
EOF_PREFS
	[ "$result" = 0 ] && { pageprefs_active=0; pageprefs_saved=; }
	return "$result"
}
app_portrait() {
	if [ "${app_land:-0}" = 0 ]; then
		app_rotation=$(dev 'settings get system user_rotation')
		app_auto=$(dev 'settings get system accelerometer_rotation'); app_land=1
	fi
	turn 0; sleep 0.5
}
pageprefs_fresh() {
	# Clear only the activity task, not a process/service or preferences. This resets scroll.
	# NEW_TASK | CLEAR_TASK; Intent's shell parser has no --activity-new-task option.
	if ! sh "am start -W -f 0x10008000 -n $1 >/dev/null"; then
		bad "$cur: could not freshly open $1"; return 1
	fi
	sleep 0.7
	app_window_ok "$2" || return 1
	ui "$2"
}
page_header_folded() {
	local top root
	root=$(sweep_screen "$1")
	top=$(xnodes "$1" | awk -F '\t' '
		list && $1==depth+1 { split($6,b," "); if(b[3]>b[1] && b[4]>b[2]) {print b[2];exit} }
		$4~/RecyclerView$/ || $5~/id\/(recycler_view|list)$/ {list=1;depth=$1}')
	if echo "$root" | awk -v top="$top" 'NF==4 && top~/^[0-9]+$/ && top<140 && top>=0 && $4-$2>$3-$1 {f=1} END{exit !f}'; then
		ok "$cur: folded portrait header, first row at $top px"
	else bad "$cur: header not folded or no first row ($top; $root)"; fi
}
pageprefs_effect() {
	local label=$1 value=$2 i file
	[ "$pageprefs_component" = cat.kenny.r1.batterydetails/.BatteryDetailsActivity ] || return 0
	case "$label:$value" in 'Temperature in:Fahrenheit'|'Current in:Milliamps'|'24 hour graph:Shown'|'24 hour graph:Hidden') ;; *) return 0 ;; esac
	pageprefs_fresh "$pageprefs_component" battery-page-effect || return 1
	for i in 1 2 3 4 5 6; do
		ui battery-page-effect
		case "$label:$value" in
			'Temperature in:Fahrenheit')
				xnodes battery-page-effect | cut -f7 | grep -Eq '[0-9][.,0-9]*[[:space:]]*°F' && { ok "$cur: Fahrenheit appears in a reading"; return; } ;;
			'Current in:Milliamps')
				xnodes battery-page-effect | cut -f7 | grep -Eq '[0-9][.,0-9]*[[:space:]]*mA' && { ok "$cur: milliamps appear in a reading"; return; } ;;
			'24 hour graph:'*)
				# Header is on screen, so a missing/recycled header cannot pass Hidden.
				if [ -n "$(app_id_text battery-page-effect percent)" ]; then
					file="$SHOTS/smoke-battery-page-effect-activity.txt"
					dev "dumpsys activity $pageprefs_component" > "$file"
					if awk -v shown="$value" '/GraphView\{/ && /:id\/graph/ {
						found=1; visible=substr($2,1,1)=="V"
					} END {exit !(found && (shown=="Shown"?visible:!visible))}' "$file"; then
						ok "$cur: graph $value matches view visibility"; return
					fi
				fi ;;
		esac
		sleep 0.5
	done
	bad "$cur: $label=$value did not change the reading/graph"
}
pageprefs_cycle() {
	local snap=$1 label=$2 choices=$3 first after expected
	pageprefs_remember "$snap" "$label" "$choices" || return 1
	first=${choices%%\|*}
	pageprefs_set "$snap" "$label" "$choices" "$first" || { bad "$cur: cannot establish first $label choice"; return 1; }
	# Re-find by wheel even after a normalization tap. Exactly one press changes the row.
	app_focus "$snap" "$label" || return 1
	side_press; sleep 0.5; ui "$snap"
	after=$(app_value "$snap" "$label" "$choices"); expected=$(app_next "$choices" "$first")
	[ "$after" = "$expected" ] && ok "$cur: one press changes $label ($first -> $after)" || bad "$cur: wrong summary after $label press ($after)"
	pageprefs_effect "$label" "$after"
	app_focus "$snap" "$label" || return 1
	app_tap "$snap" "$label" || return 1
	sleep 0.5; ui "$snap"
	expected=$(app_next "$choices" "$after"); after=$(app_value "$snap" "$label" "$choices")
	[ -n "$expected" ] && [ "$after" = "$expected" ] && ok "$cur: tap changes $label ($after)" || bad "$cur: wrong summary after $label tap ($after)"
	pageprefs_effect "$label" "$after"
	pageprefs_set "$snap" "$label" "$choices" "$first" && ok "$cur: $label returns to first choice ($first)" || bad "$cur: could not return $label to first choice"
}
t_overlaylook() {
	local app_modes app_tuning
	[ "${pageprefs_active:-0}" = 0 ] || { bad "$cur: unresolved preference cleanup"; return; }
	app_begin cat.kenny.r1.perfoverlay/.OverlayActivity || { app_end; return; }
	app_portrait; pageprefs_fresh cat.kenny.r1.perfoverlay/.OverlayActivity overlaylook || { app_end; return; }
	page_header_folded overlaylook
	pageprefs_begin cat.kenny.r1.perfoverlay/.OverlayActivity
	pageprefs_cycle overlaylook 'Text size' 'Small|Medium|Large'
	pageprefs_cycle overlaylook Background 'Dark|Light|None'
	pageprefs_cycle overlaylook Updates 'Every second|Every 2 seconds|Every 5 seconds'
	app_end
}
t_batterypage() {
	local app_modes app_tuning ui_fast=1
	[ "${pageprefs_active:-0}" = 0 ] || { bad "$cur: unresolved preference cleanup"; return; }
	app_begin cat.kenny.r1.batterydetails/.BatteryDetailsActivity || { app_end; return; }
	app_portrait; pageprefs_fresh cat.kenny.r1.batterydetails/.BatteryDetailsActivity batterypage || { app_end; return; }
	page_header_folded batterypage
	pageprefs_begin cat.kenny.r1.batterydetails/.BatteryDetailsActivity
	pageprefs_cycle batterypage 'Readings update' 'Every 2 seconds|Every second|Every 5 seconds'
	pageprefs_cycle batterypage 'Temperature in' 'Celsius|Fahrenheit'
	pageprefs_cycle batterypage 'Current in' 'Amps|Milliamps'
	pageprefs_cycle batterypage '24 hour graph' 'Shown|Hidden'
	app_end
}
t_reportcontents() {
	local app_modes app_tuning
	[ "${pageprefs_active:-0}" = 0 ] || { bad "$cur: unresolved preference cleanup"; return; }
	app_begin cat.kenny.r1.problemreport/.ProblemReportActivity || { app_end; return; }
	app_portrait; pageprefs_fresh cat.kenny.r1.problemreport/.ProblemReportActivity reportcontents || { app_end; return; }
	page_header_folded reportcontents
	pageprefs_begin cat.kenny.r1.problemreport/.ProblemReportActivity
	pageprefs_cycle reportcontents 'List of apps' 'Included|Left out, logs can still name apps'
	# Never activate Save report, GitHub, the note editor or the self-test runner.
	app_end
}
t_smallheaders() {
	local app_modes app_tuning component
	for component in cat.kenny.r1.performance/.PerformanceActivity cat.kenny.r1.radiosleep/.RadioSleepActivity; do
		app_begin "$component" || { app_end; continue; }
		app_portrait; pageprefs_fresh "$component" smallheaders || { app_end; continue; }
		page_header_folded smallheaders
		# Navigation only: no CPU profile, extreme saver, radio timeout or switch is pressed.
		app_end
	done
}
stress_cancel_end() {
	[ "${stress_dialog_active:-0}" = 1 ] || return 0
	# Back dismisses the pending confirmation even if an assertion failed. Never Skip it.
	key 4; stress_dialog_active=0
}
t_stresscancel() {
	local app_modes app_tuning way i found=0
	# Private activity, host/root only. Opening it reads state; enabling OC/tweaks is forbidden.
	host_only || return
	app_begin cat.kenny.r1.performance/.OverclockActivity || { app_end; return; }
	app_portrait
	for way in down up; do
		for i in $(seq 1 24); do
			ui stresscancel
			# This exact unforced link opens the confirmation. The inverse link would
			# change forced mode immediately, and is never activated by this test.
			if sweep_focus_has stresscancel 'Pick speeds without the stress test' \
				&& awk 'BEGIN{RS="<"} / focused="true"/ && / enabled="true"/ {f=1} END{exit !f}' "$SHOTS/smoke-stresscancel.xml"; then found=1; break; fi
			wheel "$way" 1 >/dev/null; sleep 0.2
		done
		[ "$found" = 1 ] && break
	done
	if [ "$found" != 1 ]; then
		echo 'SKIP stresscancel: unforced skip link not reachable/enabled; no CPU setting changed'
		app_end; return
	fi
	stress_dialog_active=1; side_press; sleep 0.5; ui stresscancel-dialog
	if xnodes stresscancel-dialog | cut -f7 | grep -Fxq 'Skip the stress test?'; then
		if xfocus stresscancel-dialog | awk -F '\t' '$1!="out" && $6~/id\/button2$/ && $8=="Cancel" {f=1} END{exit !f}'; then
			ok 'stresscancel: Skip the stress test opens with Cancel focused'
		else bad 'stresscancel: confirmation did not focus Cancel'; fi
		app_tap stresscancel-dialog Cancel; sleep 0.4; ui stresscancel-closed
		if xnodes stresscancel-closed | cut -f7 | grep -Fxq 'Skip the stress test?'; then
			bad 'stresscancel: Cancel did not dismiss confirmation'
		else ok 'stresscancel: Cancel closes without accepting'; stress_dialog_active=0; fi
	else bad 'stresscancel: expected confirmation did not open'; fi
	app_end
}
t_walkielatch() {
	local app_modes app_tuning before after
	# Direct private settings entry needs the host/root runner. Opening WalkieActivity would
	# start its microphone/network service; this regression never launches that activity.
	host_only || return
	[ "$(app_service_state cat.kenny.r1.walkie)" = off ] || { echo 'SKIP walkielatch: preserve an existing or unreadable radio service'; return; }
	[ "${pageprefs_active:-0}" = 0 ] || { bad "$cur: unresolved preference cleanup"; return; }
	toolbox_silent_begin || return
	app_begin cat.kenny.r1.walkie/.WalkieSettingsActivity || { app_end; return; }
	app_focus walkielatch 'Talk sounds' || { app_end; return; }
	side_hold; sleep 0.5
	if focus_win | grep -Fq '.WalkieSettingsActivity'; then bad 'walkielatch: hold did not leave settings'; app_end; return; fi
	ok 'walkielatch: hold leaves settings'
	sh 'am start -W -n cat.kenny.r1.walkie/.WalkieSettingsActivity >/dev/null'; sleep 0.6
	pageprefs_begin cat.kenny.r1.walkie/.WalkieSettingsActivity
	pageprefs_remember walkielatch 'Talk sounds' 'On|Off' || { app_end; return; }
	before=$(app_value walkielatch 'Talk sounds' 'On|Off')
	app_tap walkielatch 'Talk sounds'; sleep 0.4; ui walkielatch
	after=$(app_value walkielatch 'Talk sounds' 'On|Off')
	[ "$after" = "$(app_next 'On|Off' "$before")" ] && ok 'walkielatch: touch changes Talk sounds' || bad 'walkielatch: touch did not change Talk sounds'
	# Own handler clicks the focused view on select-UP, including an UP without DOWN.
	# That can act on the first press; the safe default title deliberately does nothing.
	# No wheel/retry press before the first post-touch press. Settings now defaults
	# to its harmless title; restoring that focus must not switch a preference.
	side_press; sleep 0.5; ui walkielatch
	if xfocus walkielatch | awk -F '\t' '$1=="self" && $5~/TextView$/ && $8=="Walkie-Talkie settings" {title=1} END{exit !title}'; then
		if [ "$(app_value walkielatch 'Talk sounds' 'On|Off')" != "$after" ]; then
			bad 'walkielatch: restoring the harmless title changed Talk sounds'; app_end; return
		fi
		ok 'walkielatch: first post-touch press restores the harmless title without changing Talk sounds'
		# Once the highlight is on a setting, exactly one press must change it.
		# Wheel travel only: no extra select can hide a dropped row press.
		app_focus walkielatch 'Talk sounds' || { app_end; return; }
		side_press; sleep 0.5; ui walkielatch
		if sweep_focus_has walkielatch 'Talk sounds' && [ "$(app_value walkielatch 'Talk sounds' 'On|Off')" = "$before" ]; then
			ok 'walkielatch: one press changes Talk sounds once its highlight is back'
		else bad 'walkielatch: highlighted Talk sounds did not change on one press'; fi
	elif sweep_focus_has walkielatch 'Talk sounds' && [ "$(app_value walkielatch 'Talk sounds' 'On|Off')" = "$before" ]; then
		ok 'walkielatch: one post-touch press restores highlight and changes the focused row once'
	else bad 'walkielatch: one post-touch press did not change the focused row'; fi
	app_end
}
# Use the explicit Add page: it contains kinds, never saved-network switches. Tapping
# its inert title enters touch mode. Page owns select-UP and activates its restored kind
# on the first press; this is an explicit app-handler exception to the generic OS rule.
t_tunnelslatch() {
	local app_modes app_tuning ui_fast=1
	app_begin cat.kenny.r1.tunnels/.HomeActivity || { app_end; return; }
	sh 'am start -W -f 0x10008000 -n cat.kenny.r1.tunnels/.HomeActivity --ez add true >/dev/null'; sleep 0.5
	if ! tunnels_home_page || [ "$(app_id_text tunnels-home title)" != 'Add a network' ]; then
		bad 'tunnelslatch: no explicit Add page'; app_end; return
	fi
	app_focus tunnels-latch WireGuard || { app_end; return; }
	if [ "$(app_id_text tunnels-latch hint)" != 'Press to set up WireGuard' ] \
		|| xnodes tunnels-latch | awk -F '\t' '$5~/id\/switch_hit$/ {f=1} END{exit !f}'; then
		bad 'tunnelslatch: unsafe Add contents'; app_end; return
	fi
	app_tap_id tunnels-latch title || { bad 'tunnelslatch: no inert title touch target'; app_end; return; }
	sleep 0.3
	if ! tunnels_home_page || [ "$(app_id_text tunnels-home title)" != 'Add a network' ] \
		|| [ "$(app_id_text tunnels-home hint)" != 'Press to set up WireGuard' ]; then
		bad 'tunnelslatch: title touch left the identified Add page'; app_end; return
	fi
	[ -z "$(sweep_focus_id tunnels-home)" ] || { bad 'tunnelslatch: title touch did not remove the highlight'; app_end; return; }
	# Page handles an uncanceled select-UP itself, even when the framework used DOWN
	# to restore focus. This first-press activation is an app-handler exception.
	# No intervening wheel step or second press may hide a stale hold latch.
	side_press; sleep 0.5
	if tunnels_page tunnels-latch WireGuard 'Step 1 of 3' && focus_win | grep -Eq 'cat\.kenny\.r1\.tunnels/(cat\.kenny\.r1\.tunnels)?\.WgStartActivity[ }]'; then
		ok 'tunnelslatch: one post-touch press opens unsaved WireGuard setup from Add'
		side_hold; sleep 0.5
	else bad 'tunnelslatch: one post-touch Add-page press did not activate the first kind'; fi
	# Only activities are closed. No profile, switch, Save, consent, or service action is used.
	tunnels_finish
}

synth_loop_desc() { xnodes "$1" | awk -F '\t' '$5~/id\/loop$/ {print $8;exit}'; }
synth_loop_wait() {
	local i
	for i in 1 2 3 4 5 6; do
		ui "$1"
		[ "$(synth_loop_desc "$1")" = "$2" ] && return 0
		sleep 0.25
	done
	bad "$cur: loop expected $2, got $(synth_loop_desc "$1")"; return 1
}
synth_loop_seek() {
	local i label
	# In Play, down passes six pads and the loop action, then Clear while clearable.
	for i in 1 2 3 4 5 6 7 8 9; do
		ui "$1"; label=$(app_id_text "$1" setting)
		[ "$label" = "$2" ] && return 0
		case "$label" in Play\ *|'Record a loop'|'Cancel recording'|'Close the loop'|'Add to the loop'|'Done adding'|'Clear the loop') ;;
			*) bad "$cur: refusing loop wheel outside Play ($label)"; return 1 ;;
		esac
		wheel down 1 >/dev/null; sleep 0.4
	done
	bad "$cur: wheel did not reach $2"; return 1
}
synth_pad_tap() {
	local at dpi bounds
	dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) return 1 ;; esac
	bounds=$(sweep_bounds "$1" pads)
	at=$(echo "$bounds" | awk -v pad="$2" -v dpi="$dpi" 'NF==4 {
		w=$3-$1;h=$4-$2;cols=2*w>=3*h?3:2;rows=int((6+cols-1)/cols);gap=8*dpi/160
		cw=(w-gap*(cols-1))/cols;ch=(h-gap*(rows-1))/rows
		if(pad>=0 && pad<6 && cw>0 && ch>0) print int($1+(pad%cols)*(cw+gap)+cw/2),int($2+int(pad/cols)*(ch+gap)+ch/2)
	}')
	[ -n "$at" ] || { bad "$cur: no safe pad centre"; return 1; }
	sh "input tap $at"; sleep 0.4
}
synth_loop_tap() {
	local snap=$1 part=$2 bounds dpi at
	bounds=$(sweep_bounds "$snap" loop); dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) return 1 ;; esac
	# Portrait LoopView is wide. Use its source's fixed Clear width (56dp), 6dp gap,
	# and the 64dp minimum action width; choose inside that guaranteed action area.
	at=$(echo "$bounds" | awk -v dpi="$dpi" -v part="$part" 'NF==4 {
		d=dpi/160;w=$3-$1;h=$4-$2
		if(w<2.5*h || w<140*d || h<=0) exit
		x=part=="clear"?$3-28*d:$3-56*d-6*d-32*d
		print int(x),int(($2+$4)/2)
	}')
	[ -n "$at" ] || { bad "$cur: cannot locate portrait loop $part"; return 1; }
	sh "input tap $at"; sleep 0.4
}
synth_loop_end() {
	[ "${synth_loop_active:-0}" = 1 ] || return 0
	local i desc
	# Cleanup may be running after a failure, pause or configuration change. Mute is still
	# owned by toolbox_silent_end, which runs only after this hook and leaving the app.
	sh 'am start -W --activity-reorder-to-front -n cat.kenny.r1.synth/.SynthActivity >/dev/null'; sleep 0.4
	# Touch hit rectangles below are portrait; app_end restores the saved orientation.
	turn 0; sleep 0.4; ui synthloop-clean
	desc=$(synth_loop_desc synthloop-clean)
	case "$desc" in
		'Ready, Cancel') synth_loop_tap synthloop-clean action ;;
		'Recording, Play'|'Looping, Add'|'Adding, Done'|'Stopped, Play') synth_loop_tap synthloop-clean clear ;;
		'Loop, Record') : ;;
		*) bad "$cur: no loop state during cleanup" ;;
	esac
	if ! synth_loop_wait synthloop-clean 'Loop, Record'; then
		# Last resort: discard only this app's in-memory engine, preserving preferences.
		sh 'am force-stop cat.kenny.r1.synth'; synth_loop_active=0; return 1
	fi
	for i in 1 2 3 4 5 6 7; do
		ui synthloop-clean
		[ "$(synth_mode synthloop-clean)" = "$synth_loop_mode" ] && break
		app_tap_id synthloop-clean setting; sleep 0.3
	done
	[ "$(synth_mode synthloop-clean)" = "$synth_loop_mode" ] && ok "$cur: restored original synth mode" || bad "$cur: original synth mode not restored"
	key 3; sleep 0.4; synth_loop_active=0
}
# LoopView draws duration on Canvas rather than exporting it as text. As with the existing
# dial-key pixel check, the host checks rendered ink; no OCR/tool install is required.
synth_loop_duration_ink() {
	[ -z "${R1_ONDEVICE:-}" ] || return 77
	python3 - "$@" <<'PYLOOP'
import struct,sys
from pathlib import Path
raw=Path(sys.argv[1]).read_bytes()
if len(raw)<12: raise SystemExit(77)
w,h,fmt=struct.unpack_from('<III',raw);head=len(raw)-w*h*4
if fmt not in (1,2,5) or head not in (12,16) or not (0<w<8192 and 0<h<8192): raise SystemExit(77)
l,t,r,b=map(int,sys.argv[2].split());d=float(sys.argv[3])/160
if not (0<=l<r<=w and 0<=t<b<=h and r-l>=2.5*(b-t)): raise SystemExit(77)
# Remove action/Clear pills, frame, and bottom playback marks. Only the card name and
# duration remain. Name is white; duration is text_dim (#B8B8B8). Reject components
# containing white cores, so anti-aliased edges of "Looping" cannot fake a duration.
r=int(r-(56+6+64+6)*d);t+=int(3*d);b-=int(10*d)
pixels={}
for y in range(t,b):
 for x in range(l+int(10*d),r-int(4*d)):
  off=head+4*(y*w+x);v=tuple(raw[off:off+3]);v=v[::-1] if fmt==5 else v
  if max(v)-min(v)<=4 and min(v)>=110:pixels[(x,y)]=max(v)
components=[]
while pixels:
 p,ink=pixels.popitem();stack=[p];n=1;peak=ink
 while stack:
  x,y=stack.pop()
  for dx in (-1,0,1):
   for dy in (-1,0,1):
    q=(x+dx,y+dy)
    if q in pixels:
     peak=max(peak,pixels.pop(q));n+=1;stack.append(q)
 if n>=max(4,int(4*d*d)) and 160<=peak<=195:components.append(n)
print('duration glyph components:',components)
raise SystemExit(0 if len(components)>=2 and sum(components)>=12*d*d else 1)
PYLOOP
}
synth_loop_landscape() {
	local rotation snap file pads loop hint screen dpi
	for rotation in 1 3; do
		turn "$rotation"; sleep 0.6; snap=synthloop-land-$rotation; ui "$snap"
		file="$SHOTS/smoke-$snap-activity.txt"
		dev 'dumpsys activity cat.kenny.r1.synth/.SynthActivity' > "$file"
		pads=$(sweep_canvas_bounds "$file" pads); loop=$(sweep_canvas_bounds "$file" loop)
		hint=$(sweep_canvas_bounds "$file" hint_row); screen=$(sweep_screen "$snap")
		dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
		case "$dpi" in ''|*[!0-9]*) bad "$cur: no landscape density"; return 1 ;; esac
		if sweep_inside "$pads" "$screen" && sweep_inside "$loop" "$screen" && sweep_inside "$hint" "$screen" \
			&& sweep_clear "$pads" "$loop" && sweep_clear "$pads" "$hint" && sweep_clear "$loop" "$hint" \
			&& awk -v p="$pads" -v v="$loop" -v s="$screen" -v dpi="$dpi" 'BEGIN {
				split(p,a," ");split(v,b," ");split(s,c," ");d=dpi/160;w=a[3]-a[1];h=a[4]-a[2]
				exit !(c[3]-c[1]>c[4]-c[2] && 2*w>=3*h && b[4]-b[2]>b[3]-b[1] && b[1]>=a[3] && (w-16*d)/3>=48*d && (h-8*d)/2>=40*d)
			}'; then ok "$cur: rotation $rotation loop is a side column; three pads across, clear of hint"
		else bad "$cur: landscape loop/pads/hint overlap, clip or wrong columns"; fi
	done
}
t_synthloop() {
	local app_modes app_tuning ui_fast=1 i label rc bounds dpi
	toolbox_silent_begin || return
	app_begin cat.kenny.r1.synth/.SynthActivity || { app_end; return; }
	app_portrait; ui synthloop
	synth_loop_hint=$(app_id_text synthloop hint)
	synth_loop_mode=$(synth_mode synthloop)
	[ -n "$synth_loop_hint" ] && [ -n "$synth_loop_mode" ] || { bad 'synthloop: no initial mode'; app_end; return; }
	synth_loop_active=1
	for i in 1 2 3 4 5 6 7; do
		ui synthloop
		[ "$(synth_mode synthloop)" = Play ] && break
		app_tap_id synthloop setting; sleep 0.3
	done
	if [ "$(synth_mode synthloop)" != Play ]; then bad 'synthloop: could not reach Play'; app_end; return; fi
	# Clear a leftover in-memory take through its UI, keeping every knob/preset unchanged.
	case "$(synth_loop_desc synthloop)" in
		'Ready, Cancel') synth_loop_tap synthloop action ;;
		'Loop, Record') : ;;
		*) synth_loop_tap synthloop clear ;;
	esac
	synth_loop_wait synthloop 'Loop, Record' || { app_end; return; }
	synth_loop_seek synthloop 'Record a loop' || { app_end; return; }
	ok 'synthloop: wheel passes all six pads to the loop action'
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Ready, Cancel' || { app_end; return; }
	synth_pad_tap synthloop 0 || { app_end; return; }
	synth_loop_wait synthloop 'Recording, Play' || { app_end; return; }
	synth_pad_tap synthloop 1 || { app_end; return; }
	synth_loop_seek synthloop 'Close the loop' || { app_end; return; }
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Looping, Add' || { app_end; return; }
	ok 'synthloop: two touch pads record and close to Looping'
	rawshot synthloop-length; shot synthloop-length
	bounds=$(sweep_bounds synthloop loop); dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
	synth_loop_duration_ink "$SHOTS/smoke-synthloop-length.raw" "$bounds" "$dpi"; rc=$?
	case "$rc" in 0) ok 'synthloop: loop length text is rendered on the card' ;; 77) echo 'SKIP synthloop length pixels: host raw RGBA/RGBX/BGRA analysis required' ;; *) bad 'synthloop: no rendered loop length' ;; esac
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Adding, Done' || { app_end; return; }
	synth_pad_tap synthloop 2
	synth_loop_seek synthloop 'Done adding' || { app_end; return; }
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Looping, Add' || { app_end; return; }
	# Leaving pauses an existing take; returning must show Stopped, then Play resumes it.
	key 3; sleep 0.5; sh 'am start -W --activity-reorder-to-front -n cat.kenny.r1.synth/.SynthActivity >/dev/null'; sleep 0.5
	synth_loop_wait synthloop 'Stopped, Play' || { app_end; return; }
	synth_loop_seek synthloop 'Play the loop' || { app_end; return; }
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Looping, Add' || { app_end; return; }
	synth_loop_seek synthloop 'Clear the loop' || { app_end; return; }
	side_press; sleep 0.4
	synth_loop_wait synthloop 'Loop, Record' || { app_end; return; }
	ok 'synthloop: wheel and press Clear returns to Loop'
	# The action is a Canvas hit target; tapping the whole view centre would hit the card.
	synth_loop_tap synthloop action
	synth_loop_wait synthloop 'Ready, Cancel' || { app_end; return; }
	synth_pad_tap synthloop 0
	synth_loop_wait synthloop 'Recording, Play' || { app_end; return; }
	synth_loop_tap synthloop clear
	synth_loop_wait synthloop 'Loop, Record' || { app_end; return; }
	ok 'synthloop: touch action arms; touch Clear discards the take'
	synth_loop_landscape
	app_end
}

t_routerset() {
	local app_modes app_tuning has_code vpn after i
	app_begin cat.kenny.r1.router/.RouterActivity || { app_end; return; }
	ui routerset
	has_code=$(xnodes routerset | awk -F '\t' '$7=="Big code" || $8=="Big code" { n++ } END { print n+0 }')
	case "$(app_id_text routerset own_state)" in
		'Not passed on yet'|'It did not start. Try once more.')
			[ "$has_code" = 0 ] && ok 'routerset: off network has no Big code' || bad 'routerset: Big code is visible while off'
			if xnodes routerset | awk -F '\t' '
				NR==1 { split($6,r," ") }
				$2=="true" && $3=="cat.kenny.r1.router" { split($6,b," ");
					if (b[3]>b[1] && b[4]>b[2] && b[1]>=r[1] && b[2]>=r[2] && b[3]<=r[3] && b[4]<=r[4]) found=1 }
				END { exit !found }'; then ok 'routerset: off network focus is on a visible control'
			else bad 'routerset: off network focus is missing or outside the screen'; fi ;;
		*) echo 'SKIP routerset off-state check: network is on or starting; no hotspot changes made' ;;
	esac
	app_cycle routerset 'Stays on with nobody on it' 'Goes off once nobody is on it for a while|On until you turn it off'
	ui routerset
	if [ "$has_code" -gt 0 ]; then
		if app_focus routerset 'Big code'; then
			side_press; sleep 0.8; app_title routerset-code Done
			key 4; sleep 0.5; ui routerset
			app_tap routerset 'Big code'; sleep 0.8; app_title routerset-code Done
			ui routerset-code; app_tap routerset-code Done; sleep 0.5
		fi
	else echo "SKIP routerset Big code: hotspot is off"; fi
	app_landscape routerset-land
	app_sight routerset-land 'Stays on with nobody on it' 60
	wheel up 8 >/dev/null; sleep 0.5; ui routerset-vpn
	vpn=$(app_id_text routerset-vpn vpn_state)
	case "$vpn" in
		'Through your VPN. Press to go around it.'|'Around your VPN. Press to send it through.')
			if app_focus routerset-vpn "$vpn"; then
				side_press; sleep 0.6; ui routerset-vpn
				after=$(app_id_text routerset-vpn vpn_state)
				[ "$after" = "$(app_next 'Through your VPN. Press to go around it.|Around your VPN. Press to send it through.' "$vpn")" ] \
					&& ok 'routerset: VPN changes by wheel/press' || bad 'routerset: VPN did not switch'
				wheel down 1 >/dev/null; sleep 0.4
				if app_focus routerset-vpn "$after"; then
					side_press; sleep 0.5; ui routerset-vpn
					[ "$(app_id_text routerset-vpn vpn_state)" = "$vpn" ] && ok 'routerset: wheel can switch VPN back' \
						|| bad 'routerset: wheel could not switch VPN back'
				fi
				# Touch the same row, then return to the recorded state even after a failed press.
				after=$(app_id_text routerset-vpn vpn_state)
				app_tap routerset-vpn "$after"; sleep 0.5; ui routerset-vpn
				[ "$(app_id_text routerset-vpn vpn_state)" != "$after" ] && ok 'routerset: VPN changes by touch' || bad 'routerset: VPN touch did not switch'
				for i in 1 2; do
					after=$(app_id_text routerset-vpn vpn_state)
					[ "$after" = "$vpn" ] && break
					app_tap routerset-vpn "$after"; sleep 0.5; ui routerset-vpn
				done
				[ "$(app_id_text routerset-vpn vpn_state)" = "$vpn" ] && ok 'routerset: restored VPN route' || bad 'routerset: VPN route not restored'
			fi ;;
		*) echo 'SKIP routerset reversible VPN focus: needs an active VPN' ;;
	esac
	shot routerset; app_end
}

t_portset() {
	local app_modes app_tuning result
	app_begin cat.kenny.r1.port/.PortSettings || { app_end; return; }
	app_title portset 'Port settings' || { app_end; return; }
	ui portset; result=$(app_id_text portset copy_result)
	if [ -n "$result" ]; then
		if echo "$result" | awk '$0 ~ /^Copy stopped - [0-9]+ of [0-9]+ photos? copied$/ && $4 <= $6 && $6 > 0 { found = 1 } END { exit !found }'; then
			ok "portset: previous cancelled copy reports completed and total counts ($result)"
		else bad "portset: malformed cancelled-copy count ($result)"; fi
	else echo 'SKIP portset previous copy result: no cancelled copy recorded'; fi
	app_cycle portset 'Sheet for a computer' 'On|Off - tap the port note instead'
	app_cycle portset 'Sheet for a USB stick' 'On|Off - the stick is in Files'
	app_cycle portset 'Role when unlocked' 'Power only|Files|Share internet|Webcam' Webcam
	app_cycle portset 'Eject after copying' 'Off - eject it yourself|On'
	app_landscape portset-land
	app_sight portset-land 'Sheet for a computer' 52
	app_sight portset-land 'Eject after copying' 52
	# Automatic role and eject behaviour require real hardware; their preference rows do not.
	echo "SKIP portset automatic roles and photo eject: needs a computer or USB stick"
	echo 'SKIP portset copy-cancel count: needs a USB stick and photos to copy'
	shot portset; app_end
}

t_phoneset() {
	local app_modes app_tuning at before
	app_begin cat.kenny.r1.phone/.PhoneSettings || { app_end; return; }
	app_title phoneset 'Phone settings' || { app_end; return; }
	app_cycle phoneset 'Music player in the shade' "On - the phone's music on the lock screen too|Off - only on this page"
	app_cycle phoneset 'Call card' 'On - a call shows over everything|Off - calls are not shown'
	app_cycle phoneset 'Notifications from before' 'On - in the shade, without a sound|Off - only new ones in the shade'
	app_landscape phoneset-land
	app_sight phoneset-land 'Music player in the shade' 62
	app_sight phoneset-land 'Notifications from before' 62
	sh "am start -W --activity-clear-task -n cat.kenny.r1.phone/.PhoneActivity"; sleep 1
	ui phoneset-notice
	# No clearing of notifications: open by press, close by tap, keeping the phone's data intact.
	at=$(xnodes phoneset-notice | awk -F '\t' '$5 ~ /id\/clear$/ { split($6, b, " "); print b[1], b[2]; exit }')
	if [ -z "$at" ]; then
		echo "SKIP phoneset notification expansion: needs a paired phone with a notification"
	else
		if app_focus phoneset-notice 'Clear all'; then
			app_min_target phoneset-notice clear_all
		fi
		before=$(xnodes phoneset-notice | awk -F '\t' '$5 ~ /id\/title$/ { print $7; exit }')
		if app_focus phoneset-notice "$before"; then
			side_press; sleep 0.6; ui phoneset-open
			wheel down 1 >/dev/null; sleep 0.5; ui phoneset-clear
			[ "$(focus_field phoneset-clear 5)" = 'cat.kenny.r1.phone:id/clear' ] \
				&& ok 'phoneset: opening a notice exposes its Clear to the wheel' || bad 'phoneset: Clear is not wheel reachable'
			wheel up 1 >/dev/null; sleep 0.4; ui phoneset-open
			at=$(focus_field phoneset-open 6 | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }')
			[ -n "$at" ] && sh "input tap $at"; sleep 0.5
			wheel down 1 >/dev/null; sleep 0.4; ui phoneset-closed
			[ "$(focus_field phoneset-closed 5)" != 'cat.kenny.r1.phone:id/clear' ] \
				&& ok 'phoneset: touch closes a notice and removes its Clear wheel stop' || bad 'phoneset: touch left the notice open'
		fi
	fi
	shot phoneset; app_end
}

t_webdeskset() {
	local app_modes app_tuning was_on before after expected i
	was_on=$(app_service_state cat.kenny.r1.webdesk)
	# WebDeskActivity.onResume starts the server unconditionally when off. There is currently
	# no settings-only intent: do not launch it to test labels at the cost of starting a server.
	if [ "$was_on" != on ]; then
		echo "SKIP webdeskset: server $was_on; current activity auto-starts it (needs a settings-only entry point for off-state labels)"
		return
	fi
	app_begin cat.kenny.r1.webdesk/.WebDeskActivity || { app_end; return; }
	app_cycle webdeskset 'Keep the screen on' 'checked=false|checked=true'
	app_focus webdeskset 'Turn off when unused' || { app_end; return; }
	before=$(app_value webdeskset 'Turn off when unused' '1 hour|3 hours|15 min|30 min')
	if [ -z "$before" ]; then bad 'webdeskset: unknown idle timeout'; app_end; return; fi
	# From any saved value, four successive presses traverse 60 -> 180 -> 15 -> 30 minutes
	# cyclically and return to the original. Read service state after every transition.
	after=$before
	for i in 1 2 3 4; do
		expected=$(app_next '1 hour|3 hours|15 min|30 min' "$after")
		app_focus webdeskset 'Turn off when unused' || break
		side_press; sleep 0.5; ui webdeskset
		after=$(app_value webdeskset 'Turn off when unused' '1 hour|3 hours|15 min|30 min')
		[ "$after" = "$expected" ] && ok "webdeskset: idle timeout cycles to $expected" || bad "webdeskset: idle timeout $after, expected $expected"
		[ "$(app_service_state cat.kenny.r1.webdesk)" = "$was_on" ] \
			&& ok 'webdeskset: idle timeout leaves service state unchanged' || bad 'webdeskset: idle timeout changed service state'
	done
	for i in 1 2 3 4; do
		[ "$after" = "$before" ] && break
		app_tap webdeskset 'Turn off when unused'; sleep 0.4; ui webdeskset
		after=$(app_value webdeskset 'Turn off when unused' '1 hour|3 hours|15 min|30 min')
	done
	[ "$after" = "$before" ] && ok 'webdeskset: restored idle timeout' || bad 'webdeskset: idle timeout was not restored'
	app_landscape webdeskset-land
	app_sight webdeskset-land 'Keep the screen on' 60
	app_sight webdeskset-land 'Turn off when unused' 60
	shot webdeskset
	echo 'SKIP webdeskset screen wake and timeout expiry: needs a connected computer or a timed session'
	echo 'SKIP webdeskset DNS-SD address replacement: needs a change between live networks and a DNS-SD client'
	app_end
	[ "$(app_service_state cat.kenny.r1.webdesk)" = "$was_on" ] \
		&& ok 'webdeskset: service state preserved through exit' || bad 'webdeskset: service state changed through exit'
}

t_usbemuset() {
	local app_modes app_tuning point
	# QS preferences opens the hub even if a USB session is already running.
	app_begin cat.kenny.r1.usbemu/.UsbActivity || { app_end; return; }
	sh "am start -W -a android.service.quicksettings.action.QS_TILE_PREFERENCES -n cat.kenny.r1.usbemu/.UsbActivity"; sleep 1
	if ! app_focus usbemuset Settings; then app_end; return; fi
	side_press; sleep 0.8
	app_title usbemuset Settings || { app_end; return; }
	app_cycle usbemuset 'Wheel pointer step' 'Fine - 4 pixels|Normal - 16 pixels|Fast - 48 pixels'
	app_cycle usbemuset 'Touchpad speed' 'Slow|Normal|Fast'
	app_cycle usbemuset 'Reverse scrolling' 'Off - wheel up scrolls the PC up|On - wheel up scrolls the PC down'
	app_cycle usbemuset 'Open drives read-only' 'Off - writable images open writable|On - switch Access to let the PC write'
	app_landscape usbemuset-land
	app_sight usbemuset-land 'Wheel pointer step' 48
	app_sight usbemuset-land 'Open drives read-only' 48
	# Tap just outside the painted back icon, inside its 48dp delegated touch target.
	ui usbemuset-land
	point=$(xnodes usbemuset-land | awk -F '\t' '$8 == "Back" { split($6, b, " "); print int((b[1] + b[3]) / 2), b[4] + 1; exit }')
	if [ -n "$point" ]; then
		sh "input tap $point"; sleep 0.6
		if app_title usbemuset-land 'USB Devices'; then ok 'usbemuset: one tap on Back opens the hub'
		else bad 'usbemuset: Back consumed its first tap'; fi
	else bad 'usbemuset: missing Back target'; fi
	shot usbemuset
	echo "SKIP usbemuset HID and drive effects: needs a computer and an active USB session"
	app_end
}

# Second app round: named UI mutations, guarded root checks and EXIT-owned resources.
r2_begin() {
	[ "${r2_backup_active:-0}" = 0 ] && [ "${r2_secure_active:-0}" = 0 ] && [ "${pageprefs_active:-0}" = 0 ] \
		|| { bad "$cur: unresolved second-round cleanup"; return 1; }
	app_begin "$1" || { app_end; return 1; }
	app_portrait
	pageprefs_fresh "$1" "$2" || { app_end; return 1; }
}
r2_activity() {
	local window
	window=$(focus_win)
	case "$window" in *" $app_package/.$1"*|*" $app_package/$app_package.$1"*) return 0;; esac
	return 1
}
r2_focus_id() {
	local way i was now
	for way in down up; do
		was=
		for i in $(seq 1 32); do
			if [ -n "${app_focus_activity:-}" ] && ! r2_activity "$app_focus_activity"; then
				bad "$cur: expected $app_focus_activity before focus travel ($(focus_win))"; return 1
			fi
			app_window_ok "$1" || return 1
			ui "$1"
			if [ -n "${app_focus_activity:-}" ] && ! r2_activity "$app_focus_activity"; then
				bad "$cur: expected $app_focus_activity before focus travel ($(focus_win))"; return 1
			fi
			now=$(focus_field "$1" 5)
			case "$now" in *":id/$2") return 0;; esac
			now="$now $(focus_field "$1" 6) $(focused_text "$1")"
			[ "$now" = "$was" ] && break
			was=$now; wheel "$way" 1 >/dev/null; sleep 0.3
		done
	done
	bad "$cur: no wheel stop on id/$2"; return 1
}
r2_fresh() { pageprefs_fresh "$1" "$2"; }
# Only these three non-system UIDs are backed up. A running user's service is never stopped.
# Force-stop before snapshot and restore avoids SharedPreferences' live cache rewriting it.
r2_backup_begin() {
	local state
	[ "${r2_backup_active:-0}" = 0 ] && [ "${pageprefs_active:-0}" = 0 ] || return 1
	case "$1:$2" in cat.kenny.r1.toolbox:toolbox|cat.kenny.r1.walkie:walkie|cat.kenny.r1.webdesk:desk) ;; *) return 1;; esac
	state=$(app_service_state "$1")
	[ "$state" = off ] || { echo "SKIP $cur: $1 service $state; preserving existing activity/session"; return 1; }
	r2_backup_pkg=$1; r2_backup_path=/data/user/0/$1/shared_prefs/$2.xml
	r2_backup_dir=/data/local/tmp/r1-smoke-round2-$$
	sh "am force-stop $1" || return 1
	# Refuse an in-flight recovery file rather than discard another writer's preferences.
	if ! sh "[ ! -e $r2_backup_path.bak ] && mkdir $r2_backup_dir && if [ -e $r2_backup_path ]; then cp -p $r2_backup_path $r2_backup_dir/prefs && touch $r2_backup_dir/present; else touch $r2_backup_dir/absent; fi"; then
		bad "$cur: cannot snapshot $1 preferences"; return 1
	fi
	r2_backup_active=1
}
r2_resources_end() {
	local name value tab failed_restore=0
	if [ "${r2_backup_active:-0}" = 1 ]; then
		# This is an owned, test-started app; stop audio/network work before volume returns.
		sh "am force-stop $r2_backup_pkg"
		if sh "if [ -f $r2_backup_dir/present ]; then cp -p $r2_backup_dir/prefs $r2_backup_path && rm -f $r2_backup_path.bak && restorecon $r2_backup_path && cmp -s $r2_backup_dir/prefs $r2_backup_path; else rm -f $r2_backup_path $r2_backup_path.bak; fi"; then
			sh "rm -rf $r2_backup_dir"; r2_backup_active=0
			ok "$cur: restored exact saved preferences and stopped $r2_backup_pkg"
		else bad "$cur: preference restore failed; backup kept at $r2_backup_dir"; fi
	fi
	if [ "${r2_secure_active:-0}" = 1 ]; then
		tab=$(printf '\t')
		while IFS="$tab" read -r name value; do
			[ -n "$name" ] || continue
			if [ "$value" = absent ]; then
				sh "content call --uri content://lineagesettings/secure --method DELETE_secure --arg $name" </dev/null >/dev/null
				[ -z "$(lget "$name" </dev/null)" ] || failed_restore=1
			else
				lset "$name" "$value" </dev/null
				[ "$(lget "$name" </dev/null)" = "$value" ] || failed_restore=1
			fi
		done <<EOF_R2_SECURE
$r2_secure_saved
EOF_R2_SECURE
		if [ "$failed_restore" = 0 ]; then r2_secure_active=0; r2_secure_saved=; ok "$cur: restored original Lineage settings"
		else bad "$cur: Lineage settings restoration failed"; fi
	fi
}
r2_after_home() {
	[ "${r2_tour_active:-0}" = 1 ] || return 0
	if [ "$r2_tour_saved" = null ]; then sh 'settings delete secure r1_wheel_tour_done >/dev/null'
	else sh "settings put secure r1_wheel_tour_done $r2_tour_saved"; fi
	[ "$(dev 'settings get secure r1_wheel_tour_done')" = "$r2_tour_saved" ] \
		&& { r2_tour_active=0; ok "$cur: restored tour-shown state"; } || bad "$cur: tour-shown state not restored"
}
r2_secure_remember() {
	local value tab
	value=$(lget "$1"); tab=$(printf '\t'); [ -n "$value" ] || value=absent
	r2_secure_saved="${r2_secure_saved:-}${r2_secure_saved:+
}$1${tab}$value"; r2_secure_active=1
}
r2_permission() {
	dev "dumpsys package $1" | awk '
		/User 0:/ {user=1;next}
		/User [0-9]+:/ {user=0}
		user && $1=="android.permission.RECORD_AUDIO:" && /granted=true/ {yes=1}
		END {exit !yes}'
}
r2_no_danger_focus() {
	case "$(focus_field "$1" 5)" in
		*:id/title|*:id/start|*:id/scan) ;;
		*) bad "$cur: missing, destructive or unexpected first focus ($(focus_field "$1" 5))"; return 1;;
	esac
	ok "$cur: first focus is harmless"
}
r2_pinned_hint() {
	local snap=$1 min=$2 file hint scroll screen row dpi
	ui "$snap"; file="$SHOTS/smoke-$snap-activity.txt"
	dev "dumpsys activity $app_package" > "$file"
	hint=$(sweep_canvas_bounds "$file" hint_row); scroll=$(sweep_canvas_bounds "$file" scroll)
	screen=$(sweep_screen "$snap"); row=$(focus_field "$snap" 6)
	dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad "$cur: no density for pinned hint"; return 1;; esac
	if sweep_inside "$hint" "$screen" && sweep_inside "$scroll" "$screen" \
		&& sweep_inside "$row" "$scroll" && awk -v h="$hint" -v s="$scroll" -v r="$row" -v dpi="$dpi" -v min="$min" 'BEGIN {
			split(h,a," ");split(s,b," ");split(r,c," ");exit !(b[4]<=a[2] && c[4]<=a[2] && c[4]-c[2]>=min*dpi/160)
		}'; then ok "$cur: last row is fully clear of the pinned hint"
	else bad "$cur: hint/last row overlaps or clips ($hint; scroll $scroll; row $row)"; return 1; fi
}

t_walkiemessages() {
	local app_modes app_tuning i channel state
	host_only || return
	state=$(app_service_state cat.kenny.r1.walkie)
	[ "$state" = off ] || { echo "SKIP walkiemessages: existing receiver $state; opening/leaving settings could stop it"; return; }
	toolbox_silent_begin || return
	r2_begin cat.kenny.r1.walkie/.WalkieSettingsActivity walkiemessages || { toolbox_silent_end; return; }
	if [ "$(focus_field walkiemessages 4)" = android.widget.TextView ] && [ "$(focused_text walkiemessages)" = 'Walkie-Talkie settings' ]; then ok 'walkiemessages: settings first focus is the title'
	else bad 'walkiemessages: first focus is not the settings title'; fi
	app_end
	r2_backup_begin cat.kenny.r1.walkie walkie || { toolbox_silent_end; return; }
	# app_end restored the saved media value; silence again before the receiver can start.
	toolbox_silent_begin || { r2_resources_end; return; }
	app_begin cat.kenny.r1.walkie/.WalkieActivity || { app_end; return; }
	app_portrait; r2_fresh cat.kenny.r1.walkie/.WalkieActivity walkiemessages || { app_end; return; }
	app_min_target walkiemessages messages_button
	# Never hold or press a channel: only the explicit Messages line may be pressed.
	for i in $(seq 1 12); do
		ui walkiemessages
		[ "$(app_id_text walkiemessages hint)" = 'Press to see the last messages heard. Hold the side button to talk.' ] && break
		wheel down 1 >/dev/null; sleep .2
	done
	if [ "$(app_id_text walkiemessages hint)" = 'Press to see the last messages heard. Hold the side button to talk.' ]; then
		side_press; sleep .5; ui walkiemessages-list
		if r2_activity MessagesActivity; then
			ok 'walkiemessages: wheel/press Messages opens recent messages'
			app_title walkiemessages-list 'No messages yet. The last five heard stay here until the walkie-talkie is turned off.'
			side_hold; sleep .4
			r2_activity WalkieActivity && ok 'walkiemessages: hold goes back' || bad 'walkiemessages: hold did not go back'
		else bad 'walkiemessages: drum press did not open Messages'; fi
	else bad 'walkiemessages: cannot reach Messages safely on drum'; fi
	# No replay is attempted, even if another peer supplies a message during the test.
	if r2_activity WalkieActivity; then
		ui walkiemessages; app_tap_id walkiemessages messages_button; sleep .4
		r2_activity MessagesActivity && ok 'walkiemessages: 48dp button opens Messages by touch' || bad 'walkiemessages: touch did not open Messages'
		key 4
	fi
	echo 'SKIP walkiemessages replay: needs a second r1 and a received message; no transmission/replay attempted'
	app_end
}

r2_listener_snapshot() {
	local file="$SHOTS/smoke-$1-listeners.txt"
	dev 'cat /proc/net/tcp /proc/net/tcp6' > "$file" || return 1
	[ "$(grep -c local_address "$file")" = 2 ] || return 1
	awk '$4=="0A" {split($2,a,":"); if(a[2]=="0050" || a[2]=="1F90" || a[2]=="1F91") print a[2]}' "$file"
}
# Custom QrView inherits View's accessibility class (android.view.View). Its URL
# description and square visible bounds identify it; the dialog also supplies Done.
r2_qr() {
	xnodes "$1" | awk -F '\t' '
		$8~/^http:\/\/[^ ]+\/$/ {split($6,b," "); if(b[3]>b[1] && b[4]>b[2] && b[3]-b[1]==b[4]-b[2]) qr=1}
		$7=="Done" {done=1}
		END {exit !(qr && done)}'
}
t_webdeskstart() {
	local app_modes app_tuning listeners way i expected_focus build
	host_only || return
	listeners=$(r2_listener_snapshot webdesk-baseline) || { echo 'SKIP webdeskstart: cannot inspect TCP listeners'; return; }
	[ -z "$listeners" ] || { echo 'SKIP webdeskstart: a candidate port is already listening; cannot attribute its owner safely'; return; }
	r2_backup_begin cat.kenny.r1.webdesk desk || return
	app_begin cat.kenny.r1.webdesk/.WebDeskActivity || { app_end; return; }
	app_portrait; r2_fresh cat.kenny.r1.webdesk/.WebDeskActivity webdeskstart || { app_end; return; }
	r2_no_danger_focus webdeskstart
	pageprefs_set webdeskstart 'Ask before starting' 'checked=false|checked=true' checked=true || { bad 'webdeskstart: cannot enable Ask'; app_end; return; }
	# Keep this app owned for EXIT cleanup even while explicitly stopping its server.
	sh 'am force-stop cat.kenny.r1.webdesk'
	r2_fresh cat.kenny.r1.webdesk/.WebDeskActivity webdesk-off || { app_end; return; }
	build=$(dev 'getprop ro.haretic.version')
	expected_focus=start
	# 117 has the confirmed default-title bug; later builds focus the Turn on control (id/start).
	[ "$build" != haretic-20261119-1753-nightly ] || expected_focus=title
	if [ "$(focus_field webdesk-off 5)" = "cat.kenny.r1.webdesk:id/$expected_focus" ] \
		&& [ -n "$(app_id_text webdesk-off off_text)" ] \
		&& [ "$(app_service_state cat.kenny.r1.webdesk)" = off ]; then
		ok "webdeskstart: Ask On opens off with $expected_focus focused ($build)"
	else bad "webdeskstart: Ask On did not wait off on $expected_focus ($build)"; fi
	listeners=$(r2_listener_snapshot webdesk-off)
	if [ "$?" = 0 ] && [ -z "$listeners" ]; then ok 'webdeskstart: Ask On has no TCP listener on 80/8080/8081'
	else bad 'webdeskstart: Ask On is listening or listener inspection failed'; fi
	app_focus webdesk-last 'Ask before starting' && r2_pinned_hint webdesk-last 60
	pageprefs_set webdeskstart 'Ask before starting' 'checked=false|checked=true' checked=false || { bad 'webdeskstart: cannot restore Ask Off'; app_end; return; }
	sh 'am force-stop cat.kenny.r1.webdesk'
	r2_fresh cat.kenny.r1.webdesk/.WebDeskActivity webdesk-on || { app_end; return; }
	for i in 1 2 3 4 5; do
		[ "$(app_service_state cat.kenny.r1.webdesk)" = on ] && break; sleep .3
	done
	listeners=$(r2_listener_snapshot webdesk-on)
	if [ "$?" = 0 ] && [ -n "$listeners" ] && [ "$(app_service_state cat.kenny.r1.webdesk)" = on ]; then ok 'webdeskstart: Ask Off automatically starts a listening server'
	else bad 'webdeskstart: Ask Off failed to start/listen'; fi
	ui webdesk-on; r2_no_danger_focus webdesk-on
	if [ -n "$(sweep_bounds webdesk-on scan)" ]; then
		for way in hold back tap; do
			ui webdesk-on; app_tap_id webdesk-on scan; sleep .4; ui webdesk-qr
			if ! r2_qr webdesk-qr; then bad 'webdeskstart: Code to scan did not show QR view'; break; fi
			case "$way" in hold) side_hold;; back) key 4;; tap) app_tap webdesk-qr Done;; esac
			sleep .4; ui webdesk-closed
			if r2_qr webdesk-closed; then bad "webdeskstart: $way left QR open"; key 4
			else ok "webdeskstart: $way closes the QR view"; fi
		done
	else echo 'SKIP webdeskstart QR: no address on the existing Wi-Fi; no network changes made'; fi
	app_end
	[ "$(app_service_state cat.kenny.r1.webdesk)" = off ] && ok 'webdeskstart: server stopped at exit' || bad 'webdeskstart: server left running'
}

t_routerrefresh() {
	local app_modes app_tuning wifi before after i
	host_only || return
	# Opening Router renames stock AndroidAP_* configurations. Do not do that to a user.
	wifi=$(dev 'dumpsys wifi')
	case "$wifi" in *AndroidAP_*) echo 'SKIP routerrefresh: stock AP name would be changed on open'; return;; esac
	[ -n "$wifi" ] || { echo 'SKIP routerrefresh: cannot read existing hotspot state'; return; }
	r2_begin cat.kenny.r1.router/.RouterActivity routerrefresh || return
	case "$(app_id_text routerrefresh own_state)" in 'Not passed on yet'|'It did not start. Try once more.') ;; *) echo 'SKIP routerrefresh: network on/starting; no network change made'; app_end; return;; esac
	ok 'routerrefresh: opened with network off without pressing Pass it on'
	if app_focus routerrefresh 'Stays on with nobody on it'; then
		before="$(focus_field routerrefresh 5) $(focus_field routerrefresh 6) $(focused_text routerrefresh)"
		for i in 1 2; do
			sleep 2.5; ui routerrefresh
			after="$(focus_field routerrefresh 5) $(focus_field routerrefresh 6) $(focused_text routerrefresh)"
			[ "$after" = "$before" ] && ok "routerrefresh: focus survives refresh $i" || bad "routerrefresh: focus changed at refresh $i"
		done
		r2_pinned_hint routerrefresh 60
	fi
	app_end
}

toolbox_has_lap() {
	xnodes "$1" | awk -F '\t' -v want="Lap $2" '$7==want {found=1} END{exit !found}'
}
toolbox_wait_lap() {
	local snap=$1 wanted=$2 i end ui_fast=1
	end=$(( $(date +%s) + 10 ))
	for i in $(seq 1 21); do
		r2_activity TallyActivity || return 1
		ui "$snap"
		# The newest lap is first and visible in portrait, even if lower laps clip there.
		toolbox_has_lap "$snap" "$wanted" && return 0
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .5
	done
	return 1
}
t_toolboxextra() {
	local app_modes app_tuning i file laps screen hint visible
	host_only || return
	r2_begin cat.kenny.r1.toolbox/.ToolboxSettingsActivity toolboxextra || return
	pageprefs_begin cat.kenny.r1.toolbox/.ToolboxSettingsActivity
	pageprefs_cycle toolboxextra 'Level zero' 'Forgotten when the level closes|Kept until you press it away'
	pageprefs_cycle toolboxextra 'Tuner needle' 'Thin|Large, to read from further away'
	app_end
	# Four laps are real saved tally data. Preserve the whole file exactly, never erase user data.
	r2_backup_begin cat.kenny.r1.toolbox toolbox || return
	app_begin cat.kenny.r1.toolbox/.TallyActivity || { app_end; return; }
	app_portrait; r2_fresh cat.kenny.r1.toolbox/.TallyActivity toolbox-tally || { app_end; return; }
	side_hold; sleep .3
	# An empty tally's hold leaves it; reopen without a hold before adding the four laps.
	if ! r2_activity TallyActivity; then r2_fresh cat.kenny.r1.toolbox/.TallyActivity toolbox-tally || { app_end; return; }; fi
	ui toolbox-tally-empty
	if [ "$(app_id_text toolbox-tally-empty count)" != 0 ] \
		|| xnodes toolbox-tally-empty | awk -F '\t' '$7~/^Lap [0-9]+$/ {found=1} END{exit !found}'; then
		bad 'toolboxextra: hold did not establish an empty tally fixture'; app_end; return
	fi
	# The first turn after a settings tap may only leave touch mode. Prime it before counting laps.
	wheel down 1 >/dev/null; sleep .2
	for i in 1 2 3 4; do
		wheel down 1 >/dev/null; side_press
		# Side presses are dispatched after multi-press detection. Do not group four
		# unacknowledged presses or rotate before the lap exists; never retry a select.
		if toolbox_wait_lap "toolbox-lap-$i" "$i"; then
			ok "toolboxextra: one side press recorded lap $i"
		else
			shot toolbox-lap-missing
			dev 'dumpsys activity cat.kenny.r1.toolbox/.TallyActivity' > "$SHOTS/smoke-toolbox-lap-missing-activity.txt"
			bad "toolboxextra: one side press did not record lap $i; fixture incomplete, no clipping verdict"
			app_end; return
		fi
	done
	app_landscape toolbox-fourlaps; file="$SHOTS/smoke-toolbox-fourlaps-activity.txt"
	dev 'dumpsys activity cat.kenny.r1.toolbox/.TallyActivity' > "$file"
	laps=$(sweep_canvas_bounds "$file" laps); hint=$(sweep_canvas_bounds "$file" hint_row); screen=$(sweep_screen toolbox-fourlaps)
	visible=$(xnodes toolbox-fourlaps | awk -F '\t' '$7~/^Lap [1-4]$/ {n++} END{print n+0}')
	if [ "$visible" = 4 ] \
		&& sweep_inside "$laps" "$screen" && sweep_clear "$laps" "$hint"; then ok 'toolboxextra: all four lap lines fit in landscape above the hint'
	else bad "toolboxextra: four recorded laps not all visible after rotation (visible=$visible; $laps; $screen; $hint)"; fi
	app_end
	if ! r2_permission cat.kenny.r1.toolbox; then echo 'SKIP toolboxextra Sound Level hold: microphone not already granted; no permission request made'; return; fi
	toolbox_silent_begin || return
	app_begin cat.kenny.r1.toolbox/.SoundActivity || { app_end; return; }
	app_portrait; r2_fresh cat.kenny.r1.toolbox/.SoundActivity toolbox-sound || { app_end; return; }
	if [ "$(app_id_text toolbox-sound words)" = 'The microphone is in use by something else.' ]; then
		echo 'SKIP toolboxextra Sound Level hold: microphone busy'; app_end; return
	fi
	app_tap_id toolbox-sound level; sleep .5; ui toolbox-sound
	local held
	held=$(app_id_text toolbox-sound level); sleep 1; ui toolbox-sound-still
	if [ "$(app_id_text toolbox-sound-still hint)" = 'Held. Press to listen again.' ] && [ -n "$held" ] && [ "$(app_id_text toolbox-sound-still level)" = "$held" ]; then
		ok 'toolboxextra: tap holds the Sound Level reading unchanged'
	else bad 'toolboxextra: Sound Level tap did not hold'; fi
	app_end
}

t_portsheets() {
	local app_modes app_tuning role functions sum battery volume tasks pending
	host_only || return
	tasks=$(dev 'dumpsys activity activities')
	case "$tasks" in *'ACTIVITY MANAGER ACTIVITIES'*) ;; *) echo 'SKIP portsheets: cannot establish existing activity/copy state'; return;; esac
	if echo "$tasks" | grep -Eq 'cat\.kenny\.r1\.port/[^ ]*PortActivity'; then
		echo 'SKIP portsheets: existing port sheet may own a photo copy; preserving it'; return
	fi
	r2_begin cat.kenny.r1.port/.PortActivity portsheets || return
	app_title portsheets 'Wheel to pick - press or tap to choose'
	functions=$(dev 'svc usb getFunctions') || { bad 'portsheets: cannot read USB functions'; app_end; return; }
	# UsbManager.usbFunctionsToString(FUNCTION_NONE) is the empty string.
	case "$functions" in *mtp*) role=Files;; *rndis*|*ncm*) role='Share internet';; *uvc*) role=Webcam;; ''|*none*|*adb*|*midi*|*ptp*) role='Power only';; *) role=;; esac
	sum=$(app_id_text portsheets sum)
	case "$sum" in "$role - "*) [ -n "$role" ] && ok 'portsheets: computer summary names the current USB role';; *) bad "portsheets: wrong role in summary ($sum; $functions)";; esac
	battery=$(dev 'dumpsys battery')
	if echo "$battery" | grep -Eq '(AC|USB|Wireless|Dock) powered: true'; then
		case "$sum" in *' - Charging'*|*' - On power, not charging') ok 'portsheets: plugged-in power summary shown';; *) bad "portsheets: plugged-in power summary missing ($sum)";; esac
	else
		case "$sum" in *' - Not charging') ok 'portsheets: unplugged summary does not claim charging';; *) bad "portsheets: charging claim while unplugged ($sum)";; esac
	fi
	app_end
	volume=$(dev 'sm list-volumes public' | awk '$2=="mounted" && $1~/^public:/ {print $1;exit}')
	if [ -z "$volume" ]; then echo 'SKIP portsheets stick guide and Stop copying: needs a mounted USB stick; no copy/eject attempted'; return; fi
	[ "$(app_service_state cat.kenny.r1.port)" = off ] || { echo 'SKIP portsheets stick: existing port service/copy preserved'; return; }
	pending=$(dev 'if [ -f /data/user/0/cat.kenny.r1.port/shared_prefs/port.xml ]; then cat /data/user/0/cat.kenny.r1.port/shared_prefs/port.xml; else echo absent; fi')
	if [ "$?" != 0 ] || echo "$pending" | awk 'BEGIN{RS="<"} /name="copy_total"/ && /value="[1-9][0-9]*"/ {found=1} END{exit !found}'; then
		echo 'SKIP portsheets stick: pending copy result or unreadable state; preserve its acknowledgement'; return
	fi
	app_begin cat.kenny.r1.port/.PortActivity || { app_end; return; }
	if ! sh "am start -W -f 0x10008000 -n cat.kenny.r1.port/.PortActivity --es volume $volume >/dev/null"; then
		bad 'portsheets: stick sheet launch failed'; app_end; return
	fi
	sleep .5; app_window_ok port-stick || { app_end; return; }; ui port-stick
	app_title port-stick 'Open in Files'
	app_title port-stick 'Wheel to pick - press or tap to choose'
	echo 'SKIP portsheets Stop copying: no photo copy started by this navigation-only check'
	app_end
}

t_phonekeys() {
	local app_modes app_tuning id dpi bounds track screen
	host_only || return
	toolbox_silent_begin || return
	r2_begin cat.kenny.r1.phone/.PhoneSettings phonekeys || { toolbox_silent_end; return; }
	pageprefs_begin cat.kenny.r1.phone/.PhoneSettings
	pageprefs_remember phonekeys 'Large music keys' 'Off - small, beside the song|On - under the song, easy to tap' || { app_end; return; }
	pageprefs_set phonekeys 'Large music keys' 'Off - small, beside the song|On - under the song, easy to tap' 'On - under the song, easy to tap' || { bad 'phonekeys: cannot enable large keys'; app_end; return; }
	r2_fresh cat.kenny.r1.phone/.PhoneActivity phonekeys-page || { app_end; return; }
	if [ -n "$(sweep_bounds phonekeys-page big_prev)" ]; then
		dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//'); track=$(sweep_bounds phonekeys-page track); screen=$(sweep_screen phonekeys-page)
		for id in big_prev big_toggle big_next; do
			bounds=$(sweep_bounds phonekeys-page "$id")
			if sweep_inside "$bounds" "$screen" && awk -v a="$bounds" -v t="$track" -v dpi="$dpi" 'BEGIN {split(a,b," ");split(t,c," ");exit !(dpi>0 && b[3]-b[1]>=int(64*dpi/160+0.5) && b[2]>=c[4])}'; then ok "phonekeys: $id is 64dp wide and below song"
			else bad "phonekeys: small/clipped/misplaced $id ($bounds)"; fi
			if awk -v id="$id" 'BEGIN{RS="<"} index($0,"resource-id=\"cat.kenny.r1.phone:id/" id "\"") && / enabled="true"/ {yes=1} END{exit !yes}' "$SHOTS/smoke-phonekeys-page.xml"; then
				r2_focus_id phonekeys-page "$id" && ok "phonekeys: enabled $id is wheel reachable"
			else echo "SKIP phonekeys wheel on $id: connected phone does not expose that music command"; fi
		done
	else
		case "$(app_id_text phonekeys-page state)" in 'Waiting for the iPhone'|Pairing|'Bluetooth is off'|'Connected, without notifications')
			[ "$(focus_field phonekeys-page 5)" = cat.kenny.r1.phone:id/settings ] && ok 'phonekeys: waiting first focus is Settings' || bad 'phonekeys: waiting first focus is not Settings';;
		*) echo 'SKIP phonekeys waiting first focus: companion is off or already connected';; esac
		echo 'SKIP phonekeys rendered/reachable music keys: needs a connected iPhone exposing music commands; no Bluetooth or companion switch changed'
	fi
	r2_fresh cat.kenny.r1.phone/.PhoneSettings phonekeys || { app_end; return; }
	pageprefs_set phonekeys 'Large music keys' 'Off - small, beside the song|On - under the song, easy to tap' 'Off - small, beside the song' && ok 'phonekeys: Off restored before original preference cleanup' || bad 'phonekeys: cannot return keys to Off'
	r2_fresh cat.kenny.r1.phone/.PhoneActivity phonekeys-off || { app_end; return; }
	if [ -n "$(sweep_bounds phonekeys-off track)" ]; then
		if [ -z "$(sweep_bounds phonekeys-off big_prev)" ] && [ -n "$(sweep_bounds phonekeys-off prev)" ]; then ok 'phonekeys: Off restores small keys beside song'
		else bad 'phonekeys: Off left large keys or lost small keys'; fi
	fi
	app_end
}

t_usbshortcuts() {
	local app_modes app_tuning state first
	host_only || return
	state=$(dev 'getprop sys.r1.usbemu.st.session')
	case "$state" in ''|idle) ;; *) echo 'SKIP usbshortcuts: existing USB session preserved; no session navigation'; return;; esac
	r2_begin cat.kenny.r1.usbemu/.UsbActivity usbshortcuts || return
	app_title usbshortcuts 'USB Devices'
	first=$(focused_text usbshortcuts)
	r2_row_is usbshortcuts 'USB drive or CD' && ok 'usbshortcuts: hub first focus is USB drive or CD' || bad "usbshortcuts: wrong hub first focus ($first)"
	app_focus usbshortcuts Settings || { app_end; return; }
	side_press; sleep .4
	pageprefs_begin cat.kenny.r1.usbemu/.UsbActivity
	pageprefs_cycle usbshortcuts 'Shortcut keys for' 'Windows and Linux - Ctrl + C copies|Mac - Cmd + C copies'
	pageprefs_end
	# Keep the system-Back regression visible. Build 117 exits this FragmentActivity
	# instead of routing Back to goBack(); the proposed app fix uses its dispatcher.
	key 4; sleep .4; ui usb-hub
	if ! xnodes usb-hub | cut -f7 | grep -Fxq 'USB Devices'; then
		bad 'APP usbshortcuts: system Back from Settings did not return to USB Devices'
		# Continue the independent hub-hold check only after reopening an identified idle hub.
		r2_fresh cat.kenny.r1.usbemu/.UsbActivity usb-hub || { app_end; return; }
	fi
	if ! xnodes usb-hub | cut -f7 | grep -Fxq 'USB Devices'; then
		bad 'usbshortcuts: no idle hub; refusing hold'; app_end; return
	fi
	app_title usb-hub 'USB Devices'
	side_hold; sleep .4
	if focus_win | grep -Fq 'cat.kenny.r1.usbemu/'; then
		ui usb-hub-hold; shot usb-hub-hold
		bad 'APP usbshortcuts: hub hold stayed in app or clicked a row'
	else ok 'usbshortcuts: hub hold leaves without activating a row'; fi
	[ "$(dev 'getprop sys.r1.usbemu.st.session')" = "$state" ] && ok 'usbshortcuts: session state unchanged' || bad 'usbshortcuts: unexpected USB session'
	echo 'SKIP usbshortcuts sending shortcuts: Shortcuts is session-only; no USB session started'
	app_end
}

r2_row_is() {
	xfocus "$1" | awk -F '\t' -v want="$2" '
		$1=="self" && $5~/(RecyclerView|NestedScrollView|ScrollView|ListView)$/ {container=1}
		$1!="out" && ($8==want || $9==want || index($9,want " - ")==1) {found=1}
		END {exit !(found && !container)}'
}
r2_practice_status() {
	if xnodes "$1" | awk -F '\t' -v text="$2" '
		$7==text {split($6,a," ");found=1}
		$4=="android.widget.ScrollView" {split($6,b," ");scroll=1}
		END {exit !(found && scroll && a[2]>=0 && a[4]>a[2] && a[4]<=b[2])}'; then
		ok "$cur: top status changed to $2"
	else bad "$cur: status missing or no longer above controls ($2)"; return 1; fi
}
r2_wheel_page() {
	app_focus "$1" "$2" || return 1
	side_press; sleep .5; ui "$1"
	r2_activity WheelActivity || { bad "$cur: wheel subpage left WheelActivity"; return 1; }
	# Folded action-bar titles are drawn outside the accessibility hierarchy.
	case "$2" in
		'How the wheel works') app_title "$1" 'Try it' ;;
		Taskbar) app_title "$1" 'Buttons and order' ;;
		'Parts of the system') app_title "$1" 'Columns in the shade and home' ;;
		*) app_title "$1" "$2" ;;
	esac
}
practice_slider_state() {
	awk '$1~/SeekBar\{/ && /:id\/practice_slider/ && length($2)==9 {print substr($3,4,1);exit}' "$@"
}
practice_read_slider() {
	dev 'dumpsys activity cat.kenny.r1.wheel.settings/.PracticeActivity' > "$SHOTS/smoke-practice-release-activity.txt"
	practice_slider_state "$SHOTS/smoke-practice-release-activity.txt"
}
practice_capture_slider() {
	practice_native=$(dev 'dumpsys activity cat.kenny.r1.wheel.settings/.PracticeActivity')
	practice_state=$(printf '%s\n' "$practice_native" | practice_slider_state)
}
practice_save_slider() {
	printf '%s\n' "$practice_native" > "$SHOTS/smoke-practice-release-activity.txt"
}
practice_release_slider() {
	local practice_native practice_state end i
	r2_activity PracticeActivity || return 1
	practice_capture_slider
	# Snapshot writes on the host may outlast the4s timeout. Only a positively
	# released, still-focused slider may establish a fresh grab/release fixture.
	if [ "$practice_state" = . ]; then
		printf '%s\n' "$practice_native" | activity_focus_lines | grep -q ':id/practice_slider' || return 2
		r2_activity PracticeActivity || return 1
		echo 'Practice release fixture: expired grab; verify one fresh grab, then one release'
		side_press
		end=$(( $(date +%s) + 2 ))
		for i in $(seq 1 21); do
			practice_capture_slider
			[ "$practice_state" != P ] || break
			[ "$(date +%s)" -lt "$end" ] || break
			sleep .05
		done
	fi
	[ "$practice_state" = P ] || { practice_save_slider; return 2; }
	end=$(( $(date +%s) + 3 ))
	wheel down 1 >/dev/null
	practice_capture_slider
	[ "$practice_state" = P ] && [ "$(date +%s)" -lt "$end" ] || { practice_save_slider; return 2; }
	# No disk writes or accessibility captures until the one explicit release settles.
	side_press
	for i in $(seq 1 31); do
		practice_capture_slider
		[ "$(date +%s)" -lt "$end" ] || { practice_save_slider; return 1; }
		[ "$practice_state" != . ] || break
		sleep .1
	done
	practice_save_slider
	[ "$practice_state" = . ] || return 1
	for i in 1 2 3 4; do
		wheel down 1 >/dev/null; sleep .1; ui practice
		r2_activity PracticeActivity || return 1
		if xnodes practice | cut -f7 | grep -Eq '^Slider at [0-9]+ of 10$'; then return 1; fi
		[ "$(focus_field practice 5)" != cat.kenny.r1.wheel.settings:id/practice_text ] || return 0
	done
	return 1
}

practice_wait_activity() {
	local i end=$(( $(date +%s) + 6 ))
	for i in $(seq 1 31); do
		r2_activity "$1" && return 0
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .2
	done
	return 1
}
practice_wait_help() {
	practice_wait_activity WheelActivity || return 1
	local i
	for i in 1 2 3; do
		r2_activity WheelActivity || return 1
		ui wheelpractice
		r2_activity WheelActivity || return 1
		xnodes wheelpractice | cut -f7 | grep -Fxq 'Try it' && return 0
		sleep .1
	done
	return 1
}
practice_parent_failure() {
	bad "wheelpractice: $1 did not return to Help ($(focus_win))"
	ui practice-parent-missing; shot practice-parent-missing
	dev 'dumpsys activity activities' > "$SHOTS/smoke-practice-parent-tasks.txt"
	dev 'logcat -d -t 1500 -v threadtime' | grep -Ei 'ActivityTaskManager|InputDispatcher|Gesture|Quickstep|NavigationBar' > "$SHOTS/smoke-practice-parent-events.log"
}
practice_done_bounds() {
	xnodes "$1" | awk -F '\t' '$3=="cat.kenny.r1.wheel.settings" && $4~/TextView$/ && $7=="Done" {print $6;exit}'
}
practice_done_point() {
	local bounds
	bounds=$(practice_done_bounds "$1")
	awk -v b="$bounds" 'BEGIN{split(b,a," ");if(a[3]<=a[1] || a[4]<=a[2])exit 1
		print int((a[1]+a[3])/2),int((a[2]+a[4])/2)}'
}

practice_tap_done() {
	local i bounds last= point stable=0
	for i in 1 2 3 4 5; do
		r2_activity PracticeActivity || return 1
		ui practice-tap-done; bounds=$(practice_done_bounds practice-tap-done)
		if [ -n "$bounds" ] && [ "$bounds" = "$last" ]; then stable=1; break; fi
		last=$bounds; sleep .1
	done
	[ "$stable" = 1 ] || return 1
	dev 'dumpsys activity cat.kenny.r1.wheel.settings/.PracticeActivity' > "$SHOTS/smoke-practice-tap-insets.txt"
	point=$(practice_done_point practice-tap-done "$SHOTS/smoke-practice-tap-insets.txt") || return $?
	[ -n "$point" ] && r2_activity PracticeActivity || return 1
	# Exactly one tap at the real centre. Mandatory gestures do not forbid normal taps.
	printf 'Done centre tap: %s\n' "$point"
	dev 'dumpsys activity activities' > "$SHOTS/smoke-practice-before-tap-tasks.txt"
	sh "input tap $point"
}
practice_focus() { local app_focus_activity=PracticeActivity; app_focus "$@"; }
practice_focus_id() { local app_focus_activity=PracticeActivity; r2_focus_id "$@"; }
t_wheelpractice() {
	local app_modes app_tuning before after release_rc tap_rc ui_fast=1
	r2_begin cat.kenny.r1.wheel.settings/.WheelActivity wheelpractice || return
	sh 'am start -W -S -f 0x10008000 -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null'
	practice_wait_activity WheelActivity || { bad 'wheelpractice: fresh parent did not open'; app_end; return; }
	# The activity is resumed before its first frame: a dump taken then is empty, so wait for
	# the page's list to be on screen
	local tries=0
	ui wheelpractice
	while [ -z "$(sweep_screen wheelpractice)" ] && [ "$tries" -lt 10 ]; do
		tries=$((tries + 1)); sleep .3; ui wheelpractice
	done
	page_header_folded wheelpractice
	r2_wheel_page wheelpractice 'How the wheel works' || { app_end; return; }
	ui wheelpractice
	if ! r2_row_is wheelpractice 'Try it'; then wheel down 1 >/dev/null; sleep .3; ui wheelpractice; fi
	if r2_row_is wheelpractice 'Try it'; then ok 'wheelpractice: Try it is the first help row'
	else bad 'wheelpractice: help first row is not Try it'; fi
	app_focus wheelpractice 'Try it' || { app_end; return; }
	side_press
	practice_wait_activity PracticeActivity || { bad 'wheelpractice: Try it did not open Practice'; app_end; return; }
	dev 'dumpsys activity activities' > "$SHOTS/smoke-practice-parent-chain.txt"
	ui practice
	practice_focus practice Apples || { app_end; return; }
	side_press; sleep .3; ui practice
	r2_practice_status practice 'Picked Apples'
	practice_focus_id practice practice_switch || { app_end; return; }
	side_press; sleep .3; ui practice
	app_title practice 'Switch on'
	practice_focus_id practice practice_slider || { app_end; return; }
	side_press; sleep .3; ui practice
	# Reuse the fresh dump: reconnecting twice can consume the 4s grab timeout.
	if xnodes practice | cut -f7 | grep -Fxq 'Grabbed - turn to set it'; then ok 'wheelpractice: shows Grabbed - turn to set it'
	else bad 'wheelpractice: missing grabbed status'; fi
	wheel down 1 >/dev/null; sleep .3; ui practice
	after=$(xnodes practice | cut -f7 | sed -n '/^Slider at [0-9][0-9]* of 10$/p' | head -1)
	if [ "$(focus_field practice 5)" = cat.kenny.r1.wheel.settings:id/practice_slider ] \
		&& [ -n "$after" ] && [ "$after" != 'Slider at 5 of 10' ]; then
		ok 'wheelpractice: grabbed wheel changes slider value without moving focus'
		r2_practice_status practice "$after"
	else bad 'wheelpractice: grabbed slider did not change its initial value'; fi
	if practice_release_slider; then ok 'wheelpractice: verified grabbed slider releases on one press; bounded wheel traversal reaches text without changing slider'
	else
		release_rc=$?
		if [ "$release_rc" = 2 ]; then bad 'wheelpractice: grab expired before explicit-release check'
		else bad 'wheelpractice: explicit slider release/traversal did not settle'; fi
	fi
	practice_focus_id practice practice_text && ok 'wheelpractice: text field is wheel reachable'
	# Reaching the field is enough: no keyboard typing or app setting is needed.
	practice_focus practice Done || { app_end; return; }
	r2_activity PracticeActivity || { bad 'wheelpractice: Practice left before wheel Done'; app_end; return; }
	side_press
	if practice_wait_help; then ok 'wheelpractice: wheel Done returns to Help'
	else practice_parent_failure 'wheel Done'; app_end; return; fi
	app_focus wheelpractice 'Try it' || { app_end; return; }
	side_press
	practice_wait_activity PracticeActivity || { bad 'wheelpractice: Practice did not reopen for tap Done'; app_end; return; }
	practice_focus practice Done || { app_end; return; }
	if ! practice_tap_done; then
		bad 'wheelpractice: no stable Done centre or Practice window'; app_end; return
	fi
	if practice_wait_help; then ok 'wheelpractice: tap Done returns to Help'
	else practice_parent_failure 'tap Done'; app_end; return; fi
	app_focus wheelpractice 'Try it' || { app_end; return; }
	side_press
	local ready=0 i
	for i in 1 2 3 4 5 6 7 8; do
		if r2_activity PracticeActivity; then ready=1; break; fi
		sleep .2
	done
	if [ "$ready" = 1 ]; then
		side_hold
		# The successful destination is the Help page, not merely any other activity.
		if practice_wait_help; then ok 'wheelpractice: hold returns to Help'
		else ui practice-hold; shot practice-hold; bad 'APP wheelpractice: hold did not return to Help'; fi
	else bad 'wheelpractice: Practice did not reopen for hold'; fi
	app_end
}

# ListPreferences, unlike the app cycle rows, open a single-choice dialog. Match a checked
# radio option, then travel to the exact option; never press the underlying page's switch.
# AbsListView owns focus; its CheckedTextView row is selected, not necessarily focused.
# Never accept the list merely because the desired label is somewhere in its subtree.
r2_dialog_seek() {
	local way i
	for way in down up; do
		for i in 1 2 3 4; do
			app_window_ok "$1" || return 1
			ui "$1"
			if awk -v want="$2" 'BEGIN {RS="<"}
				function attr(key, m) {
					if(!match($0," " key "=\"[^\"]*\""))return ""
					m=substr($0,RSTART+length(key)+3,RLENGTH-length(key)-4);return m
				}
				/^node / && attr("class")~/ListView$/ && attr("focused")=="true" {listFocus=1}
				/^node / && attr("class")~/CheckedTextView$/ && attr("text")==want {
					selected=attr("selected")=="true"; rowFocus=attr("focused")=="true"
				}
				END {exit !(rowFocus || listFocus && selected)}' "$SHOTS/smoke-$1.xml"; then return 0; fi
			wheel "$way" 1 >/dev/null; sleep .2
		done
	done
	bad "$cur: choice list never selected $2"; return 1
}
r2_list_pick() {
	local snap=$1 label=$2 wanted=$3 method=$4 i way at
	app_focus "$snap" "$label" || return 1
	if [ "$method" = touch ]; then app_tap "$snap" "$label"; else side_press; fi
	sleep .3; ui "$snap-dialog"
	if ! xnodes "$snap-dialog" | awk -F '\t' '$4~/CheckedTextView$/ {f=1} END{exit !f}'; then
		bad "$cur: $label did not open a choice dialog"; key 4; return 1
	fi
	if [ "$method" = touch ]; then
		at=$(node_at "$snap-dialog" "$wanted"); [ -n "$at" ] || { key 4; return 1; }
		sh "input tap $at"
	else
		r2_dialog_seek "$snap-dialog" "$wanted" || { key 4; return 1; }
		side_press
	fi
	sleep .3; ui "$snap"
	if xnodes "$snap" | awk -F '\t' '$4~/CheckedTextView$/ {f=1} END{exit !f}'; then bad "$cur: choice dialog did not close"; key 4; return 1; fi
	[ "$(app_value "$snap" "$label" "$wanted")" = "$wanted" ] || { bad "$cur: wrong $label summary after choice"; return 1; }
}
r2_list_check() {
	local label=$2 key=$3 choices=$4 def=$5 method value number i
	r2_secure_remember "$key"
	# Verify all entries by wheel, then all by touch, restoring the requested default after each.
	for method in wheel touch; do
		i=0
		while [ "$i" -lt "$(echo "$choices" | awk -F '|' '{print NF}')" ]; do
			i=$((i+1)); value=$(echo "$choices" | cut -d '|' -f "$i"); number=$((i-1))
			[ "$key" = wheel_button_columns ] && number=$i
			if r2_list_pick "$1" "$label" "$value" "$method"; then
				[ "$(lget "$key")" = "$number" ] && ok "$cur: $method sets $label=$value in Lineage settings" || bad "$cur: $label did not persist $number"
			else return 1; fi
			r2_list_pick "$1" "$label" "$def" touch || return 1
			case "$key" in wheel_taskbar_size|wheel_button_columns) number=1;; *) number=0;; esac
			[ "$(lget "$key")" = "$number" ] && ok "$cur: restored $label=$def and verified Lineage value" || bad "$cur: default $label did not persist"
		done
	done
}
t_wheellook() {
	local app_modes app_tuning
	r2_begin cat.kenny.r1.wheel.settings/.WheelActivity wheellook || return
	r2_wheel_page wheellook Taskbar || { app_end; return; }
	app_focus wheellook Size || { app_end; return; }
	r2_list_check wheellook Size wheel_taskbar_size 'Compact|Normal|Large' Normal
	r2_list_check wheellook Colour wheel_taskbar_color 'Black|Translucent|Theme colour' Black
	r2_list_check wheellook 'Focus ring' wheel_taskbar_ring 'Soft|White|Theme colour' Soft
	app_end
}
t_wheelparts() {
	local app_modes app_tuning
	r2_begin cat.kenny.r1.wheel.settings/.WheelActivity wheelparts || return
	if [ "$(lget wheel_ui)" = 0 ]; then echo 'SKIP wheelparts: wheel UI disabled; its switch is not changed'; app_end; return; fi
	r2_wheel_page wheelparts 'Parts of the system' || { app_end; return; }
	ui wheelparts
	if ! r2_row_is wheelparts 'Columns in the shade and home'; then wheel down 1 >/dev/null; sleep .3; ui wheelparts; fi
	r2_row_is wheelparts 'Columns in the shade and home' && ok 'wheelparts: first focus is Columns list row' || bad 'wheelparts: first focus is not Columns list row'
	r2_list_check wheelparts 'Columns in the shade and home' wheel_button_columns 'One - a list|Two - a grid' 'One - a list'
	app_end
}
t_tourskip() {
	local app_modes app_tuning bounds
	host_only || return
	r2_tour_saved=$(dev 'settings get secure r1_wheel_tour_done')
	case "$r2_tour_saved" in null|0|1) ;; *) echo 'SKIP tourskip: unknown tour-shown state'; return;; esac
	r2_tour_active=1
	r2_begin cat.kenny.r1.wheel.settings/.TourActivity tourskip || { r2_after_home; return; }
	app_title tourskip 'Made for the wheel' || { app_end; return; }
	bounds=$(xnodes tourskip | awk -F '\t' '$7=="Skip" {print $6;exit}')
	rawshot tour-skip-before; wheel up 1 >/dev/null; sleep .3; ui tourskip; rawshot tour-skip-after
	if texts tourskip | grep -Fxq 'Press to leave the tour' && [ -n "$bounds" ]; then
		set -- $bounds
		shots_differ tour-skip-before tour-skip-after "$1" "$2" "$3" "$4" && ok 'tourskip: wheel up draws the Skip ring on a non-exercise step' || bad 'tourskip: no Skip ring change'
	else bad 'tourskip: wheel up did not choose Skip'; fi
	side_press; sleep .4
	if r2_activity TourActivity; then bad 'tourskip: press did not skip'; else ok 'tourskip: one press skips tour'; fi
	app_end
}
wheellatch_state() {
	local label
	for label in 'Extreme battery saver' 'Battery saver' Balanced Performance; do
		printf '%s\n' "$label"; app_row "$1" "$label"
	done
	dev 'cat /data/user_de/0/cat.kenny.r1.performance/shared_prefs/performance.xml 2>/dev/null; settings get global low_power'
}
wheellatch_profiles_page() {
	# The preference fragment may replace the body in PerformanceActivity rather
	# than start AppProfilesActivity. Require the unique subpage, not just its class.
	{ r2_activity AppProfilesActivity || r2_activity PerformanceActivity; } || return 1
	xnodes "$1" | awk -F '\t' '
		$7=="Add an app" {add=1}
		$7=="While one of these apps is in front it uses its own profile. Every other app uses the profile you picked." {intro=1}
		$7=="Extreme battery saver" {main=1}
		END{exit !(add && intro && !main)}'
}
t_wheellatch() {
	local app_modes app_tuning at before after
	r2_begin cat.kenny.r1.performance/.PerformanceActivity wheellatch || return
	app_focus wheellatch 'Per-app profiles' || { app_end; return; }
	# A title touch removes the highlight without any hold or a profile/CPU change.
	at=$(xnodes wheellatch | awk -F '\t' '
		$4~/RecyclerView$/ {split($6,b," "); top=b[2];list=1}
		$7=="Performance" {candidate[++n]=$6}
		END {if(!list)exit; for(i=1;i<=n;i++) {split(candidate[i],b," ");
			if(b[3]>b[1] && b[4]>b[2] && b[2]>=0 && b[4]<=top) {
				print int((b[1]+b[3])/2),int((b[2]+b[4])/2);exit
			}}}')
	if [ -z "$at" ]; then
		# A folded toolbar title is canvas-drawn. Its centre above the list is inert;
		# the back arrow is at the left and every profile row is below this boundary.
		at=$(xnodes wheellatch | awk -F '\t' '$4~/RecyclerView$/ {split($6,b," ");if(b[2]>=80 && b[3]>b[1])print int((b[1]+b[3])/2),int((28+b[2])/2);exit}')
	fi
	[ -n "$at" ] || { bad 'wheellatch: no safe title above the preference list'; app_end; return; }
	before=$(wheellatch_state wheellatch)
	sh "input tap $at"
	sleep .3; ui wheellatch-touch
	# RecyclerView can retain non-clickable input focus in touch mode. Its native focus
	# is not a visible wheel ring; ViewRootImpl suppresses that ring while in touch mode.
	if [ -n "$(sweep_focus_id wheellatch-touch)" ]; then
		dev 'dumpsys activity cat.kenny.r1.performance/.PerformanceActivity' > "$SHOTS/smoke-wheellatch-touch-activity.txt"
		if ! xfocus wheellatch-touch | awk -F '\t' '$1=="self" && $5~/RecyclerView$/ {ok=1} END{exit !ok}' \
			|| ! grep -q 'mInTouchMode=true' "$SHOTS/smoke-wheellatch-touch-activity.txt"; then
			bad 'wheellatch: touch did not enter the expected ring-free state'; app_end; return
		fi
	fi
	# OS rule: the first press after a touch restores the ring and acts on nothing.
	# It may focus Extreme saver, so do not activate that default. Wheel-only travel
	# chooses the harmless subpage before the second physical select; no extra selects.
	side_press; sleep .4; ui wheellatch-first
	after=$(wheellatch_state wheellatch-first)
	if ! r2_activity PerformanceActivity || [ -z "$(xfocus wheellatch-first)" ] \
		|| ! xfocus wheellatch-first | awk -F '\t' '$1=="self" && $5!~/(RecyclerView|ScrollView)$/ {row=1} END{exit !row}' \
		|| [ "$before" != "$after" ]; then
		bad 'wheellatch: first post-touch press opened a page, changed a setting, or failed to restore a row'; app_end; return
	fi
	ok 'wheellatch: first post-touch press restores a row without opening or changing anything'
	app_focus wheellatch-second 'Per-app profiles' || { app_end; return; }
	side_press; sleep .4; ui wheellatch-open
	if wheellatch_profiles_page wheellatch-open; then
		ok 'wheellatch: second press opens the wheel-focused harmless subpage'
	else bad 'wheellatch: second post-touch press did not open the focused harmless subpage'; fi
	key 4; app_end
}

# Tunnels: navigating only. Every press is allowlisted on an identified Home/Add or ZtJoin
# page. Review starts on its read-only card; return by hold, never press Save.
tunnels_page() {
	ui "$1"
	[ "$(app_id_text "$1" title)" = "$2" ] && [ "$(app_id_text "$1" step)" = "$3" ]
}
# Window titles may print an abbreviated or fully qualified activity class.
# Accept only the explicit navigation page, never another tunnels window or VPN consent.
tunnels_activity() {
	local component
	case "$1" in HomeActivity|ZtJoinActivity) ;; *) return 1 ;; esac
	component=$(focus_win | sed -n 's/.* cat\.kenny\.r1\.tunnels\/\([^ }]*\).*/\1/p')
	case "$component" in ".$1"|"cat.kenny.r1.tunnels.$1") return 0 ;; esac
	return 1
}
# The validation note is below the pad. Read the typed ID before scrolling, then reveal
# the note by focus travel only; no press is sent to the name, scanner or any other action.
tunnels_zero_error() {
	local i
	for i in 1 2 3 4; do
		if ! tunnels_page tunnels-error ZeroTier 'Step 1 of 2' || ! tunnels_activity ZtJoinActivity; then return 1; fi
		if xnodes tunnels-error | cut -f7 | grep -Fxq 'This is not a network ID. Check it with whoever runs the network.'; then return 0; fi
		wheel down 1 >/dev/null; sleep 0.2
	done
	return 1
}
tunnels_home_title() {
	[ "$1" = VPN ] && return 0
	[ "$1" = 'Private networks' ] || return 1
	case "$(dev 'getprop ro.haretic.version')" in
		haretic-20261119-2223|haretic-20261119-2223-nightly) return 0 ;;
	esac
	return 1
}
tunnels_home_page() {
	tunnels_activity HomeActivity || return 1
	ui tunnels-home
	[ "$(app_id_text tunnels-home title)" = 'Add a network' ] && return 0
	tunnels_home_title "$(app_id_text tunnels-home title)"
}
# A finish/IME/focus transition can outlast the fixed post-hold delay. Do not send a
# second back to a keypad that failed to leave: wait for an identified parent page first.
tunnels_wait_home() {
	local i
	for i in 1 2 3 4 5 6 7 8; do
		tunnels_home_page && return 0
		sleep 0.3
	done
	return 1
}
tunnels_return_home() {
	local depth
	for depth in 1 2 3; do
		tunnels_wait_home || return 1
		case "$(app_id_text tunnels-home title)" in
			'VPN'|'Private networks') tunnels_home_title "$(app_id_text tunnels-home title)"; return $? ;;
			'Add a network')
				# Older/retained stacks can leave the Add instance beneath the wizard.
				# It is a known parent, so Back closes only that list; never select a row.
				[ "$depth" != 3 ] || return 1
				key 4; sleep 0.3 ;;
			*) return 1 ;;
		esac
	done
	return 1
}
tunnels_seek_add() {
	local way i
	for way in down up; do
		for i in $(seq 1 32); do
			tunnels_home_page || { bad 'tunnels: left Home while seeking Add'; return 1; }
			if [ "$(app_id_text tunnels-home hint)" = 'Press to add a network' ] \
				&& [ "$(sweep_focus_id tunnels-home)" != switch_hit ] \
				&& xfocus tunnels-home | awk -F '\t' '$1!="out" && $8=="Add a network" { f=1 } END { exit !f }'; then return 0; fi
			wheel "$way" 1 >/dev/null; sleep 0.2
		done
	done
	bad 'tunnels: no safe Add a network row'; return 1
}
tunnels_add_list() {
	tunnels_home_page || { bad 'tunnels: expected Home before opening add list'; return 1; }
	if tunnels_home_title "$(app_id_text tunnels-home title)" \
		&& ! app_id_text tunnels-home hint | grep -Eq '^Press to set up (WireGuard|Mullvad|Tailscale|ZeroTier)$'; then
		# Match the action hint as well as the name: a saved network can itself be named
		# Add a network. Searching only moves the wheel; switch stops are never pressed.
		tunnels_seek_add || return 1
		side_press; sleep 0.5
		tunnels_home_page || { bad 'tunnels: Add did not open the add list'; return 1; }
	fi
	# The focused kind's setup hint will also be checked before each tap below.
	app_focus tunnels-add WireGuard || return 1
	[ "$(app_id_text tunnels-add hint)" = 'Press to set up WireGuard' ] \
		|| { bad 'tunnels: expected the network-kind list'; return 1; }
}
tunnels_open_kind() {
	local kind=$1
	tunnels_add_list || return 1
	app_focus tunnels-add "$kind" || return 1
	[ "$(app_id_text tunnels-add hint)" = "Press to set up $kind" ] \
		|| { bad "tunnels: wrong hint before tapping $kind"; return 1; }
	# Header and page class exclude saved-network rows even if one has a kind's name.
	tunnels_home_page || return 1
	app_tap tunnels-add "$kind" || { bad "tunnels: no tap target for $kind"; return 1; }
	sleep 0.5
}
tunnels_press_key() {
	local id=$1 i way was now ways='down up'
	[ "${2:-down}" != up ] || ways='up down'
	case "$id" in hex_[0-9a-f]|key_back|key_next) ;; *) bad "tunnels: refusing key $id"; return 1 ;; esac
	# Each fresh snapshot validates the keypad page and focused id together. Recheck the
	# activity after the dump, including after every wheel step; never press Review's Save.
	for way in $ways; do
		was=
		for i in $(seq 1 32); do
			if ! tunnels_page tunnels-pad ZeroTier 'Step 1 of 2' || ! tunnels_activity ZtJoinActivity; then
				printf 'tunnels guard: window='; focus_win
				printf 'tunnels guard: title=%s step=%s focus=%s\n' "$(app_id_text tunnels-pad title)" "$(app_id_text tunnels-pad step)" "$(sweep_focus_id tunnels-pad)"
				dev 'logcat -d -t 1500 -v threadtime' > "$SHOTS/smoke-tunnels-guard-logcat.txt"
				bad "tunnels: refusing $id outside the ZeroTier keypad"; return 1
			fi
			if [ "$(sweep_focus_id tunnels-pad)" = "$id" ]; then
				side_press; sleep 0.15; return 0
			fi
			# Page.move deliberately does not wrap. Reverse at an unchanged endpoint.
			now="$(focus_field tunnels-pad 5)|$(focus_field tunnels-pad 6)|$(focused_text tunnels-pad)"
			[ "$now" != "$was" ] || break
			was=$now
			wheel "$way" 1 >/dev/null; sleep 0.2
		done
	done
	bad "tunnels: no safe keypad stop on $id"; return 1
}

tunnels_key_visible() {
	local snap=$1 id=$2 visible full
	visible=$(sweep_bounds "$snap" "$id")
	dev 'dumpsys activity cat.kenny.r1.tunnels/.ZtJoinActivity' > "$SHOTS/smoke-$snap-visibility.txt"
	full=$(sweep_canvas_bounds "$SHOTS/smoke-$snap-visibility.txt" "$id")
	if awk -v a="$full" -v b="$visible" 'BEGIN{split(a,x," ");split(b,y," ");exit !(x[4]>x[2]&&x[3]>x[1]&&y[4]-y[2]>=x[4]-x[2]&&y[3]-y[1]>=x[3]-x[1])}'; then
		ok "tunnels: validation keeps $id fully visible (native $full; visible $visible)"
	else shot "$snap-clipped"; bad "APP tunnels: validation clips $id (native $full; visible $visible)"; fi
}

tunnels_finish() {
	# No force-stop, service command, switch, or VPN permission dialog is involved.
	app_end
	sh 'am start -W --activity-clear-top -n cat.kenny.r1.tunnels/.HomeActivity'; sleep 0.5
	if tunnels_return_home; then
		ok 'tunnels: left on VPN home'
	else bad 'tunnels: could not return to VPN home'; fi
}
# Both lines belong to the pinned hint; the action and navigation text must be visible.
tunnels_hint_lines() {
	if [ "$(app_id_text "$1" hint_turn)" = 'Turn to move - hold to go back' ] \
		&& xnodes "$1" | awk -F '\t' '$5~/id\/hint$/ {split($6,a," ");h=1} $5~/id\/hint_turn$/ {split($6,b," ");t=1} END {exit !(h&&t&&a[4]<=b[2]&&b[4]>b[2])}'; then
		ok "$cur: action hint and hint_turn are visible on separate lines"
	else bad "$cur: missing or overlapping second hint line"; fi
}
tunnels_read_card() {
	if [ "$(app_id_text "$1" hint)" = 'Here to read - nothing to press' ] \
		&& awk 'BEGIN{RS="<"} / focused="true"/ && / clickable="false"/ && /class="android.widget.LinearLayout"/ {yes=1} END{exit !yes}' "$SHOTS/smoke-$1.xml"; then
		ok "$cur: initial focus is a read-only status/review card"
	else bad "$cur: initial focus is not the read-only card"; return 1; fi
}
t_tunnelsfirstpress() {
	local app_modes app_tuning ui_fast=1 point before after service
	app_begin cat.kenny.r1.tunnels/.HomeActivity || { app_end; return; }
	sh 'am start -W -f 0x10008000 -n cat.kenny.r1.tunnels/.HomeActivity >/dev/null'; sleep .5
	ui tunnels-saved-home
	# A saved network row has a switch_hit sibling. Tap its left-hand title, never that switch.
	point=$(xnodes tunnels-saved-home | awk -F '\t' '$5~/id\/title$/ {split($6,b," ");x=int((b[1]+b[3])/2);y=int((b[2]+b[4])/2);have=1} $5~/id\/switch_hit$/ && have {split($6,b," ");if(x<b[1]) {print x,y;exit}}')
	if [ -z "$point" ]; then
		echo 'SKIP tunnelsfirstpress: no saved network exists; no network created'
		echo 'SKIP Tailscale options: sign-in required; no sign-in or VPN action attempted'
		app_end; return
	fi
	service=$(app_service_state cat.kenny.r1.tunnels)
	sh "input tap $point"; sleep .5; ui tunnels-network-before
	if ! focus_win | grep -Eq 'cat\.kenny\.r1\.tunnels/(cat\.kenny\.r1\.tunnels)?\.NetworkActivity[ }]'; then
		bad 'tunnelsfirstpress: saved row tap did not open NetworkActivity'; app_end; return
	fi
	# A read-only initial hint guards this press;
	# do not insert a wheel step, which would hide the post-touch first-press bug.
	before=$(xnodes tunnels-network-before | awk -F '\t' '$5~/id\/summary$/ {print $7}')
	# A TAP opens in touch mode with no input focus and the generic hint. The page's
	# focusedByDefault status card is restored by the first side press, without a wheel step.
	# Refuse any already-focused clickable control; it is not the expected touch-mode state.
	if [ -n "$(xfocus tunnels-network-before)" ] && ! tunnels_read_card tunnels-network-before; then
		bad 'tunnelsfirstpress: unexpected existing focus; refusing press'; app_end; return
	fi
	side_press; sleep .5; ui tunnels-network-after
	after=$(xnodes tunnels-network-after | awk -F '\t' '$5~/id\/summary$/ {print $7}')
	if [ -n "$before" ] && [ "$before" = "$after" ] && [ "$service" = "$(app_service_state cat.kenny.r1.tunnels)" ]; then
		ok 'tunnelsfirstpress: one side press after TAP leaves network state text and service unchanged'
	else bad 'tunnelsfirstpress: state text or service changed after first press'; fi
	tunnels_read_card tunnels-network-after
	tunnels_hint_lines tunnels-network-after
	echo 'SKIP Tailscale options: sign-in required; no sign-in or VPN action attempted'
	tunnels_finish
}
t_tunnels() {
	# The keypad rechecks its page before every press. Fresh live snapshots keep those
	# checks bounded without waiting for accessibility idle on each of the many digits.
	local ui_fast=1
	local app_modes app_tuning vpn connectivity kind count row seen='' i
	# Do not run while a VPN is active/starting, or while always-on might start one for us.
	vpn=$(dev 'settings get secure always_on_vpn_app')
	case "$vpn" in ''|null) ;; *) echo 'SKIP tunnels: always-on VPN configured or unreadable'; return ;; esac
	if [ "$(app_service_state cat.kenny.r1.tunnels)" != off ]; then
		echo 'SKIP tunnels: tunnel service present or service state unreadable'; return
	fi
	connectivity=$(dev 'dumpsys connectivity')
	case "$connectivity" in
		*'Permission Denial'*|*'Error'*|*"Can't find"*|'') echo 'SKIP tunnels: cannot verify VPN state'; return ;;
	esac
	if echo "$connectivity" | awk '
		# Only networks that exist: requests that listen for a VPN are always there
		/NetworkAgentInfo\{/ && /Transports:/ { s=$0; sub(/^.*Transports: */,"",s); sub(/ *Capabilities:.*$/,"",s);
			gsub(/[&|,]/," ",s); n=split(s,a," "); for(i=1;i<=n;i++) if(a[i]=="VPN") on=1 }
		END { exit !on }'; then echo 'SKIP tunnels: VPN is active'; return; fi
	if ! echo "$connectivity" | grep -Eq 'NetworkAgentInfo|NetworkFactories|Network Requests'; then
		echo 'SKIP tunnels: unrecognized connectivity dump; no VPN state assurance'; return
	fi
	app_begin cat.kenny.r1.tunnels/.HomeActivity || { app_end; return; }
	# Clear an older wizard stack without killing any app process or service.
	sh 'am start -W --activity-clear-top -n cat.kenny.r1.tunnels/.HomeActivity'; sleep 0.5
	ui tunnels-empty
	if ! xnodes tunnels-empty | cut -f5 | grep -q 'id/switch_hit$'; then
		if [ "$(awk 'BEGIN{RS="<"} /class="android.widget.LinearLayout"/ && / clickable="true"/ {n++} END{print n+0}' "$SHOTS/smoke-tunnels-empty.xml")" = 1 ] \
			&& [ "$(app_id_text tunnels-empty hint)" = 'Press to add a network' ]; then
			ok 'tunnels: empty Home has exactly one Add a network row'
		else bad 'tunnels: empty Home does not expose the one Add row'; fi
		tunnels_hint_lines tunnels-empty
	fi
	tunnels_add_list || { tunnels_finish; return; }
	count=0
	for kind in WireGuard Mullvad Tailscale ZeroTier; do
		app_focus tunnels-add "$kind" || { tunnels_finish; return; }
		row=$(xfocus tunnels-add | awk -F '\t' '$1!="out" && $6 ~ /id\/title$/ { print $8; exit }')
		if [ "$row" != "$kind" ] || [ "$(app_id_text tunnels-add hint)" != "Press to set up $kind" ]; then
			bad "tunnels: wheel focus/hint does not identify $kind"; tunnels_finish; return
		fi
		case "|$seen|" in *"|$row|"*) bad 'tunnels: wheel repeated a kind row'; tunnels_finish; return ;; esac
		seen=${seen:+$seen|}$row; count=$((count+1))
		tunnels_hint_lines tunnels-add
		ok "tunnels: wheel reaches distinct $kind row with its own hint"
	done
	[ "$count" = 4 ] && ok 'tunnels: all four add rows reached by wheel'
	for kind in WireGuard Mullvad Tailscale ZeroTier; do
		tunnels_open_kind "$kind" || { tunnels_finish; return; }
		case "$kind" in WireGuard) count=3 ;; *) count=2 ;; esac
		if ! tunnels_page tunnels-first "$kind" "Step 1 of $count"; then
			bad "tunnels: tap did not open $kind first wizard page"; tunnels_finish; return
		fi
		ok "tunnels: tap opens $kind, Step 1 of $count"
		side_hold; sleep 0.5
		if tunnels_return_home; then ok "tunnels: hold returns from $kind"
		else bad "tunnels: hold did not return from $kind"; tunnels_finish; return; fi
	done
	tunnels_open_kind ZeroTier || { tunnels_finish; return; }
	if ! tunnels_page tunnels-pad ZeroTier 'Step 1 of 2' \
		|| [ "$(app_id_text tunnels-pad number)" != '____ ____ ____ ____' ]; then
		bad 'tunnels: expected an empty ZeroTier keypad'; tunnels_finish; return
	fi
	# Next is visually dimmed rather than enabled=false in current source. Its effective
	# enabling is proved by staying on step 1 at 15 characters, then advancing at 16.
	for i in 1 2 3 4 5 6 7 8; do tunnels_press_key hex_a || { tunnels_finish; return; }; done
	for i in 1 2 3 4 5 6 7; do tunnels_press_key hex_b || { tunnels_finish; return; }; done
	tunnels_press_key key_next || { tunnels_finish; return; }
	if tunnels_page tunnels-pad ZeroTier 'Step 1 of 2' \
		&& [ "$(app_id_text tunnels-pad number)" = 'aaaa aaaa bbbb bbb_' ]; then
		ok 'tunnels: Next cannot advance with 15 hex characters'
		tunnels_key_visible tunnels-pad key_next
	else bad 'tunnels: incomplete ID advanced or keypad input failed'; tunnels_finish; return; fi
	# Next is after the pad. Seek earlier keys upward rather than scrolling into the name
	# and QR rows below it, which hides the ID readout from accessibility's visible dump.
	tunnels_press_key hex_b up || { tunnels_finish; return; }
	ui tunnels-pad
	if [ "$(app_id_text tunnels-pad number)" != 'aaaa aaaa bbbb bbbb' ]; then
		bad 'tunnels: wheel/press did not type 16 hex characters'; tunnels_finish; return
	fi
	tunnels_press_key key_next || { tunnels_finish; return; }
	if tunnels_page tunnels-review ZeroTier 'Step 2 of 2'; then
		ok 'tunnels: 16 hex characters enable Next to review'
		tunnels_read_card tunnels-review
		tunnels_hint_lines tunnels-review
		# Review starts on its read-only card. A hold is the ONLY input sent here.
		side_hold; sleep 0.5
	else bad 'tunnels: complete ID did not enable Next'; tunnels_finish; return; fi
	if ! tunnels_page tunnels-pad ZeroTier 'Step 1 of 2'; then
		bad 'tunnels: hold did not leave review'; tunnels_finish; return
	fi
	for i in $(seq 1 16); do tunnels_press_key key_back up || { tunnels_finish; return; }; done
	ui tunnels-pad
	if [ "$(app_id_text tunnels-pad number)" != '____ ____ ____ ____' ]; then
		bad 'tunnels: keypad erase failed'; tunnels_finish; return
	fi
	for i in $(seq 1 16); do tunnels_press_key hex_0 up || { tunnels_finish; return; }; done
	tunnels_press_key key_next || { tunnels_finish; return; }
	if tunnels_page tunnels-pad ZeroTier 'Step 1 of 2' \
		&& [ "$(app_id_text tunnels-pad number)" = '0000 0000 0000 0000' ] \
		&& tunnels_zero_error; then
		ok 'tunnels: 16 zeros are refused with the network-ID message'
		tunnels_key_visible tunnels-error key_back
	else bad 'tunnels: zero ID was not refused on step 1 with its message'; fi
	# The pad's Back handler clears the unsaved Draft, including the earlier review ID.
	if tunnels_activity ZtJoinActivity \
		&& tunnels_page tunnels-pad ZeroTier 'Step 1 of 2'; then
		side_hold; sleep 0.5
		tunnels_return_home && ok 'tunnels: final hold returns to VPN home' \
			|| bad 'tunnels: final hold did not return home'
	fi
	# Clearing the activity stack is also safe if an unexpected page defeated an assertion.
	tunnels_finish
}

# Sweep regressions: only read focus while on action rows. Each has bounded travel; the
# shared cleanup also runs from the runner's EXIT trap on cancellation, including brightness.
sweep_begin() {
	sweep_modes=$(lget wheel_app_modes); sweep_tuning=$(lget wheel_app_tuning)
	sweep_auto=$(dev "settings get system accelerometer_rotation")
	sweep_rotation=$(dev "settings get system user_rotation")
	sweep_brightness=; sweep_bmode=; sweep_dir=; sweep_dialtab=; sweep_walkie_stop=0; sweep_media_volume=; sweep_wake=0
	sweep_active=1
	lset wheel_app_modes "$1=app"; lset wheel_app_tuning ""
	turn 0; key 3; wheel down 1 >/dev/null; sleep 0.4
	if [ "$1" = com.android.dialer ]; then
		sh "am start -W -S -n com.android.dialer/.main.impl.MainActivity >/dev/null 2>&1"; sleep 2; ui sweep-dialtab
		sweep_dialtab=$(sweep_selected_tab sweep-dialtab)
		if [ -z "$sweep_dialtab" ]; then
			echo "SKIP $cur: cannot save Dialer's remembered tab"; sweep_end; return 1
		fi
		key 3; wheel down 1 >/dev/null; sleep 0.3
	fi
}
sweep_selected_tab() {
	awk 'BEGIN { RS = "<" } / selected="true"/ {
		if ($0 ~ /id\/speed_dial_tab"/) { print 0; exit }
		if ($0 ~ /id\/call_log_tab"/) { print 1; exit }
		if ($0 ~ /id\/contacts_tab"/) { print 2; exit }
		if ($0 ~ /id\/voicemail_tab"/) { print 3; exit }
	}' $SHOTS/smoke-$1.xml
}
sweep_restore() {
	case "$3" in ''|null) sh "settings delete $1 $2 >/dev/null" ;; *) sh "settings put $1 $2 $3" ;; esac
}
sweep_end() {
	[ "${sweep_active:-0}" = 1 ] || return 0
	if [ -n "$sweep_dialtab" ]; then
		sh "am start -W -S -n com.android.dialer/.main.impl.MainActivity -a ACTION_SHOW_TAB --ei EXTRA_SHOW_TAB $sweep_dialtab >/dev/null 2>&1"; sleep 0.4
	fi
	if [ "${sweep_wake:-0}" = 1 ]; then key 224; sh 'wm dismiss-keyguard'; fi
	# Leave the brightness window before restoring so a late slider callback cannot overwrite it.
	key 3; sleep 0.4
	if [ -n "$sweep_brightness" ]; then
		sweep_restore system screen_brightness "$sweep_brightness"
		sweep_restore system screen_brightness_mode "$sweep_bmode"
	fi
	if [ -n "$sweep_dir" ]; then
		sh "rmdir $sweep_dir/a $sweep_dir/b $sweep_dir/c $sweep_dir 2>/dev/null"
	fi
	if [ "${sweep_walkie_stop:-0}" = 1 ]; then sh 'am force-stop cat.kenny.r1.walkie'; fi
	if [ -n "${sweep_media_volume:-}" ]; then
		sh "cmd media_session volume --stream 3 --set $sweep_media_volume"
		dev 'cmd media_session volume --stream 3 --get' | grep -Fq "volume is $sweep_media_volume in range" \
			|| bad "$cur: could not restore original media volume $sweep_media_volume"
	fi
	lset wheel_app_modes "$sweep_modes"; lset wheel_app_tuning "$sweep_tuning"
	local restore_rotation=${sweep_rotation:-0} restore_auto=${sweep_auto:-1}
	[ "$restore_rotation" != null ] || restore_rotation=0
	[ "$restore_auto" != null ] || restore_auto=1
	rotation_back "$restore_rotation" "$restore_auto"
	case "$sweep_rotation" in ''|null) sweep_restore system user_rotation "$sweep_rotation" ;; esac
	case "$sweep_auto" in ''|null) sweep_restore system accelerometer_rotation "$sweep_auto" ;; esac
	sweep_active=0
}
sweep_has() {
	dev "pm path $1" | grep -q '^package:' && return 0
	echo "SKIP $cur: $1 is not installed"; return 1
}
# Pure parser: View.toString() has public V/F/E/... flags, THEN private R/F/S/... flags.
# The second F column means focused, not the F in the public (focusable) flags.
activity_focus_lines() {
	awk '$1 ~ /\{/ && length($2) == 9 && substr($3, 2, 1) == "F" { print }'
}
activity_focus() {
	dev "dumpsys activity $2" > $SHOTS/smoke-$1-activity.txt
	activity_focus_lines < $SHOTS/smoke-$1-activity.txt
}
sweep_focus_id() { focus_field "$1" 5 | sed 's|.*/||'; }
# Find focus by wheel only. No press is ever used to get onto an action row.
sweep_seek_id() {
	local i way
	for way in down up; do
		for i in $(seq 1 "${3:-24}"); do
			ui "$1"
			[ "$(sweep_focus_id "$1")" = "$2" ] && return 0
			wheel "$way" 1 >/dev/null; sleep 0.3
		done
	done
	bad "$cur: wheel never reached $2"; return 1
}
# Text outside file rows: a folder's name already visible in a row is not proof it opened.
sweep_file_chrome() {
	xnodes "$1" | awk -F '\t' '
		row && $1 <= depth { row = 0 }
		$5 ~ /id\/item_root$/ { row = 1; depth = $1 }
		!row { if ($7 != "") print $7; if ($8 != "") print $8 }'
}

t_filesfocus() {
	sweep_has com.android.documentsui || return
	sweep_begin com.android.documentsui
	# A disposable directory of folders only: a press cannot open an arbitrary user's file.
	local dir=/sdcard/Download/r1-smoke-wheel-$$-$(date +%s) f name before after
	if [ "$(dev "mkdir $dir 2>/dev/null && echo made")" != made ]; then
		echo "SKIP $cur: cannot create temporary folders in Download"; sweep_end; return
	fi
	sweep_dir=$dir
	sh "mkdir $dir/a $dir/b $dir/c"
	sh "am start -W -S -n com.android.documentsui/.files.FilesActivity -a android.intent.action.VIEW -d content://com.android.externalstorage.documents/document/primary%3ADownload%2F${dir##*/} -t vnd.android.document/directory >/dev/null 2>&1"
	sleep 3; ui filesfocus
	if ! sweep_file_chrome filesfocus | grep -Fq "${dir##*/}" \
		|| ! xnodes filesfocus | cut -f7 | grep -qx a; then
		echo "SKIP $cur: external-storage directory URI did not show the fixture"; sweep_end; return
	fi
	# Build 122 should focus the first real row as soon as this loaded directory is shown.
	f=$(activity_focus filesfocus-first com.android.documentsui/.files.FilesActivity)
	case "$f" in
		*id/item_root*) ok "Files: loaded directory initially focuses a real row ($f)" ;;
		*) shot filesfocus-first; bad "Files: initial focus is ${f:-missing}, not a real row" ;;
	esac
	# Explicit URI starts at this root, independent of the last directory. Two actual steps.
	wheel down 2 >/dev/null; sleep 0.6
	f=$(activity_focus filesfocus com.android.documentsui/.files.FilesActivity)
	case "$f" in
		*id/item_root*) ok "Files: two steps focus a real item_root ($f)" ;;
		*) shot filesfocus; bad "Files: real focus is ${f:-missing}, not item_root"; sweep_end; return ;;
	esac
	case "$f" in *id/coordinator_layout*) bad 'Files: coordinator focus sink owns real focus'; sweep_end; return ;; esac
	ui filesfocus; before=$(sweep_file_chrome filesfocus)
	side_press; sleep 1.5; ui filesfocus-open; after=$(sweep_file_chrome filesfocus-open)
	name=$(printf '%s\n' "$after" | grep -xE 'a|b|c' | head -1)
	if [ -n "$name" ] && [ "$before" != "$after" ]; then
		ok "Files: side press opens folder $name, now named in toolbar/breadcrumb"
	else shot filesfocus-open; bad 'Files: side press did not put a folder name in toolbar/breadcrumb'; fi
	side_hold; sleep 0.5; sweep_end
}

# View.toString private flags are R/F/S/P/...; the fourth flag is pressed. Compose's
# grab sets its focused AndroidComposeView pressed, and the framework doubles the ring then.
sweep_compose_grab_state() {
	awk '$1 ~ /AndroidComposeView\{/ {
		print substr($3, 4, 1) == "P" ? "grabbed" : "released"; found=1; exit
	} END { if (!found) print "unknown" }'
}

t_brightgrab() {
	sweep_has com.android.settings || return
	sweep_begin com.android.settings
	local original mode before after released next b1 b2 i grabbed_state released_state
	original=$(dev "settings get system screen_brightness")
	mode=$(dev "settings get system screen_brightness_mode")
	case "$original" in ''|*[!0-9]*) echo "SKIP $cur: no numeric screen_brightness"; sweep_end; return ;; esac
	sweep_brightness=$original; sweep_bmode=$mode
	# Manual, safely away from either end. BrightnessSynchronizer mirrors DisplayManager's
	# float brightness to SCREEN_BRIGHTNESS on this tree; there is no float settings key.
	sh "settings put system screen_brightness_mode 0; settings put system screen_brightness 100"
	sh "am start -W -S -a android.settings.DISPLAY_SETTINGS >/dev/null 2>&1"; sleep 2
	if ! app_focus brightgrab 'Brightness level'; then sweep_end; return; fi
	side_press; sleep 1; ui brightgrab-dialog
	if ! xnodes brightgrab-dialog | cut -f3 | grep -qx com.android.systemui; then
		bad 'Brightness level did not open SystemUI dialog'; sweep_end; return
	fi
	# Locate the Compose slider by its resource tag/class, without activating other controls.
	for i in 1 2 3 4 5 6; do
		if xfocus brightgrab-dialog | awk -F '\t' '$1 != "out" && ($6 ~ /(^|[\/:])slider$/ || $5 ~ /SeekBar/) { yes = 1 } END { exit !yes }'; then break; fi
		wheel down 1 >/dev/null; sleep 0.2; ui brightgrab-dialog
	done
	if ! xfocus brightgrab-dialog | awk -F '\t' '$1 != "out" && ($6 ~ /(^|[\/:])slider$/ || $5 ~ /SeekBar/) { yes = 1 } END { exit !yes }'; then
		bad 'Brightness dialog: slider never focused'; sweep_end; return
	fi
	before=$(dev "settings get system screen_brightness")
	# No idle-waiting UI dump while grabbed: auto-release is four seconds. Targeted activity
	# flags and raw screenshots are fast, and verify the second press before that timeout.
	rawshot brightgrab-normal
	side_press; sleep 0.2; wheel down 3 >/dev/null; sleep 0.2
	rawshot brightgrab-grabbed
	grabbed_state=$(activity_focus brightgrab-held com.android.systemui/.settings.brightness.BrightnessDialog | sweep_compose_grab_state)
	side_press; sleep 0.4
	released_state=$(activity_focus brightgrab-release-fast com.android.systemui/.settings.brightness.BrightnessDialog | sweep_compose_grab_state)
	sleep 0.3; rawshot brightgrab-released
	[ "$grabbed_state" = grabbed ] && [ "$released_state" = released ] \
		&& ok 'Brightness: second press clears the host grab flag' \
		|| bad "Brightness: grab/release flags $grabbed_state -> $released_state"
	sleep 0.4
	after=$(dev "settings get system screen_brightness")
	[ "$after" != "$before" ] && ok "Brightness: grabbed wheel changes value ($before -> $after)" \
		|| bad "Brightness: three grabbed steps left value at $before"
	ui brightgrab-letgo; b1=$(focus_field brightgrab-letgo 6)
	if ! focus_win | grep -Fq 'settings.brightness.BrightnessDialog'; then
		bad 'Brightness: side press left the slider dialog (taskbar or another page)'
		sweep_end; return
	fi
	released=$(dev "settings get system screen_brightness")
	wheel down 1 >/dev/null; sleep 0.5; ui brightgrab-next; rawshot brightgrab-next
	next=$(dev "settings get system screen_brightness"); b2=$(focus_field brightgrab-next 6)
	# This dialog can have only one stop (the slider). Its next step need not move focus;
	# it must leave the value alone and keep the dialog, with the normal-width ring.
	[ -n "$b1" ] && [ -n "$b2" ] && [ "$next" = "$released" ] \
		&& focus_win | grep -Fq 'settings.brightness.BrightnessDialog' \
		&& ok "Brightness: released wheel leaves value unchanged ($released); focus $b1 -> $b2" \
		|| bad "Brightness: after let go bounds $b1 -> $b2, value $released -> $next or dialog left"
	sweep_end
	# Automatic brightness may immediately choose a new sensor-driven value when re-enabled.
	if [ "$(dev "settings get system screen_brightness_mode")" != "$mode" ]; then
		bad 'Brightness: original automatic/manual mode did not restore'
	elif [ "$mode" = 1 ]; then
		ok "Brightness: restored original $original and resumed automatic mode"
	elif [ "$(dev "settings get system screen_brightness")" = "$original" ]; then
		ok "Brightness: restored original $original"
	else bad 'Brightness: original value did not restore'; fi
}

t_calcrows() {
	sweep_has com.android.calculator2 || return
	sweep_begin com.android.calculator2
	sh "am start -W -S -n com.android.calculator2/.Calculator >/dev/null 2>&1"; sleep 2
	sweep_seek_id calcrows eq 36 || { sweep_end; return; }
	# Portrait advanced_pad.xml: four columns, including the Deg/Rad toggle in row two.
	local want got
	for want in op_sqrt const_pi op_pow op_fact toggle_mode fun_sin fun_cos fun_tan; do
		wheel down 1 >/dev/null; sleep 0.5; ui calcrows; got=$(sweep_focus_id calcrows)
		[ "$got" = "$want" ] && ok "Calculator: next key $want" \
			|| { shot calcrows-$want; bad "Calculator: expected $want, got ${got:-nothing}"; sweep_end; return; }
	done
	sweep_end
}

# Record only the focused node/descendant labels, not every text elsewhere on the page.
sweep_focus_labels() { xfocus "$1" | awk -F '\t' '$1 != "out" { print $8; print $9 }'; }
sweep_top_bounds() {
	focus_field "$1" 6 | awk 'NF == 4 && $2 >= 0 && $4 <= 120 && $3 > $1 && $4 > $2 { print }'
}
t_composerows() {
	sweep_has com.android.calculator2 || return
	sweep_begin com.android.settings
	# Start Calculator first so Force stop is enabled, but never activate an App info action.
	sh "am start -W -S -n com.android.calculator2/.Calculator >/dev/null 2>&1"; sleep 1
	sh "am start -W -S -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.android.calculator2 >/dev/null 2>&1"; sleep 2
	if ! app_focus composerows Notifications; then sweep_end; return; fi
	local want i labels= top= bounds seen='|' actions=0
	for i in $(seq 1 12); do
		wheel up 1 >/dev/null; sleep 0.4; ui composerows
		labels="$labels
$(sweep_focus_labels composerows)"
		bounds=$(sweep_top_bounds composerows)
		[ -n "$bounds" ] && top="$top
$(sweep_focus_labels composerows)"
	done
	printf '%s\n' "$labels" | grep -ixq 'Force stop' && ok 'App info: up visits Force stop' || bad 'App info: up skipped Force stop'
	# Depending on archiving, Open lives either in the action row or the top bar. This build
	# uses the top-bar launch icon; Disable/Uninstall presence can vary with the system image.
	printf '%s\n' "$labels" | grep -ixqE 'Disable|Uninstall|Enable' && ok 'App info: up visits the other action button' || bad 'App info: up skipped other action buttons'
	printf '%s\n' "$top" | grep -iqE '^(Open|Open app|Launch app)$' && ok 'App info: up reaches top-bar Open app' || bad 'App info: top-bar Open app not reached'
	sh "am start -W -S -a android.settings.MANAGE_ALL_APPLICATIONS_SETTINGS >/dev/null 2>&1"; sleep 3
	ui composerows-apps
	# Like composefirst, opening must focus the first row. Do not wheel down to a different row.
	bounds=$(focus_field composerows-apps 6)
	if ! echo "$bounds" | awk 'NF == 4 && $2 >= 100 { yes = 1 } END { exit !yes }'; then
		bad "All apps: first row not focused ($bounds)"; sweep_end; return
	fi
	seen='|'; actions=0; labels=
	for i in 1 2 3; do
		wheel up 1 >/dev/null; sleep 0.4; ui composerows-apps
		bounds=$(sweep_top_bounds composerows-apps)
		labels="$labels
$(sweep_focus_labels composerows-apps)"
		case "$seen" in *"|$bounds|"*) ;; *) [ -n "$bounds" ] && { actions=$((actions + 1)); seen="$seen$bounds|"; } ;; esac
	done
	[ "$actions" = 3 ] && ok 'All apps: three up steps visit distinct top-bar buttons' || bad "All apps: only $actions distinct top-bar stops ($seen)"
	for want in 'More options' Search 'Back|Navigate up'; do
		printf '%s\n' "$labels" | grep -ixqE "$want" && ok "All apps: reached $want" || bad "All apps: missed $want"
	done
	sweep_end
}

t_updaterrow() {
	sweep_has org.lineageos.updater || return
	sweep_begin org.lineageos.updater
	sh "am start -W -S -n org.lineageos.updater/.UpdatesActivity >/dev/null 2>&1"; sleep 3
	local way i labels want down_seen=0
	# Source header actions are Recent changes and Report issues. Include the initial focus;
	# do not press check-for-updates or any action (the test itself makes no HTTP request).
	for way in up down up; do
		labels=
		for i in $(seq 1 12); do
			ui updaterrow
			labels="$labels
$(sweep_focus_labels updaterrow)"
			wheel "$way" 1 >/dev/null; sleep 0.3
		done
		# First up pass positions at the start; the down and final up are the assertions.
		if [ "$way" = down ] || [ "$down_seen" = 1 ]; then
			for want in 'Recent changes' 'Report issues'; do
				printf '%s\n' "$labels" | grep -ixq "$want" && ok "Updater: $way visits $want" || bad "Updater: $way skipped $want"
			done
		fi
		[ "$way" = down ] && down_seen=1
	done
	sweep_end
}

t_viewerbar() {
	sweep_has org.lineageos.glimpse || return
	sweep_begin org.lineageos.glimpse
	local photo i token seen='|' count=0 labels= back_seen=0
	photo=$(dev "content query --uri content://media/external/images/media --projection _id" | sed -n 's/^Row:.*_id=\([0-9]*\).*$/\1/p' | head -1)
	if [ -z "$photo" ]; then echo "SKIP $cur: Gallery needs at least one readable photo"; sweep_end; return; fi
	# REVIEW is Gallery's editable internal viewer; ACTION_VIEW deliberately hides edit/favorite/trash.
	sh "am start -W -S -n org.lineageos.glimpse/.ViewActivity -a android.provider.action.REVIEW -d content://media/external/images/media/$photo -t image/* --grant-read-uri-permission >/dev/null 2>&1"; sleep 2
	ui viewerbar
	if ! xnodes viewerbar | cut -f5 | grep -q 'id/favoriteButton$'; then
		bad 'Gallery: photo viewer actions did not open'; sweep_end; return
	fi
	# Preserve and count the first focus, too. A repeat before five distinct stops is a failure.
	for i in $(seq 1 12); do
		ui viewerbar; token="$(focus_field viewerbar 5)@$(focus_field viewerbar 6)"
		if [ "$token" = @ ]; then bad 'Gallery: viewer lost focus'; break; fi
		case "$seen" in *"|$token|"*) break ;; esac
		seen="$seen$token|"; count=$((count + 1))
		# This build's toolbar Back icon lacks a content description. Identify its real
		# navigation ImageButton under the toolbar; do not silently waive the stop.
		if xfocus viewerbar | awk -F '\t' '$1 == "self" && $5 ~ /ImageButton$/ { button = 1 } $1 == "out" && $6 ~ /id\/toolbar$/ { toolbar = 1 } END { exit !(button && toolbar) }'; then back_seen=1; fi
		labels="$labels
$(sweep_focus_labels viewerbar)"
		wheel down 1 >/dev/null; sleep 0.5
	done
	[ "$count" -ge 5 ] && ok "Gallery: $count distinct viewer stops before repeat" || bad "Gallery: only $count viewer stops before repeat"
	for want in 'favoriteButton' 'shareButton' 'adjustButton' 'deleteButton'; do
		case "$seen" in *"$want@"*) ok "Gallery: wheel visits $want" ;; *) bad "Gallery: wheel skipped $want" ;; esac
	done
	[ "$back_seen" = 1 ] && ok 'Gallery: reaches toolbar Back button' || bad 'Gallery: skipped toolbar Back button'
	printf '%s\n' "$labels" | grep -ixq 'More options' && ok 'Gallery: reaches overflow' || bad 'Gallery: skipped overflow'
	# No viewer action is pressed, particularly Trash. System Back is safe to verify.
	key 4; sleep 0.6
	if focus_win | grep -Eq 'org[.]lineageos[.]glimpse/.*ViewActivity'; then
		bad 'Gallery: system Back did not leave the viewer'
	else ok 'Gallery: system Back leaves the viewer'; fi
	sweep_end
}

t_calgrid() {
	sweep_has org.lineageos.etar || return
	sweep_begin org.lineageos.etar
	sh "am start -W -S -n org.lineageos.etar/com.android.calendar.AllInOneActivity >/dev/null 2>&1"; sleep 3
	local i bounds cls escaped=0
	# Find the existing day/week hour grid, not a toolbar button. Do not force VIEW=DAY:
	# Etar persists START_VIEW/DETAILED_VIEW on pause, including after an intent override.
	for i in $(seq 1 12); do
		ui calgrid; cls=$(focus_field calgrid 4); bounds=$(focus_field calgrid 6)
		case "$cls:$bounds" in *DayView*|*:'0 90 480 640') break ;; esac
		wheel down 1 >/dev/null; sleep 0.3
	done
	case "$cls:$bounds" in *DayView*|*:'0 90 480 640') ;; *) echo "SKIP $cur: day/week hour grid unavailable (current view/onboarding/calendar setup)"; sweep_end; return ;; esac
	activity_focus calgrid-start org.lineageos.etar/com.android.calendar.AllInOneActivity
	for i in $(seq 1 30); do
		wheel up 1 >/dev/null; sleep 0.2; ui calgrid
		cls=$(focus_field calgrid 4); bounds=$(focus_field calgrid 6)
		echo "Calendar evidence: up $i, $cls [$bounds]"
		activity_focus "calgrid-$i" org.lineageos.etar/com.android.calendar.AllInOneActivity
		# Etar's toolbar itself is a real action: setupToolbar wires it to goToDate().
		# Prove the action opens, then cancel; do not mistake its ViewGroup class for the grid.
		if [ "$(sweep_focus_id calgrid)" = toolbar ]; then
			side_press; sleep 0.6; ui calgrid-date
			if xnodes calgrid-date | cut -f5 | grep -q 'id/mtrl_calendar_frame$'; then
				ok 'Calendar: toolbar opens Go to date; cancel preserves the date'
				escaped=1
			else bad 'Calendar: focused toolbar did not open its date picker'; fi
			key 4; sleep 0.4; break
		fi
		# A real, small action outside the grid, not a vanished focus or its encompassing host.
		if [ "$bounds" != '0 90 480 640' ] && echo "$bounds" | awk 'NF == 4 && $3 > $1 && $4 > $2 && $4 - $2 < 100 { yes = 1 } END { exit !yes }'; then
			case "$cls" in *Button*|*ImageView*) escaped=1; break ;; esac
		fi
	done
	[ "$escaped" = 1 ] && ok "Calendar: leaves day grid for action in $i steps ($bounds)" || bad 'Calendar: trapped in day grid after 30 up steps'
	sweep_end
}

# Explicit ACTION_SHOW_TAB is handled by OldMainActivityPeer, before the remembered tab.
# It opens in wheel mode and never needs a tap or an extra step after first focus is assigned.
sweep_dialer_tab() {
	local tab
	case "$2" in speed_dial_tab) tab=0 ;; call_log_tab) tab=1 ;; *) return 1 ;; esac
	sh "am start -W -S -n com.android.dialer/.main.impl.MainActivity -a ACTION_SHOW_TAB --ei EXTRA_SHOW_TAB $tab >/dev/null 2>&1"; sleep 3
	ui "$1"
}

t_recentsfirst() {
	sweep_has com.android.dialer || return
	sweep_begin com.android.dialer || return
	local calls f
	calls=$(dev "content query --uri content://call_log/calls --projection _id")
	case "$calls" in *'Row:'*) echo "SKIP $cur: call history is not empty"; sweep_end; return ;; *'No result found.'*) ;; *) echo "SKIP $cur: cannot verify empty call history (permission/provider)"; sweep_end; return ;; esac
	sweep_dialer_tab recentsfirst call_log_tab || { sweep_end; return; }
	if ! xnodes recentsfirst | cut -f7 | grep -ixq 'Make a call'; then
		echo "SKIP $cur: no Make a call empty state (permissions/default dialer?)"; sweep_end; return
	fi
	f=$(focused_in recentsfirst)
	case "$f:$(sweep_focus_id recentsfirst)" in
		Button*:empty_list_view_action) ok "Dialer Recents: first focus is Make a call ($f)" ;;
		*) shot recentsfirst; bad "Dialer Recents: first focus ${f:-nothing}, id $(sweep_focus_id recentsfirst), not Make a call" ;;
	esac
	sweep_end
}

t_firststop() {
	sweep_begin com.android.settings
	firststop_pages
	sh "am start -W -S -a android.settings.NETWORK_PROVIDER_SETTINGS >/dev/null 2>&1"; sleep 3
	ui firststop-internet
	local f point
	f=$(focused_in firststop-internet)
	if [ -z "$f" ]; then bad 'Internet: no first focus'
	elif xfocus firststop-internet | awk -F '\t' '$1 != "out" && ($8 == "Use Wi-Fi" || $9 == "Use Wi-Fi" || $6 ~ /id\/(main_switch_bar|switch_bar)$/) { yes = 1 } END { exit !yes }'; then
		bad 'Internet: first focus is the Use Wi-Fi switch bar'
	else ok "Internet: first focus is a harmless row ($f)"; fi
	# Only navigate to a positively identified Connected row. Stay in wheel mode so the
	# details page gets its real initial focus; never press the first stop or the switch.
	point=$(node_at firststop-internet Connected)
	if [ -z "$point" ]; then
		echo "SKIP $cur Wi-Fi details: no connected network's Connected row"; side_hold; sleep 0.4; sweep_end; return
	fi
	app_focus firststop-internet Connected || { sweep_end; return; }
	if xfocus firststop-internet | awk -F '\t' '$1 != "out" && ($8 == "Use Wi-Fi" || $9 == "Use Wi-Fi" || $5 ~ /Switch$/) { unsafe = 1 } END { exit !unsafe }'; then
		bad 'Internet: Connected search landed on switch/container; refusing press'; sweep_end; return
	fi
	side_press; sleep 2
	ui firststop-details
	if ! xnodes firststop-details | cut -f7 | grep -ixq Forget; then
		echo "SKIP $cur Wi-Fi details: connected row did not open a details page with Forget"
	else
		f=$(focused_in firststop-details)
		if [ -z "$f" ]; then bad 'Wi-Fi details: no first focus'
		elif xfocus firststop-details | awk -F '\t' '$1 == "self" && $5 ~ /Button$/ { button = 1 } $1 != "out" && (tolower($8) == "forget" || tolower($9) == "forget") { forget = 1 } END { exit !(button && forget) }'; then
			bad 'Wi-Fi details: first focus is the Forget button'
		else ok "Wi-Fi details: first focus is not Forget ($f)"; fi
	fi
	# Leave with holds, never a press on either risky control.
	side_hold; sleep 0.5; side_hold; sleep 0.4; sweep_end
}

# Host-only pixel analysis; rawshot itself uses $R1SH to fetch screencap from the device.
# Do not require Python, image decoders, or app-private files on the device.
sweep_ring_runs() {
	[ -z "${R1_ONDEVICE:-}" ] || return 77
	python3 - "$@" <<'PYRING'
import collections, struct, sys
from pathlib import Path

def skip(why):
    print('unsupported screenshot: ' + why, file=sys.stderr)
    raise SystemExit(77)

raw = Path(sys.argv[1]).read_bytes()
if len(raw) < 12: skip('short header')
w, h, fmt = struct.unpack_from('<III', raw)
if not (0 < w <= 8192 and 0 < h <= 8192) or fmt not in (1, 2, 5):
    skip('needs RGBA/RGBX/BGRA8888')
head = len(raw) - w*h*4
if head not in (12, 16): skip('unknown stride/header')
l, t, r, b = map(int, sys.argv[2].split())
if not (0 <= l < r <= w and 0 <= t < b <= h and r-l >= 30): skip('invalid key bounds')
colors = []
for word in sys.argv[3].split():
    word = word.lstrip('#')[-6:]
    if len(word) != 6: continue
    try: colors.append(tuple(bytes.fromhex(word)))
    except ValueError: pass
if not colors: skip('no resolved accent colors')

def rgb(x, y):
    p = raw[head + 4*(y*w+x): head + 4*(y*w+x)+3]
    return tuple(p[::-1] if fmt == 5 else p)

def accent(p):
    return any(max(abs(a-b) for a,b in zip(p,c)) <= 22 for c in colors)

# Left quarter only: digit/letter ink near the centre must never count as another ring.
end = l + max(12, (r-l)//4)
counts = []
for y in ((t+b)//2-1, (t+b)//2, (t+b)//2+1):
    flags = [accent(rgb(x,y)) for x in range(l,end)]
    runs = []; start = None
    for i, match in enumerate(flags + [False]):
        if match and start is None: start = i
        if not match and start is not None:
            runs.append(i-start); start = None
    if any(n > max(10, (r-l)//10) for n in runs): skip('accent fill, not isolated strokes')
    counts.append(len(runs))
count, agree = collections.Counter(counts).most_common(1)[0]
if agree < 2: skip('unstable middle scanlines')
print(count)
PYRING
}

t_dialkeyring() {
	if [ -n "${R1_ONDEVICE:-}" ]; then echo "SKIP $cur: host-only accent pixel analysis needs Python; no device Python"; return; fi
	command -v python3 >/dev/null 2>&1 || { echo "SKIP $cur: host has no python3 for raw screenshot"; return; }
	sweep_has com.android.dialer || return
	sweep_begin com.android.dialer || return
	sh "am start -W -S -a android.intent.action.DIAL >/dev/null 2>&1"; sleep 2
	sweep_seek_id dialkeyring one 16 || { sweep_end; return; }
	local bounds palette color result rc
	bounds=$(focus_field dialkeyring 6); palette=
	# Wheel uses primary_dark; the removed own stroke used accent1_600 (light) or _200
	# (night). Resolve all three through overlays, rather than hardcoding tonight's wallpaper.
	for color in system_primary_dark system_accent1_600 system_accent1_200; do
		palette="$palette $(dev "cmd overlay lookup android android:color/$color" | grep -oE '#[0-9a-fA-F]{6,8}' | tail -1)"
	done
	if [ -z "$(echo "$palette" | tr -d ' ')" ]; then echo "SKIP $cur: overlay lookup did not resolve accent palette"; sweep_end; return; fi
	sleep 1; rawshot dialkeyring; shot dialkeyring
	result=$(sweep_ring_runs "$SHOTS/smoke-dialkeyring.raw" "$bounds" "$palette"); rc=$?
	if [ "$rc" = 77 ]; then echo "SKIP $cur: unsupported raw frame/theme (see pixel diagnostic)"
	elif [ "$rc" != 0 ]; then bad 'Dialer: raw screenshot analysis failed'
	elif [ "$result" = 1 ]; then ok 'Dialer: focused key has exactly one accent ring'
	else bad "Dialer: focused key has $result accent runs, expected one"; fi
	sweep_end
}

# Second-sweep regressions. These navigate and measure; no target, pad, switch, or dial key
# is pressed except the verified drawer icon hold and Device name/Cancel dialog controls.
sweep_bounds() { xnodes "$1" | awk -F '\t' -v id="$2" '$5 ~ ("id/" id "$") { print $6; exit }'; }
sweep_screen() { xnodes "$1" | awk -F '\t' 'NR==1 { print $6; exit }'; }
sweep_inside() {
	awk -v a="$1" -v b="$2" -v margin="${3:-0}" 'BEGIN {
		if(split(a,x," ")!=4 || split(b,y," ")!=4) exit 1
		exit !(x[3]>x[1] && x[4]>x[2] && y[3]>y[1] && y[4]>y[2] &&
			x[1]>=y[1]+margin && x[2]>=y[2]+margin && x[3]<=y[3]-margin && x[4]<=y[4]-margin)
	}'
}
sweep_clear() {
	awk -v a="$1" -v b="$2" 'BEGIN {
		if(split(a,x," ")!=4 || split(b,y," ")!=4 || x[3]<=x[1] || x[4]<=x[2] || y[3]<=y[1] || y[4]<=y[2]) exit 1
		exit !(x[3]<=y[1] || y[3]<=x[1] || x[4]<=y[2] || y[4]<=x[2])
	}'
}
sweep_focus_has() {
	xfocus "$1" | awk -F '\t' -v text="$2" '$1!="out" && ($8==text || $9==text || index($9,text " - ")==1) { f=1 } END { exit !f }'
}
sweep_wait_focus() {
	local i
	for i in 1 2 3 4 5 6 7 8; do
		ui "$1"; sweep_focus_has "$1" "$2" && return 0
		sleep 0.3
	done
	bad "$cur: first focus is not $2 ($(focused_text "$1"))"; return 1
}
sweep_popup_focus() { sweep_inside "$(focus_field "$1" 6)" "$(sweep_bounds "$1" popup_container)"; }
# Drawer setup is a transition, not a fixed delay. A Home hotseat tile is never
# an app-row substitute, even though it has the same accessibility TextView class.
sweep_launcher_window() {
	focus_win | grep -Eq ' com\.android\.launcher3/(com\.android\.launcher3\.)?[^ }]+[ }]'
}
sweep_drawer_signature() {
	xnodes "$1" | awk -F '\t' '
		function visible(rect,min, a) {split(rect,a," ");return a[3]-a[1]>=min && a[4]-a[2]>=min && a[1]>=0 && a[2]>=0 && a[3]<=480 && a[4]<=640}
		NR==1 {portrait=$3=="com.android.launcher3" && $6=="0 0 480 640"}
		$3=="com.android.launcher3" && $5~/id\/popup_container$/ && visible($6,1) {popup=1}
		inList && $1<=depth {inList=0}
		$3=="com.android.launcher3" && $5~/id\/apps_list_view$/ && visible($6,120) {inList=1;depth=$1;list=$6}
		inList && $1>depth && $3=="com.android.launcher3" && $5~/id\/icon$/ && $4~/(TextView|BubbleTextView)$/ && visible($6,12) {if(!found){found=1;rect=list}}
		END{if(portrait && found && !popup)print rect}'
}
sweep_home_signature() {
	xnodes "$1" | awk -F '\t' '
		function visible(rect,a){split(rect,a," ");return a[3]>a[1] && a[4]>a[2]}
		NR==1 {portrait=$3=="com.android.launcher3" && $6=="0 0 480 640"}
		$3=="com.android.launcher3" && $5~/id\/workspace$/ && visible($6) {workspace=$6}
		$3=="com.android.launcher3" && $5~/id\/hotseat$/ && visible($6) {hotseat=$6}
		$3=="com.android.launcher3" && $5~/id\/wheel_home_tabs$/ && visible($6) {tabs=$6}
		$3=="com.android.launcher3" && $5~/id\/(apps_list_view|popup_container|overview_panel)$/ && visible($6) {other=1}
		END{if(portrait && workspace!="" && !other)print workspace "|" hotseat "|" tabs}'
}
sweep_wait_drawer_state() {
	local state=$1 snap=$2 i signature last= stable=0 end=$(( $(date +%s) + 8 ))
	for i in $(seq 1 25); do
		if sweep_launcher_window;then
			ui "$snap"
			if sweep_launcher_window;then
				if [ "$state" = home ];then signature=$(sweep_home_signature "$snap")
				else signature=$(sweep_drawer_signature "$snap");fi
			else signature=;fi
		else signature=;fi
		if [ -n "$signature" ] && [ "$signature" = "$last" ];then stable=$((stable+1));else stable=0;fi
		[ "$stable" -lt 1 ] || return 0
		last=$signature
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .2
	done
	return 1
}
sweep_drawer_failure() {
	bad "Drawer: $2 ($(focus_win))"
	ui "$1-failed";shot "$1-failed"
	dev 'dumpsys window' > "$SHOTS/smoke-$1-window.txt"
	dev 'dumpsys activity activities' > "$SHOTS/smoke-$1-tasks.txt"
	dev 'logcat -d -t 1500 -v threadtime' > "$SHOTS/smoke-$1-events.log"
}
sweep_open_drawer() {
	local snap=$1 attempt
	key 224;sh 'wm dismiss-keyguard'
	for attempt in 1 2;do
		if ! sweep_wait_drawer_state home "$snap-home-$attempt";then
			sweep_drawer_failure "$snap" 'portrait Home did not settle before drawer swipe';return 1
		fi
		# Same gesture as t_allapps; y560 is above the hidden-pill swipe region.
		sh 'input swipe 240 560 240 120 300'
		if sweep_wait_drawer_state drawer "$snap-open-$attempt";then return 0;fi
		# Retry a missed transition only on confirmed Home, never toggle/swipe an
		# already open or half-populated drawer or send wheel input to another app.
		if ! sweep_launcher_window || [ -z "$(sweep_home_signature "$snap-open-$attempt")" ];then
			sweep_drawer_failure "$snap" 'drawer did not reach a populated stable state after swipe';return 1
		fi
		[ "$attempt" = 2 ] || echo 'Drawer setup: swipe left Home unchanged; one bounded Home-only retry'
	done
	sweep_drawer_failure "$snap" 'drawer did not open after two verified Home swipes';return 1
}
sweep_drawer_icon() {
	local i
	for i in $(seq 1 24);do
		sweep_launcher_window || { sweep_drawer_failure "$1" 'Launcher lost before drawer focus';return 1; }
		ui "$1"
		if ! sweep_launcher_window || [ -z "$(sweep_drawer_signature "$1")" ];then
			sweep_drawer_failure "$1" 'drawer disappeared before app-icon focus; refusing wheel input on Home';return 1
		fi
		if xfocus "$1" | awk -F '\t' '$1=="out" && $4=="com.android.launcher3" && $6~/id\/apps_list_view$/ {drawer=1}
			$1=="self" && $4=="com.android.launcher3" && $6~/id\/icon$/ && $5~/(TextView|BubbleTextView)$/ {icon=1} END{exit !(drawer && icon)}';then return 0;fi
		wheel down 1 >/dev/null;sleep .3
	done
	sweep_drawer_failure "$1" 'drawer open but no app icon acquired focus';return 1
}

t_drawermenu() {
	local icon first at ui_fast=1
	sweep_has com.android.launcher3 || return
	sweep_begin com.android.launcher3 || return
	# Wait for Home, then for a populated drawer, before any wheel-focus search.
	sweep_open_drawer drawermenu || { sweep_end;return; }
	sweep_drawer_icon drawermenu || { sweep_end; return; }
	icon="$(focus_field drawermenu 6)|$(focused_text drawermenu)"
	side_hold; sleep 0.6; ui drawermenu-popup
	if ! sweep_popup_focus drawermenu-popup; then bad 'Drawer: held icon popup did not take focus'; sweep_end; return; fi
	ok 'Drawer: icon hold opens popup with focus inside it'
	first="$(focus_field drawermenu-popup 5)|$(focus_field drawermenu-popup 6)|$(focused_text drawermenu-popup)"
	wheel down 1 >/dev/null; sleep 0.4; ui drawermenu-popup-next
	if sweep_popup_focus drawermenu-popup-next \
		&& [ "$(focus_field drawermenu-popup-next 5)|$(focus_field drawermenu-popup-next 6)|$(focused_text drawermenu-popup-next)" != "$first" ]; then
		ok 'Drawer: wheel moves to another popup node'
	else bad 'Drawer: wheel stayed on the same node or escaped popup'; fi
	side_hold; sleep 0.5; ui drawermenu-back
	if [ -z "$(sweep_bounds drawermenu-back popup_container)" ] \
		&& [ "$(focus_field drawermenu-back 6)|$(focused_text drawermenu-back)" = "$icon" ] \
		&& xfocus drawermenu-back | awk -F '\t' '$1=="out" && $6 ~ /id\/apps_list_view$/ { f=1 } END { exit !f }'; then
		ok 'Drawer: popup hold returns to the same icon with drawer open'
	else bad 'Drawer: popup hold lost the icon or closed the drawer'; sweep_end; return; fi
	at=$(focus_field drawermenu-back 6 | awk 'NF==4 && $3>$1 && $4>$2 { print int(($1+$3)/2),int(($2+$4)/2) }')
	[ -n "$at" ] || { bad 'Drawer: icon has no touch bounds'; sweep_end; return; }
	sh "input swipe $at $at 900"; sleep 0.5
	wheel down 1 >/dev/null; sleep 0.4; ui drawermenu-touch
	sweep_popup_focus drawermenu-touch && ok 'Drawer: wheel enters touch-opened popup' \
		|| bad 'Drawer: touch-opened popup left focus on the apps behind it'
	key 4; sweep_end
}

# A selected ListView child may own no focus: retain its row's bounds and descendant label.
sweep_resolver_selection() {
	awk 'BEGIN { RS="<"; OFS="\t" }
		function attr(key,   v) { if(!match($0," " key "=\"[^\"]*\"")) return ""; return substr($0,RSTART+length(key)+3,RLENGTH-length(key)-4) }
		/^node / {
			if(attr("resource-id") ~ /id\/resolver_list$/) list=d
			if(list!="" && d==list+1) { bounds=attr("bounds"); label=""; selected=0 }
			if(list!="" && d>list) {
				if(attr("selected")=="true") selected=1
				if(attr("text")!="") label=label "|" attr("text")
				if(attr("content-desc")!="") label=label "|" attr("content-desc")
			}
			if($0 ~ /\/>[ \t\r\n]*$/) {
				if(list!="" && d==list+1 && selected) { gsub(/[^0-9]+/," ",bounds); sub(/^ /,"",bounds); sub(/ $/,"",bounds); print bounds,label }
			} else d++
		}
		/^\/node>/ {
			d--; if(list!="" && d==list+1 && selected) { gsub(/[^0-9]+/," ",bounds); sub(/^ /,"",bounds); sub(/ $/,"",bounds); print bounds,label }
			if(list!="" && d==list) list=""
		}' "$SHOTS/smoke-$1.xml" | tail -1
}
t_sharesheet() {
	sweep_begin android || return
	local i selected previous='' bounds bar reached=0 stable=0 moved=0
	sh 'am start -W -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT hi >/dev/null 2>&1'; sleep 1.5; ui sharesheet
	bar=$(sweep_bounds sharesheet button_bar)
	if [ -z "$bar" ] || [ -z "$(sweep_bounds sharesheet button_once)" ] || [ -z "$(sweep_bounds sharesheet button_always)" ]; then
		echo 'SKIP sharesheet: chooser has no Just once / Always resolver bar'; key 4; sweep_end; return
	fi
	for i in $(seq 1 32); do
		ui sharesheet
		selected=$(sweep_resolver_selection sharesheet)
		case "$(sweep_focus_id sharesheet)" in
			button_once|button_always) if [ -n "$previous" ]; then reached=1; break; fi ;;
		esac
		if [ -n "$selected" ]; then
			if [ "$selected" = "$previous" ]; then stable=$((stable+1))
			else [ -z "$previous" ] || moved=1; stable=0; fi
			previous=$selected
			if [ "$stable" -ge 2 ]; then
				case "$(sweep_focus_id sharesheet)" in button_once|button_always) reached=1 ;; *) [ "$moved" = 0 ] || reached=1 ;; esac
				[ "$reached" = 0 ] || break
			fi
		fi
		wheel down 1 >/dev/null; sleep 0.4
	done
	bounds=$(printf '%s\n' "$previous" | cut -f1); bar=$(sweep_bounds sharesheet button_bar)
	if [ "$reached" = 1 ] && sweep_inside "$bounds" "$(sweep_screen sharesheet)" \
		&& awk -v a="$bounds" -v b="$bar" 'BEGIN { split(a,x," "); split(b,y," "); exit !(x[4]<=y[2]) }'; then
		ok 'Share sheet: last selected target row is above the resolver button bar'
	else bad "Share sheet: last target not reached or covered ($bounds; bar $bar)"; fi
	# Navigation only: no target, Just once, or Always is activated.
	key 4; sweep_end
}

# Default logical display dimensions, preferring WM's rotated override over base device size.
sweep_display_size() {
	awk '/mDisplayId=/ { s=$0; sub(/^.*mDisplayId=/,"",s); id=s+0 }
		id==0 && /m(Base|Override)DisplayInfo=.*real [0-9]+ x [0-9]+/ {
			s=$0; sub(/^.*real /,"",s); split(s,a," "); size=a[1] " " a[3]+0
			if($0 ~ /mOverrideDisplayInfo=/) over=size; else base=size
		} END { if(over!="") print over; else if(base!="") print base }' "$1"
}
t_rotlock() {
	sweep_has com.android.settings || return
	sweep_begin com.android.settings || return
	local size
	sh 'am start -W -a android.settings.DISPLAY_SETTINGS >/dev/null 2>&1'; sleep 1
	sh 'settings put system accelerometer_rotation 0'; turn 1; sleep 0.7
	sweep_wake=1
	key 223; sleep 1; key 224; sh 'wm dismiss-keyguard'; sleep 1
	[ "$(dev 'settings get system user_rotation')" = 1 ] \
		&& [ "$(dev 'settings get system accelerometer_rotation')" = 0 ] \
		&& ok 'Rotation lock: landscape user rotation survives sleep/wake' || bad 'Rotation lock: waking replaced the saved landscape rotation'
	if ! focus_win | grep -Fq com.android.settings; then
		echo 'SKIP rotlock display: keyguard did not dismiss back to Settings'
	else
		dev 'dumpsys display' > "$SHOTS/smoke-rotlock-display.txt"
		size=$(sweep_display_size "$SHOTS/smoke-rotlock-display.txt")
		echo "$size" | awk 'NF==2 && $1>$2 && $2>0 { f=1 } END { exit !f }' \
			&& ok 'Rotation lock: display is landscape after waking' || bad "Rotation lock: display is not landscape ($size)"
	fi
	sweep_end
}

sweep_cancel_at() {
	xnodes "$1" | awk -F '\t' '$5 ~ /id\/button2$/ || tolower($7)=="cancel" {
		split($6,b," "); if(b[3]>b[1] && b[4]>b[2]) { print int((b[1]+b[3])/2),int((b[2]+b[4])/2); exit } }'
}
t_imeland() {
	sweep_has com.android.settings || return
	sweep_begin com.android.settings || return
	local i field at before full hidden shown started size
	before=$(dev 'settings get global device_name')
	turn 1; sh 'am start -W -a android.settings.DEVICE_NAME >/dev/null 2>&1'; sleep 1; ui imeland
	dev 'dumpsys display' > "$SHOTS/smoke-imeland-display.txt"
	size=$(sweep_display_size "$SHOTS/smoke-imeland-display.txt")
	if ! echo "$size" | awk 'NF==2 && $1>$2 && $2>0 { f=1 } END { exit !f }'; then
		bad "Landscape IME: display did not turn ($size)"; key 4; sweep_end; return
	fi
	field=$(xnodes imeland | awk -F '\t' '$4 ~ /EditText$/ { print $6; exit }')
	if [ -z "$field" ]; then
		if ! app_focus imeland 'Device name'; then key 4; sweep_end; return; fi
		side_press; sleep 0.6; ui imeland
		field=$(xnodes imeland | awk -F '\t' '$4 ~ /EditText$/ { print $6; exit }')
	fi
	at=$(echo "$field" | awk 'NF==4 && $3>$1 && $4>$2 { print int(($1+$3)/2),int(($2+$4)/2) }')
	if [ -z "$at" ]; then echo 'SKIP imeland: this Device name page has no editable dialog'; key 4; sweep_end; return; fi
	sh "input tap $at"
	for i in 1 2 3 4 5 6 7 8; do
		dev 'dumpsys input_method' > "$SHOTS/smoke-imeland-ime.txt"
		shown=$(sed -n 's/.*mWindowVisible=\(true\|false\).*/\1/p' "$SHOTS/smoke-imeland-ime.txt" | tail -1)
		started=$(sed -n 's/.*mInputViewStarted=\(true\|false\).*/\1/p' "$SHOTS/smoke-imeland-ime.txt" | tail -1)
		[ "$shown:$started" = true:true ] && break
		sleep 0.3
	done
	full=$(sed -n 's/.*mIsFullscreen=\(true\|false\).*/\1/p' "$SHOTS/smoke-imeland-ime.txt" | tail -1)
	hidden=$(sed -n 's/.*mExtractViewHidden=\(true\|false\).*/\1/p' "$SHOTS/smoke-imeland-ime.txt" | tail -1)
	[ "$shown:$started:$full" = true:true:false ] \
		&& ok "Landscape IME: shown without fullscreen/extract mode (extract hidden=$hidden)" \
		|| bad "Landscape IME: window=$shown input=$started fullscreen=$full extractHidden=$hidden"
	# The dialog moves up as the keyboard's window comes in: a dump taken in that moment has the
	# dialog where it was, its buttons behind the keyboard, so wait for the layout to settle
	for i in 1 2 3 4 5; do
		ui imeland-keyboard; at=$(sweep_cancel_at imeland-keyboard)
		[ -n "$at" ] && break
		sleep 0.6
	done
	[ -n "$at" ] && ok 'Landscape IME: dialog Cancel remains on screen' || bad 'Landscape IME: keyboard hides dialog Cancel'
	# Tap only the identified negative button. No text is typed and no positive button pressed.
	if [ -n "$at" ]; then sh "input tap $at"; else key 4; sleep 0.2; key 4; fi
	[ "$(dev 'settings get global device_name')" = "$before" ] \
		&& ok 'Landscape IME: cancelled without changing device name' || bad 'Landscape IME: device name changed'
	sweep_end
}

t_wheelsetfirst() {
	sweep_has cat.kenny.r1.wheel.settings || return
	sweep_begin cat.kenny.r1.wheel.settings || return
	local bounds screen
	sh 'am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null 2>&1'; sleep 1
	if ! sweep_wait_focus wheelsetfirst 'How the wheel works'; then sweep_end; return; fi
	ok 'Wheel settings: first focus is How the wheel works'
	wheel up 1 >/dev/null; sleep 0.4; ui wheelsetfirst-up
	sweep_focus_has wheelsetfirst-up 'Use the wheel UI' \
		&& ok 'Wheel settings: one up reaches the main switch without toggling it' || bad 'Wheel settings: one up did not reach the main switch'
	if app_focus wheelsetfirst-last 'Show hints again'; then
		bounds=$(focus_field wheelsetfirst-last 6); screen=$(sweep_screen wheelsetfirst-last)
		if sweep_inside "$bounds" "$screen" && awk -v a="$bounds" -v b="$screen" 'BEGIN { split(a,x," "); split(b,y," "); exit !(x[4]<=y[4]-8) }'; then
			ok 'Wheel settings: last row has at least 8px below it'
		else bad "Wheel settings: last row reaches screen edge ($bounds; screen $screen)"; fi
	fi
	sweep_end
}

# Unclipped native view rectangles for Canvas controls. Activity dumps use parent-relative
# logical bounds; accumulate offsets rather than trusting accessibility's clipped rectangles.
sweep_activity_nodes() {
	awk 'BEGIN { OFS="\t" }
		/View Hierarchy:/ { active=1; header=match($0,/[^ ]/)-1; n=0; next }
		active {
			indent=match($0,/[^ ]/)-1
			if(indent<=header && $0 !~ /^[ \t]*$/) { active=0; next }
			if($1 !~ /\{/ || length($2)!=9 || $4 !~ /^-?[0-9]+,-?[0-9]+--?[0-9]+,-?[0-9]+/) next
			while(n>0 && levels[n-1]>=indent) n--
			split($4,a,","); match(a[2],/^-?[0-9]+/); top=substr(a[2],1,RLENGTH)+0; right=substr(a[2],RLENGTH+2)+0
			left=a[1]+0; bottom=a[3]+0
			px=n>0?xs[n-1]:0; py=n>0?ys[n-1]:0
			xs[n]=px+left; ys[n]=py+top; levels[n]=indent
			cls=$1; sub(/\{.*/,"",cls); id=""
			if(match($0,/[A-Za-z0-9._]+:id\/[A-Za-z0-9_]+/)) { id=substr($0,RSTART,RLENGTH); sub(/^.*\//,"",id) }
			print cls,id,xs[n] " " ys[n] " " px+right " " py+bottom
			n++
		}' "$1"
}
sweep_canvas_bounds() { sweep_activity_nodes "$1" | awk -F '\t' -v id="$2" '$2==id { print $3; exit }'; }
sweep_land_lane() {
	local snap=$1 kind=$2 rotation=$3 offset edge lane hint content screen file dpi
	file="$SHOTS/smoke-$snap-activity.txt"
	screen=$(sweep_screen "$snap"); lane=$(sweep_canvas_bounds "$file" "$([ "$kind" = synth ] && echo knob || echo drum)")
	hint=$(sweep_canvas_bounds "$file" hint_row)
	if [ "$kind" = synth ]; then content=$(sweep_canvas_bounds "$file" pads)
	else content=$(sweep_canvas_bounds "$file" channel); fi
	offset=$(dev 'getprop ro.vendor.display.primary_orientation'); [ -n "$offset" ] || offset=0
	case "$offset" in 0|90|180|270) ;; *) bad "$cur: invalid primary orientation $offset"; return 1 ;; esac
	edge=$(((rotation+offset/90)%4))
	dpi=$(dev 'wm density' | tail -1 | sed 's/.*: *//')
	case "$dpi" in ''|*[!0-9]*) bad "$cur: no density for landscape geometry"; return 1 ;; esac
	if ! sweep_inside "$lane" "$screen" || ! sweep_inside "$hint" "$screen" \
		|| ! sweep_inside "$content" "$screen" || ! sweep_clear "$lane" "$hint" \
		|| ! awk -v a="$lane" -v s="$screen" 'BEGIN { split(a,x," "); split(s,y," "); exit !(x[3]-x[1]>x[4]-x[2] && y[3]-y[1]>y[4]-y[2]) }'; then
		bad "$cur: missing, clipped, tall or overlapping landscape strip ($lane; hint $hint; screen $screen)"; return 1
	fi
	case "$edge" in
		1) awk -v a="$lane" -v b="$content" 'BEGIN { split(a,x," "); split(b,y," "); exit !(x[2]<160 && x[4]<=y[2]) }' \
			&& ok "$cur: rotation $rotation strip is wide under the title" || { bad "$cur: top strip misplaced ($lane; content $content)"; return 1; } ;;
		3) awk -v a="$lane" -v b="$content" -v h="$hint" -v dpi="$dpi" 'BEGIN { split(a,x," "); split(b,y," "); split(h,z," "); exit !(x[2]>=y[4] && x[4]<=z[2] && z[2]-x[4]<=16*dpi/160) }' \
			&& ok "$cur: rotation $rotation strip is after content, before bottom hint" || { bad "$cur: bottom strip misplaced ($lane; content $content; hint $hint)"; return 1; } ;;
		*) echo "SKIP $cur physical edge: primary orientation $offset makes wheel edge $edge"; return 1 ;;
	esac
	if [ "$kind" = synth ]; then
		# PadsView draws six Canvas pads and exposes only their combined description. Derive
		# each cell from its actual, unclipped bounds and the current source's 8dp gap/columns.
		if ! sweep_clear "$content" "$hint" || ! awk -v a="$content" -v screen="$screen" -v hint="$hint" -v dpi="$dpi" 'BEGIN {
			split(a,p," "); split(screen,s," "); split(hint,h," "); d=dpi/160; width=p[3]-p[1]; height=p[4]-p[2]
			cols=2*width>=3*height?3:2; rows=int((6+cols-1)/cols); gap=8*d
			w=(width-gap*(cols-1))/cols; v=(height-gap*(rows-1))/rows
			for(i=0;i<6;i++) { l=p[1]+(i%cols)*(w+gap); t=p[2]+int(i/cols)*(v+gap); r=l+w; b=t+v
				if(w<126*d || v<40*d || l<s[1] || t<s[2] || r>s[3] || b>s[4] || !(r<=h[1] || h[3]<=l || b<=h[2] || h[4]<=t)) exit 1 }
		}'; then bad "$cur: a pad/label is clipped, too short, or overlaps the hint"; return 1; fi
		ok "$cur: all six pad cells and label lanes fit clear of the hint"
	else
		sweep_clear "$content" "$hint" && ok "$cur: channel and whole drum row area stay clear of the hint" \
			|| { bad "$cur: channel rows overlap hint"; return 1; }
	fi
}
t_synthland() {
	sweep_has cat.kenny.r1.synth || return
	sweep_begin cat.kenny.r1.synth || return
	local rotation snap
	for rotation in 1 3; do
		turn "$rotation"
		sh 'am start -W --activity-reorder-to-front -n cat.kenny.r1.synth/.SynthActivity >/dev/null 2>&1'; sleep 0.7
		snap=synthland-$rotation; ui "$snap"
		dev 'dumpsys activity cat.kenny.r1.synth/.SynthActivity' > "$SHOTS/smoke-$snap-activity.txt"
		sweep_land_lane "$snap" synth "$rotation"
	done
	# No wheel, press, hold or pad touch is sent to the synth.
	sweep_end
}
t_walkieland() {
	sweep_has cat.kenny.r1.walkie || return
	local was_on rotation snap
	was_on=$(app_service_state cat.kenny.r1.walkie)
	case "$was_on" in on|off) ;; *) echo 'SKIP walkieland: cannot save radio service state'; return ;; esac
	sweep_begin cat.kenny.r1.walkie || return
	# Voice and chimes use USAGE_MEDIA. Mute before launch so even incoming radio audio
	# stays silent; the saved stream volume is restored by normal and interrupted cleanup.
	sweep_media_volume=$(dev 'cmd media_session volume --stream 3 --get' | sed -n 's/.*volume is \([0-9][0-9]*\) in range.*/\1/p' | tail -1)
	case "$sweep_media_volume" in ''|*[!0-9]*) echo 'SKIP walkieland: cannot save media volume for silent test'; sweep_media_volume=; sweep_end; return ;; esac
	if [ "$sweep_media_volume" = 0 ]; then
		# Already silent: avoid rewriting zero, which could alter a pre-existing mute/index.
		sweep_media_volume=
	else
		sh 'cmd media_session volume --stream 3 --set 0'
	fi
	if ! dev 'cmd media_session volume --stream 3 --get' | grep -Fq 'volume is 0 in range'; then
		bad 'walkieland: cannot mute media before launch'; sweep_end; return
	fi
	[ "$was_on" != off ] || sweep_walkie_stop=1
	for rotation in 1 3; do
		turn "$rotation"
		sh 'am start -W -n cat.kenny.r1.walkie/.WalkieActivity >/dev/null 2>&1'; sleep 0.7
		snap=walkieland-$rotation; ui "$snap"
		dev 'dumpsys activity cat.kenny.r1.walkie/.WalkieActivity' > "$SHOTS/smoke-$snap-activity.txt"
		sweep_land_lane "$snap" walkie "$rotation"
	done
	# Do not press, touch or hold the drum: no talking, replay or channel change.
	sweep_end
}

t_routerinset() {
	sweep_has cat.kenny.r1.router || return
	sweep_begin cat.kenny.r1.router || return
	local bounds screen
	sh 'am start -W -n cat.kenny.r1.router/.RouterActivity >/dev/null 2>&1'; sleep 0.8
	if sweep_seek_id routerinset own 24; then
		bounds=$(focus_field routerinset 6); screen=$(sweep_screen routerinset)
		if sweep_inside "$bounds" "$screen" && awk -v a="$bounds" -v s="$screen" 'BEGIN {
			split(a,x," "); split(s,y," "); exit !(x[1]>=y[1]+12 && x[3]<=y[3]-12 && x[3]-x[1]>(y[3]-y[1])/2) }'; then
			ok 'Router: focused full-width card is inset at least 12px at both edges'
		else bad "Router: focused card reaches an edge ($bounds; screen $screen)"; fi
	fi
	# The own card is focused only. Never press it, any hotspot control, or the VPN row.
	sweep_end
}

t_dialland() {
	sweep_has com.android.dialer || return
	sweep_begin com.android.dialer || return
	local digit bounds screen
	turn 1
	sh 'am start -W -S -n com.android.dialer/.main.impl.MainActivity -a android.intent.action.DIAL >/dev/null 2>&1'; sleep 1
	ui dialland; screen=$(sweep_screen dialland)
	if ! echo "$screen" | awk 'NF==4 && $3-$1>$4-$2 { f=1 } END { exit !f }'; then
		bad "Dialer: keypad did not turn landscape ($screen)"; sweep_end; return
	fi
	if [ -n "$(sweep_bounds dialland rotary_dial)" ]; then echo 'SKIP dialland: rotary keypad selected'; sweep_end; return; fi
	for digit in one two three four five six seven eight nine zero; do
		bounds=$(sweep_bounds dialland "$digit")
		if sweep_inside "$bounds" "$screen" && echo "$bounds" | awk 'NF==4 && $4-$2>=64 { f=1 } END { exit !f }'; then
			ok "Dialer landscape: $digit key is at least 64px tall"
		else bad "Dialer landscape: $digit key is missing, clipped or too short ($bounds)"; fi
	done
	# No digit or call key is pressed; Dialer's remembered tab is restored by sweep_end.
	sweep_end
}

# Direct toolbar TextViews use logical, parent-relative bounds in the activity dump. Checking
# both title/subtitle also catches clipping hidden by accessibility's visible-bounds clipping.
sweep_toolbar_fit() {
	awk '/View Hierarchy:/ { active=1; next }
		active && $1 ~ /\{/ && length($2)==9 {
			indent=match($0,/[^ ]/)-1
			if(toolbar && indent<=depth) toolbar=0
			if($0 ~ /:id\/toolbar[ }]/) {
				toolbar=1; depth=indent; split($4,a,","); match(a[2],/^-?[0-9]+/); height=a[3]-substr(a[2],1,RLENGTH); next
			}
			if(toolbar && indent==depth+2 && $1 ~ /TextView\{/) {
				split($4,a,","); match(a[2],/^-?[0-9]+/); top=substr(a[2],1,RLENGTH)+0; bottom=a[3]+0
				count++; if(top<0 || bottom<=top || bottom>height) bad=1
			}
		} END { if(count<2) exit 77; exit bad }' "$1"
}
t_etartitle() {
	sweep_has org.lineageos.etar || return
	sweep_begin org.lineageos.etar || return
	local rotation rc
	for rotation in 0 1; do
		turn "$rotation"
		sh 'am start -W -n org.lineageos.etar/com.android.calendar.AllInOneActivity >/dev/null 2>&1'; sleep 0.8
		ui "etartitle-$rotation"
		if ! sweep_screen "etartitle-$rotation" | awk -v rotation="$rotation" 'NF==4 && (rotation==0 ? $4-$2>$3-$1 : $3-$1>$4-$2) { f=1 } END { exit !f }'; then
			bad "Calendar: display did not reach rotation $rotation"; continue
		fi
		dev 'dumpsys activity org.lineageos.etar/com.android.calendar.AllInOneActivity' > "$SHOTS/smoke-etartitle-$rotation-activity.txt"
		sweep_toolbar_fit "$SHOTS/smoke-etartitle-$rotation-activity.txt"; rc=$?
		if [ "$rc" = 77 ]; then echo "SKIP etartitle rotation $rotation: current calendar view has no title/subtitle pair"
		elif [ "$rc" = 0 ]; then ok "Calendar: title and subtitle fit toolbar at rotation $rotation"
		else bad "Calendar: subtitle/title extends outside toolbar at rotation $rotation"; fi
	done
	# Keep the user's view/date; no VIEW override or calendar action is sent.
	sweep_end
}

settingssearch_has_query() {
	xnodes "$1" | awk -F '\t' '$5~/id\/(search_src_text|search_query)$/ && $7=="brightness" {found=1} END{exit !found}'
}
settingssearch_has_results() {
	xnodes "$1" | awk -F '\t' '
		$5~/id\/(search_src_text|search_query)$/ && $7=="brightness" {query=1}
		$5=="android:id/title" && tolower($7)~/brightness/ {result=1}
		END{exit !(query&&result)}'
}
settingssearch_wait_results() {
	local snap=$1 limit=${2:-90} end i window
	end=$(( $(date +%s) + limit ))
	# SearchFragment records a query while the fresh-boot index is still building;
	# onIndexingFinished starts its loader later. Wait for actual result titles.
	# Both wall time and attempts are bounded; ui itself has a per-command timeout.
	for i in $(seq 1 181); do
		window=$(focus_win)
		case "$window" in *com.android.settings.intelligence/*SearchActivity*) ;; *) return 1;; esac
		ui "$snap"
		settingssearch_has_results "$snap" && return 0
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .5
	done
	return 1
}
settingssearch_end() {
	# Home alone preserves Search in Settings task history. Pop it even after a failed
	# assertion so a later warm Settings launch cannot resume the other package.
	case "$(focus_win)" in *com.android.settings.intelligence/*SearchActivity*) key 4; key 4;; esac
	sweep_end
}
t_settingssearch() {
	sweep_begin com.android.settings || return
	local i window focus ready=0 ui_fast=1
	sh 'am start -W -S -a android.settings.SETTINGS >/dev/null'; sleep 1
	# Search sits above the list's top: preserve the two up turns that reveal it.
	wheel up 2 >/dev/null; sleep 1
	ui settingssearch-main
	if [ -z "$(sweep_bounds settingssearch-main search_action_bar)" ]; then
		bad 'Settings search: no search bar'; settingssearch_end; return
	fi
	app_tap_id settingssearch-main search_action_bar
	# Search is another package/activity. A keyboard field need not report class EditText.
	for i in $(seq 1 20); do
		window=$(focus_win)
		case "$window" in *com.android.settings.intelligence/*SearchActivity*) ready=1; break;; esac
		sleep .25
	done
	if [ "$ready" != 1 ]; then
		ui settingssearch-query; bad "Settings search: SearchActivity did not open ($window)"; settingssearch_end; return
	fi
	ui settingssearch-query
	sh 'input text brightness'
	if settingssearch_wait_results settingssearch-results 90; then
		ok 'Settings search: SearchActivity accepts brightness and shows live results'
	else
		if settingssearch_has_query settingssearch-results; then
			bad 'Settings search: brightness query accepted, index/live results did not become ready within the bounded wait'
		else bad 'Settings search: brightness query missing after input'; fi
		shot settingssearch-timeout
		dev 'logcat -d -t 1500 -v threadtime' | grep -Ei 'SearchFragment|Indexing|IndexDatabase|SettingsIntelligence' > "$SHOTS/smoke-settingssearch-index.log"
		settingssearch_end; return
	fi
	ready=0
	for i in 1 2 3 4 5 6 7 8; do
		wheel down 1 >/dev/null; sleep .25; ui settingssearch-step
		focus=$(focused_in settingssearch-step)
		if [ -n "$focus" ] && [ "$(focus_field settingssearch-step 6 | awk '{print $2}')" -ge 100 ] \
			&& xfocus settingssearch-step | awk -F '\t' '$1=="self" && ($6~/search_src_text|search_query/ || $5~/EditText|AutoCompleteTextView/) {query=1} $1!="out" && tolower($8)~/brightness/ {result=1} END{exit !(!query&&result)}' \
			&& ! dev 'dumpsys input_method' | grep -q 'mInputShown=true'; then ready=1; break; fi
	done
	[ "$ready" = 1 ] && ok "Settings search: wheel leaves query, closes keyboard and focuses a result ($focus)" \
		|| bad "Settings search: result focus/keyboard transition failed ($focus)"
	settingssearch_end
}

# Hidden-pill navigation: taps that open/finish an activity must not race recents.
# Host-only, read-only navigation; every tap is on a freshly identified allowlisted row.
bottomstrip_window_is() {
	local component
	component=$(printf '%s\n' "$1" | sed -n 's/.* u[0-9][0-9]* \([^ }]*\/[^ }]*\).*/\1/p')
	case "$2:$component" in
		settings-main:com.android.settings/.Settings|settings-main:com.android.settings/com.android.settings.Settings|settings-main:com.android.settings/.homepage.SettingsHomepageActivity|settings-main:com.android.settings/com.android.settings.homepage.SettingsHomepageActivity) return 0 ;;
		settings-subpage:com.android.settings/*)
			bottomstrip_window_is "$1" settings-main && return 1
			case "$component" in *SearchActivity*|*SearchResult*) return 1 ;; esac
			return 0 ;;
		wheel:cat.kenny.r1.wheel.settings/.WheelActivity|wheel:cat.kenny.r1.wheel.settings/cat.kenny.r1.wheel.settings.WheelActivity|help:cat.kenny.r1.wheel.settings/.WheelActivity|help:cat.kenny.r1.wheel.settings/cat.kenny.r1.wheel.settings.WheelActivity) return 0 ;;
		practice:cat.kenny.r1.wheel.settings/.PracticeActivity|practice:cat.kenny.r1.wheel.settings/cat.kenny.r1.wheel.settings.PracticeActivity) return 0 ;;
		toolbox:cat.kenny.r1.toolbox/.ToolboxActivity|toolbox:cat.kenny.r1.toolbox/cat.kenny.r1.toolbox.ToolboxActivity) return 0 ;;
		toolbox-settings:cat.kenny.r1.toolbox/.ToolboxSettingsActivity|toolbox-settings:cat.kenny.r1.toolbox/cat.kenny.r1.toolbox.ToolboxSettingsActivity) return 0 ;;
		home:com.android.launcher3/*) return 0 ;;
	esac
	return 1
}
bottomstrip_wait() {
	local expected=$1 snap=$2 i stable=0 end=$(( $(date +%s) + 8 )) window
	for i in $(seq 1 33); do
		window=$(focus_win)
		if bottomstrip_window_is "$window" "$expected"; then
			if [ "$expected" = help ]; then
				ui "$snap"
				if ! xnodes "$snap" | awk -F '\t' '$3=="cat.kenny.r1.wheel.settings" && $7=="Try it" {f=1} END{exit !f}' \
					|| ! bottomstrip_window_is "$(focus_win)" help; then stable=0
				else stable=$((stable+1)); fi
			else stable=$((stable+1)); fi
			[ "$stable" -lt 2 ] || return 0
		else stable=0; fi
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .2
	done
	return 1
}
bottomstrip_fail() {
	bad "bottomstrip $1: $2 ($(focus_win))"
	ui "bottomstrip-$1-fail"; shot "bottomstrip-$1-fail"
	dev 'dumpsys activity activities' > "$SHOTS/smoke-bottomstrip-$1-tasks.txt"
}
bottomstrip_launch() {
	key 224; sh 'wm dismiss-keyguard'
	sh "am start -W -S -f 0x10008000 -n $1 >/dev/null" || return 1
	bottomstrip_wait "$2" "bottomstrip-$2"
}
# Nearest clickable, enabled ancestor of an exact title, never a list container.
# Preserve signed coordinates; a clipped negative bound is not a visible target.
bottomstrip_row() {
	awk -v want="$2" -v pkg="$3" 'BEGIN{RS="<"}
		function attr(k, m) {
			if(!match($0," " k "=\"[^\"]*\""))return ""
			m=substr($0,RSTART+length(k)+3,RLENGTH-length(k)-4)
			gsub(/&amp;/,"\\&",m);gsub(/&apos;/,"\047",m);return m
		}
		/^node / {
			n++;p[n]=d?path[d-1]:0;path[d]=n
			b[n]=attr("bounds");c[n]=attr("clickable")=="true";enabled[n]=attr("enabled")=="true"
			cl[n]=attr("class");package[n]=attr("package")
			if(attr("text")==want && package[n]==pkg) {
				r=n;while(r && !c[r])r=p[r]
				if(r && enabled[r] && cl[r]!~/(RecyclerView|ScrollView|ListView|Switch|CheckBox|ToggleButton|SeekBar|Spinner|Button)$/ && package[r]==pkg) {
					v=b[r];gsub(/[^0-9-]+/," ",v);sub(/^ /,"",v);sub(/ $/,"",v)
					split(v,a," ");if(a[3]>a[1] && a[4]>a[2]) {print v;exit}
				}
			}
			if($0!~/\/>[ \t\r\n]*$/)d++
		}
		/^\/node>/ {d--}' "$SHOTS/smoke-$1.xml"
}
bottomstrip_viewport() {
	xnodes "$1" | awk -F '\t' -v pkg="$2" '$3==pkg && $4~/(RecyclerView|ScrollView|ListView)$/ {
		split($6,a," ");area=(a[3]-a[1])*(a[4]-a[2]);if(area>best){best=area;rect=$6}
	} END{if(best>0)print rect}'
}
# Modes: actual centre; full-row centre in the requested30px band; bottom-4;
# or any visible intersection with the37px strip. No point outside the control.
bottomstrip_point() {
	awk -v rect="$1" -v w="$2" -v h="$3" -v mode="$4" -v full="${5:-0}" 'BEGIN {
		if(split(rect,a," ")!=4 || a[3]<=a[1] || a[4]<=a[2] || w<=0 || h<=37)exit 1
		x=int((a[1]+a[3])/2);y=int((a[2]+a[4])/2)
		if(mode=="aligned") {if(full<=0 || full>220)exit 1;y=int(a[2]+full/2);if(y<h-34 || y>h-4)exit 1}
		else if(mode=="edge")y=h-4
		else if(mode=="overlap") {top=a[2]>h-37?a[2]:h-37;bot=a[4]<h?a[4]:h;if(bot<=top)exit 1;y=int((top+bot)/2)}
		else if(mode!="centre")exit 1
		if(x<0 || x>=w || y<0 || y>=h || x<a[1] || x>=a[3] || y<a[2] || y>=a[4])exit 1
		print x,y
	}'
}
bottomstrip_drag() {
	awk -v rect="$1" -v delta="$2" -v w="$3" -v h="$4" 'BEGIN {
		if(split(rect,a," ")!=4 || delta==0)exit 1
		top=a[2]+16;if(top<64)top=64;bot=a[4]-16;if(bot>h-50)bot=h-50
		room=bot-top-16;if(room<32)exit 1;cap=room<120?room:120
		if(delta>0){if(delta<24)delta=24;if(delta>cap)delta=cap;y=top+8}
		else {if(delta>-24)delta=-24;if(delta< -cap)delta=-cap;y=bot-8}
		x=int((a[1]+a[3])/2);if(x<24 || x>=w-24)exit 1
		print x,int(y),x,int(y+delta),250
	}'
}
bottomstrip_screen() {
	local i end=$(( $(date +%s) + 6 ))
	for i in $(seq 1 25); do
		ui "$1"
		[ "$(sweep_screen "$1")" != "0 0 $2 $3" ] || return 0
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .2
	done
	return 1
}
# Learn an unclipped row height, then scroll by measured deltas. A clipped visible
# sliver cannot pretend that its midpoint is the centre of the actual row.
bottomstrip_align_settings() {
	local snap=$1 w=$2 h=$3 label i rect view vtop vbottom full point last= stall=0 drag delta end
	for label in Display Apps System; do
		bottomstrip_launch com.android.settings/.Settings settings-main || return 1
		bottomstrip_screen "$snap" "$w" "$h" || return 1
		full=0;last=;stall=0;end=$(( $(date +%s) + 45 ))
		for i in $(seq 1 24); do
			bottomstrip_window_is "$(focus_win)" settings-main || return 1
			ui "$snap"
			bottomstrip_window_is "$(focus_win)" settings-main || return 1
			rect=$(bottomstrip_row "$snap" "$label" com.android.settings)
			view=$(bottomstrip_viewport "$snap" com.android.settings)
			[ -n "$view" ] || break
			set -- $view;vtop=$2;vbottom=$4
			if [ -n "$rect" ]; then
				set -- $rect
				# Full visible rows only; the strip target may subsequently be clipped.
				if [ "$2" -gt "$((vtop+3))" ] && [ "$4" -lt "$((vbottom-3))" ] \
					&& [ "$4" -le "$((h-40))" ] && [ "$(( $4-$2 ))" -ge 40 ] && [ "$(( $4-$2 ))" -le 220 ]; then full=$(( $4-$2 )); fi
				point=$(bottomstrip_point "$rect" "$w" "$h" aligned "$full")
				if [ -n "$point" ]; then
					if [ "$last" = "$label:$rect:$point" ]; then
						bottomstrip_label=$label;bottomstrip_target=$point
						return 0
					fi
					last="$label:$rect:$point";sleep .15;continue
				fi
				if [ "$last" = "$rect" ]; then stall=$((stall+1)); else stall=0; fi
				[ "$stall" -lt 3 ] || break
				last=$rect
				if [ "$full" -gt 0 ]; then delta=$((h-20-$2-full/2)); else delta=-80; fi
			else delta=-100; fi
			drag=$(bottomstrip_drag "$view" "$delta" "$w" "$h") || break
			bottomstrip_window_is "$(focus_win)" settings-main || return 1
			sh "input swipe $drag"
			[ "$(date +%s)" -lt "$end" ] || break
			sleep .15
		done
	done
	return 1
}
bottomstrip_settings_step() {
	local step=$1 w=$2 h=$3 bottomstrip_label bottomstrip_target
	if ! bottomstrip_align_settings "bottomstrip-$step" "$w" "$h"; then
		bottomstrip_fail "$step" "TEST setup: no identified full-row centre in $((h-34))..$((h-4))";return 1
	fi
	bottomstrip_window_is "$(focus_win)" settings-main || { bottomstrip_fail "$step" 'main list lost before tap';return 1; }
	printf 'bottomstrip %s: %s row centre at %s\n' "$step" "$bottomstrip_label" "$bottomstrip_target"
	sh "input tap $bottomstrip_target"
	if bottomstrip_wait settings-subpage "bottomstrip-$step-open"; then
		ok "bottomstrip $step: strip-centre tap opens the Settings subpage"
		key 4
		bottomstrip_wait settings-main "bottomstrip-$step-back" || { bottomstrip_fail "$step" 'Back did not return to Settings main';return 1; }
	else bottomstrip_fail "$step" 'strip tap did not open a Settings subpage (Launcher is not success)';return 1; fi
}
bottomstrip_focus() {
	local snap=$1 label=$2 expected=$3 way i
	for way in down up; do
		for i in $(seq 1 24); do
			bottomstrip_window_is "$(focus_win)" "$expected" || return 1
			ui "$snap"
			bottomstrip_window_is "$(focus_win)" "$expected" || return 1
			r2_row_is "$snap" "$label" && return 0
			wheel "$way" 1 >/dev/null;sleep .15
		done
	done
	return 1
}
bottomstrip_open_practice() {
	bottomstrip_launch cat.kenny.r1.wheel.settings/.WheelActivity wheel || return 1
	bottomstrip_focus bottomstrip-help 'How the wheel works' wheel || return 1
	side_press
	bottomstrip_wait help bottomstrip-help || return 1
	bottomstrip_focus bottomstrip-help 'Try it' help || return 1
	side_press
	bottomstrip_wait practice bottomstrip-practice || return 1
	bottomstrip_screen bottomstrip-practice 480 640 || return 1
	bottomstrip_focus bottomstrip-practice Done practice || return 1
	dev 'dumpsys activity activities' > "$SHOTS/smoke-bottomstrip-practice-parent.txt"
}
bottomstrip_done_rect() {
	local i rect last=
	for i in 1 2 3 4 5; do
		bottomstrip_window_is "$(focus_win)" practice || return 1
		ui bottomstrip-done
		bottomstrip_window_is "$(focus_win)" practice || return 1
		rect=$(bottomstrip_row bottomstrip-done Done cat.kenny.r1.wheel.settings)
		if [ -n "$rect" ] && [ "$rect" = "$last" ]; then printf '%s\n' "$rect";return 0;fi
		last=$rect;sleep .15
	done
	return 1
}
bottomstrip_practice_step() {
	local rect point edge
	if ! bottomstrip_open_practice; then bottomstrip_fail practice-centre 'parent Help -> Try it setup failed';return 1;fi
	rect=$(bottomstrip_done_rect)
	point=$(bottomstrip_point "$rect" 480 640 centre)
	[ -n "$point" ] || { bottomstrip_fail practice-centre 'no stable visible Done centre';return 1; }
	edge=$(bottomstrip_point "$rect" 480 640 edge)
	bottomstrip_window_is "$(focus_win)" practice || { bottomstrip_fail practice-centre 'Practice lost before tap';return 1; }
	printf 'bottomstrip practice-centre: Done [%s], tap %s\n' "$rect" "$point"
	sh "input tap $point"
	if bottomstrip_wait help bottomstrip-help; then ok 'bottomstrip practice-centre: Done centre returns to How the wheel works'
	else bottomstrip_fail practice-centre 'parent-launched Done centre did not return to Help';fi
	if [ -z "$edge" ]; then echo "SKIP bottomstrip practice-bottom: bottom-4 point is outside Done [$rect]";return;fi
	# A fresh parent launch makes the optional second tap an independent finish.
	if ! bottomstrip_open_practice; then bottomstrip_fail practice-bottom 'parent Help -> Try it setup failed';return 1;fi
	rect=$(bottomstrip_done_rect);point=$(bottomstrip_point "$rect" 480 640 edge)
	if [ -z "$point" ]; then echo "SKIP bottomstrip practice-bottom: fresh Done no longer reaches bottom-4 [$rect]";return;fi
	bottomstrip_window_is "$(focus_win)" practice || { bottomstrip_fail practice-bottom 'Practice lost before tap';return 1; }
	sh "input tap $point"
	if bottomstrip_wait help bottomstrip-help; then ok 'bottomstrip practice-bottom: Done tap at y636 returns to How the wheel works'
	else bottomstrip_fail practice-bottom 'Done tap at y636 did not return to Help';fi
}
bottomstrip_toolbox_step() {
	local i rect last= stable=0 view drag point end=$(( $(date +%s) + 45 ))
	bottomstrip_launch cat.kenny.r1.toolbox/.ToolboxActivity toolbox || { bottomstrip_fail toolbox-settings 'Toolbox home did not open';return 1; }
	bottomstrip_screen bottomstrip-toolbox 480 640 || { bottomstrip_fail toolbox-settings 'portrait640px screen not ready';return 1; }
	for i in $(seq 1 24); do
		bottomstrip_window_is "$(focus_win)" toolbox || { bottomstrip_fail toolbox-settings 'Toolbox lost while scrolling';return 1; }
		ui bottomstrip-toolbox
		bottomstrip_window_is "$(focus_win)" toolbox || { bottomstrip_fail toolbox-settings 'Toolbox lost during capture';return 1; }
		rect=$(bottomstrip_row bottomstrip-toolbox Settings cat.kenny.r1.toolbox)
		if [ -n "$rect" ] && [ "$rect" = "$last" ];then stable=$((stable+1));else stable=0;fi
		[ "$stable" -lt 2 ] || break
		last=$rect;view=$(bottomstrip_viewport bottomstrip-toolbox cat.kenny.r1.toolbox)
		drag=$(bottomstrip_drag "$view" -120 480 640) || break
		sh "input swipe $drag"
		[ "$(date +%s)" -lt "$end" ] || break
		sleep .15
	done
	[ "$stable" -ge 2 ] && [ -n "$rect" ] || { bottomstrip_fail toolbox-settings 'TEST setup: last Settings row did not settle at scroll end';return 1; }
	point=$(bottomstrip_point "$rect" 480 640 overlap)
	if [ -z "$point" ];then echo "SKIP bottomstrip toolbox-settings: last Settings row has no visible part in strip [$rect]";return;fi
	bottomstrip_window_is "$(focus_win)" toolbox || { bottomstrip_fail toolbox-settings 'Toolbox lost before tap';return 1; }
	printf 'bottomstrip toolbox-settings: row [%s], strip tap %s\n' "$rect" "$point"
	sh "input tap $point"
	if bottomstrip_wait toolbox-settings bottomstrip-toolbox-settings;then ok 'bottomstrip toolbox-settings: last row strip tap opens Toolbox Settings'
	else bottomstrip_fail toolbox-settings 'last row strip tap did not open Toolbox Settings';fi
}
bottomstrip_swipe_step() {
	bottomstrip_launch com.android.settings/.Settings settings-main || { bottomstrip_fail home-swipe 'Settings did not open';return 1; }
	bottomstrip_screen bottomstrip-swipe 480 640 || { bottomstrip_fail home-swipe 'portrait640px screen not ready';return 1; }
	bottomstrip_window_is "$(focus_win)" settings-main || { bottomstrip_fail home-swipe 'Settings lost before swipe';return 1; }
	sh 'input swipe 240 636 240 300 180'
	if bottomstrip_wait home bottomstrip-home;then ok 'bottomstrip home-swipe: short180ms swipe reaches Launcher without a hold'
	else bottomstrip_fail home-swipe 'short swipe did not reach Launcher';return 1;fi
	# Reuse the existing task: force-stop/clear-task could hide a stuck transition.
	if sh 'am start -W -n com.android.settings/.Settings >/dev/null' \
		&& bottomstrip_wait settings-main bottomstrip-home-relaunch;then ok 'bottomstrip home-relaunch: Settings regains focus after Home swipe'
	else bottomstrip_fail home-relaunch 'Settings did not regain focus after Home swipe';fi
}
# EXIT-safe state journal, separate from other app/sweep tests; no long fixed sleeps.
bottomstrip_end() {
	[ "${bottomstrip_active:-0}" = 1 ] || return 0
	local i restored=0 ui_fast=1 rotation=${bottomstrip_rotation:-0} auto=${bottomstrip_auto:-1}
	[ "$rotation" != null ] || rotation=0
	[ "$auto" != null ] || auto=1
	# Restore while a rotating activity is present; Launcher can keep NOSENSOR rotation.
	bottomstrip_launch com.android.settings/.Settings settings-main || bottomstrip_fail cleanup 'Settings unavailable while restoring orientation'
	sh "cmd window user-rotation lock $rotation"
	case "$rotation" in
		0|2) bottomstrip_screen bottomstrip-cleanup-rotation 480 640 ;;
		1|3) bottomstrip_screen bottomstrip-cleanup-rotation 640 480 ;;
	esac || bottomstrip_fail cleanup 'original orientation did not settle'
	lset wheel_app_modes "$bottomstrip_modes";lset wheel_app_tuning "$bottomstrip_tuning"
	if [ "$auto" = 1 ];then sh 'cmd window user-rotation free';else sh "cmd window user-rotation lock $rotation";fi
	for i in 1 2 3 4 5 6 7 8;do
		sweep_restore system user_rotation "$bottomstrip_rotation"
		sweep_restore system accelerometer_rotation "$bottomstrip_auto"
		if [ "$(dev 'settings get system user_rotation')" = "$bottomstrip_rotation" ] \
			&& [ "$(dev 'settings get system accelerometer_rotation')" = "$bottomstrip_auto" ];then restored=1;break;fi
		sleep .2
	done
	[ "$restored" = 1 ] && [ "$(lget wheel_app_modes)" = "$bottomstrip_modes" ] \
		&& [ "$(lget wheel_app_tuning)" = "$bottomstrip_tuning" ] || bottomstrip_fail cleanup 'rotation or app modes/tuning not restored'
	key 3
	bottomstrip_wait home bottomstrip-cleanup || bottomstrip_fail cleanup 'Launcher not in front after cleanup'
	bottomstrip_active=0
}
t_bottomstrip() {
	host_only || return
	local ui_fast=1
	bottomstrip_modes=$(lget wheel_app_modes);bottomstrip_tuning=$(lget wheel_app_tuning)
	bottomstrip_auto=$(dev 'settings get system accelerometer_rotation')
	bottomstrip_rotation=$(dev 'settings get system user_rotation')
	case "$bottomstrip_auto:$bottomstrip_rotation" in
		0:0|0:1|0:2|0:3|0:null|1:0|1:1|1:2|1:3|1:null|null:0|null:1|null:2|null:3|null:null) ;;
		*) bottomstrip_fail cleanup 'TEST setup: cannot snapshot original rotation';return 1 ;;
	esac
	bottomstrip_active=1
	lset wheel_app_modes 'com.android.settings=app,cat.kenny.r1.wheel.settings=app,cat.kenny.r1.toolbox=app'
	lset wheel_app_tuning ''
	if turn 0;then
		bottomstrip_settings_step settings-portrait 480 640
		bottomstrip_practice_step
		bottomstrip_toolbox_step
		bottomstrip_swipe_step
	else
		bottomstrip_fail settings-portrait 'could not lock portrait'
		echo 'SKIP bottomstrip practice-centre/practice-bottom: portrait setup failed'
		echo 'SKIP bottomstrip toolbox-settings: portrait setup failed'
		echo 'SKIP bottomstrip home-swipe/home-relaunch: portrait setup failed'
	fi
	if turn 1;then bottomstrip_settings_step settings-landscape 640 480
	else bottomstrip_fail settings-landscape 'could not lock landscape rotation1';fi
	bottomstrip_end
}

tests=${*:-tour boot dialog pin shade settings settingssearch bottomstrip home power_volume touch pinch homepage overlay slider zones reach allapps stock compose appinfo rotation edge turned openwith ime kbheight powerhold subpage firststop landshade landpower noring outslider choicelist title appdefaults toolhold tooltouch synthmodes walkieset toolboxset walkielatch tunnelslatch overlaylook batterypage reportcontents smallheaders stresscancel synthloop routerset portset phoneset webdeskset usbemuset tunnels tunnelsfirstpress routername wifidialog plaintext dialpad timepicker offcamera alongrow gallery toolbar applist overlist actionbar knobs folder dial passthrough homemenu readorder emptyfirst recentsfirst filesfocus brightgrab calcrows composerows updaterrow viewerbar calgrid dialkeyring drawermenu sharesheet rotlock imeland wheelsetfirst synthland walkieland routerinset dialland etartitle composefirst listadjust boxstep scrollsight triple wakedouble natural apptune shortlist wheeltune wheelsens locktap volte walkiemessages webdeskstart routerrefresh toolboxextra portsheets phonekeys usbshortcuts wheelpractice wheellook wheelparts tourskip wheellatch}
# The screen stays on through the run, as the lock screen's 10 s timeout would sleep it between a
# test's keys and dumps; the user's setting comes back after
stay=$(dev "settings get global stay_on_while_plugged_in")
trap 'runner_cleanup $?' EXIT
# Killed, as by timeout, it puts it back too
trap 'exit 143' TERM INT HUP
crash_init
sh "svc power stayon true"
# The self test's runner (r1-selftest.sh) follows the run in R1_STATUS, and stops it between
# tests when the page asks with the run's token
n=$(echo $tests | wc -w); i=0
progress() {
	[ -n "${R1_STATUS:-}" ] || return 0
	if [ "$(getprop sys.r1.selftest.cancel)" = "${R1_TOKEN:-}" ]; then
		echo "== cancelled"
		exit 3
	fi
	echo "running $1 $n $2" > $R1_STATUS
}
# A test that ended on a touch leaves the windows in touch mode, where the wheel's first step only
# brings the highlight back: each test starts from the wheel, so its steps count as written
# Tour's isolated Skip test must launch its own activity, not trigger Home's first-run tour.
untouch() { key 224; sh 'wm dismiss-keyguard'; [ "${1:-}" = tourskip ] && return; key 3; wheel down 1 >/dev/null; sleep 0.3; }
# The brightness the run found: a test's tap or drag in the shade can land on its slider, and the
# screen then stayed at full brightness for hours. Put back after every test and at the end
run_bright=$(dev "settings get system screen_brightness"); run_bmode=$(dev "settings get system screen_brightness_mode")
bright_back() {
	case "$run_bright" in ''|*[!0-9]*) return ;; esac
	[ "$(dev "settings get system screen_brightness")" = "$run_bright" ] \
		|| { echo "brightness moved in ${cur:-?}, put back to $run_bright"; sh "settings put system screen_brightness $run_bright"; }
	case "$run_bmode" in 0|1) [ "$(dev "settings get system screen_brightness_mode")" = "$run_bmode" ] \
		|| sh "settings put system screen_brightness_mode $run_bmode" ;; esac
}
for t in $tests; do
	i=$((i + 1)); progress $i $t
	echo "== $t"
	untouch "$t"
	cur=$t; t_$t
	bright_back
done
# A test that failed runs once more at the end, its lines marked RETRY: one that passes then was
# caught by something passing, as the host's adb server restarted under it; one that fails again
# is broken. The first run's FAIL stays in the log either way
retry=1
if [ "${R1_SMOKE_RETRY:-1}" != 0 ]; then
for t in $(echo "$failed" | tr ' ' '\n' | awk 'NF && !seen[$0]++'); do
	progress $n $t
	echo "== retry $t"
	untouch "$t"
	cur=$t; t_$t
	bright_back
done

fi
