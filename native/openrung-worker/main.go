// SPDX-License-Identifier: GPL-3.0-or-later
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/openrung/openrung/brokerapi"
	xnet "github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/infra/conf/serial"
	_ "github.com/xtls/xray-core/main/distro/all"
)

const protocolVersion = 1
const probeURL = "https://www.gstatic.com/generate_204"

type request struct {
	V int `json:"v"`
	ID int64 `json:"id"`
	Cmd string `json:"cmd"`
	Relay json.RawMessage `json:"relay,omitempty"`
	AppVersion string `json:"appVersion,omitempty"`
	AndroidAPI string `json:"androidApi,omitempty"`
	Limit int `json:"limit,omitempty"`
	TimeoutMs int `json:"timeoutMs,omitempty"`
	TunFD int `json:"tunFd,omitempty"`
	MTU int `json:"mtu,omitempty"`
}

type response struct { V int `json:"v"`; ID int64 `json:"id"`; OK bool `json:"ok"`; Result any `json:"result,omitempty"`; Error string `json:"error,omitempty"` }
type relay struct { Host string `json:"host"`; Port int `json:"port"`; UUID string `json:"uuid"`; Flow string `json:"flow"`; PublicKey string `json:"publicKey"`; ShortID string `json:"shortId"`; ServerName string `json:"serverName"` }

func reply(w *bufio.Writer, r response) { _ = json.NewEncoder(w).Encode(r); _ = w.Flush() }

func discover(req request) (any, error) {
	limit := req.Limit
	if limit == 0 { limit = 12 }
	if limit < 1 || limit > 50 { return nil, fmt.Errorf("relay limit out of range") }
	api := brokerapi.NewClient(nil, brokerapi.Options{AppVersion: strings.TrimSpace(req.AppVersion), Platform: brokerapi.PlatformAndroid, PlatformVersion: strings.TrimSpace(req.AndroidAPI)})
	defer api.CloseIdleConnections()
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second); defer cancel()
	result, err := api.FirstReachable(ctx, brokerapi.BrokerCandidates(""), brokerapi.ListOptions{Limit: limit})
	if err != nil { return nil, err }
	if !result.RelayList.SignatureVerified { return nil, fmt.Errorf("relay directory signature was not verified") }
	body := result.RelayList.JSON()
	if len(body) == 0 { return nil, fmt.Errorf("broker returned empty verified relay directory") }
	var value any
	if err := json.Unmarshal(body, &value); err != nil { return nil, fmt.Errorf("verified relay json: %w", err) }
	return map[string]any{"directory": value}, nil
}

func parseRelay(raw json.RawMessage) (relay, error) {
	var r relay
	if len(raw) == 0 { return r, fmt.Errorf("relay is required") }
	if err := json.Unmarshal(raw, &r); err != nil { return r, fmt.Errorf("bad relay json: %w", err) }
	r.Host = strings.Trim(strings.TrimSpace(r.Host), "[]")
	if r.Host == "" || r.Port < 1 || r.Port > 65535 { return r, fmt.Errorf("relay endpoint is invalid") }
	if r.UUID == "" || r.PublicKey == "" || r.ServerName == "" { return r, fmt.Errorf("relay credentials are incomplete") }
	if r.Flow != "xtls-rprx-vision" { return r, fmt.Errorf("unsupported flow %q", r.Flow) }
	return r, nil
}

func proxyOutbound(r relay) map[string]any { return map[string]any{
	"tag":"proxy", "protocol":"vless",
	"settings":map[string]any{"address":r.Host,"port":r.Port,"id":r.UUID,"flow":r.Flow,"encryption":"none"},
	"streamSettings":map[string]any{"network":"tcp","security":"reality","realitySettings":map[string]any{"serverName":r.ServerName,"fingerprint":"chrome","publicKey":r.PublicKey,"shortId":r.ShortID,"spiderX":"/"}},
	"mux":map[string]any{"enabled":false},
} }

func buildInstance(cfg map[string]any) (*core.Instance,error) { raw,err:=json.Marshal(cfg); if err!=nil{return nil,err}; config,err:=serial.LoadJSONConfig(bytes.NewReader(raw)); if err!=nil{return nil,fmt.Errorf("xray config: %w",err)}; return core.New(config) }
func fetch204(inst *core.Instance, timeoutMs int) (int64,error) {
	if timeoutMs < 1000 { timeoutMs = 1000 }
	tr:=&http.Transport{DisableKeepAlives:true,DialContext:func(ctx context.Context,_ string,addr string)(net.Conn,error){ host,ptext,err:=net.SplitHostPort(addr);if err!=nil{return nil,err};pi,err:=strconv.Atoi(ptext);if err!=nil{return nil,err};p,err:=xnet.PortFromInt(uint32(pi));if err!=nil{return nil,err};return core.Dial(ctx,inst,xnet.TCPDestination(xnet.ParseAddress(host),p)) }}
	defer tr.CloseIdleConnections(); client:=&http.Client{Transport:tr,Timeout:time.Duration(timeoutMs)*time.Millisecond}; start:=time.Now(); resp,err:=client.Get(probeURL);if err!=nil{return 0,err};_,_=io.Copy(io.Discard,io.LimitReader(resp.Body,4096));_=resp.Body.Close();if resp.StatusCode!=http.StatusNoContent{return 0,fmt.Errorf("probe returned HTTP %d",resp.StatusCode)};return time.Since(start).Milliseconds(),nil
}
func probe(req request)(any,error){ r,err:=parseRelay(req.Relay);if err!=nil{return nil,err};inst,err:=buildInstance(map[string]any{"log":map[string]any{"loglevel":"none"},"outbounds":[]any{proxyOutbound(r)}});if err!=nil{return nil,err};if err:=inst.Start();err!=nil{return nil,err};defer inst.Close();ms,err:=fetch204(inst,req.TimeoutMs);if err!=nil{return nil,err};return map[string]any{"latencyMs":ms},nil }

func main() {
	in:=bufio.NewScanner(os.Stdin);in.Buffer(make([]byte,64*1024),2*1024*1024);out:=bufio.NewWriter(os.Stdout)
	for in.Scan(){var req request;if err:=json.Unmarshal(in.Bytes(),&req);err!=nil{reply(out,response{V:protocolVersion,OK:false,Error:"bad request: "+err.Error()});continue};if req.V!=protocolVersion{reply(out,response{V:protocolVersion,ID:req.ID,OK:false,Error:fmt.Sprintf("unsupported protocol version %d",req.V)});continue}
		var result any;var err error
		switch req.Cmd { case "discover": result,err=discover(req); case "probe": result,err=probe(req); case "start","health","stop": err=fmt.Errorf("command not wired yet"); default: err=fmt.Errorf("unknown command") }
		if err!=nil{reply(out,response{V:protocolVersion,ID:req.ID,OK:false,Error:err.Error()})}else{reply(out,response{V:protocolVersion,ID:req.ID,OK:true,Result:result})}
	}
	if err:=in.Err();err!=nil{fmt.Fprintln(os.Stderr,"openrung-worker stdin:",err)}
}
