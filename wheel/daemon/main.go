// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0

// r1-wheeld owns the rabbit r1 scroll wheel. It grabs the och1970-wheel input device, so
// nothing else sees its steps, and sends every step on through its own uinput device,
// r1-wheel, as the keys the current mode calls for (keylayout/r1-wheel.kl), or as smooth
// scrolling through a second one, r1-wheel-touch:
//
//	taskbar     KEY_SCROLLUP / KEY_SCROLLDOWN: NAVIGATE_PREVIOUS / NEXT. The window policy
//	            gives them to SystemUI's wheel taskbar while an app is in front; a system
//	            window (a menu, the shade, the taskbar) gets them, and r1-wheel.kcm turns
//	            them into D-pad steps there.
//	app         KEY_UP / KEY_DOWN: DPAD_UP / DOWN, the app moves its focus.
//	scroll      the content scrolls as under a finger: r1-wheel-touch, a touchscreen as
//	            big as the real one, drags in the middle of the screen, each step glided
//	            out over a few frames and further the faster the wheel spins, and lets go
//	            standing still, so nothing flings. Every app scrolls with it; a rotary
//	            encoder's scrolling only reaches a focused view, which touch mode has none.
//	tab         Shift+Tab / Tab: through links and fields.
//	arrows      KEY_LEFT / KEY_RIGHT: seeking, page turns, carousels.
//	page        KEY_PAGEUP / KEY_PAGEDOWN.
//	volume      KEY_VOLUMEUP / KEY_VOLUMEDOWN.
//	brightness  KEY_BRIGHTNESSUP / KEY_BRIGHTNESSDOWN.
//	media       KEY_PREVIOUSSONG / KEY_NEXTSONG.
//	zoom        Ctrl+= / Ctrl+-.
//	camera      KEY_ZOOMIN / KEY_ZOOMOUT, which camera apps zoom with (the press, SystemUI's,
//	            is the camera key).
//	touch:X1:Y1:X2:Y2
//	            touch zones: a step up taps the screen at (X1, Y1) through r1-wheel-touch,
//	            a step down at (X2, Y2), in thousandths of the screen's width and height,
//	            for games and apps that only take touches.
//
// A press of the side button tends to nudge the wheel: steps while it is held and just after
// it is let go are dropped, so a press doesn't step the focus off what it was meant for.
//
// SystemUI sets the mode over the init socket /dev/socket/r1-wheel, one line per change:
// "mode taskbar", "mode scroll" and so on. Without a client the mode is taskbar. If the
// daemon stops, the grab goes with it and the wheel's own D-pad keys reach Android again.
//
// SystemUI also passes on the user's tuning, "tune <name> <value>":
//
//	distance  how far a scroll step drags, in percent of the default (25 to 400)
//	spin      how much a fast spin speeds scrolling up: 0 not at all, 1 the default, 2 more
//	guard     how long after the side button is let go its nudges are dropped, in ms (0 to 500);
//	          0 keeps every step, while the button is held too
//	reverse   1 turns the wheel's direction round
//
// The self test (selftest/, as the shell user) turns the wheel over the socket too,
// "inject <up|down> <steps>": each step goes the way a turn of the wheel's own does, the
// mode's, as the shell may not write the wheel's input device. "inject side <press|hold|
// double>" presses the side button through r1-side, a uinput device made for it at the
// first, its KEY_POWER as the side button's own (Generic.kl). "r1-wheeld inject down 3"
// sends a line.

// One thread is plenty, whatever the cgroup CPU quota says
//
//go:debug containermaxprocs=0
//go:debug updatemaxprocs=0
package main

import (
	"bufio"
	"fmt"
	"math"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"
)

const (
	wheelName = "och1970-wheel"
	outName   = "r1-wheel"

	touchName = "r1-wheel-touch"
	sideName  = "r1-side"

	evSyn = 0x00
	evKey = 0x01
	evAbs = 0x03

	absX              = 0x00
	absY              = 0x01
	absMtSlot         = 0x2f
	absMtPositionX    = 0x35
	absMtPositionY    = 0x36
	absMtTrackingID   = 0x39
	btnTouch          = 0x14a
	inputPropDirect   = 0x01
	touchscreenWidth  = 480 // without a touchscreen to copy
	touchscreenHeight = 640

	keyMinus          = 12
	keyEqual          = 13
	keyTab            = 15
	keyLeftCtrl       = 29
	keyLeftShift      = 42
	keyUp             = 103
	keyPageUp         = 104
	keyLeft           = 105
	keyRight          = 106
	keyDown           = 108
	keyPageDown       = 109
	keyVolumeDown     = 114
	keyVolumeUp       = 115
	keyPower          = 116
	keyNextSong       = 163
	keyPreviousSong   = 165
	keyScrollUp       = 177
	keyScrollDown     = 178
	keyBrightnessDown = 224
	keyBrightnessUp   = 225
	keySelect         = 353
	keyZoomIn         = 418
	keyZoomOut        = 419
	keyMax            = 0x2ff
	absMax            = 0x3f

	busVirtual = 0x06
	// Apart, or the input reader takes both devices for one and the finger for a touchpad
	productKeys  = 0x0001
	productTouch = 0x0002
	productSide  = 0x0003

	// Wheel steps this soon after the side button is let go are its nudge
	buttonSettle = 150 * time.Millisecond

	// A scroll step drags this share of the screen's height, gliding out over frames of
	// glideFrame, each taking glideShare of what is left: quick at first, easing out
	detentShare = 0.11
	glideFrame  = 8 * time.Millisecond
	glideShare  = 0.3
	// The finger lets go once it stood still this long, and takes a new grip before it
	// would leave the middle of the screen
	liftAfter       = 90 * time.Millisecond
	stillBeforeLift = 50 * time.Millisecond
	gripTop         = 0.15
	gripBottom      = 0.85
	// A pinch step zooms by this factor's log (1.15 times), gliding out as a scroll step;
	// its two fingers stand on the screen's long middle line, this share of its length
	// apart at the closest and the widest, and first move past the span's slop (Android's
	// is twice the touch slop) before the zoom counts
	pinchShare   = 0.14
	pinchClosest = 0.12
	pinchWidest  = 0.8
	pinchSlop    = 3 * touchSlop
	// A touch zone's tap: down this long, short of Android's long press
	tapTime = 40 * time.Millisecond
	// Android's touch slop, about 8 dp: the first part of a drag that only starts it
	touchSlop = 10
	// A spin's speed: its steps over this window. From spinRate steps a second on, each
	// step scrolls one more detent each spinRateStep, up to maxDetents.
	spinWindow   = 250 * time.Millisecond
	spinRate     = 10
	spinRateStep = 8
	maxDetents   = 4
)

// ioctl request numbers, asm-generic layout: dir(2) size(14) type(8) nr(8)
func ioc(dir, typ, nr, size uintptr) uintptr {
	return dir<<30 | size<<16 | typ<<8 | nr
}

var (
	eviocgrab    = ioc(1, 'E', 0x90, 4)
	uiSetEvbit   = ioc(1, 'U', 100, 4)
	uiSetKeybit  = ioc(1, 'U', 101, 4)
	uiSetAbsbit  = ioc(1, 'U', 103, 4)
	uiSetPropbit = ioc(1, 'U', 110, 4)
	uiAbsSetup   = ioc(1, 'U', 4, unsafe.Sizeof(uinputAbsSetup{}))
	uiDevSetup   = ioc(1, 'U', 3, unsafe.Sizeof(uinputSetup{}))
	uiDevCreate  = ioc(0, 'U', 1, 0)
	uiDevDestroy = ioc(0, 'U', 2, 0)
)

func eviocgname(n uintptr) uintptr { return ioc(2, 'E', 0x06, n) }

// EVIOCGBIT(ev, n): the codes a device has for event type ev
func eviocgbit(ev, n uintptr) uintptr { return ioc(2, 'E', 0x20+ev, n) }

// struct input_event on a 64-bit kernel
type inputEvent struct {
	Sec, Usec int64
	Type      uint16
	Code      uint16
	Value     int32
}

// struct input_absinfo
type absInfo struct {
	Value, Minimum, Maximum, Fuzz, Flat, Resolution int32
}

// struct uinput_abs_setup
type uinputAbsSetup struct {
	Code uint16
	_    uint16
	Info absInfo
}

// EVIOCGABS(abs): a device's range of an axis
func eviocgabs(abs uintptr) uintptr { return ioc(2, 'E', 0x40+abs, unsafe.Sizeof(absInfo{})) }

// struct uinput_setup
type uinputSetup struct {
	Bustype, Vendor, Product, Version uint16
	Name                              [80]byte
	FfEffectsMax                      uint32
}

func ioctl(fd int, req, arg uintptr) error {
	if _, _, e := syscall.Syscall(syscall.SYS_IOCTL, uintptr(fd), req, arg); e != 0 {
		return e
	}
	return nil
}

type mode int

const (
	modeTaskbar mode = iota
	modeApp
	modeScroll
	modeTab
	modeArrows
	modePage
	modeVolume
	modeBrightness
	modeMedia
	modeZoom
	modeCamera
	modeTouch
	modePinch
)

var modeNames = map[string]mode{
	"taskbar": modeTaskbar, "app": modeApp, "scroll": modeScroll, "tab": modeTab,
	"arrows": modeArrows, "page": modePage, "volume": modeVolume,
	"brightness": modeBrightness, "media": modeMedia, "zoom": modeZoom, "pinch": modePinch,
	"camera": modeCamera,
}

// chord is a key with the modifiers held around it.
type chord struct {
	mods []uint16
	key  uint16
}

// The keys each key mode sends for a step up and a step down; scroll has none.
var modeKeys = map[mode][2]chord{
	modeTaskbar:    {{key: keyScrollUp}, {key: keyScrollDown}},
	modeApp:        {{key: keyUp}, {key: keyDown}},
	modeTab:        {{mods: []uint16{keyLeftShift}, key: keyTab}, {key: keyTab}},
	modeArrows:     {{key: keyLeft}, {key: keyRight}},
	modePage:       {{key: keyPageUp}, {key: keyPageDown}},
	modeVolume:     {{key: keyVolumeUp}, {key: keyVolumeDown}},
	modeBrightness: {{key: keyBrightnessUp}, {key: keyBrightnessDown}},
	modeMedia:      {{key: keyPreviousSong}, {key: keyNextSong}},
	modeZoom: {{mods: []uint16{keyLeftCtrl}, key: keyEqual},
		{mods: []uint16{keyLeftCtrl}, key: keyMinus}},
	modeCamera: {{key: keyZoomIn}, {key: keyZoomOut}},
}

// Every key r1-wheel can send. Select it never sends: with it r1-wheel has the D-pad's five keys,
// so Android takes it for a D-pad and its steps move the focus out of text as a remote's do; a
// keyboard's arrows stay in a text view (TextView keeps them from a keyboard-only source)
var outKeys = []uintptr{
	keyMinus, keyEqual, keyTab, keyLeftCtrl, keyLeftShift, keyUp, keyPageUp, keyLeft,
	keyRight, keyDown, keyPageDown, keyVolumeDown, keyVolumeUp, keyNextSong,
	keyPreviousSong, keyScrollUp, keyScrollDown, keyBrightnessDown, keyBrightnessUp, keySelect,
	keyZoomIn, keyZoomOut,
}

type daemon struct {
	mu    sync.Mutex
	mode  mode
	out   *os.File
	touch *os.File
	// The self test's side button, made at its first press
	side *os.File
	// The touchscreen's size in its own units
	width, height int32
	// Dragging still to glide out, in touch units (up positive), and whether a glide runs
	glide   float64
	gliding bool
	// The finger: down, where, the drag's tracking id, when it last moved
	down     bool
	fingerY  float64
	tracking int32
	moved    time.Time
	// The times of the spin's latest steps and its direction, for its speed
	steps     []time.Time
	direction int
	// The side button: held, or when it was let go
	button         bool
	buttonReleased time.Time
	// SystemUI's connections, told of each step an app gets so the wheel ticks there too
	clients map[net.Conn]bool
	tuning  tuning
	// The touch zones' points for a step up and down, in thousandths of the screen
	zones [2][2]int32
	// The tap's lift, pending while the finger is down for a zone
	lift *time.Timer
	// The pinch: the zoom still to glide out (log of its factor, out positive), its two
	// fingers down and how far apart, the slop still to move before the zoom counts
	pinch      float64
	pinching   bool
	pinchDown  bool
	span       float64
	spanSlop   float64
	pinchMoved time.Time
}

// tuning is the user's, from SystemUI's "tune" lines.
type tuning struct {
	distance float64 // times detentShare
	spin     int     // 0 none, 1 the default, 2 strong
	guard    time.Duration
	reverse  bool
}

var defaultTuning = tuning{distance: 1, spin: 1, guard: buttonSettle}

// tune sets one of the user's tunings; whether it knew the name and value.
func (d *daemon) tune(name, value string) bool {
	n, err := strconv.Atoi(value)
	if err != nil {
		return false
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	switch name {
	case "distance":
		d.tuning.distance = float64(max(25, min(400, n))) / 100
	case "spin":
		d.tuning.spin = max(0, min(2, n))
	case "guard":
		d.tuning.guard = time.Duration(max(0, min(500, n))) * time.Millisecond
	case "reverse":
		d.tuning.reverse = n != 0
	default:
		return false
	}
	return true
}

// notify tells the clients of a step outside the taskbar, whose own steps tick in SystemUI.
// Called with mu held; a client that can't take a line at once misses it.
func (d *daemon) notify(up bool) {
	line := []byte("step down\n")
	if up {
		line = []byte("step up\n")
	}
	for c := range d.clients {
		c.SetWriteDeadline(time.Now().Add(20 * time.Millisecond))
		c.Write(line)
	}
}

// setButton notes the side button going down (true) or up.
func (d *daemon) setButton(down bool) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.button = down
	if !down {
		d.buttonReleased = time.Now()
	}
}

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, "r1-wheeld: "+format+"\n", args...)
}

func (d *daemon) setMode(m mode) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.liftTap()
	d.liftPinch()
	d.mode = m
}

// liftPinch lets go of a pinch's fingers, what is left of its zoom dropped. Locked.
func (d *daemon) liftPinch() {
	d.pinch = 0
	if d.pinchDown {
		d.fingers(false)
	}
}

// fingers sends the pinch's two fingers: down span apart about the middle, or both up.
func (d *daemon) fingers(down bool) {
	x, y := d.width/2, float64(d.height)/2
	for i, dy := range []float64{-d.span / 2, d.span / 2} {
		write(d.touch, evAbs, absMtSlot, int32(i))
		if !down {
			write(d.touch, evAbs, absMtTrackingID, -1)
			continue
		}
		if !d.pinchDown {
			write(d.touch, evAbs, absMtTrackingID, d.tracking+int32(i))
		}
		write(d.touch, evAbs, absMtPositionX, x)
		write(d.touch, evAbs, absMtPositionY, int32(y+dy))
		if i == 0 {
			write(d.touch, evAbs, absX, x)
			write(d.touch, evAbs, absY, int32(y+dy))
		}
	}
	if down && !d.pinchDown {
		write(d.touch, evKey, btnTouch, 1)
	} else if !down {
		write(d.touch, evKey, btnTouch, 0)
		d.tracking += 2
	}
	write(d.touch, evSyn, 0, 0)
	d.pinchDown = down
}

// pinchStep adds a step's zoom to the pinch, starting it if it isn't running. Locked.
func (d *daemon) pinchStep(direction int) {
	d.pinch += float64(d.detents(direction)) * pinchShare * d.tuning.distance
	if !d.pinching {
		d.pinching = true
		go d.pinchOut()
	}
}

// pinchOut glides the pinch's zoom out a frame at a time as glideOut does a scroll's: two
// fingers down close together to zoom in, wide apart to zoom out, moving past the slop first;
// out of room they stand still and let go, and a new grip follows.
func (d *daemon) pinchOut() {
	closest := float64(d.height) * pinchClosest
	widest := float64(d.height) * pinchWidest
	for {
		time.Sleep(glideFrame)
		d.mu.Lock()
		if d.pinch == 0 {
			if d.pinchDown && time.Since(d.pinchMoved) >= liftAfter {
				d.fingers(false)
			}
			if !d.pinchDown {
				d.pinching = false
				d.mu.Unlock()
				return
			}
			d.mu.Unlock()
			continue
		}
		dir := 1.0
		if d.pinch < 0 {
			dir = -1
		}
		if !d.pinchDown {
			d.span = widest
			if dir > 0 {
				d.span = closest
			}
			d.spanSlop = pinchSlop
			d.fingers(true)
			d.pinchMoved = time.Now()
			d.mu.Unlock()
			continue
		}
		if d.spanSlop > 0 {
			move := math.Min(d.spanSlop, touchSlop)
			d.spanSlop -= move
			d.span += dir * move
			d.fingers(true)
			d.pinchMoved = time.Now()
			d.mu.Unlock()
			continue
		}
		part := d.pinch * glideShare
		if math.Abs(part) < 0.002 {
			part = d.pinch
		}
		next := d.span * math.Exp(part)
		if next < closest || next > widest {
			if time.Since(d.pinchMoved) >= stillBeforeLift {
				d.fingers(false)
			}
			d.mu.Unlock()
			continue
		}
		d.pinch -= part
		if math.Abs(d.pinch) < 0.001 {
			d.pinch = 0
		}
		d.span = next
		d.fingers(true)
		d.pinchMoved = time.Now()
		d.mu.Unlock()
	}
}

// liftTap lets go of a touch zone's tap still down. Locked.
func (d *daemon) liftTap() {
	if d.lift != nil && d.lift.Stop() && d.down {
		d.finger(false, 0, 0)
	}
}

// parseZones reads a touch mode's "touch:X1:Y1:X2:Y2", each 0 to 1000.
func parseZones(name string) ([2][2]int32, bool) {
	var zones [2][2]int32
	parts := strings.Split(name, ":")
	if len(parts) != 5 || parts[0] != "touch" {
		return zones, false
	}
	for i, part := range parts[1:] {
		n, err := strconv.Atoi(part)
		if err != nil || n < 0 || n > 1000 {
			return zones, false
		}
		zones[i/2][i%2] = int32(n)
	}
	return zones, true
}

func (d *daemon) setZones(zones [2][2]int32) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.liftTap()
	d.mode = modeTouch
	d.zones = zones
}

// tap taps the screen at a touch zone, 0 for a step up's, 1 for down's. Locked; a tap still
// down for the step before is let go first.
func (d *daemon) tap(zone int) {
	d.liftTap()
	x := int32(int64(d.zones[zone][0]) * int64(d.width-1) / 1000)
	y := int32(int64(d.zones[zone][1]) * int64(d.height-1) / 1000)
	d.finger(true, x, y)
	d.lift = time.AfterFunc(tapTime, func() {
		d.mu.Lock()
		defer d.mu.Unlock()
		if d.down && !d.gliding {
			d.finger(false, 0, 0)
		}
	})
}

func write(f *os.File, typ, code uint16, value int32) error {
	ev := inputEvent{Type: typ, Code: code, Value: value}
	buf := (*[unsafe.Sizeof(inputEvent{})]byte)(unsafe.Pointer(&ev))[:]
	_, err := f.Write(buf)
	return err
}

// key sends a whole press of a key, or of a chord: down, synced, then up.
func (d *daemon) key(c chord) {
	for _, m := range c.mods {
		write(d.out, evKey, m, 1)
	}
	if err := write(d.out, evKey, c.key, 1); err != nil {
		logf("uinput write: %v", err)
		return
	}
	write(d.out, evSyn, 0, 0)
	write(d.out, evKey, c.key, 0)
	for i := len(c.mods) - 1; i >= 0; i-- {
		write(d.out, evKey, c.mods[i], 0)
	}
	write(d.out, evSyn, 0, 0)
}

// detents is how far a step scrolls at the spin's speed: up 1, down -1. Locked.
func (d *daemon) detents(direction int) int {
	now := time.Now()
	if direction != d.direction {
		d.steps = d.steps[:0]
		d.direction = direction
	}
	d.steps = append(d.steps, now)
	recent := d.steps[:0]
	for _, t := range d.steps {
		if now.Sub(t) < spinWindow {
			recent = append(recent, t)
		}
	}
	d.steps = recent
	rate := int(float64(len(recent)) / spinWindow.Seconds())
	n := 1
	switch d.tuning.spin {
	case 1:
		if rate >= spinRate {
			n = min(maxDetents, 2+(rate-spinRate)/spinRateStep)
		}
	case 2:
		// Sooner, steeper and further
		if rate >= spinRate*3/4 {
			n = min(maxDetents*2, 2+(rate-spinRate*3/4)/(spinRateStep/2))
		}
	}
	return n * direction
}

// scrollStep adds a step's drag to the glide, starting it if it isn't running. Locked.
func (d *daemon) scrollStep(direction int) {
	d.glide += float64(d.detents(direction)) * detentShare * d.tuning.distance * float64(d.height)
	if !d.gliding {
		d.gliding = true
		go d.glideOut()
	}
}

// finger sends the finger's state: down at (x, y), or up.
func (d *daemon) finger(down bool, x, y int32) {
	if down {
		write(d.touch, evAbs, absMtSlot, 0)
		write(d.touch, evAbs, absMtTrackingID, d.tracking)
		write(d.touch, evAbs, absMtPositionX, x)
		write(d.touch, evAbs, absMtPositionY, y)
		write(d.touch, evAbs, absX, x)
		write(d.touch, evAbs, absY, y)
		if !d.down {
			write(d.touch, evKey, btnTouch, 1)
		}
	} else {
		write(d.touch, evAbs, absMtSlot, 0)
		write(d.touch, evAbs, absMtTrackingID, -1)
		write(d.touch, evKey, btnTouch, 0)
		d.tracking++
	}
	write(d.touch, evSyn, 0, 0)
	d.down = down
}

// glideOut drags the glide out a frame at a time, then lets go once the finger stood still.
func (d *daemon) glideOut() {
	x := d.width / 2
	for {
		time.Sleep(glideFrame)
		d.mu.Lock()
		if d.glide == 0 {
			if d.down && time.Since(d.moved) >= liftAfter {
				d.finger(false, 0, 0)
			}
			if !d.down {
				d.gliding = false
				d.mu.Unlock()
				return
			}
			d.mu.Unlock()
			continue
		}
		// Up: the content goes down, the finger with it
		dir := 1.0
		if d.glide < 0 {
			dir = -1
		}
		if !d.down {
			// From the side that leaves the drag the most room, past the slop
			d.fingerY = float64(d.height) * (0.5 - dir*0.2)
			d.finger(true, x, int32(d.fingerY))
			d.glide += dir * touchSlop
			d.moved = time.Now()
			d.mu.Unlock()
			continue
		}
		part := d.glide * glideShare
		if math.Abs(part) < 1 {
			part = d.glide
		}
		next := d.fingerY + part
		if next < float64(d.height)*gripTop || next > float64(d.height)*gripBottom {
			// Out of room: let go once standing still (Android takes a finger 40 ms
			// still as stopped, so the app doesn't fling), and a new grip follows
			if time.Since(d.moved) >= stillBeforeLift {
				d.finger(false, 0, 0)
			}
			d.mu.Unlock()
			continue
		}
		d.glide -= part
		if math.Abs(d.glide) < 0.5 {
			d.glide = 0
		}
		d.fingerY = next
		d.finger(true, x, int32(d.fingerY))
		d.moved = time.Now()
		d.mu.Unlock()
	}
}

// step turns a wheel step, a key event (value 1 press, 0 release, 2 repeat), into the mode's.
// The wheel sends each step as a press and its release at once: the press makes all of it.
func (d *daemon) step(code uint16, value int32) {
	if value != 1 {
		return
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.tuning.guard > 0 && (d.button || time.Since(d.buttonReleased) < d.tuning.guard) {
		// The side button's nudge
		return
	}
	up := (code == keyUp) != d.tuning.reverse
	if d.mode != modeTaskbar {
		d.notify(up)
	}
	if d.mode == modeTouch && d.touch != nil {
		if d.gliding {
			// The scroll mode's drag is still out; the next step taps
			return
		}
		if up {
			d.tap(0)
		} else {
			d.tap(1)
		}
		return
	}
	if d.mode == modePinch && d.touch != nil {
		if d.gliding {
			// The scroll mode's drag is still out
			return
		}
		if up {
			d.pinchStep(1)
		} else {
			d.pinchStep(-1)
		}
		return
	}
	if d.mode == modeScroll && d.touch != nil {
		if d.pinching {
			// A pinch's fingers are still down, as just after the mode changed
			return
		}
		if up {
			d.scrollStep(1)
		} else {
			d.scrollStep(-1)
		}
		return
	}
	keys, ok := modeKeys[d.mode]
	if !ok {
		keys = modeKeys[modeTaskbar]
	}
	if up {
		d.key(keys[0])
	} else {
		d.key(keys[1])
	}
}

// axis is a touchscreen axis to set up and its range.
type axis struct {
	code uintptr
	info absInfo
}

func createDevice(name string, product uint16, evbits, keys []uintptr, axes []axis, props []uintptr) (*os.File, error) {
	f, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	fd := int(f.Fd())
	fail := func(what string, err error) (*os.File, error) {
		f.Close()
		return nil, fmt.Errorf("%s %s: %w", name, what, err)
	}
	for _, ev := range evbits {
		if err := ioctl(fd, uiSetEvbit, ev); err != nil {
			return fail("UI_SET_EVBIT", err)
		}
	}
	for _, k := range keys {
		if err := ioctl(fd, uiSetKeybit, k); err != nil {
			return fail(fmt.Sprintf("UI_SET_KEYBIT %d", k), err)
		}
	}
	for _, a := range axes {
		if err := ioctl(fd, uiSetAbsbit, a.code); err != nil {
			return fail(fmt.Sprintf("UI_SET_ABSBIT %d", a.code), err)
		}
		setup := uinputAbsSetup{Code: uint16(a.code), Info: a.info}
		if err := ioctl(fd, uiAbsSetup, uintptr(unsafe.Pointer(&setup))); err != nil {
			return fail(fmt.Sprintf("UI_ABS_SETUP %d", a.code), err)
		}
	}
	for _, p := range props {
		if err := ioctl(fd, uiSetPropbit, p); err != nil {
			return fail(fmt.Sprintf("UI_SET_PROPBIT %d", p), err)
		}
	}
	setup := uinputSetup{Bustype: busVirtual, Product: product, Version: 1}
	copy(setup.Name[:], name)
	if err := ioctl(fd, uiDevSetup, uintptr(unsafe.Pointer(&setup))); err != nil {
		return fail("UI_DEV_SETUP", err)
	}
	if err := ioctl(fd, uiDevCreate, 0); err != nil {
		return fail("UI_DEV_CREATE", err)
	}
	return f, nil
}

// openWheel finds the wheel among /dev/input/event* by name and grabs it.
func openWheel() (*os.File, error) {
	paths, _ := filepath.Glob("/dev/input/event*")
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		var name [64]byte
		if ioctl(int(f.Fd()), eviocgname(uintptr(len(name))), uintptr(unsafe.Pointer(&name[0]))) == nil &&
			strings.TrimRight(string(name[:]), "\x00") == wheelName {
			if err := ioctl(int(f.Fd()), eviocgrab, 1); err != nil {
				f.Close()
				return nil, fmt.Errorf("EVIOCGRAB %s: %w", path, err)
			}
			return f, nil
		}
		f.Close()
	}
	return nil, fmt.Errorf("no %s input device", wheelName)
}

// touchscreenSize is the real touchscreen's range, which r1-wheel-touch copies so the
// display maps both alike.
func touchscreenSize() (int32, int32) {
	paths, _ := filepath.Glob("/dev/input/event*")
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		var x, y absInfo
		var name [64]byte
		ioctl(int(f.Fd()), eviocgname(uintptr(len(name))), uintptr(unsafe.Pointer(&name[0])))
		ok := strings.TrimRight(string(name[:]), "\x00") != touchName &&
			ioctl(int(f.Fd()), eviocgabs(absMtPositionX), uintptr(unsafe.Pointer(&x))) == nil &&
			ioctl(int(f.Fd()), eviocgabs(absMtPositionY), uintptr(unsafe.Pointer(&y))) == nil &&
			x.Maximum > 0 && y.Maximum > 0
		f.Close()
		if ok {
			return x.Maximum + 1, y.Maximum + 1
		}
	}
	return touchscreenWidth, touchscreenHeight
}

// buttonPaths lists the devices with KEY_POWER: the side button may come from more than one
// (the PMIC's power key and the keypad both have it).
func buttonPaths() []string {
	var found []string
	paths, _ := filepath.Glob("/dev/input/event*")
	for _, path := range paths {
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		var bits [keyMax/8 + 1]byte
		var name [64]byte
		if ioctl(int(f.Fd()), eviocgbit(evKey, uintptr(len(bits))), uintptr(unsafe.Pointer(&bits[0]))) == nil &&
			bits[keyPower/8]&(1<<(keyPower%8)) != 0 &&
			ioctl(int(f.Fd()), eviocgname(uintptr(len(name))), uintptr(unsafe.Pointer(&name[0]))) == nil &&
			strings.TrimRight(string(name[:]), "\x00") != outName {
			found = append(found, path)
		}
		f.Close()
	}
	return found
}

// watchButton follows a side button device, read and not grabbed, so Android still gets
// every press; reopened if it goes away.
func (d *daemon) watchButton(path string) {
	var ev inputEvent
	buf := (*[unsafe.Sizeof(inputEvent{})]byte)(unsafe.Pointer(&ev))[:]
	for {
		button, err := os.Open(path)
		if err != nil {
			logf("side button %s: %v, retrying", path, err)
			time.Sleep(5 * time.Second)
			continue
		}
		for {
			if _, err = button.Read(buf); err != nil {
				break
			}
			if ev.Type == evKey && ev.Code == keyPower && ev.Value != 2 {
				d.setButton(ev.Value == 1)
			}
		}
		button.Close()
		d.setButton(false)
		logf("side button %s: %v, reopening", path, err)
		time.Sleep(time.Second)
	}
}

// readWheel forwards the wheel's key events until the device goes away.
func (d *daemon) readWheel(wheel *os.File) error {
	var ev inputEvent
	buf := (*[unsafe.Sizeof(inputEvent{})]byte)(unsafe.Pointer(&ev))[:]
	for {
		if _, err := wheel.Read(buf); err != nil {
			return err
		}
		if ev.Type == evKey && (ev.Code == keyUp || ev.Code == keyDown) {
			d.step(ev.Code, ev.Value)
		}
	}
}

// serve reads mode lines from SystemUI and sends it "step up" and "step down" lines. Any client may set it; SELinux decides who connects.
func (d *daemon) serve(l net.Listener) {
	for {
		conn, err := l.Accept()
		if err != nil {
			logf("accept: %v", err)
			time.Sleep(time.Second)
			continue
		}
		d.mu.Lock()
		d.clients[conn] = true
		d.mu.Unlock()
		go func() {
			defer func() {
				d.mu.Lock()
				delete(d.clients, conn)
				d.mu.Unlock()
				conn.Close()
			}()
			lines := bufio.NewScanner(conn)
			for lines.Scan() {
				fields := strings.Fields(lines.Text())
				if len(fields) == 2 && fields[0] == "mode" {
					if m, ok := modeNames[fields[1]]; ok {
						d.setMode(m)
						continue
					}
					if zones, ok := parseZones(fields[1]); ok {
						d.setZones(zones)
						continue
					}
				}
				if len(fields) == 3 && fields[0] == "tune" && d.tune(fields[1], fields[2]) {
					continue
				}
				if len(fields) == 3 && fields[0] == "inject" && d.inject(fields[1], fields[2]) {
					// Done: the client waits for it
					conn.Write([]byte("injected\n"))
					continue
				}
				logf("ignored %q", lines.Text())
			}
		}()
	}
}

// inject turns the wheel as the self test asks, a step every 150 ms as a quick hand does,
// or presses the side button; whether it knew the line.
func (d *daemon) inject(way, count string) bool {
	if way == "side" {
		return d.injectSide(count)
	}
	n, err := strconv.Atoi(count)
	if err != nil || n < 1 || n > 50 || way != "up" && way != "down" {
		return false
	}
	code := uint16(keyDown)
	if way == "up" {
		code = keyUp
	}
	for i := 0; i < n; i++ {
		if i > 0 {
			time.Sleep(150 * time.Millisecond)
		}
		d.step(code, 1)
	}
	return true
}

// injectSide presses the side button: a press, a hold of 1.2 s (the long press), or two
// presses 100 ms apart, the policy's double press.
func (d *daemon) injectSide(how string) bool {
	if how != "press" && how != "hold" && how != "double" {
		return false
	}
	if d.side == nil {
		side, err := createDevice(sideName, productSide, []uintptr{evKey}, []uintptr{keyPower}, nil, nil)
		if err != nil {
			logf("uinput: %v", err)
			return false
		}
		d.side = side
		// Android takes a moment to add a new device; a key before that is lost
		time.Sleep(500 * time.Millisecond)
	}
	press := func(held time.Duration) {
		d.setButton(true)
		write(d.side, evKey, keyPower, 1)
		write(d.side, evSyn, 0, 0)
		time.Sleep(held)
		write(d.side, evKey, keyPower, 0)
		write(d.side, evSyn, 0, 0)
		d.setButton(false)
	}
	switch how {
	case "press":
		press(0)
	case "hold":
		press(1200 * time.Millisecond)
	case "double":
		press(0)
		time.Sleep(100 * time.Millisecond)
		press(0)
	}
	return true
}

// injectClient is "r1-wheeld inject <up|down> [steps]" or "r1-wheeld inject side
// <press|hold|double>": it asks the running daemon and waits until it is done.
func injectClient(args []string) {
	if len(args) == 1 {
		args = append(args, "1")
	}
	if len(args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: r1-wheeld inject <up|down> [steps] | side <press|hold|double>")
		os.Exit(2)
	}
	conn, err := net.Dial("unix", "/dev/socket/"+outName)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer conn.Close()
	fmt.Fprintf(conn, "inject %s %s\n", args[0], args[1])
	// Step lines come too, to every client; at most 50 steps take 7.5 s
	conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	lines := bufio.NewScanner(conn)
	for lines.Scan() {
		if lines.Text() == "injected" {
			return
		}
	}
	fmt.Fprintln(os.Stderr, "r1-wheeld didn't take it (see its log)")
	os.Exit(1)
}

// initSocket is the listening socket init made from the rc file's socket line.
func initSocket() (net.Listener, error) {
	// init publishes it with every character but letters and digits as '_'
	env := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' {
			return r
		}
		return '_'
	}, outName)
	fd, err := strconv.Atoi(os.Getenv("ANDROID_SOCKET_" + env))
	if err != nil {
		return nil, fmt.Errorf("no init socket %s", outName)
	}
	// init binds the socket but leaves listening to the service
	if err := syscall.Listen(fd, 4); err != nil {
		return nil, fmt.Errorf("listen on %s: %w", outName, err)
	}
	return net.FileListener(os.NewFile(uintptr(fd), outName))
}

func main() {
	if len(os.Args) > 1 && os.Args[1] == "inject" {
		injectClient(os.Args[2:])
		return
	}
	out, err := createDevice(outName, productKeys, []uintptr{evKey}, outKeys, nil, nil)
	if err != nil {
		logf("uinput: %v", err)
		os.Exit(1)
	}
	width, height := touchscreenSize()
	xs := absInfo{Maximum: width - 1}
	ys := absInfo{Maximum: height - 1}
	touch, err := createDevice(touchName, productTouch, []uintptr{evKey, evAbs}, []uintptr{btnTouch},
		[]axis{{absX, xs}, {absY, ys}, {absMtSlot, absInfo{Maximum: 1}}, {absMtPositionX, xs},
			{absMtPositionY, ys}, {absMtTrackingID, absInfo{Maximum: 65535}}},
		[]uintptr{inputPropDirect})
	if err != nil {
		logf("uinput: %v; scroll mode sends the D-pad, pinch zoom the zoom keys", err)
		modeKeys[modeScroll] = modeKeys[modeApp]
		modeKeys[modePinch] = modeKeys[modeZoom]
	}
	d := &daemon{mode: modeTaskbar, out: out, touch: touch, width: width, height: height,
		clients: map[net.Conn]bool{}, tuning: defaultTuning}

	if l, err := initSocket(); err != nil {
		logf("%v; the mode stays taskbar", err)
	} else {
		go d.serve(l)
	}

	buttons := buttonPaths()
	if len(buttons) == 0 {
		logf("no input device with KEY_POWER, steps aren't held back around presses")
	}
	for _, path := range buttons {
		go d.watchButton(path)
	}

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-stop
		ioctl(int(out.Fd()), uiDevDestroy, 0)
		if touch != nil {
			ioctl(int(touch.Fd()), uiDevDestroy, 0)
		}
		os.Exit(0)
	}()

	for {
		wheel, err := openWheel()
		if err != nil {
			logf("%v, retrying", err)
			time.Sleep(2 * time.Second)
			continue
		}
		logf("grabbed %s", wheelName)
		err = d.readWheel(wheel)
		wheel.Close()
		logf("%s: %v, reopening", wheelName, err)
		time.Sleep(time.Second)
	}
}
