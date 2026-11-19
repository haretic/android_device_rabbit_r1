// SPDX-License-Identifier: MIT
package main

import (
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"
	"unicode/utf8"
)

func main() { os.Exit(run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr)) }

func run(args []string, in io.Reader, out, errout io.Writer) int {
	logger := log.New(errout, "r1-tunneld: ", log.LstdFlags|log.LUTC)
	fail := func(message string) int { logger.Print(message); return 1 }
	if len(args) == 0 {
		return fail("usage: r1-tunneld wg --socket NAME | genkey | pubkey")
	}
	switch args[0] {
	case "genkey":
		if len(args) != 1 {
			return fail("genkey takes no arguments")
		}
		private, public, err := generateKey()
		if err != nil {
			return fail("key generation failed")
		}
		// This explicit key-generation command is the only private-key stdout output.
		if _, err := fmt.Fprintf(out, "%s\n%s\n", private, public); err != nil {
			return fail("key output failed")
		}
		return 0
	case "pubkey":
		if len(args) != 1 {
			return fail("pubkey takes no arguments")
		}
		text, err := io.ReadAll(io.LimitReader(in, 129))
		if err != nil || len(text) > 128 {
			return fail("invalid private key input")
		}
		public, err := publicKey(strings.TrimSpace(string(text)))
		clear(text)
		if err != nil {
			return fail("invalid private key input")
		}
		if _, err := fmt.Fprintln(out, public); err != nil {
			return fail("key output failed")
		}
		return 0
	case "wg":
		flags := flag.NewFlagSet("wg", flag.ContinueOnError)
		flags.SetOutput(io.Discard) // flag errors can quote arbitrary argument values.
		name := flags.String("socket", "", "abstract control socket name")
		if flags.Parse(args[1:]) != nil || flags.NArg() != 0 || *name == "" ||
			len(*name) > 107 || strings.ContainsRune(*name, 0) || !utf8.ValidString(*name) {
			return fail("usage: r1-tunneld wg --socket NAME")
		}
		connection, err := net.DialUnix("unix", nil, &net.UnixAddr{Net: "unix", Name: "\x00" + *name})
		if err != nil {
			return fail("control connection failed")
		}
		if err := checkControlPeer(connection); err != nil {
			connection.Close()
			return fail("control peer has a different UID")
		}
		if err := handleConnection(connection, attachTUN(logger), logger); err != nil {
			return fail("tunnel session failed")
		}
		return 0
	default:
		return fail("unknown command")
	}
}
