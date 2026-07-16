# ant-mobile-android

Autonomi's Android reference app — **external-signer paid uploads over
WalletConnect**. A Compose app that exercises the Autonomi SDK end-to-end: pick a
file, preview the storage cost, pay from the user's own wallet (the app never
holds a key), upload, and round-trip it back via Download.

Mirrors the iOS reference, [`ant-mobile-ios`](https://github.com/WithAutonomi/ant-mobile-ios),
and is the canonical worked example for
[`ant-sdk/docs/mobile-external-signer.md`](https://github.com/WithAutonomi/ant-sdk/blob/main/docs/mobile-external-signer.md).

## What it does

Four tabs:

- **Uploads** — pick a file; shows a fast sampled cost preview
  (`estimateFileCost`) while it prepares the real quote (`prepareFileUpload`),
  then a confirm sheet. On approve it runs the external-signer paid flow
  (`paymentTransactions` → sign each tx via the connected wallet →
  `waitForReceipt` → `finalizeUpload`/`finalizeUploadMerkle` with live progress),
  handling **both** the wave and merkle payment shapes.
- **Downloads** — paste a data-map address to stream content back to a file
  (`downloadPublicToFile`, with progress).
- **Wallet** — connect a self-custody wallet via **Reown AppKit** (WalletConnect);
  shows address, chain, and ANT/ETH balances.
- **Settings** — a **Developer** section to point the app at a devnet host.

All ABI encoding, receipt polling, and the merkle-winner lookup live in the SDK —
the app builds no calldata itself. (`waitForReceipt` / `merkleWinnerPoolHash` /
`networkInfo` are SDK free functions; only `paymentTransactions` / `prepare*` /
`finalize*` are `Client` methods.)

## Prerequisites

- **Android Studio** *or* a command-line Android toolchain:
  - `brew install --cask android-commandlinetools`
  - JDK 17: `brew install openjdk@17` (`export JAVA_HOME=/opt/homebrew/opt/openjdk@17`)
  - Android SDK platform 34 + build-tools 34.0.0 (+ an arm64-v8a emulator image
    if not using a physical device). Set `ANDROID_HOME` (e.g.
    `~/Library/Android/sdk`).
  - Build with the bundled wrapper (`./gradlew`, Gradle 8.9 — required for AGP 8.5.2).
    When a change doesn't seem to take, use `./gradlew clean assembleDebug`
    (a "1s BUILD SUCCESSFUL" can skip `compileDebugKotlin`).
- A physical device or emulator, and — for the paid flow — a WalletConnect wallet
  (e.g. MetaMask) funded on the target chain (Arbitrum Sepolia for a test devnet).

### Autonomi SDK dependency

Consumes the published SDK from the [ant-maven](https://github.com/WithAutonomi/ant-maven)
GitHub Pages Maven repo — `implementation("com.autonomi:ant-android:0.0.7")`
(repo declared in `settings.gradle.kts`). Bump the version to adopt a newer SDK
release; no local AAR needed. The SDK pulls JNA + `kotlinx-coroutines-core`
transitively.

## Connecting to a devnet

The app connects from a **devnet manifest**. Set **Settings → Developer → devnet
host** to the host serving the manifest API, e.g. `192.168.0.62:8088`; the app
fetches `http://<host>/api/devnet-manifest.json` over HTTP and polls
`/api/info` for liveness.

- **Physical device on the same LAN (recommended, and how e2e is validated):**
  point the devnet host at the machine's LAN IP. Cross-device LAN devnet works
  with the **released** SDK (needs `ant-android` ≥ 0.0.7) — the SDK's
  `connectFromDevnetManifest*` auto-detects a LAN vs loopback manifest and binds
  `0.0.0.0` when a non-loopback bootstrap addr is present.
- **Emulator:** `127.0.0.1` inside the emulator is the emulator, not the host,
  and the NAT does not reliably establish QUIC to loopback-bound devnet services —
  prefer a physical device on the LAN. (Historical NAT-rewrite recipe using
  `10.0.2.2` is no longer the supported path.)

To stand up a LAN devnet, use the `ant-devnet` harness in
[`ant-node`](https://github.com/WithAutonomi/ant-node) (≥ v0.14.4; not shipped
in the release tarballs — run it from a source checkout):

```sh
# Anvil-backed LAN devnet (embedded local EVM — frictionless, no wallet needed):
cargo run --release --bin ant-devnet -- --preset small --enable-evm \
  --host <lan-ip> --serve-port 8088

# Arbitrum Sepolia-backed LAN devnet (real external-signer paid flow):
cargo run --release --bin ant-devnet -- --preset small \
  --host <lan-ip> --evm-network arbitrum-sepolia --serve-port 8088
```

`--host` makes the nodes advertise your LAN IP; `--serve-port` exposes the
manifest API this app fetches (`/api/devnet-manifest.json` + `/api/info`).

## Build & run

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :app:installDebug
adb shell am start -n com.autonomi.examples.antdemo/.MainActivity
```

## WalletConnect setup

Get a project id from <https://dashboard.reown.com> and set `reownProjectId` in
`MainActivity.kt` (Reown project ids are public client identifiers — safe to
ship). Connect from the **Wallet** tab; approve in the wallet app. For a test
devnet, fund the wallet on **Arbitrum Sepolia** (chain 421614). If a wallet
session gets into a bad state, clearing app data resets the WalletConnect
session/SQLite stores.

## Caveats

- This is a **devnet** reference app; the devnet-manifest connect path is
  test-only. Production bootstrap discovery/config differ.
- The app plants `HOME` via a libc `setenv` shim before the first FFI call
  (`AntFfiBootstrap.kt`) because `ant-core` reads `$HOME` and otherwise aborts
  with `HomeDirNotFound`. Workaround pending an explicit data-dir FFI arg.
- The bundled signing config is `debug` for one-tap install/run. Don't ship to
  Play with this.
