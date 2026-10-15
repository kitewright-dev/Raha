// SPDX-License-Identifier: GPL-3.0-or-later

// Package rahacore is Raha's single gomobile surface. It holds two things that
// must never leak into Kotlin: OpenRung directory verification (brokerapi) and
// the packet engine (Xray-core VLESS + REALITY + Vision over the Android TUN).
package rahacore

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/openrung/openrung/brokerapi"
)

// FetchVerifiedRelays races OpenRung's broker fronts and returns the exact
// relay-directory JSON bytes only after brokerapi has verified the Ed25519
// signature, freshness, channel and wire schema. Kotlin must never fall back to
// its own HTTP fetch when this fails.
func FetchVerifiedRelays(appVersion string, androidAPI string, limit int) (string, error) {
	if limit < 1 || limit > 50 {
		return "", fmt.Errorf("relay limit out of range")
	}
	api := brokerapi.NewClient(nil, brokerapi.Options{
		AppVersion:      strings.TrimSpace(appVersion),
		Platform:        brokerapi.PlatformAndroid,
		PlatformVersion: strings.TrimSpace(androidAPI),
	})
	defer api.CloseIdleConnections()

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	result, err := api.FirstReachable(ctx, brokerapi.BrokerCandidates(""), brokerapi.ListOptions{Limit: limit})
	if err != nil {
		return "", err
	}
	if !result.RelayList.SignatureVerified {
		return "", fmt.Errorf("relay directory signature was not verified")
	}
	body := result.RelayList.JSON()
	if len(body) == 0 {
		return "", fmt.Errorf("broker returned an empty verified relay directory")
	}
	return string(body), nil
}
