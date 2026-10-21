// SPDX-FileCopyrightText: 2026 The LineageOS Project
// SPDX-License-Identifier: Apache-2.0

// r1-wheeld owns the rabbit r1 scroll wheel. It grabs the och1970-wheel input device, so
// nothing else sees its steps, and sends every step on through its own uinput device,
// r1-wheel, as the key the current mode calls for:
//
//	taskbar  KEY_SCROLLUP / KEY_SCROLLDOWN: NAVIGATE_PREVIOUS / NEXT (keylayout/r1-wheel.kl).
//	         The window policy gives them to SystemUI's wheel taskbar while an app is in
//	         front; a system window (a menu, the shade, the taskbar) gets them, and
//	         r1-wheel.kcm turns them into D-pad steps there.
//	app      KEY_UP / KEY_DOWN: DPAD_UP / DOWN, the app moves its focus.
//	volume   KEY_VOLUMEUP / KEY_VOLUMEDOWN.
//
// A press of the side button tends to nudge the wheel: steps while it is held and just after
// it is let go are dropped, so a press doesn't step the focus off what it was meant for.
//
// SystemUI sets the mode over the init socket /dev/socket/r1-wheel, one line per change:
// "mode taskbar", "mode app" or "mode volume". Without a client the mode is taskbar. If the
// daemon stops, the grab goes with it and the wheel's own D-pad keys reach Android again.

// One thread is plenty, whatever the cgroup CPU quota says
//
//go:debug containermaxprocs=0
//go:debug updatemaxprocs=0
package main

import (
	"bufio"
	"fmt"
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

	evSyn = 0x00
	evKey = 0x01

	keyUp         = 103
	keyDown       = 108
	keyVolumeDown = 114
	keyVolumeUp   = 115
	keyScrollUp   = 177
	keyScrollDown = 178
	keyPower      = 116
	keyMax        = 0x2ff

	busVirtual = 0x06

	// Wheel steps this soon after the side button is let go are its nudge
	buttonSettle = 150 * time.Millisecond
)

// ioctl request numbers, asm-generic layout: dir(2) size(14) type(8) nr(8)
func ioc(dir, typ, nr, size uintptr) uintptr {
	return dir<<30 | size<<16 | typ<<8 | nr
}

var (
	eviocgrab    = ioc(1, 'E', 0x90, 4)
	uiSetEvbit   = ioc(1, 'U', 100, 4)
	uiSetKeybit  = ioc(1, 'U', 101, 4)
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
	modeVolume
)

var modeNames = map[string]mode{"taskbar": modeTaskbar, "app": modeApp, "volume": modeVolume}

// The key each mode sends for a step up and a step down.
var modeKeys = map[mode][2]uint16{
	modeTaskbar: {keyScrollUp, keyScrollDown},
	modeApp:     {keyUp, keyDown},
	modeVolume:  {keyVolumeUp, keyVolumeDown},
}

type daemon struct {
	mu   sync.Mutex
	mode mode
	out  *os.File
	// The key a held step went out as, so its release matches even if the mode changed
	held map[uint16]uint16
	// The side button: held, or when it was let go
	button         bool
	buttonReleased time.Time
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
	d.mode = m
}

func (d *daemon) emit(typ, code uint16, value int32) error {
	ev := inputEvent{Type: typ, Code: code, Value: value}
	buf := (*[unsafe.Sizeof(inputEvent{})]byte)(unsafe.Pointer(&ev))[:]
	_, err := d.out.Write(buf)
	return err
}

// step forwards one wheel key event (value 1 press, 0 release, 2 repeat).
func (d *daemon) step(code uint16, value int32) {
	d.mu.Lock()
	defer d.mu.Unlock()
	var out uint16
	if value == 1 {
		if d.button || time.Since(d.buttonReleased) < buttonSettle {
			// The button's nudge; its release finds nothing held and goes too
			return
		}
		keys := modeKeys[d.mode]
		if code == keyUp {
			out = keys[0]
		} else {
			out = keys[1]
		}
		d.held[code] = out
	} else {
		var ok bool
		if out, ok = d.held[code]; !ok {
			return
		}
		if value == 0 {
			delete(d.held, code)
		}
	}
	if err := d.emit(evKey, out, value); err != nil {
		logf("uinput write: %v", err)
		return
	}
	d.emit(evSyn, 0, 0)
}

func createOutput() (*os.File, error) {
	f, err := os.OpenFile("/dev/uinput", os.O_WRONLY|syscall.O_NONBLOCK, 0)
	if err != nil {
		return nil, err
	}
	fd := int(f.Fd())
	if err := ioctl(fd, uiSetEvbit, evKey); err != nil {
		f.Close()
		return nil, fmt.Errorf("UI_SET_EVBIT: %w", err)
	}
	for _, k := range []uintptr{keyUp, keyDown, keyVolumeDown, keyVolumeUp, keyScrollUp, keyScrollDown} {
		if err := ioctl(fd, uiSetKeybit, k); err != nil {
			f.Close()
			return nil, fmt.Errorf("UI_SET_KEYBIT %d: %w", k, err)
		}
	}
	setup := uinputSetup{Bustype: busVirtual, Version: 1}
	copy(setup.Name[:], outName)
	if err := ioctl(fd, uiDevSetup, uintptr(unsafe.Pointer(&setup))); err != nil {
		f.Close()
		return nil, fmt.Errorf("UI_DEV_SETUP: %w", err)
	}
	if err := ioctl(fd, uiDevCreate, 0); err != nil {
		f.Close()
		return nil, fmt.Errorf("UI_DEV_CREATE: %w", err)
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

// serve reads mode lines from SystemUI. Any client may set it; SELinux decides who connects.
func (d *daemon) serve(l net.Listener) {
	for {
		conn, err := l.Accept()
		if err != nil {
			logf("accept: %v", err)
			time.Sleep(time.Second)
			continue
		}
		go func() {
			defer conn.Close()
			lines := bufio.NewScanner(conn)
			for lines.Scan() {
				fields := strings.Fields(lines.Text())
				if len(fields) == 2 && fields[0] == "mode" {
					if m, ok := modeNames[fields[1]]; ok {
						d.setMode(m)
						continue
					}
				}
				logf("ignored %q", lines.Text())
			}
		}()
	}
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
	out, err := createOutput()
	if err != nil {
		logf("uinput: %v", err)
		os.Exit(1)
	}
	d := &daemon{mode: modeTaskbar, out: out, held: map[uint16]uint16{}}

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
