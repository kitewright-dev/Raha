// SPDX-License-Identifier: GPL-3.0-or-later
package brokerbridge

import (
    "context"
    "fmt"
    "time"

    "github.com/openrung/openrung/brokerapi"
)

// Client is a deliberately narrow gomobile surface. Verification, freshness,
// redirect refusal and broker-front policy remain inside upstream brokerapi.
type Client struct {
    api *brokerapi.Client
}

func NewClient(appVersion string) *Client {
    return &Client{api: brokerapi.NewClient(nil, brokerapi.Options{
        AppVersion: appVersion,
        Platform: brokerapi.PlatformAndroid,
    })}
}

// FetchVerifiedRelays returns the exact JSON bytes after brokerapi has
// authenticated and validated the relay directory. Kotlin must never perform a
// fallback HTTP fetch when this operation fails.
func (c *Client) FetchVerifiedRelays(limit int) (string, error) {
    if c == nil || c.api == nil {
        return "", fmt.Errorf("broker client is not initialized")
    }
    if limit < 1 || limit > 50 {
        return "", fmt.Errorf("relay limit out of range")
    }

    ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
    defer cancel()

    result, err := c.api.FirstReachable(
        ctx,
        brokerapi.BrokerCandidates(""),
        brokerapi.ListOptions{Limit: limit},
    )
    if err != nil {
        return "", err
    }

    body := result.RelayList.JSON()
    if len(body) == 0 {
        return "", fmt.Errorf("broker returned an empty verified relay directory")
    }
    return string(body), nil
}
