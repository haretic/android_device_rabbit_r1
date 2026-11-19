// SPDX-License-Identifier: MIT
package main

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun"
	"golang.zx2c4.com/wireguard/tun/tuntest"
)

type shiftedWake struct{ *engine }

func (e shiftedWake) Wake(now time.Time) error { return e.engine.Wake(now.Add(121 * time.Second)) }

type protocolEngine struct {
	app    *net.UnixConn
	reader *bufio.Reader
	tun    *tuntest.ChannelTUN
	done   chan struct{}
	err    error
}

func startProtocolEngine(t *testing.T) *protocolEngine {
	t.Helper()
	helper, app := socketPair(t)
	r, _ := testPipe(t)
	s := &protocolEngine{app: app, reader: bufio.NewReader(app), tun: tuntest.NewChannelTUN(), done: make(chan struct{})}
	go func() {
		s.err = handleConnection(helper, func(fd int) (backend, error) {
			// Exercise real SCM_RIGHTS receipt without requiring a privileged kernel
			// TUN: consume the passed pipe fd, then attach the upstream channel TUN.
			if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
				unix.Close(fd)
				return nil, err
			}
			unix.Close(fd)
			e, err := newEngine(s.tun.TUN(), log.New(io.Discard, "", 0))
			if err != nil {
				return nil, err
			}
			return shiftedWake{e}, nil
		}, log.New(io.Discard, "", 0))
		close(s.done)
	}()
	t.Cleanup(func() {
		app.Close()
		select {
		case <-s.done:
			if s.err != nil {
				t.Error("engine session failed")
			}
		case <-time.After(5 * time.Second):
			t.Error("engine session did not close")
		}
	})
	sendFD(t, app, "tun\n", int(r.Fd()))
	if line, _ := s.reader.ReadString('\n'); line != "ok\n" {
		t.Fatal("channel TUN not acknowledged")
	}
	return s
}

func (s *protocolEngine) command(t *testing.T, command string) {
	t.Helper()
	if _, err := io.WriteString(s.app, command+"\n"); err != nil {
		t.Fatal("control write failed")
	}
	if line, err := s.reader.ReadString('\n'); err != nil || line != "ok\n" {
		t.Fatal("control command failed")
	}
}

func (s *protocolEngine) get(t *testing.T) string {
	t.Helper()
	if _, err := io.WriteString(s.app, "get\n"); err != nil {
		t.Fatal("get write failed")
	}
	var text strings.Builder
	for {
		line, err := s.reader.ReadString('\n')
		if err != nil {
			t.Fatal("get read failed")
		}
		if line == "\n" {
			return text.String()
		}
		if strings.HasPrefix(line, "err ") {
			t.Fatal("get returned an error")
		}
		text.WriteString(line)
	}
}

func uapiField(config, field string) string {
	for _, line := range strings.Split(config, "\n") {
		key, value, _ := strings.Cut(line, "=")
		if key == field {
			return value
		}
	}
	return ""
}

func handshakeStamp(config string) int64 {
	seconds, _ := strconv.ParseInt(uapiField(config, "last_handshake_time_sec"), 10, 64)
	nanoseconds, _ := strconv.ParseInt(uapiField(config, "last_handshake_time_nsec"), 10, 64)
	return seconds*1e9 + nanoseconds
}

func passPacket(t *testing.T, from, to *protocolEngine, source, destination netip.Addr) {
	t.Helper()
	packet := tuntest.Ping(destination, source)
	select {
	case from.tun.Outbound <- packet:
	case <-time.After(5 * time.Second):
		t.Fatal("channel TUN did not accept packet")
	}
	select {
	case received := <-to.tun.Inbound:
		if !bytes.Equal(received, packet) {
			t.Fatal("packet changed in tunnel")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("packet did not cross encrypted loopback UDP")
	}
}

func TestTwoDevicesHandshakeAndPassPacket(t *testing.T) {
	a, b := startProtocolEngine(t), startProtocolEngine(t)
	privateA, publicA, err := generateKey()
	if err != nil {
		t.Fatal("key generation failed")
	}
	privateB, publicB, err := generateKey()
	if err != nil {
		t.Fatal("key generation failed")
	}
	ips := [2]netip.Addr{netip.MustParseAddr("10.93.0.1"), netip.MustParseAddr("10.93.0.2")}
	psk := strings.Repeat("ab", 32)
	for i, peer := range []*protocolEngine{a, b} {
		private, public := privateA, publicB
		if i == 1 {
			private, public = privateB, publicA
		}
		config := "private_key=" + private + "\nlisten_port=0\nreplace_peers=true\npublic_key=" + public +
			"\npreshared_key=" + psk + "\nreplace_allowed_ips=true\nallowed_ip=" + ips[1-i].String() + "/32\npersistent_keepalive_interval=0\n"
		peer.command(t, "set\n"+config)
		peer.command(t, "up")
		got := peer.get(t)
		if uapiField(got, "private_key") != private || uapiField(got, "public_key") != public || uapiField(got, "preshared_key") != psk || uapiField(got, "allowed_ip") != ips[1-i].String()+"/32" {
			t.Fatal("set/get round trip lost a configuration field")
		}
	}
	portA, portB := uapiField(a.get(t), "listen_port"), uapiField(b.get(t), "listen_port")
	if portA == "" || portB == "" || portA == "0" || portB == "0" {
		t.Fatal("no UDP listen ports")
	}
	a.command(t, "set\npublic_key="+publicB+"\nendpoint=127.0.0.1:"+portB+"\n")
	b.command(t, "set\npublic_key="+publicA+"\nendpoint=127.0.0.1:"+portA+"\n")
	passPacket(t, a, b, ips[0], ips[1])
	passPacket(t, b, a, ips[1], ips[0])
	initial := a.get(t)
	if handshakeStamp(initial) == 0 || uapiField(initial, "rx_bytes") == "0" || uapiField(initial, "tx_bytes") == "0" || uapiField(initial, "endpoint") != "127.0.0.1:"+portB {
		t.Fatal("handshake/counters/endpoint missing from get")
	}
	a.command(t, "rebind")
	passPacket(t, a, b, ips[0], ips[1])
	// Only the local wall clock is advanced by this fixture. Give the remote's
	// real 20ms anti-flood window time to pass, as it would during a real sleep.
	time.Sleep(2 * device.HandshakeInitationRate)
	a.command(t, "wake") // shiftedWake simulates a 121-second wall-clock sleep.
	deadline := time.Now().Add(5 * time.Second)
	for handshakeStamp(a.get(t)) <= handshakeStamp(initial) {
		if time.Now().After(deadline) {
			t.Fatal("wake did not force a fresh handshake")
		}
		time.Sleep(10 * time.Millisecond)
	}
	passPacket(t, a, b, ips[0], ips[1])
	a.command(t, "down")
	a.command(t, "wake") // Remains down; Up below is explicit.
	time.Sleep(2 * device.HandshakeInitationRate)
	a.command(t, "up")
	passPacket(t, a, b, ips[0], ips[1])
	a.command(t, "quit")
	b.app.CloseWrite()
	for _, session := range []*protocolEngine{a, b} {
		select {
		case <-session.done:
			if session.err != nil {
				t.Fatal("device did not exit cleanly")
			}
		case <-time.After(5 * time.Second):
			t.Fatal("device did not close on quit/EOF")
		}
	}
}

func TestUDPBindIPv6AndNoMarks(t *testing.T) {
	a, b := newUDPBind().(*udpBind), newUDPBind().(*udpBind)
	defer a.Close()
	defer b.Close()
	_, _, err := a.Open(0)
	if err != nil {
		t.Fatal("UDP open failed")
	}
	receivers, port, err := b.Open(0)
	if err != nil {
		t.Fatal("UDP open failed")
	}
	if a.SetMark(0) == nil || a.SetMark(1) == nil {
		t.Fatal("socket marking must be unsupported")
	}
	if _, err := a.ParseEndpoint("example.invalid:51820"); err == nil {
		t.Fatal("DNS endpoint accepted")
	}
	if a.v6 == nil || b.v6 == nil {
		t.Skip("IPv6 unavailable on host")
	}
	endpoint, err := a.ParseEndpoint(fmt.Sprintf("[::1]:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Send([][]byte{[]byte("v6")}, endpoint); err != nil {
		t.Fatal("IPv6 send failed")
	}
	b.v6.SetReadDeadline(time.Now().Add(3 * time.Second))
	packets := [][]byte{make([]byte, 64)}
	sizes := make([]int, 1)
	// v6 is last when both families are present.
	endpoints := make([]conn.Endpoint, 1)
	if n, err := receivers[len(receivers)-1](packets, sizes, endpoints); err != nil || n != 1 || string(packets[0][:sizes[0]]) != "v6" {
		t.Fatal("IPv6 receive failed")
	}
}

type mtuProbe struct {
	tun.Device
	calls, closes int
	fail          bool
}

func (p *mtuProbe) MTU() (int, error) {
	p.calls++
	if p.fail {
		return 0, io.ErrUnexpectedEOF
	}
	return 1300, nil
}
func (p *mtuProbe) Close() error { p.closes++; return p.Device.Close() }

func TestMTUAndEngineLogRedaction(t *testing.T) {
	probe := &mtuProbe{Device: tuntest.NewChannelTUN().TUN()}
	var logs bytes.Buffer
	e, err := newEngine(probe, log.New(&logs, "", 0))
	if err != nil {
		t.Fatal("engine creation failed")
	}
	secret := strings.Repeat("de", 32)
	e.IpcSet(secret + "\n") // Upstream's parse error quotes the entire malformed line.
	e.Close()
	if probe.calls != 1 || probe.closes != 1 {
		t.Fatal("MTU was not cached or TUN not closed")
	}
	if strings.Contains(logs.String(), secret) {
		t.Fatal("engine error leaked secret input")
	}
	probe = &mtuProbe{Device: tuntest.NewChannelTUN().TUN(), fail: true}
	if _, err := newEngine(probe, log.New(io.Discard, "", 0)); err == nil || probe.closes != 1 {
		t.Fatal("unreadable MTU was silently replaced")
	}
}
