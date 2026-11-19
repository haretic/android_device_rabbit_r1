// SPDX-License-Identifier: MIT
package main

import (
	"bytes"
	"encoding/hex"
	"errors"
	"io"
	"log"
	"strings"
	"testing"
	"time"
)

type fakeBackend struct {
	set                                      string
	get                                      string
	sets, ups, downs, rebinds, wakes, closes int
	opErr                                    error
}

func (f *fakeBackend) IpcSet(text string) error { f.set = text; f.sets++; return f.opErr }
func (f *fakeBackend) IpcGet() (string, error)  { return f.get, f.opErr }
func (f *fakeBackend) Up() error                { f.ups++; return f.opErr }
func (f *fakeBackend) Down() error              { f.downs++; return f.opErr }
func (f *fakeBackend) BindUpdate() error        { f.rebinds++; return f.opErr }
func (f *fakeBackend) Wake(time.Time) error     { f.wakes++; return f.opErr }
func (f *fakeBackend) Close()                   { f.closes++ }

type memoryControl struct {
	in  *strings.Reader
	out bytes.Buffer
}

func (c *memoryControl) Read(p []byte) (int, error)  { return c.in.Read(p) }
func (c *memoryControl) Write(p []byte) (int, error) { return c.out.Write(p) }

func TestControlCommandsAndGetFraming(t *testing.T) {
	secret := strings.Repeat("11", 32)
	config := "private_key=" + secret + "\nreplace_peers=true\npublic_key=" + strings.Repeat("22", 32) +
		"\nendpoint=127.0.0.1:51820\nallowed_ip=10.0.0.2/32\npersistent_keepalive_interval=25\n\n"
	engine := &fakeBackend{get: "last_handshake_time_sec=42\nrx_bytes=9\n"}
	control := &memoryControl{in: strings.NewReader("set\n" + config + "get\nup\ndown\nrebind\nwake\nquit\nup\n")}
	var logs bytes.Buffer
	if err := serve(control, engine, log.New(&logs, "", 0)); err != nil {
		t.Fatal("serve failed")
	}
	want := "ok\nlast_handshake_time_sec=42\nrx_bytes=9\n\nok\nok\nok\nok\nok\n"
	if control.out.String() != want {
		t.Fatal("incorrect reply framing")
	}
	if engine.set != config || engine.sets != 1 || engine.ups != 1 || engine.downs != 1 || engine.rebinds != 1 || engine.wakes != 1 || engine.closes != 1 {
		t.Fatal("commands were lost, repeated, or executed after quit")
	}
	if strings.Contains(logs.String(), secret) {
		t.Fatal("key leaked to logs")
	}
}

func TestEmptyGet(t *testing.T) {
	control := &memoryControl{in: strings.NewReader("get\nquit\n")}
	if err := serve(control, &fakeBackend{}, log.New(io.Discard, "", 0)); err != nil {
		t.Fatal(err)
	}
	if control.out.String() != "\nok\n" {
		t.Fatal("empty get must have exactly one terminating empty line")
	}
}

func TestQuitAndEOF(t *testing.T) {
	for _, input := range []string{"", "quit\n", "up", "set\nprivate_key=" + strings.Repeat("11", 32) + "\n"} {
		engine := &fakeBackend{}
		control := &memoryControl{in: strings.NewReader(input)}
		if err := serve(control, engine, log.New(io.Discard, "", 0)); err != nil {
			t.Fatal("EOF/quit was not clean")
		}
		if engine.closes != 1 || engine.ups != 0 || engine.sets != 0 {
			t.Fatal("partial command applied or device not closed")
		}
	}
}

func TestRedaction(t *testing.T) {
	secret := strings.Repeat("ac", 32)
	for _, command := range []string{"up", "down", "rebind", "wake", "get", "set\nprivate_key=" + secret + "\n"} {
		var logs bytes.Buffer
		engine := &fakeBackend{opErr: errors.New("secret input: " + secret)}
		control := &memoryControl{in: strings.NewReader(command + "\nquit\n")}
		if err := serve(control, engine, log.New(&logs, "", 0)); err != nil {
			t.Fatal("serve failed")
		}
		if strings.Contains(logs.String(), secret) || strings.Contains(control.out.String(), secret) {
			t.Fatal("upstream error leaked key")
		}
	}
	control := &memoryControl{in: strings.NewReader(secret + "\nset\nsecret_field=" + secret + "\n\nquit\n")}
	engine := &fakeBackend{}
	if err := serve(control, engine, log.New(io.Discard, "", 0)); err != nil {
		t.Fatal(err)
	}
	if engine.sets != 0 || strings.Contains(control.out.String(), secret) {
		t.Fatal("invalid input was applied or echoed")
	}
}

func TestConfigValidation(t *testing.T) {
	key := strings.Repeat("12", 32)
	prefix := "private_key=" + key + "\nlisten_port=0\nreplace_peers=true\npublic_key=" + key + "\n"
	for _, suffix := range []string{
		"endpoint=127.0.0.1:51820\nallowed_ip=0.0.0.0/0\nreplace_allowed_ips=true\npersistent_keepalive_interval=25\npreshared_key=" + key,
		"endpoint=[::1]:51820\nallowed_ip=::/0\nprotocol_version=1",
		"remove=true", "update_only=true",
	} {
		if validateConfig(prefix+suffix+"\n\n") != nil {
			t.Fatal("valid UAPI rejected")
		}
	}
	for _, input := range []string{
		"fwmark=0\n", "mtu=1420\n", "address=10.0.0.1\n", "private_key=short\n", "listen_port=65536\n",
		"replace_peers=false\n", "endpoint=127.0.0.1:42\n", prefix + "private_key=" + key,
		prefix + "endpoint=example.invalid:51820", prefix + "endpoint=[fe80::1%wlan0]:51820",
		prefix + "endpoint=127.0.0.1:0", prefix + "allowed_ip=not-an-ip", prefix + "persistent_keepalive_interval=-1",
		prefix + "preshared_key=" + strings.Repeat("zz", 32), prefix + "remove=false",
	} {
		if validateConfig(input) == nil {
			t.Fatal("invalid or privileged UAPI accepted")
		}
	}
}

func TestMalformedAndBoundedFrames(t *testing.T) {
	for _, input := range []string{
		strings.Repeat("x", maxLineBytes+1) + "\n",
		"\xff\n",
		"set\n" + strings.Repeat("allowed_ip=10.0.0.0/8\n", maxConfigBytes/20+1) + "\n",
	} {
		control := &memoryControl{in: strings.NewReader(input)}
		engine := &fakeBackend{}
		if serve(control, engine, log.New(io.Discard, "", 0)) == nil {
			t.Fatal("malformed frame accepted")
		}
		if engine.sets != 0 || engine.closes != 1 {
			t.Fatal("malformed frame applied or leaked device")
		}
	}
	control := &memoryControl{in: strings.NewReader("\r\nup\r\ndown\r\nquit\r\n")}
	if err := serve(control, &fakeBackend{}, log.New(io.Discard, "", 0)); err != nil {
		t.Fatal("CRLF rejected")
	}
}

func TestKeyCommands(t *testing.T) {
	var output, logs bytes.Buffer
	if run([]string{"genkey"}, strings.NewReader(""), &output, &logs) != 0 {
		t.Fatal("genkey failed")
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 || len(lines[0]) != 64 || len(lines[1]) != 64 || logs.Len() != 0 {
		t.Fatal("incorrect key output")
	}
	privateBytes, err := hex.DecodeString(lines[0])
	if err != nil || privateBytes[0]&7 != 0 || privateBytes[31]&128 != 0 || privateBytes[31]&64 == 0 {
		t.Fatal("generated private scalar is not clamped")
	}
	clear(privateBytes)
	var public bytes.Buffer
	if run([]string{"pubkey"}, strings.NewReader(lines[0]+"\n"), &public, &logs) != 0 || public.String() != lines[1]+"\n" {
		t.Fatal("genkey/pubkey disagree")
	}
	known, err := publicKey("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
	if err != nil || known != "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a" {
		t.Fatal("curve25519 RFC vector mismatch")
	}
	for _, input := range []string{"", "not-a-key", strings.Repeat("a", 129)} {
		output.Reset()
		logs.Reset()
		if run([]string{"pubkey"}, strings.NewReader(input), &output, &logs) == 0 || output.Len() != 0 {
			t.Fatal("invalid key accepted")
		}
		if input != "" && strings.Contains(logs.String(), input) {
			t.Fatal("invalid key echoed")
		}
	}
	for _, args := range [][]string{{"wg", "--tun-fd", "3", "--control-fd", "4"}, {"wg"}, {"wg", "--socket", "bad\x00name"}} {
		output.Reset()
		logs.Reset()
		if run(args, strings.NewReader(""), &output, &logs) == 0 || output.Len() != 0 {
			t.Fatal("obsolete/invalid invocation accepted")
		}
	}
}
