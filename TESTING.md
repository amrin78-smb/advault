# Testing

## Automated tests

Run: `./gradlew testDebugUnitTest` (JVM + Robolectric) and `./gradlew connectedDebugAndroidTest` (device).

| Area | Tests | Covers |
|---|---|---|
| `vpn` | 53 | IPv4/IPv6 packet parse/build + checksums, DNS parser (compression, loops, 20k fuzz inputs), block responses per mode/qtype, cache TTL/LRU/ID rewrite, real-socket UDP/TCP upstreams, timeout→next server→SERVFAIL, TC-bit TCP fallback, DoH encoding, excluded-app passthrough, per-app modes, stats batching, throughput benchmark |
| `filtering` | 52 | matcher semantics, allow/block precedence, category × mode logic, parsers & invalid/duplicate counts, custom-rule validation, update flow (304, failure keeps old list, HTML rejected), WorkManager scheduling, **regression: ~100 essential domains never blocked by the real bundled lists**, known ad domains blocked, provider DB integration (classification-only domains never blocked), 250k-rule benchmark |
| `intelligence` | 30 | provider JSON validation (bad domains, schema, size, protected shared hosts), suffix matching (1M lookups), override versioning, SDK signature + game detection, uid→mode with shared uids, Reward Assistant classification and wording invariants |
| `feature` | 52 | dashboard state mapping (estimate labelling, zero states), start flow (other-VPN confirmation, consent), app search/filter/sort, exclusion → VPN reload, activity filtering, DNS input validation, formatters |
| androidTest | 8 | Compose dashboard rendering for every protection state |

**v0.9.0: 187/187 unit tests pass; 8/8 instrumented tests pass (API 36 emulator).** Lint: 0 errors.

### Benchmarks (desktop JVM, shared machine)
- Domain matcher, 250k rules: build 66–105 ms, ≈8–9 MB, 1.6–2.4 M hit lookups/s, 1.9–3.6 M miss lookups/s.
- Real bundled lists (~297k domains): compile 0.7–1.6 s; on the emulator 0.7–2.9 s.
- Full DNS handler path, 100k rules: ~270k–300k queries/s (excludes the per-query uid system call).

## Emulator integration test report — v0.9.0 (Android 16 / API 36, x86_64)

| # | Scenario | Result |
|---|---|---|
| 1 | Install debug + release APKs; onboarding; dashboard | ✅ |
| 2 | VPN consent → start; tun0 with 10.111.222.1/32 + fd66::1/128; foreground notification | ✅ |
| 3 | Ad domains (doubleclick, googlesyndication, mopub, unityads) → null answer | ✅ |
| 4 | Normal names resolve over IPv4 and IPv6 (AAAA) | ✅ |
| 5 | Normal TCP traffic: HTTPS browsing in Chrome (Wikipedia, GitHub), raw HTTP via nc | ✅ |
| 6 | Per-app attribution in Activity log (Chrome, Google Play services), latency, cache hits | ✅ |
| 7 | Exclude Chrome → uid removed from VPN ranges (dumpsys), Chrome still browses, no new log entries; re-protect | ✅ |
| 8 | Airplane mode on → "Waiting for network"; off → resumes and filters | ✅ |
| 9 | Wi-Fi → mobile data → Wi-Fi switching keeps filtering | ✅ |
| 10 | Upstreams: Cloudflare DoH, Quad9 DoT (healthy, ~134 ms), network default (DHCP), Cloudflare plain | ✅ |
| 11 | Blocklist "Update now": 7 remote lists downloaded, validated, recompiled | ✅ |
| 12 | Custom block (`example.org` + subdomains) and allow (`ads.mopub.com`) rules | ✅ (cached names: see limitations) |
| 13 | Stop protection → tun0 removed, normal DNS | ✅ |
| 14 | App update (reinstall) → protection resumes | ✅ |
| 15 | Device reboot → protection resumes via BootReceiver | ✅ |
| 16 | Process killed (`kill -9`) → resumes on app open and via watchdog job in background | ✅ (after fix in 0.9.0) |
| 17 | Release (R8-minified) build: all screens, VPN start, blocking, no crashes | ✅ |
| 18 | Reward Assistant / Blocklists / Settings / Diagnostics screens render with real data | ✅ |

Issues found and fixed during this pass: silent protection loss after process kill (watchdog added),
Android 10 crash in diagnostics (`getOwnerUid` API 30), unreadable onboarding text and dark status icons,
preinstalled apps hidden from the app list, shell listed as a game, provider shared-infrastructure
domains being blocked (`graph.facebook.com`).

## Physical-device test checklist — PENDING
Not yet performed (no physical device available to the build environment). Please report results:

- [ ] Install release APK on your phone (model/Android version: ____)
- [ ] Normal web browsing, video streaming (YouTube/Netflix), social media
- [ ] Game with banner ads; game with interstitials; AdMob game; Unity Ads game
- [ ] Game with rewarded ads: *Block all ads* vs *Allow rewarded ads*
- [ ] App that needs analytics (turn Analytics category off if it breaks)
- [ ] Chrome with Secure DNS on vs off
- [ ] Wi-Fi ↔ mobile data, airplane mode, reboot
- [ ] Battery usage after 24 h (Settings → Battery), memory in Developer options
- [ ] Samsung: add AdVault to *Never sleeping apps*; check it survives overnight
