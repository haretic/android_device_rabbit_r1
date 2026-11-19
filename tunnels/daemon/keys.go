// SPDX-License-Identifier: MIT
package main

import (
	"crypto/rand"
	"encoding/hex"
	"errors"

	"golang.org/x/crypto/curve25519"
)

func generateKey() (private, public string, err error) {
	var key [32]byte
	if _, err = rand.Read(key[:]); err != nil {
		return "", "", err
	}
	// The same scalar clamping and curve implementation used by the engine.
	key[0] &= 248
	key[31] = key[31]&127 | 64
	private = hex.EncodeToString(key[:])
	public, err = publicKey(private)
	clear(key[:])
	return
}

func publicKey(private string) (string, error) {
	var key, public [32]byte
	if len(private) != hex.EncodedLen(len(key)) {
		return "", errors.New("invalid private key")
	}
	if _, err := hex.Decode(key[:], []byte(private)); err != nil {
		return "", errors.New("invalid private key")
	}
	curve25519.ScalarBaseMult(&public, &key)
	clear(key[:])
	return hex.EncodeToString(public[:]), nil
}
