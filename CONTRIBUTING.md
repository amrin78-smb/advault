# Contributing / development

## Setup
- JDK 17+ (21 recommended), Android SDK platform 36 + build-tools, Android Studio optional.
- `local.properties` with `sdk.dir=...` (not committed).

## Everyday commands
```bash
./gradlew testDebugUnitTest              # all unit tests (must pass)
./gradlew lintDebug                      # 0 errors required
./gradlew installDebug                   # com.advault.app.debug on a device/emulator
./gradlew connectedDebugAndroidTest      # instrumented UI tests
```

## Code layout & rules
See [ARCHITECTURE.md](ARCHITECTURE.md). Each package (`vpn`, `filtering`, `intelligence`, `feature`)
depends only on `core` and the interfaces in `data/repository`. Keep the DNS hot path (packet parsing,
`FilterEngine.evaluate`, `ProviderRepository.match`, `AppRepository.modeForUid`) allocation-light,
lock-free and off the main thread.

## Changing filter rules
- Bundled lists live in `app/src/main/assets/blocklists/` (gzip, `.gzip` extension — AGP rewrites `.gz`).
  Bump `BuiltInLists.SNAPSHOT_EPOCH_MS` when they change and update `SOURCES.md` (licence + attribution).
- **Never add a rule without running `BundledListsTest`** — it checks ~100 essential domains stay unblocked.
  Use `builtin_allow.txt` for exceptions.
- Provider data: edit `app/src/main/assets/intelligence/providers.json`, bump `dataVersion`. Once on `main`,
  installed apps pick it up via *Rewards → Update provider database*. Mark shared infrastructure
  `"blockable": false`.

## Releases
1. Bump `versionCode`/`versionName` in `app/build.gradle.kts`; update `CHANGELOG.md`.
2. All unit tests + lint pass; smoke-test the release build on a device/emulator.
3. `./gradlew assembleRelease assembleDebug`
4. `gh release create vX.Y.Z AdVault-vX.Y.Z-release.apk AdVault-vX.Y.Z-debug.apk --notes-file ...`

### Signing
The release key is **never committed**. Gradle reads `~/.advault/keystore.properties`
(or the path in `ADVAULT_KEYSTORE_PROPERTIES`):
```
storeFile=/home/you/.advault/advault-release.jks
storePassword=...
keyAlias=advault
keyPassword=...
```
Keep a backup of the keystore: losing it means users must uninstall to install future updates.

## Principles (non-negotiable)
No ad SDKs or tracking in AdVault, no external VPN servers, no HTTPS interception or certificates,
no forged ad/reward events, no executing downloaded content, statistics must be real.
