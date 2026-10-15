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

## How it works now (v0.1.0)

Tap connect and Raha does this:

1. asks the OpenRung broker for relays through the pinned `brokerapi` (signature + freshness checked in native code, no plain HTTP fallback)
2. drops anything that isn't VLESS + REALITY + Vision with a valid lease (leases are checked against the broker's signed `server_time`, so a wrong phone clock doesn't kill every relay)
3. quick TCP check on all of them, keeps the best 6
4. real REALITY handshake through each of those 6 and fetches a 204 page, picks the fastest one that actually worked
5. only then opens the VPN and hands the TUN fd to Xray-core's tun inbound

If nothing passes step 4 the VPN never comes up, so you don't end up with a dead tunnel eating your traffic.

## Layout

- `native/rahacore` Go core built with gomobile: `brokerapi` for discovery, Xray-core for the engine
- `app/` Android app (Kotlin, no XML layouts)
- `scripts/build-rahacore-android.sh` builds `app/libs/rahacore.aar`
- CI builds the APK on every push to main, and cuts a release whenever `versionName` changes

## Build

Needs Go (version from `native/rahacore/go.mod`), JDK 17, Android SDK 35 and an NDK.

```sh
./scripts/build-rahacore-android.sh
gradle :app:assembleRelease
```

The repo ships a sideload signing key so every build installs over the last one. Don't use it for a store release.

## License

GPL-3.0-or-later, because the app links OpenRung's `brokerapi` (GPL-3.0-or-later). Xray-core is MPL-2.0.

## Status

Experimental. First build that actually tunnels traffic, still needs real-network testing.
