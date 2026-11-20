# Known limitations

AdVault is a DNS filter. It is honest about what that can and cannot do.

## Rewarded ads
- AdVault **cannot** grant, fake, skip or complete rewarded ads. Rewards are controlled by the game
  developer; many ad networks verify completion server-side. AdVault never forges SDK callbacks,
  manipulates memory, modifies game binaries, automates taps, or intercepts HTTPS.
- DNS cannot tell rewarded ad requests from interstitial/banner requests (SDKs use the same domains).
  *Block all ads* therefore usually makes rewarded ads — and the rewards that depend on them — unavailable.
  *Allow rewarded ads* excludes the game from AdVault, so all its ads show.
- Ad-format labels per domain are "associated with", never proof that an ad was shown or completed.
- The game compatibility database (`games.json`) ships empty; "alternatives available" and
  "requires verified completion" statuses appear only once verified entries are added.

## Filtering coverage
- Ads served from the **same domain as content** (common in games and video apps such as YouTube) cannot be blocked.
- Apps using their **own DNS-over-HTTPS/TLS** (Chrome/Firefox "Secure DNS", some games) or hard-coded DNS
  servers bypass filtering.
- **Private DNS** set to a provider hostname (strict mode) bypasses AdVault entirely. *Automatic* mode works.
  The dashboard warns when Private DNS is active.
- Android caches DNS answers system-wide. A new block/allow rule applies immediately to names not yet cached,
  and to cached names after their TTL (usually minutes) or after toggling protection off/on.
- Only DNS is filtered. There is no per-connection/IP filtering beyond DNS (by design: it avoids a fragile
  user-space TCP stack and keeps all non-DNS traffic untouched).
- DNS over TCP to AdVault is answered with a reset (clients fall back to UDP). Answers over ~16 KB are truncated.
- Single-label names are forwarded without filtering.

## Per-app behaviour
- App attribution uses `getConnectionOwnerUid`; some lookups (e.g. made by the system resolver on an app's
  behalf) show as "Unknown app" and use the default mode.
- Apps sharing a Linux uid are routed together: excluding one excludes all of them.
- SDK detection only sees SDKs with declared manifest components/meta-data. Detecting an SDK means it is
  packaged, not that it shows ads. System apps that were never updated are not scanned.
- Game detection by engine (Unity, Unreal…) is a heuristic.
- Until the first app scan completes after launch or install, apps use the default mode.

## Android platform
- Only one VPN can be active. Starting AdVault disconnects another VPN (AdVault asks first). AdVault cannot
  detect another app's *Always-on* VPN setting.
- If Android kills AdVault, protection is resumed when the app starts again or by a 15-minute watchdog.
  If Android forbids a background restart, a "tap to resume" notification is shown. Enable **Always-on VPN**
  for AdVault for the most reliable behaviour. OEM battery savers (Samsung, Xiaomi…) need AdVault
  exempted.
- Changing an app's exclusion briefly reconnects the VPN.

## Data & statistics
- "Estimated data saved" = blocked ad/tracking requests × a configurable average (default 25 KB). It is an
  estimate and is labelled as one. All other counters are measured.
- Dashboard counters lag live traffic by up to ~5 s (batched writes).
- The four AdVault-curated lists (mobile ads, attribution, analytics, telemetry) update with app releases only.
- Scheduled blocklist updates run only when the battery is not low (WorkManager constraint).

## Testing status
- Verified on an Android 16 (API 36) emulator: see [TESTING.md](TESTING.md).
- **Pending on a physical device:** Samsung/Pixel behaviour, real games with AdMob/Unity/AppLovin ads,
  rewarded-ad flows, battery and memory measurements, Android 10–13, IPv6-only mobile networks,
  another VPN app taking over (`onRevoke`).
