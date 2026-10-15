# OpenRung source adapter

Raha treats OpenRung as one external relay source, not as Raha's protocol or identity.

## Trust boundary

OpenRung's production relay directory is not ordinary JSON that Raha should fetch and trust. The upstream `brokerapi` module verifies freshness and an Ed25519 signature over the raw relay-list response. Production responses from non-loopback brokers must verify against OpenRung's compiled production key set.

For that reason `OpenRungProvider` remains fail-closed until Raha integrates a pinned, versioned verifier. Do not replace it with a generic HTTP GET + JSON parser.

## Planned adapter

1. Call the `rahacore` native binding (`FetchVerifiedRelays`) around a pinned release of upstream `brokerapi`.
2. Let `brokerapi` perform broker-front selection, TLS policy, redirect refusal, signature verification, and freshness checks.
3. Receive only the exact verified relay JSON bytes.
4. Decode those verified bytes into Raha's own `RelayCandidate` model.
5. Probe candidates from the user's current network.
6. Rank locally and pass the winner to Raha's tunnel engine.

This keeps Raha's discovery/ranking/UI independent while preserving the source network's authentication boundary.

## Licensing

`brokerapi` is GPL-3.0-or-later. If Raha distributes a binary containing it, Raha must comply with the applicable GPL source and notice obligations. Pin a released `brokerapi/vX.Y.Z` tag rather than tracking upstream `main`.
