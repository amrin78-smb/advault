# Roadmap

| Version | Milestone | Status |
|---|---|---|
| 0.1–0.8 | Dashboard, VPN, DNS filtering, blocklists, per-app filtering, provider intelligence, Reward Assistant, statistics | ✅ delivered together in 0.9.0 |
| **0.9.0** | **Beta: emulator-validated build** | ✅ released |
| 0.9.x | Physical-device findings (Samsung/Pixel), battery & memory measurements, game compatibility entries | ⏳ next — needs device testing |
| 1.0.0 | Stable: all acceptance criteria verified on a physical device | planned |

## Candidates after 1.0
- Verified `games.json` compatibility entries (rewarded-ad behaviour per game).
- Flush/refresh hint after rule changes (Android's DNS cache can't be flushed by apps).
- Optional per-app DNS log export (local file, user-initiated).
- Home-screen quick-settings tile for protection on/off.
- Split into Gradle modules if build times grow.
- Signed provider-database updates (in addition to HTTPS + validation).

## Acceptance criteria (spec §16)
| Criterion | Status |
|---|---|
| Installs; polished dashboard; VPN permission flow; start/stop reliable | ✅ emulator |
| DNS processed; ads blocked; normal traffic works | ✅ emulator |
| App-specific filtering & exclusions | ✅ emulator |
| Blocklist updates; custom rules | ✅ emulator |
| Statistics reflect real activity; activity logs | ✅ emulator |
| Reward Assistant with defined limitations | ✅ |
| IPv4 & IPv6; network switching | ✅ emulator (IPv6-only mobile network pending) |
| No critical crashes; automated tests pass | ✅ |
| Release APK signed; published as GitHub Release; README link | ✅ |
| Documentation complete | ✅ |
| **Physical-device validation** | ⏳ pending → required for 1.0 |
