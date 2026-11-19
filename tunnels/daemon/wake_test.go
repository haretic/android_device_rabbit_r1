// SPDX-License-Identifier: MIT
package main

import (
	"encoding/hex"
	"fmt"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/device"
)

type handshakeCounter struct {
	expired, sent int
	retry         bool
}

func (p *handshakeCounter) ExpireCurrentKeypairs() { p.expired++ }
func (p *handshakeCounter) SendHandshakeInitiation(retry bool) error {
	p.sent++
	p.retry = retry
	return nil
}

func TestWakeWallClockSelection(t *testing.T) {
	now := time.Now()
	var config string
	peers := map[device.NoisePublicKey]*handshakeCounter{}
	for i, age := range []time.Duration{121 * time.Second, 120 * time.Second, 119 * time.Second, -time.Hour, 0, 200 * time.Second} {
		var key device.NoisePublicKey
		key[0] = byte(i + 1)
		peers[key] = &handshakeCounter{}
		stamp := now.Add(-age)
		if i == 4 {
			stamp = time.Unix(0, 0)
		}
		config += "public_key=" + hex.EncodeToString(key[:]) + "\n"
		if i != 5 {
			config += "endpoint=127.0.0.1:51820\n"
		}
		config += fmt.Sprintf("last_handshake_time_sec=%d\nlast_handshake_time_nsec=%d\n", stamp.Unix(), stamp.Nanosecond())
	}
	if err := wakePeers(config, now, func(key device.NoisePublicKey) handshakePeer { return peers[key] }); err != nil {
		t.Fatal(err)
	}
	for key, peer := range peers {
		want := 0
		if key[0] == 1 || key[0] == 5 {
			want = 1
		}
		if peer.expired != want || peer.sent != want || peer.retry {
			t.Fatal("wake chose wrong peer, failed to expire, or used a retry")
		}
	}
	if err := (&engine{}).Wake(now); err != nil {
		t.Fatal("wake changed a down tunnel")
	}
}
