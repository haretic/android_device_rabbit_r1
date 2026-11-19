// SPDX-License-Identifier: MIT
package main

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"

	"golang.org/x/sys/unix"
)

func checkControlPeer(connection *net.UnixConn) error {
	raw, err := connection.SyscallConn()
	if err != nil {
		return err
	}
	var checkErr error
	err = raw.Control(func(fd uintptr) {
		credentials, err := unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
		if err != nil || credentials.Uid != uint32(os.Getuid()) {
			checkErr = errors.New("control peer UID mismatch")
		}
	})
	if err != nil {
		return err
	}
	return checkErr
}

// receiveTUN consumes exactly "tun\n", even if stream reads split that line or
// later control commands were sent immediately after it. Returned fd ownership
// transfers to the caller; every descriptor on an invalid bootstrap is closed.
func receiveTUN(connection *net.UnixConn) (int, error) {
	var line [4]byte
	// Linux permits at most 253 descriptors in one SCM_RIGHTS message.
	oob := make([]byte, unix.CmsgSpace(256*4))
	var descriptors []int
	keep := false
	defer func() {
		if !keep {
			for _, fd := range descriptors {
				unix.Close(fd)
			}
		}
	}()
	for offset := 0; offset < len(line); {
		n, oobn, flags, _, err := connection.ReadMsgUnix(line[offset:], oob)
		messages, parseErr := unix.ParseSocketControlMessage(oob[:oobn])
		for _, message := range messages {
			if message.Header.Level != unix.SOL_SOCKET || message.Header.Type != unix.SCM_RIGHTS {
				parseErr = errors.New("unexpected ancillary message")
				continue
			}
			fds, rightsErr := unix.ParseUnixRights(&message)
			if rightsErr != nil {
				parseErr = rightsErr
			}
			for _, fd := range fds {
				unix.CloseOnExec(fd)
				descriptors = append(descriptors, fd)
			}
		}
		if parseErr != nil || flags&(unix.MSG_CTRUNC|unix.MSG_TRUNC) != 0 || len(descriptors) > 1 {
			return -1, errors.New("invalid tun descriptor message")
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return -1, io.EOF
			}
			return -1, errors.New("tun descriptor read failed")
		}
		if n == 0 {
			return -1, io.EOF
		}
		offset += n
	}
	if !bytes.Equal(line[:], []byte("tun\n")) {
		return -1, errors.New("expected tun descriptor message")
	}
	if len(descriptors) != 1 {
		return -1, errors.New("missing tun descriptor")
	}
	keep = true
	return descriptors[0], nil
}

// attach takes ownership of fd, including closing it on failure.
func handleConnection(connection *net.UnixConn, attach func(int) (backend, error), logger *log.Logger) error {
	defer connection.Close()
	fd, err := receiveTUN(connection)
	if errors.Is(err, io.EOF) {
		return nil
	}
	if err != nil {
		// receiveTUN errors are fixed messages, never input text or engine errors.
		fmt.Fprintf(connection, "err %s\n", err.Error())
		return err
	}
	engine, err := attach(fd)
	if err != nil {
		fmt.Fprintln(connection, "err tun attachment failed")
		return errors.New("tun attachment failed")
	}
	if _, err := fmt.Fprintln(connection, "ok"); err != nil {
		engine.Close()
		return errors.New("control acknowledgement failed")
	}
	logger.Print("tun attached")
	return serve(connection, engine, logger)
}
