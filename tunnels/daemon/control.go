// SPDX-License-Identifier: MIT
package main

import (
	"bufio"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"net/netip"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	maxLineBytes   = 4096
	maxConfigBytes = 1 << 20
)

type backend interface {
	IpcSet(string) error
	IpcGet() (string, error)
	Up() error
	Down() error
	BindUpdate() error
	Wake(time.Time) error
	Close()
}

func readLine(reader *bufio.Reader) (string, error) {
	line, err := reader.ReadSlice('\n')
	if errors.Is(err, io.EOF) {
		return "", io.EOF // An unfinished line is not a command.
	}
	if err != nil || len(line) > maxLineBytes || !utf8.Valid(line) {
		return "", errors.New("invalid control line")
	}
	return strings.TrimSuffix(strings.TrimSuffix(string(line), "\n"), "\r"), nil
}

func readConfig(reader *bufio.Reader) (string, error) {
	var config strings.Builder
	for {
		line, err := readLine(reader)
		if err != nil {
			return "", err
		}
		if config.Len()+len(line)+1 > maxConfigBytes {
			return "", errors.New("configuration too large")
		}
		config.WriteString(line)
		config.WriteByte('\n')
		if line == "" {
			return config.String(), nil
		}
	}
}

// Validate before applying: no DNS endpoints, marks, routing/interface settings,
// or unsupported extensions. Do not include any supplied value in an error.
func validateConfig(config string) error {
	peer := false
	invalid := errors.New("invalid configuration")
	for _, line := range strings.Split(config, "\n") {
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			return invalid
		}
		switch key {
		case "public_key":
			peer = true
			fallthrough
		case "private_key", "preshared_key":
			if (key == "private_key" && peer) || (key == "preshared_key" && !peer) || len(value) != 64 {
				return invalid
			}
			var decoded [32]byte
			_, err := hex.Decode(decoded[:], []byte(value))
			clear(decoded[:])
			if err != nil {
				return invalid
			}
		case "replace_peers":
			if peer || value != "true" {
				return invalid
			}
		case "replace_allowed_ips", "remove", "update_only":
			if !peer || value != "true" {
				return invalid
			}
		case "endpoint":
			endpoint, err := netip.ParseAddrPort(value)
			if !peer || err != nil || endpoint.Addr().Zone() != "" || endpoint.Port() == 0 {
				return errors.New("endpoint must be a numeric IP and port")
			}
		case "allowed_ip":
			if _, err := netip.ParsePrefix(value); !peer || err != nil {
				return invalid
			}
		case "listen_port", "persistent_keepalive_interval":
			if (key == "listen_port") == peer {
				return invalid
			}
			if _, err := strconv.ParseUint(value, 10, 16); err != nil {
				return invalid
			}
		case "protocol_version":
			if !peer || value != "1" {
				return invalid
			}
		default:
			return invalid
		}
	}
	return nil
}

func serve(control io.ReadWriter, engine backend, logger *log.Logger) error {
	defer engine.Close()
	defer logger.Print("tunnel closed")
	reader := bufio.NewReaderSize(control, maxLineBytes)
	writer := bufio.NewWriter(control)
	reply := func(text string) error {
		if _, err := writer.WriteString(text + "\n"); err != nil {
			return err
		}
		return writer.Flush()
	}
	for {
		command, err := readLine(reader)
		if errors.Is(err, io.EOF) {
			return nil
		}
		if err != nil {
			reply("err invalid control line")
			return errors.New("control read failed")
		}
		var operationErr error
		switch command {
		case "":
			continue
		case "set":
			config, err := readConfig(reader)
			if errors.Is(err, io.EOF) {
				return nil
			}
			if err != nil {
				reply("err invalid configuration frame")
				return errors.New("configuration read failed")
			}
			if validateConfig(config) != nil {
				operationErr = errors.New("configuration rejected")
			} else {
				operationErr = engine.IpcSet(config)
			}
		case "get":
			config, err := engine.IpcGet()
			if err != nil {
				operationErr = err
				break
			}
			// UAPI includes keys: write it only to the authenticated control socket.
			text := strings.TrimSuffix(config, "\n")
			if text != "" {
				text += "\n"
			}
			if err := reply(text); err != nil {
				return errors.New("control write failed")
			}
			continue
		case "up":
			operationErr = engine.Up()
		case "down":
			operationErr = engine.Down()
		case "rebind":
			operationErr = engine.BindUpdate()
		case "wake":
			operationErr = engine.Wake(time.Now())
		case "quit":
			return reply("ok")
		default:
			if err := reply("err unknown command"); err != nil {
				return errors.New("control write failed")
			}
			continue
		}
		if operationErr != nil {
			// Upstream errors can contain input, including keys. Never format them.
			logger.Print("control operation failed")
			if err := reply("err operation failed"); err != nil {
				return errors.New("control write failed")
			}
		} else {
			if command == "up" || command == "down" || command == "rebind" {
				logger.Print("tunnel " + command)
			}
			if err := reply("ok"); err != nil {
				return errors.New("control write failed")
			}
		}
	}
}
