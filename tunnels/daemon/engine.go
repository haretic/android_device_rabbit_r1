// SPDX-License-Identifier: MIT
package main

import (
	"bufio"
	"encoding/hex"
	"errors"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
)

type engine struct {
	*device.Device
	up    bool // Commands are serialised on the control connection.
	close sync.Once
}

// Cache the supplied interface's MTU, never substituting a guessed MTU or setting it.
type attachedTUN struct {
	tun.Device
	mtu int
}

func (t *attachedTUN) MTU() (int, error) { return t.mtu, nil }

func attachTUN(logger *log.Logger) func(int) (backend, error) {
	return func(fd int) (backend, error) {
		request, err := unix.NewIfreq("")
		if err != nil || unix.IoctlIfreq(fd, unix.TUNGETIFF, request) != nil {
			unix.Close(fd)
			return nil, errors.New("tun attachment failed")
		}
		// This is the Android backend's fd path: it makes blocking fds pollable,
		// inspects the existing TUN, and does not create an interface or set MTU.
		tunDevice, _, err := tun.CreateUnmonitoredTUNFromFD(fd)
		if err != nil {
			unix.Close(fd)
			return nil, errors.New("tun attachment failed")
		}
		return newEngine(tunDevice, logger)
	}
}

// newEngine takes ownership of tunDevice on both success and failure.
func newEngine(tunDevice tun.Device, logger *log.Logger) (*engine, error) {
	mtu, err := tunDevice.MTU()
	if err != nil || mtu <= 0 {
		tunDevice.Close()
		return nil, errors.New("cannot read tun MTU")
	}
	engineLog := &device.Logger{
		Verbosef: device.DiscardLogf,
		Errorf: func(format string, args ...any) {
			// Upstream's arguments are never formatted: UAPI errors can quote secret input.
			// Its format string is a constant that says what failed, and an errno among the
			// arguments why; both are safe to print
			text := "tunnel engine error: " + strings.TrimSpace(strings.ReplaceAll(format, "\n", " "))
			for _, arg := range args {
				var errno unix.Errno
				if err, ok := arg.(error); ok && errors.As(err, &errno) {
					text += " [" + unix.ErrnoName(errno) + "]"
				}
			}
			logger.Print(text)
		},
	}
	d := device.NewDevice(&attachedTUN{Device: tunDevice, mtu: mtu}, newUDPBind(), engineLog)
	d.DisableSomeRoamingForBrokenMobileSemantics() // Before any peer is configured.
	return &engine{Device: d}, nil
}

func (e *engine) Up() error {
	err := e.Device.Up()
	if err == nil {
		e.up = true
	}
	return err
}

func (e *engine) Down() error {
	err := e.Device.Down()
	if err == nil {
		e.up = false
	}
	return err
}

func (e *engine) Close() { e.close.Do(e.Device.Close) }

type handshakePeer interface {
	ExpireCurrentKeypairs()
	SendHandshakeInitiation(bool) error
}

func (e *engine) Wake(now time.Time) error {
	if !e.up {
		return nil // Wake must never bring a deliberately stopped tunnel up.
	}
	config, err := e.IpcGet()
	if err != nil {
		return errors.New("cannot inspect peers")
	}
	return wakePeers(config, now, func(key device.NoisePublicKey) handshakePeer {
		if peer := e.LookupPeer(key); peer != nil {
			return peer
		}
		return nil
	})
}

// The timestamps come from UAPI as wall-clock Unix seconds/nanoseconds. Comparing
// them to now drops Go's monotonic component and therefore includes suspend time.
func wakePeers(config string, now time.Time, lookup func(device.NoisePublicKey) handshakePeer) error {
	var key device.NoisePublicKey
	var seconds, nanoseconds int64
	var present, endpoint bool
	var result error
	visit := func() {
		if !present || !endpoint {
			return // Without a known endpoint the peer cannot be contacted proactively.
		}
		if seconds != 0 && now.Sub(time.Unix(seconds, nanoseconds)) <= 120*time.Second {
			return
		}
		if peer := lookup(key); peer != nil {
			// This exported API also resets lastSentHandshake, avoiding the engine's
			// monotonic five-second retry gate after suspend. No runtime patch needed.
			peer.ExpireCurrentKeypairs()
			if err := peer.SendHandshakeInitiation(false); err != nil {
				result = errors.New("wake handshake failed")
			}
		}
	}
	scanner := bufio.NewScanner(strings.NewReader(config))
	for scanner.Scan() {
		field, value, _ := strings.Cut(scanner.Text(), "=")
		switch field {
		case "public_key":
			visit()
			present, endpoint, seconds, nanoseconds = false, false, 0, 0
			if len(value) != 64 {
				return errors.New("invalid peer status")
			}
			if _, err := hex.Decode(key[:], []byte(value)); err != nil {
				return errors.New("invalid peer status")
			}
			present = true
		case "endpoint":
			endpoint = value != ""
		case "last_handshake_time_sec", "last_handshake_time_nsec":
			number, err := strconv.ParseInt(value, 10, 64)
			if err != nil || number < 0 || (field == "last_handshake_time_nsec" && number >= 1e9) {
				return errors.New("invalid peer status")
			}
			if field == "last_handshake_time_sec" {
				seconds = number
			} else {
				nanoseconds = number
			}
		}
	}
	if scanner.Err() != nil {
		return errors.New("invalid peer status")
	}
	visit()
	return result
}
