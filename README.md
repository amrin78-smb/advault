# AdVault

**System-wide ad blocker for Android, built for games.** AdVault runs a local, on-device VPN that filters
DNS lookups against ~297,000 advertising, tracking and malicious domains, so interstitial, banner and
video ads in most apps and games fail to load. Nothing leaves your phone through AdVault.

> Personal-use, sideloaded app. Not distributed on Google Play.

## ⬇️ Download

**[Latest release →](https://github.com/amrin78-smb/advault/releases/latest)**

| File | Use it for |
|---|---|
| [`AdVault-v0.9.0-release.apk`](https://github.com/amrin78-smb/advault/releases/download/v0.9.0/AdVault-v0.9.0-release.apk) | **Install this one.** Signed, minified (~6 MB). Future updates install over it. |
| [`AdVault-v0.9.0-debug.apk`](https://github.com/amrin78-smb/advault/releases/download/v0.9.0/AdVault-v0.9.0-debug.apk) | Development/troubleshooting. Installs alongside the release build (`com.advault.app.debug`). |

**Requires Android 10 or newer.**

### Install
1. Download the `-release.apk` on your phone.
2. Open it; allow "Install unknown apps" for your browser/files app when Android asks.
3. Open AdVault → follow the 3-page intro → tap the **Protection** switch → accept Android's VPN prompt.
4. Recommended: **Settings → Always-on VPN** guidance (lets Android restart AdVault if it's killed) and
   exclude AdVault from battery optimisation (essential on Samsung: add it to *Never sleeping apps*).
5. Turn **Private DNS** off or to *Automatic* in Android settings — *Private DNS provider hostname* bypasses AdVault.

Always update by installing the newer `-release.apk` over the old one; your settings are kept.

## What it does
- **DNS filtering via a local VPN** — only DNS enters the tunnel; all other traffic goes directly to the network, untouched.
- **Aggressive by default**, with six switchable categories: advertising, mobile game ads, tracking & attribution, analytics, telemetry, malicious.
- **Per-app control** — exclude an app entirely, or choose Aggressive/Balanced per app; per-app activity and counts.
- **Blocklists** — 11 bundled lists (StevenBlack, AdAway, 1Hosts Lite, malware-filter, …), daily/weekly HTTPS updates, custom lists, custom block/allow rules.
- **Provider intelligence** — 29 ad/attribution/analytics providers (AdMob, Unity Ads, AppLovin, ironSource, Meta Audience Network, …), detected from network activity and from SDK components inside installed games. Updatable without a new APK.
- **Reward Assistant** — explains, honestly, what happens to "watch an ad for a reward" in each game, and lets you choose *Block all ads* or *Allow rewarded ads* per game.
- **Encrypted upstream DNS** — Cloudflare/Google DNS-over-HTTPS, Quad9 DNS-over-TLS, or plain DNS/your network's resolver.
- **Real statistics only** — every counter is measured; "data saved" is clearly labelled as an estimate.

## What it cannot do (read this)
- It **cannot fake rewarded ads**. If you block ads, "watch an ad for coins" usually stops working. AdVault never forges ad callbacks or rewards.
- Ads served from the **same domain as the game's content** cannot be blocked by DNS.
- Apps that use their **own encrypted DNS** (e.g. Chrome *Secure DNS*) bypass filtering.

Full list: [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md).

## Privacy
Local only. No external VPN server, no analytics or ad SDKs in AdVault, no HTTPS interception, no
certificates installed. Activity logs stay on the device (default 7-day retention) and can be cleared or disabled.

## Project docs
[ARCHITECTURE](ARCHITECTURE.md) · [TESTING](TESTING.md) · [KNOWN_LIMITATIONS](KNOWN_LIMITATIONS.md) ·
[ROADMAP](ROADMAP.md) · [CHANGELOG](CHANGELOG.md) · [CONTRIBUTING](CONTRIBUTING.md)

## Build from source
```bash
./gradlew testDebugUnitTest assembleDebug        # tests + debug APK
./gradlew assembleRelease                        # needs ~/.advault/keystore.properties (see CONTRIBUTING)
```
JDK 17+ (21 recommended), Android SDK 36. Kotlin 2.2, AGP 9.1, Jetpack Compose, Hilt, Room, WorkManager.
