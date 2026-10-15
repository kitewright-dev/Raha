# Raha Architecture v0.1

## Principle

Raha owns the Android client, provider abstraction, candidate normalization, probing, ranking and connection lifecycle. Relay networks are external providers.

## Candidate model

Every provider should eventually normalize its result to a model equivalent to:

```text
RelayCandidate
- providerId
- relayId
- host
- port
- transport
- security
- serverName
- publicKey
- shortId
- fingerprint
- flow
- expiresAt
- metadata
```

Provider-specific credentials and signed envelopes must remain isolated from generic UI code.

## Discovery

`DiscoveryProvider` is responsible for:

- fetching a directory
- authenticating/verifying it when supported
- rejecting expired or malformed entries
- translating usable entries into `RelayCandidate`

The first research adapter targets the public OpenRung relay directory contract. OpenRung documents `/api/v1/relays` and uses signed relay-list responses; Raha should preserve verification rather than treating arbitrary JSON as trusted configuration.

## Selection

Initial ranking should stay deliberately simple:

1. reject expired/malformed candidates
2. TCP reachability
3. protocol handshake success
4. measured connection latency
5. recent success/failure history

Do not optimize speed before reliable connection is demonstrated.

## Connection ladder v0.1

```text
Discover
  -> Verify
  -> Normalize
  -> Probe small candidate set
  -> Direct VLESS + REALITY + Vision
  -> Connected / try next candidate
```

WSS/CDN, NAT punching, relay hubs and other fallback paths are explicitly deferred until the direct path works.

## Android boundary

The Android application should contain:

- `RahaVpnService`: Android VPN lifecycle and TUN ownership
- `rahacore` (Go, gomobile): verified discovery + Xray-core engine on the TUN fd
- `RelayRepository`: aggregates discovery providers
- `RelayRanker`: candidate ordering
- `ConnectionManager`: connection state machine

The UI observes state and does not construct transport configuration itself.

## Security notes

- Production discovery must use HTTPS.
- Signed provider responses must be verified before use.
- Provider secrets/tickets should not cross into presentation code.
- Logs must not print private credentials or complete connection secrets.
- Third-party infrastructure can disappear or change policy; provider failure must not corrupt the rest of Raha.

## Independence

Raha is not intended to masquerade as OpenRung. If GPL OpenRung modules or sing-box/libbox are eventually linked into distributed Raha binaries, Raha must comply with the applicable GPL source/notice obligations. A clean provider implementation that merely speaks a documented compatible network protocol should remain separated from upstream source code wherever practical.
