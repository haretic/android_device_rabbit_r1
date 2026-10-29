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
ui() { sh "uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml" > $SHOTS/smoke-$1.xml; }
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
failed=; retry=; cur=
dev() { sh "$*" | tr -d '\r'; }

t_boot() {
	[ "$(dev getprop sys.boot_completed)" = 1 ] && ok boot || bad boot
	dev getprop ro.build.date
	local crashes
	crashes=$(dev "logcat -b crash -d | grep -c FATAL")
	[ "${crashes:-0}" = 0 ] && ok "no crashes" || { bad "crashes: $crashes"; dev "logcat -b crash -d | grep -A3 FATAL | head -40"; }
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

t_dial() {
	# The combination dial: the PIN 3141 as 3 steps down, 1 up, 4 down, 1 up and a press. A wrong
	# one keeps the lock, a press before any turn hands over to the drum. The PIN goes after.
	local after unlock
	after=$(dev "settings get secure lock_screen_lock_after_timeout")
	unlock=$(lget wheel_unlock)
	lset wheel_unlock 1
	sh "locksettings set-pin 3141; settings put secure lock_screen_lock_after_timeout 0"
	# The bouncer, the wheel's dial in it
	key 223; sleep 3; key 224; sleep 2; key 82; sleep 2
	ui dial; shot dial
	grep -q 'wheel_pin_dial' $SHOTS/smoke-dial.xml && ok "the bouncer opens on the combination dial" \
		|| bad "no combination dial in the bouncer"
	# A wrong combination, 3 1 4 2
	wheel down 3 >/dev/null; wheel up 1 >/dev/null; wheel down 4 >/dev/null; wheel up 2 >/dev/null
	shot dial-turned; side_press; sleep 2.5
	locked && ok "a wrong combination keeps the lock" || bad "a wrong combination unlocked"
	# The right one
	wheel down 3 >/dev/null; wheel up 1 >/dev/null; wheel down 4 >/dev/null; wheel up 1 >/dev/null
	ui dial-right
	side_press; sleep 2.5
	if locked; then
		# What the bouncer showed and said, for a failure seen only now and then in a full run
		bad "the combination 3 1 4 1 left the lock up (see $SHOTS/smoke-dial-right.xml, dial-fail.log)"
		dev "logcat -d -t 300" | grep -iE 'keyguard|bouncer|wheel|lockout|LockSettings' \
			> $SHOTS/dial-fail.log
	else
		ok "the combination unlocks"
	fi
	# A press before turning: the drum
	key 223; sleep 3; key 224; sleep 2; key 82; sleep 2
	side_press; sleep 1.5; ui dial-drum; shot dial-drum
	grep -q 'wheel_pin_drum' $SHOTS/smoke-dial-drum.xml && ! grep -q 'wheel_pin_dial' $SHOTS/smoke-dial-drum.xml \
		&& ok "a press before any turn picks the digits on the drum" || bad "a press before any turn kept the dial"
	# Out of the bouncer, the PIN gone, and the lock screen with nothing to unlock dismissed:
	# the drum takes Enter as the wheel's press, so typing the PIN in it doesn't unlock
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
	lset wheel_app_modes org.lineageos.aperture=volume
	key 224
	sh "monkey -p org.lineageos.aperture -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"; sleep 2
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
	local saved
	saved=$(lget wheel_app_modes)
	lset wheel_app_modes com.android.settings=touch:500:300:500:800
	key 224
	sh "am start -W -n com.android.settings/.Settings"; sleep 2
	# The touches r1-wheel-touch sends for one step down, read while the step goes in
	sh "getevent -c 12 /dev/input/event6 > /sdcard/touch.txt 2>&1 & sleep 0.5; $(step_cmd down); sleep 1; kill %1 2>/dev/null"
	local n
	n=$(dev "grep -c . /sdcard/touch.txt")
	[ "${n:-0}" -gt 4 ] && ok "touch zone tapped ($n events)" || bad "touch zone tapped ($n events)"
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
	sh "setprop log.tag.PerfOverlay DEBUG; logcat -c"
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
	sh "am start -W -n cat.kenny.r1.batterydetails/.BatteryDetailsActivity >/dev/null"; sleep 2
	wheel down 10; sleep 1; shot reach; ui reach
	local cls; cls=$(focus_field reach 4)
	echo "focused: ${cls:-nothing} $(focus_field reach 5)"
	case "$cls" in
		""|*RecyclerView) bad "wheel stuck on the list" ;;
		*) ok "wheel reaches rows below the screen" ;;
	esac
	key 4; key 3
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
	[ "$(dev "logcat -b crash -d | grep -c FATAL")" = 0 ] && ok "no crashes switching" || bad "crash switching"
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
	sh "input keyevent 224; wm dismiss-keyguard; am start -W -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:org.lineageos.aperture >/dev/null"; sleep 2
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
else
	side_press() { sh "sendevent /dev/input/event0 1 116 1; sendevent /dev/input/event0 0 0 0; sendevent /dev/input/event0 1 116 0; sendevent /dev/input/event0 0 0 0"; }
	side_hold() { sh "sendevent /dev/input/event0 1 116 1; sendevent /dev/input/event0 0 0 0; sleep 1.2; sendevent /dev/input/event0 1 116 0; sendevent /dev/input/event0 0 0 0"; }
	# Each press one write (down, up): an exec costs ~80 ms on the device, and the policy counts
	# a double press only if the second down comes within 300 ms of the first
	SIDE_TAP=AAAAAAAAAAAAAAAAAAAAAAEAdAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEAdAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
	side_double() { sh "t=/data/local/tmp/side-tap.bin; [ -s \$t ] || echo $SIDE_TAP | base64 -d > \$t; cat \$t > /dev/input/event0; sleep 0.1; cat \$t > /dev/input/event0"; }
fi
focus_win() { dev "dumpsys window | grep -m1 mCurrentFocus"; }
t_tour() {
	# Home opens the tour the first time; each page waits for its control
	sh "settings delete secure r1_wheel_tour_done >/dev/null; am force-stop com.android.launcher3; input keyevent 224; wm dismiss-keyguard; input keyevent 3"; sleep 3
	focus_win | grep -q TourActivity && ok "home opens the tour" || { bad "no tour ($(focus_win))"; sh "settings put secure r1_wheel_tour_done 1"; return; }
	shot tour-1
	side_press; sleep 1.5; shot tour-2
	wheel down 4; sleep 1.5; shot tour-3
	side_press; sleep 1.8; shot tour-4
	side_hold; sleep 1.8; shot tour-5
	focus_win | grep -q TourActivity && ok "tour still up at the taskbar page" || bad "hold left the tour"
	# The double press opens the taskbar; the hold shuts it, the tour moves on
	side_double; sleep 1; focus_win | grep -q WheelTaskbar && tb=1 || tb=0; shot tour-taskbar
	[ $tb = 1 ] && ok "double press opens the taskbar over the tour" || bad "no taskbar ($(focus_win))"
	# A second double press shuts it
	side_double; sleep 2; shot tour-6
	focus_win | grep -q WheelTaskbar && bad "second double press left the taskbar open" || ok "second double press shuts the taskbar"
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
		sh "settings put system accelerometer_rotation 0; settings put system user_rotation $r"; sleep 2
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
	sh "settings put system user_rotation ${user:-0}; settings put system accelerometer_rotation ${auto:-1}"
	key 4; key 3
}

# The centre of the node named $2 in dump $1, as "x y"
centre_of() { grep -o "content-desc=\"$2\"[^>]*bounds=\"[^\"]*\"" $SHOTS/smoke-$1.xml | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' | awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'; }
t_edge() {
	# The taskbar hugs the edge the wheel is on as the screen turns: the top a quarter turn
	# left, the bottom a quarter turn right, its buttons along it in a row
	local auto user edge r home set hx hy sx sy
	auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	edge=$(lget wheel_taskbar_edge)
	lset wheel_taskbar_edge 0
	# Open through the view dump, which takes longer than the bar's 3 s
	local timeout; timeout=$(lget wheel_taskbar_timeout)
	lset wheel_taskbar_timeout 0
	for r in 1 3; do
		sh "settings put system accelerometer_rotation 0; settings put system user_rotation $r"; sleep 2
		key 224; sh "wm dismiss-keyguard"
		sh "am start -W -S -a android.settings.SETTINGS >/dev/null"; sleep 2
		side_double; sleep 1.5; ui edge$r; shot edge$r
		home=$(centre_of edge$r Home); set=$(centre_of edge$r "Taskbar settings")
		read hx hy <<<"$home"; read sx sy <<<"$set"
		if [ -z "$home" ] || [ -z "$set" ]; then
			bad "rotation $r: no taskbar buttons ($(focus_win))"
		elif [ $r = 1 ] && [ $hy -lt 160 ] && [ $sy -lt 160 ] && [ $sx -gt $hx ]; then
			ok "rotation 1: the taskbar along the top, beside the wheel"
		elif [ $r = 3 ] && [ $hy -gt 320 ] && [ $sy -gt 320 ] && [ $sx -gt $hx ]; then
			ok "rotation 3: the taskbar along the bottom, beside the wheel"
		else
			bad "rotation $r: taskbar Home at $home, its settings at $set"
		fi
		key 4; sleep 1
	done
	lset wheel_taskbar_edge "${edge:-0}"
	lset wheel_taskbar_timeout "${timeout:-3}"
	sh "settings put system user_rotation ${user:-0}; settings put system accelerometer_rotation ${auto:-1}"
	key 4; key 3
}
t_turned() {
	# Turned right, the wheel on the bottom, its way is turned round with the screen: a turn
	# towards the screen's start, up as the device stands, goes on down the list
	local auto user top=0 stuck=0 i f
	auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	sh "settings put system accelerometer_rotation 0; settings put system user_rotation 3"; sleep 2
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
	sh "settings put system user_rotation ${user:-0}; settings put system accelerometer_rotation ${auto:-1}"
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
	focus_win | grep -q ResolverActivity || { bad "no Open with ($(focus_win))"; sh "rm -f /sdcard/Download/r1-smoke-open.apk"; key 3; return; }
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
	lset wheel_app_modes "$saved"
	key 4; key 4; key 3
	# Nor in the taskbar mode, where the browser opens as if touched
	sh "am start -W -S -n org.lineageos.jelly/.MainActivity >/dev/null 2>&1"; sleep 3
	dev "dumpsys input_method" | grep -q 'mInputShown=true' && bad "taskbar-mode browser opened with the keyboard up" \
		|| ok "taskbar-mode browser opens without the keyboard"
	key 4; key 3
}

t_subpage() {
	# A page opened inside the window, as Wheel settings' subpages are, starts on the page too:
	# never on its back arrow
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 2 >/dev/null; sleep 0.5; side_press; sleep 2; ui subpage
	local f; f=$(focused_in subpage)
	case "$f" in
		ImageButton*|"") bad "subpage opened on '${f:-nothing}'" ;;
		*) ok "subpage opens on its first row ($f)" ;;
	esac
	key 4; key 4
}

t_firststop() {
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
	sh "settings put system accelerometer_rotation 0; settings put system user_rotation 1"; sleep 2
	key 224; sh "wm dismiss-keyguard; am start -W -S -a android.settings.DISPLAY_SETTINGS >/dev/null"; sleep 2
	sh "cmd statusbar expand-notifications"; sleep 1.5; ui landshade; shot landshade
	if grep -q 'text="Display brightness"' $SHOTS/smoke-landshade.xml && grep -q 'USB debugging' $SHOTS/smoke-landshade.xml; then
		bad "turned shade split: controls beside the notifications"
	else
		ok "turned shade is one column"
	fi
	sh "cmd statusbar collapse; settings put system user_rotation ${user:-0}; settings put system accelerometer_rotation ${auto:-1}"
	key 3
}

t_landpower() {
	# Turned, the power menu is the wheel's list as upright: one action a row, Restart naming
	# its targets, not the stock grid of round buttons
	local auto user; auto=$(dev "settings get system accelerometer_rotation"); user=$(dev "settings get system user_rotation")
	sh "settings put system accelerometer_rotation 0; settings put system user_rotation 1"; sleep 2
	key 224; sh "wm dismiss-keyguard; am start -W -S -a android.settings.DISPLAY_SETTINGS >/dev/null"; sleep 2
	sh "service call window 119 >/dev/null"; sleep 2; ui landpower; shot landpower
	grep -q 'Recovery' $SHOTS/smoke-landpower.xml && ok "turned power menu is the list" \
		|| bad "turned power menu is the grid"
	key 4; sleep 1
	sh "settings put system user_rotation ${user:-0}; settings put system accelerometer_rotation ${auto:-1}"
	key 3
}

t_noring() {
	# An app the wheel opens the taskbar in shows no focus ring: the wheel can't move it there.
	# The wheel used on home first, as a wheel user comes to an app
	key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.5
	# Contacts: no wheel mode of its own, its notification prompt answered so it opens on its page
	sh "pm grant com.android.contacts android.permission.POST_NOTIFICATIONS 2>/dev/null; am start -W -S -n com.android.contacts/.activities.PeopleActivity >/dev/null"; sleep 2.5; shot noring
	dev "dumpsys window" | grep -m1 -o 'mInTouchMode=[a-z]*' | grep -q true \
		&& ok "taskbar-mode app opens without a focus ring" || bad "taskbar-mode app shows a focus ring"
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
	# A dialog's list of choices keeps the focus itself and the wheel moves its selection: the
	# ring shows the row, so a step changes the picture
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 4 >/dev/null; sleep 0.6; side_press; sleep 2
	wheel down 2 >/dev/null; sleep 0.5; side_press; sleep 1.5; shot choice0; rawshot choice0
	wheel down 1 >/dev/null; sleep 0.8; shot choice1; rawshot choice1
	# Under the status bar, whose clock may tick
	shots_differ choice0 choice1 0 60 10000 10000 && ok "a wheel step shows in a dialog's list of choices" \
		|| bad "a wheel step in a dialog's list of choices shows nothing"
	key 4; sleep 0.5; key 4; key 4
	# Opened, such a list starts on what is set: the side button's hold, Back by default
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 2 >/dev/null; sleep 0.6; side_press; sleep 2
	wheel down 1 >/dev/null; sleep 0.5; side_press; sleep 1.5; ui choiceset
	local sel; sel=$(grep -o '<node [^>]*selected="true"[^>]*>' $SHOTS/smoke-choiceset.xml | grep -o 'text="[^"]*"' | head -1)
	local set; set=$(grep -o '<node [^>]*checked="true"[^>]*>' $SHOTS/smoke-choiceset.xml | grep -o 'text="[^"]*"' | head -1)
	[ -n "$set" ] && [ "$sel" = "$set" ] && ok "a list of choices opens on the one set ($set)" \
		|| bad "a list of choices opened on ${sel:-nothing}, not on ${set:-?}"
	key 4; sleep 0.5; key 4; key 4
}

t_title() {
	# Back from a page opened in place, the title is the page's again, not the one left
	sh "am start -W -S -n cat.kenny.r1.wheel.settings/.WheelActivity >/dev/null"; sleep 2
	wheel down 3 >/dev/null; sleep 0.6; side_press; sleep 2; shot title0; rawshot title0
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
	key 4; key 3
}

t_offcamera() {
	# From the screen off, the side button's double press opens the camera, as a phone's does
	key 223; sleep 2
	side_double; sleep 3; shot offcamera
	dev "dumpsys window" | grep -m1 'mCurrentFocus' | grep -q aperture \
		&& ok "double press from the screen off opens the camera" \
		|| bad "double press from the screen off left the camera shut"
	key 4; sleep 1; key 224; sh "wm dismiss-keyguard"; key 3
}

t_readorder() {
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
	# An empty list is no first stop: the window's first focus goes on to the button after it,
	# Messaging's Start chat, Dialer's Add favourite
	local app f
	for app in com.android.messaging/.ui.conversationlist.ConversationListActivity \
			com.android.dialer/.main.impl.MainActivity; do
		key 3; sleep 1; wheel down 1 >/dev/null; sleep 0.4
		sh "am start -W -S -n $app >/dev/null 2>&1"; sleep 4
		ui emptyfirst; f=$(focused_in emptyfirst)
		case "$f" in
			Button*) ok "${app%%/*}: the first focus is a button, not the empty list ($f)" ;;
			*) shot emptyfirst; bad "${app%%/*}: the first focus is '${f:-nothing}', not a button" ;;
		esac
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
		bad "the grid home has no Messaging and Telephone to make a folder of"
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
			*) bad "Gallery's step $((i + 1)) left the photos for '${f:-nothing}'"; key 4; key 3; return ;;
		esac
		h=$(echo "$f" | awk '{print $5 - $3}')
		if [ -n "$tall" ] && [ "$h" -lt $((tall - 4)) ]; then
			shot gallery-cut; bad "Gallery's step $((i + 1)) left its photo partly out of sight ($f)"; key 4; key 3; return
		fi
	done
	ok "Gallery's sixteen steps all land on photos in sight ($f)"
	key 4; key 3
}

tests=${*:-tour boot dialog pin shade settings home power_volume touch pinch homepage overlay slider zones reach allapps stock compose appinfo rotation edge turned openwith ime subpage firststop landshade landpower noring outslider choicelist title appdefaults offcamera alongrow gallery toolbar applist overlist actionbar knobs folder dial passthrough homemenu readorder emptyfirst composefirst}
# The screen stays on through the run, as the lock screen's 10 s timeout would sleep it between a
# test's keys and dumps; the user's setting comes back after
stay=$(dev "settings get global stay_on_while_plugged_in")
trap 'sh "settings put global stay_on_while_plugged_in ${stay:-0}"' EXIT
# Killed, as by timeout, it puts it back too
trap 'exit 143' TERM INT HUP
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
for t in $tests; do
	i=$((i + 1)); progress $i $t
	echo "== $t"
	cur=$t; t_$t
done
# A test that failed runs once more at the end, its lines marked RETRY: one that passes then was
# caught by something passing, as the host's adb server restarted under it; one that fails again
# is broken. The first run's FAIL stays in the log either way
retry=1
for t in $(echo "$failed" | tr ' ' '\n' | awk 'NF && !seen[$0]++'); do
	progress $n $t
	echo "== retry $t"
	t_$t
done
