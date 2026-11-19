// SPDX-License-Identifier: MIT
package main

import (
	"bufio"
	"bytes"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

func socketPair(t *testing.T) (*net.UnixConn, *net.UnixConn) {
	t.Helper()
	fds, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		t.Fatal(err)
	}
	var connections [2]*net.UnixConn
	for i, fd := range fds {
		file := os.NewFile(uintptr(fd), "test-control")
		connection, err := net.FileConn(file)
		file.Close()
		if err != nil {
			t.Fatal(err)
		}
		connections[i] = connection.(*net.UnixConn)
		connections[i].SetDeadline(time.Now().Add(10 * time.Second))
		t.Cleanup(func() { connection.Close() })
	}
	return connections[0], connections[1]
}

func testPipe(t *testing.T) (*os.File, *os.File) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { r.Close(); w.Close() })
	return r, w
}

func sendFD(t *testing.T, connection *net.UnixConn, message string, fds ...int) {
	t.Helper()
	n, _, err := connection.WriteMsgUnix([]byte(message), unix.UnixRights(fds...), nil)
	if err != nil || n != len(message) {
		t.Fatal("SCM_RIGHTS send failed")
	}
}

func descriptorCount(t *testing.T) int {
	t.Helper()
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		t.Fatal(err)
	}
	return len(entries)
}

func TestReceiveRealDescriptorAndPartialBootstrap(t *testing.T) {
	helper, app := socketPair(t)
	r, w := testPipe(t)
	if err := checkControlPeer(helper); err != nil {
		t.Fatal("same UID peer rejected")
	}
	if _, err := w.Write([]byte("fd-token")); err != nil {
		t.Fatal(err)
	}
	sendFD(t, app, "t", int(r.Fd()))
	if _, err := app.Write([]byte("un\nup\n")); err != nil {
		t.Fatal(err)
	}
	fd, err := receiveTUN(helper)
	if err != nil {
		t.Fatal("valid fragmented descriptor transfer rejected")
	}
	file := os.NewFile(uintptr(fd), "received-test-fd")
	defer file.Close()
	flags, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0)
	if err != nil || flags&unix.FD_CLOEXEC == 0 {
		t.Fatal("received fd is not close-on-exec")
	}
	data := make([]byte, 8)
	if _, err := io.ReadFull(file, data); err != nil || string(data) != "fd-token" {
		t.Fatal("wrong descriptor received")
	}
	line, err := bufio.NewReader(helper).ReadString('\n')
	if err != nil || line != "up\n" {
		t.Fatal("bootstrap consumed the next control command")
	}
	if _, err := r.Stat(); err != nil {
		t.Fatal("sender descriptor was closed")
	}
}

func TestRejectMissingExtraAndBadDescriptorMessages(t *testing.T) {
	for _, kind := range []string{"missing", "extra", "wrong-line", "partial-eof"} {
		t.Run(kind, func(t *testing.T) {
			helper, app := socketPair(t)
			r, _ := testPipe(t)
			before := descriptorCount(t)
			switch kind {
			case "missing":
				app.Write([]byte("tun\n"))
			case "extra":
				sendFD(t, app, "tun\n", int(r.Fd()), int(r.Fd()))
			case "wrong-line":
				sendFD(t, app, "bad\n", int(r.Fd()))
			case "partial-eof":
				sendFD(t, app, "tu", int(r.Fd()))
				app.CloseWrite()
			}
			if fd, err := receiveTUN(helper); err == nil || fd >= 0 {
				t.Fatal("invalid bootstrap accepted")
			}
			if descriptorCount(t) != before {
				t.Fatal("rejected ancillary descriptors leaked")
			}
		})
	}
}

func TestDescriptorAttachmentAndControlLifecycle(t *testing.T) {
	for _, ending := range []string{"quit", "eof"} {
		t.Run(ending, func(t *testing.T) {
			helper, app := socketPair(t)
			r, _ := testPipe(t)
			engine := &fakeBackend{}
			done := make(chan error, 1)
			go func() {
				done <- handleConnection(helper, func(fd int) (backend, error) {
					defer unix.Close(fd)
					if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
						return nil, err
					}
					return engine, nil
				}, log.New(io.Discard, "", 0))
			}()
			sendFD(t, app, "tun\n", int(r.Fd()))
			reader := bufio.NewReader(app)
			if line, _ := reader.ReadString('\n'); line != "ok\n" {
				t.Fatal("tun attachment not acknowledged")
			}
			app.Write([]byte("up\n"))
			if line, _ := reader.ReadString('\n'); line != "ok\n" {
				t.Fatal("control command lost after tun acknowledgement")
			}
			if ending == "quit" {
				app.Write([]byte("quit\n"))
				if line, _ := reader.ReadString('\n'); line != "ok\n" {
					t.Fatal("quit not acknowledged")
				}
			} else {
				app.CloseWrite()
			}
			if err := <-done; err != nil {
				t.Fatal("quit/EOF was not clean")
			}
			if engine.ups != 1 || engine.closes != 1 {
				t.Fatal("device lifecycle incorrect")
			}
		})
	}
}

func TestBootstrapEOFAndAttachmentFailure(t *testing.T) {
	helper, app := socketPair(t)
	app.CloseWrite()
	if err := handleConnection(helper, func(int) (backend, error) { t.Error("attached after EOF"); return nil, nil }, log.New(io.Discard, "", 0)); err != nil {
		t.Fatal("initial EOF should quit cleanly")
	}
	helper, app = socketPair(t)
	r, _ := testPipe(t)
	done := make(chan error, 1)
	go func() {
		done <- handleConnection(helper, func(fd int) (backend, error) {
			unix.Close(fd)
			return nil, errors.New("untrusted secret in attachment error")
		}, log.New(io.Discard, "", 0))
	}()
	sendFD(t, app, "tun\n", int(r.Fd()))
	line, _ := bufio.NewReader(app).ReadString('\n')
	if line != "err tun attachment failed\n" || <-done == nil {
		t.Fatal("attachment failure not safely reported")
	}
}

func TestAbstractSocketInvocation(t *testing.T) {
	name := fmt.Sprintf("r1-tunneld-test-%d-%d", os.Getpid(), time.Now().UnixNano())
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Net: "unix", Name: "\x00" + name})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	listener.SetDeadline(time.Now().Add(10 * time.Second))
	var output, logs bytes.Buffer
	done := make(chan int, 1)
	go func() { done <- run([]string{"wg", "--socket", name}, strings.NewReader(""), &output, &logs) }()
	app, err := listener.AcceptUnix()
	if err != nil {
		t.Fatal(err)
	}
	defer app.Close()
	app.SetDeadline(time.Now().Add(10 * time.Second))
	r, _ := testPipe(t)
	// A pipe is a real passed fd, but is not a kernel TUN: production attachment
	// must reject it. The packet integration test substitutes a channel TUN.
	sendFD(t, app, "tun\n", int(r.Fd()))
	line, _ := bufio.NewReader(app).ReadString('\n')
	if line != "err tun attachment failed\n" || <-done != 1 || output.Len() != 0 {
		t.Fatal("abstract invocation did not reject non-TUN fd")
	}
	if strings.Contains(logs.String(), name) {
		t.Fatal("socket argument echoed to logs")
	}
}
