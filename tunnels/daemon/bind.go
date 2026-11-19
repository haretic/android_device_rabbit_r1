// SPDX-License-Identifier: MIT
package main

import (
	"errors"
	"net"
	"net/netip"
	"sync"

	"golang.org/x/sys/unix"
	"golang.zx2c4.com/wireguard/conn"
)

// A plain UDP bind: no marks, privileged buffer options, interface binding, or
// route monitor. In particular it is not *conn.StdNetBind, the upstream Linux
// type that enables netlink monitoring. The app's excluded UID handles bypass.
type udpBind struct {
	mu     sync.Mutex
	v4, v6 *net.UDPConn
}

func newUDPBind() conn.Bind           { return &udpBind{} }
func (*udpBind) BatchSize() int       { return 1 }
func (*udpBind) SetMark(uint32) error { return errors.New("socket marks are unsupported") }

type ipEndpoint struct{ netip.AddrPort }

func (*ipEndpoint) ClearSrc()             {}
func (*ipEndpoint) SrcToString() string   { return "" }
func (*ipEndpoint) SrcIP() netip.Addr     { return netip.Addr{} }
func (e *ipEndpoint) DstToString() string { return e.AddrPort.String() }
func (e *ipEndpoint) DstIP() netip.Addr   { return e.Addr() }
func (e *ipEndpoint) DstToBytes() []byte  { data, _ := e.AddrPort.MarshalBinary(); return data }

func (*udpBind) ParseEndpoint(text string) (conn.Endpoint, error) {
	address, err := netip.ParseAddrPort(text)
	if err != nil || address.Addr().Zone() != "" || address.Port() == 0 {
		return nil, errors.New("endpoint must be a numeric IP and port")
	}
	return &ipEndpoint{netip.AddrPortFrom(address.Addr().Unmap(), address.Port())}, nil
}

func familyUnavailable(err error) bool {
	return errors.Is(err, unix.EAFNOSUPPORT) || errors.Is(err, unix.EPROTONOSUPPORT) || errors.Is(err, unix.EADDRNOTAVAIL)
}

func receiveUDP(socket *net.UDPConn) conn.ReceiveFunc {
	return func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
		n, address, err := socket.ReadFromUDPAddrPort(packets[0])
		if err != nil {
			return 0, err
		}
		sizes[0] = n
		endpoints[0] = &ipEndpoint{netip.AddrPortFrom(address.Addr().Unmap(), address.Port())}
		return 1, nil
	}
}

func (b *udpBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if b.v4 != nil || b.v6 != nil {
		return nil, 0, conn.ErrBindAlreadyOpen
	}
	for attempt := 0; attempt < 5; attempt++ {
		actual := port
		v4, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4zero, Port: int(port)})
		if err != nil && !familyUnavailable(err) {
			return nil, 0, err
		}
		if v4 != nil {
			actual = uint16(v4.LocalAddr().(*net.UDPAddr).Port)
		}
		v6, err := net.ListenUDP("udp6", &net.UDPAddr{IP: net.IPv6unspecified, Port: int(actual)})
		if err != nil && !familyUnavailable(err) {
			if v4 != nil {
				v4.Close()
			}
			if port == 0 && errors.Is(err, unix.EADDRINUSE) {
				continue
			}
			return nil, 0, err
		}
		if v4 == nil && v6 == nil {
			return nil, 0, errors.New("UDP unavailable")
		}
		if v4 == nil {
			actual = uint16(v6.LocalAddr().(*net.UDPAddr).Port)
		}
		b.v4, b.v6 = v4, v6
		var receivers []conn.ReceiveFunc
		if v4 != nil {
			receivers = append(receivers, receiveUDP(v4))
		}
		if v6 != nil {
			receivers = append(receivers, receiveUDP(v6))
		}
		return receivers, actual, nil
	}
	return nil, 0, errors.New("cannot allocate UDP port")
}

func (b *udpBind) Close() error {
	b.mu.Lock()
	v4, v6 := b.v4, b.v6
	b.v4, b.v6 = nil, nil
	b.mu.Unlock()
	if v4 != nil {
		v4.Close()
	}
	if v6 != nil {
		v6.Close()
	}
	return nil
}

func (b *udpBind) Send(packets [][]byte, endpoint conn.Endpoint) error {
	address, ok := endpoint.(*ipEndpoint)
	if !ok {
		return conn.ErrWrongEndpointType
	}
	b.mu.Lock()
	socket := b.v6
	if address.Addr().Is4() {
		socket = b.v4
	}
	b.mu.Unlock()
	if socket == nil {
		return net.ErrClosed
	}
	for _, packet := range packets {
		if _, err := socket.WriteToUDPAddrPort(packet, address.AddrPort); err != nil {
			return err
		}
	}
	return nil
}
