# ant-mobile-android

Autonomi's Android demo app — external-signer paid uploads over WalletConnect.
A Compose app that exercises the Autonomi SDK end-to-end: type a message, tap
Upload, get a chunk address back, paste it into Download to round-trip the
content.

Mirrors the iOS demo, [`ant-mobile-ios`](https://github.com/WithAutonomi/ant-mobile-ios).

## Prerequisites

- **Android Studio** *or* a working command-line Android toolchain:
  - `brew install --cask android-commandlinetools`
  - JDK 17: `brew install openjdk@17` (set `JAVA_HOME=/opt/homebrew/opt/openjdk@17`)
  - Android SDK platform 34 + build-tools 34.0.0 + the arm64-v8a emulator
    system image (`sdkmanager --install "platforms;android-34" "build-tools;34.0.0" "system-images;android-34;google_apis;arm64-v8a" "emulator"`)
  - Build with the bundled wrapper (`./gradlew`, Gradle 8.9 — required for AGP 8.5.2).
- `anvil` (Foundry): `brew install foundry` — for the local devnet.
- A booted Android emulator (or a real device on the same WiFi as
  your dev machine — see Real-device notes below).

### Autonomi SDK dependency

Consumes the published SDK from the [ant-maven](https://github.com/WithAutonomi/ant-maven)
GitHub Pages Maven repo — `implementation("com.autonomi:ant-android:0.0.3")`
(repo declared in `settings.gradle.kts`). Bump the version to adopt a newer
SDK release; no local AAR needed.

## Starting the local devnet

The demo uses `Client.connectFromDevnetManifest` against a devnet
running on the host. From a checkout of
[`ant-client`](https://github.com/WithAutonomi/ant-client):

```sh
# One-time: move any cached public bootstrap peers aside, otherwise the
# devnet's bootstrap nodes hang trying to dial unreachable hosts.
mv ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json \
   ~/Library/Caches/saorsa/bootstrap/bootstrap_cache.json.aside

cargo run --release --example start-local-devnet --features devnet
```

It writes the manifest at `~/Library/Application Support/ant/devnet-manifest.json`.

## Wiring the manifest into the emulator

The Android emulator doesn't share the host filesystem like iOS
Simulator does — and `127.0.0.1` inside the emulator is the emulator
itself, not the host. Rewrite peer/RPC addresses to `10.0.2.2` (the
emulator's NAT alias for host) and push the manifest in:

```sh
sed 's|127\.0\.0\.1|10.0.2.2|g; s|localhost|10.0.2.2|g' \
    "$HOME/Library/Application Support/ant/devnet-manifest.json" \
    > /tmp/devnet-manifest-android.json
adb push /tmp/devnet-manifest-android.json /data/local/tmp/devnet-manifest.json
```

> **Known limitation**: the ant-node devnet currently binds its
> bootstrap peers + Anvil RPC to `127.0.0.1` only. Even with the IP
> rewrite, the emulator's NAT may not establish QUIC connections to
> loopback-bound services. Real-device + same-WiFi works because the
> devnet's IP is a normal LAN address; emulator + devnet is a known
> gap tracked separately. The `Client.connectLocal()` and live-network
> flows are not affected.

## Build and run

```sh
./gradlew :app:installDebug
# launch via the emulator's app drawer, or:
adb shell am start -n com.autonomi.examples.antdemo/.MainActivity
```

## What it does

- **Upload**: appends a random suffix to the input text so successive
  taps produce distinct chunks (content-addressed storage means
  identical content always lands at the same address). Uploads as a
  chunk, displays the resulting address.
- **Download**: paste any chunk address (or tap "Use last") and pull
  the content back as text.

## WalletConnect spike

An exploratory spike (`app/.../wallet/`) wiring an external self-custody
wallet via **Reown AppKit** (successor to Web3Modal — same stack family as
the desktop app). The app never holds a private key: it builds the transaction
and the user's wallet signs it. Store-policy-safe payment model; Kotlin mirror
of the iOS spike (`ant-swift` AntSwiftDemo) and of `ant-ui/utils/payment.ts`.
Tracked in Linear **V2-531**.

**What the spike proves:** Connect Wallet → wallet signs a real
`eth_sendTransaction` → tx hash. The tx is an ERC-20 `approve` of the Autonomi
payment vault (the desktop's first payment step). With approve amount `0` it
costs only gas — **no token balance needed**.

**Why a real device shines here:** unlike the iOS *simulator*, a physical
Android device runs MetaMask/Trust, so it completes the signature
**end-to-end**. The approve tx targets Arbitrum, so **no devnet is needed** for
the wallet test — just internet + a wallet.

**Not yet a paid upload** — that needs the external-signer prepare/finalize
surface in `ant-ffi` (Linear **V2-391**). `EthCalldata.payForQuotes(...)` is
already implemented here for that next step.

### Running the spike

1. Get a WalletConnect project id from <https://dashboard.reown.com> and set
   `REOWN_PROJECT_ID` in `MainActivity.kt`.
2. `./gradlew :app:installDebug` on a **physical device** with a wallet app
   installed.
3. Tap **Connect Wallet**, approve in the wallet, then **Send test approve
   tx**. Needs a little ETH on Arbitrum One for gas; for a no-real-funds run,
   fill in the Arbitrum **Sepolia** token/vault addresses in
   `AutonomiContracts.kt` and target `ARBITRUM_SEPOLIA`.

### Known unknowns (verify in Android Studio)

Not yet compiled. The Reown AppKit Android surface in
`wallet/WalletConnectManager.kt` (init params, `setChains`, the
`ModalDelegate` method set, `AppKit.request`/`getAccount` shapes) and the
Compose modal host (`AppKitComponent` vs a navigation host) are written
against the docs + the WalletConnect Android lineage and **need verification
against the resolved `com.reown:appkit` 1.4.x**. These are the spots most
likely to need adjustment.

## Real-device notes

A real Android device on the same LAN as your dev machine bypasses
the emulator NAT issue. Two extra steps:
1. Bind the devnet's services to `0.0.0.0` (currently requires an
   upstream change in `ant-node`).
2. Replace `10.0.2.2` in the rewrite step with your Mac's LAN IP.

## Production caveats

- This demo plants `HOME` via a libc `setenv` shim before calling the
  FFI (see `AntFfiBootstrap.kt`). `ant-core` reads `$HOME` and aborts
  with `HomeDirNotFound` otherwise. The proper fix is for the FFI to
  accept an explicit data-dir argument; this shim is a workaround
  pending that.
- The bundled signing config is `debug` for one-tap install/run. Don't
  ship to Play with this.
