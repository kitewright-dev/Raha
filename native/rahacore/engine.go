// SPDX-License-Identifier: GPL-3.0-or-later

package rahacore

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/features/stats"
	"github.com/xtls/xray-core/infra/conf/serial"

	// Registers every Xray feature (vless, reality, tun, freedom, ...).
	_ "github.com/xtls/xray-core/main/distro/all"
)

// Relay is the minimal, already-verified endpoint description Kotlin hands to
// the engine. Kotlin builds it from the signed directory; it never contains
// anything the directory did not authenticate.
type relay struct {
	Host       string `json:"host"`
	Port       int    `json:"port"`
	UUID       string `json:"uuid"`
	Flow       string `json:"flow"`
	PublicKey  string `json:"publicKey"`
	ShortID    string `json:"shortId"`
	ServerName string `json:"serverName"`
}

const probeURL = "https://www.gstatic.com/generate_204"

var (
	mu       sync.Mutex
	instance *core.Instance
)

func init() {
	// Phones kill greedy VPN processes; keep the Go heap on a short leash.
	debug.SetMemoryLimit(96 << 20)
}

func parseRelay(relayJSON string) (relay, error) {
	var r relay
	if err := json.Unmarshal([]byte(relayJSON), &r); err != nil {
		return r, fmt.Errorf("bad relay json: %w", err)
	}
	r.Host = strings.Trim(strings.TrimSpace(r.Host), "[]")
	switch {
	case r.Host == "", r.Port < 1, r.Port > 65535:
		return r, fmt.Errorf("relay endpoint is invalid")
	case r.UUID == "", r.PublicKey == "", r.ServerName == "":
		return r, fmt.Errorf("relay credentials are incomplete")
	case r.Flow != "xtls-rprx-vision":
		return r, fmt.Errorf("unsupported flow %q", r.Flow)
	}
	return r, nil
}

func proxyOutbound(r relay) map[string]any {
	return map[string]any{
		"tag":      "proxy",
		"protocol": "vless",
		"settings": map[string]any{
			"address":    r.Host,
			"port":       r.Port,
			"id":         r.UUID,
			"flow":       r.Flow,
			"encryption": "none",
		},
		"streamSettings": map[string]any{
			"network":  "tcp",
			"security": "reality",
			"realitySettings": map[string]any{
				"serverName":  r.ServerName,
				"fingerprint": "chrome",
				"publicKey":   r.PublicKey,
				"shortId":     r.ShortID,
				"spiderX":     "/",
			},
		},
		"mux": map[string]any{"enabled": false},
	}
}

func buildInstance(cfg map[string]any) (*core.Instance, error) {
	raw, err := json.Marshal(cfg)
	if err != nil {
		return nil, err
	}
	config, err := serial.LoadJSONConfig(bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("xray config: %w", err)
	}
	return core.New(config)
}

// fetch204 opens probeURL through the given instance's default outbound and
// returns the end-to-end time in milliseconds.
func fetch204(inst *core.Instance, timeoutMs int) (int64, error) {
	if timeoutMs < 1000 {
		timeoutMs = 1000
	}
	transport := &http.Transport{
		DisableKeepAlives: true,
		DialContext: func(ctx context.Context, _ string, addr string) (net.Conn, error) {
			host, portText, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			port, err := strconv.Atoi(portText)
			if err != nil {
				return nil, err
			}
			p, err := xnet.PortFromInt(uint32(port))
			if err != nil {
				return nil, err
			}
			return core.Dial(ctx, inst, xnet.TCPDestination(xnet.ParseAddress(host), p))
		},
	}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Duration(timeoutMs) * time.Millisecond}

	start := time.Now()
	resp, err := client.Get(probeURL)
	if err != nil {
		return 0, err
	}
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent {
		return 0, fmt.Errorf("probe returned HTTP %d", resp.StatusCode)
	}
	return time.Since(start).Milliseconds(), nil
}

// ProbeRelay performs a real VLESS + REALITY + Vision handshake against the
// relay and fetches a 204 page through it. It returns the end-to-end latency in
// milliseconds. A plain TCP connect proves nothing on a filtered network; this
// does.
func ProbeRelay(relayJSON string, timeoutMs int) (int64, error) {
	r, err := parseRelay(relayJSON)
	if err != nil {
		return 0, err
	}
	inst, err := buildInstance(map[string]any{
		"log":       map[string]any{"loglevel": "none"},
		"outbounds": []any{proxyOutbound(r)},
	})
	if err != nil {
		return 0, err
	}
	if err := inst.Start(); err != nil {
		return 0, err
	}
	defer inst.Close()
	return fetch204(inst, timeoutMs)
}

// HealthCheck tests the relay the running tunnel is using, through that same
// engine. It returns the round-trip time in milliseconds.
func HealthCheck(timeoutMs int) (int64, error) {
	mu.Lock()
	inst := instance
	mu.Unlock()
	if inst == nil {
		return 0, fmt.Errorf("tunnel is not running")
	}
	return fetch204(inst, timeoutMs)
}

// Traffic from engines replaced by a relay switch, so the session totals keep
// counting across switches.
var carriedUp, carriedDown int64

func liveCounter(inst *core.Instance, name string) int64 {
	if inst == nil {
		return 0
	}
	m, ok := inst.GetFeature(stats.ManagerType()).(stats.Manager)
	if !ok || m == nil {
		return 0
	}
	c := m.GetCounter(name)
	if c == nil {
		return 0
	}
	return c.Value()
}

const (
	upCounter   = "outbound>>>proxy>>>traffic>>>uplink"
	downCounter = "outbound>>>proxy>>>traffic>>>downlink"
)

// StartTunnel runs Xray with its TUN inbound bound to the VpnService file
// descriptor. The descriptor stays owned by Kotlin: Xray works on a duplicate,
// so Kotlin closes its ParcelFileDescriptor only after StopTunnel.
//
// Calling it again while a tunnel is up switches relays on the same TUN: the
// VPN stays established and only the engine behind it is replaced.
func StartTunnel(relayJSON string, tunFd int, mtu int) error {
	r, err := parseRelay(relayJSON)
	if err != nil {
		return err
	}
	if tunFd <= 0 {
		return fmt.Errorf("invalid tun fd")
	}
	if mtu < 1280 || mtu > 9000 {
		mtu = 1500
	}

	mu.Lock()
	defer mu.Unlock()
	if instance != nil {
		carriedUp += liveCounter(instance, upCounter)
		carriedDown += liveCounter(instance, downCounter)
		_ = instance.Close()
		instance = nil
	}

	fd := strconv.Itoa(tunFd)
	_ = os.Setenv("xray.tun.fd", fd)
	_ = os.Setenv("XRAY_TUN_FD", fd)

	inst, err := buildInstance(map[string]any{
		"log":   map[string]any{"loglevel": "warning"},
		"stats": map[string]any{},
		"policy": map[string]any{"system": map[string]any{
			"statsOutboundUplink":   true,
			"statsOutboundDownlink": true,
		}},
		"inbounds": []any{map[string]any{
			"tag":      "tun",
			"port":     0,
			"protocol": "tun",
			"settings": map[string]any{"name": "raha0", "mtu": mtu},
		}},
		"outbounds": []any{
			proxyOutbound(r),
			map[string]any{"tag": "block", "protocol": "blackhole"},
		},
	})
	if err != nil {
		return err
	}
	if err := inst.Start(); err != nil {
		_ = inst.Close()
		return err
	}
	instance = inst
	return nil
}

// StopTunnel stops the engine and ends the session. Safe to call more than once.
func StopTunnel() error {
	mu.Lock()
	defer mu.Unlock()
	carriedUp, carriedDown = 0, 0
	if instance == nil {
		return nil
	}
	err := instance.Close()
	instance = nil
	return err
}

// UplinkBytes is the total bytes sent through relays this session.
func UplinkBytes() int64 {
	mu.Lock()
	defer mu.Unlock()
	return carriedUp + liveCounter(instance, upCounter)
}

// DownlinkBytes is the total bytes received through relays this session.
func DownlinkBytes() int64 {
	mu.Lock()
	defer mu.Unlock()
	return carriedDown + liveCounter(instance, downCounter)
}

// IsRunning reports whether the packet engine is up.
func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return instance != nil
}

// CoreVersion is the embedded Xray-core version, for the About line.
func CoreVersion() string {
	return core.Version()
}
