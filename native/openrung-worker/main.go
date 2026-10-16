// SPDX-License-Identifier: GPL-3.0-or-later
//
// openrung-worker is the process boundary used by Parseh. It deliberately lives
// outside the gomobile surface so Pol/IPtProxy and OpenRung never load two Go
// runtimes into the same Android process.
package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
)

const protocolVersion = 1

type request struct {
	V         int             `json:"v"`
	ID        int64           `json:"id"`
	Cmd       string          `json:"cmd"`
	Relay     json.RawMessage `json:"relay,omitempty"`
	AppVersion string         `json:"appVersion,omitempty"`
	AndroidAPI string         `json:"androidApi,omitempty"`
	Limit     int             `json:"limit,omitempty"`
	TimeoutMs int             `json:"timeoutMs,omitempty"`
	TunFD     int             `json:"tunFd,omitempty"`
	MTU       int             `json:"mtu,omitempty"`
}

type response struct {
	V      int `json:"v"`
	ID     int64 `json:"id"`
	OK     bool `json:"ok"`
	Result any `json:"result,omitempty"`
	Error  string `json:"error,omitempty"`
}

func reply(w *bufio.Writer, r response) {
	_ = json.NewEncoder(w).Encode(r)
	_ = w.Flush()
}

func main() {
	in := bufio.NewScanner(os.Stdin)
	// Relay-directory responses can be larger than Scanner's default token.
	in.Buffer(make([]byte, 64*1024), 2*1024*1024)
	out := bufio.NewWriter(os.Stdout)

	for in.Scan() {
		var req request
		if err := json.Unmarshal(in.Bytes(), &req); err != nil {
			reply(out, response{V: protocolVersion, OK: false, Error: "bad request: " + err.Error()})
			continue
		}
		if req.V != protocolVersion {
			reply(out, response{V: protocolVersion, ID: req.ID, OK: false, Error: fmt.Sprintf("unsupported protocol version %d", req.V)})
			continue
		}

		// The process/IPC framing lands separately from the engine adapter on
		// purpose. Until commands are wired to rahacore, fail closed instead of
		// pretending a candidate is usable.
		switch req.Cmd {
		case "discover", "probe", "start", "health", "stop":
			reply(out, response{V: protocolVersion, ID: req.ID, OK: false, Error: "command not wired yet"})
		default:
			reply(out, response{V: protocolVersion, ID: req.ID, OK: false, Error: "unknown command"})
		}
	}

	if err := in.Err(); err != nil {
		fmt.Fprintln(os.Stderr, "openrung-worker stdin:", err)
	}
}
