# Raha

Raha is an experimental Android connectivity client focused on resilient, multi-source relay discovery.

## Goal

Build a clean Android VPN client that can discover compatible public relay/exit sources, normalize them into one internal model, test reachability, rank candidates, and connect through the best usable path.

Raha is an independent project. It is not OpenRung and is not affiliated with OpenRung.

## v0.1 plan

1. Native Android `VpnService` shell.
2. Provider abstraction for relay discovery.
3. OpenRung-compatible provider as the first experimental source.
4. Normalize candidates into Raha's own relay model.
5. Reachability/latency checks and candidate ranking.
6. Direct VLESS + REALITY + Vision connection path.
7. Add fallback transports only after direct connectivity is proven on real networks.

## Architecture

```text
Android UI
    |
RahaVpnService
    |
Raha Core
    +-- DiscoveryProvider
    |      +-- OpenRungProvider (v0.1)
    |      +-- future providers
    |
    +-- RelayNormalizer
    +-- Probe / Ranking
    +-- Connection Engine
             |
          Relay / Exit
             |
          Internet
```

## Design rules

- No Raha-operated VPS is required for the initial experiment.
- Do not copy upstream branding or UI.
- Keep source-specific code behind provider interfaces.
- Do not assume a public third-party relay network is a permanent entitlement; compatibility and usage policy must be verified.
- Prefer signed/verified discovery data where the upstream protocol provides it.
- Never silently downgrade transport security.

## Status

Early experimental scaffold. No production-ready VPN client yet.
