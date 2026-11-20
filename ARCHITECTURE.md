# Architecture

Single Gradle module (`:app`), organised into packages that mirror the planned module layout. A single
module keeps build times and the release pipeline simple for a personal app; the package boundaries are
enforced by convention (each package depends only on `core` and the interfaces in `data`), so splitting into
Gradle modules later is mechanical.

```
com.advault
├── app/            Application, MainActivity, navigation, startup (AppInitializer)
├── core/
│   ├── common/     enums (FilterCategory, FilterMode, AppProtection, AdFormat…), DomainUtils, dispatchers
│   ├── database/   Room: blocklists, custom_rules, app_policies, activity_log, daily_stats
│   └── preferences/DataStore settings (SettingsRepository / AppSettings)
├── data/
│   ├── models/     shared domain models (ProtectionState, FilterVerdict, GameRewardProfile…)
│   └── repository/ interfaces: ProtectionController, FilterEngine, BlocklistRepository,
│                   AppRepository, ProviderRepository, RewardAssistantRepository, StatsRepository
├── vpn/            VpnService, packet + DNS engine, upstreams, stats recording, watchdog
├── filtering/      DomainMatcher, FilterEngine, parsers, blocklist storage/updates (WorkManager)
├── intelligence/   provider database, app scanning/SDK detection, game detection, Reward Assistant
└── feature/        Compose UI: dashboard, apps, activity, rewards, blocklists, settings, onboarding
```

UI is MVVM: Compose screens → Hilt ViewModels exposing `StateFlow` → repository interfaces. Hilt binds
each interface to its implementation in the owning package (`vpn/di`, `filtering/di`, `intelligence/di`).

## VPN design: DNS-redirect tunnel
```
 app ──DNS──▶ 10.111.222.2 / fd66:6164:7661::2 (virtual, routed into tun0)
                     │
              AdVaultVpnService reader thread (poll on tun fd + wake pipe)
                     │  parse IPv4/IPv6 + UDP + DNS question
                     │  uid = ConnectivityManager.getConnectionOwnerUid()
                     │  mode = AppRepository.modeForUid(uid)  (null = excluded → forward)
                     │  verdict = FilterEngine.evaluate(domain, mode)
          blocked ◀──┴──▶ allowed → DnsCache → UpstreamResolver (UDP/TCP, DoT, DoH; ≤256 in flight)
     synthesized reply          answer written back to tun0 (single serialized writer)
 app ◀──────────────────────────────────────────────
 all other traffic ──────▶ physical network directly (never enters tun0)
```
- Only /32 and /128 routes to the virtual DNS addresses are added, so TCP, UDP, QUIC and IPv6 traffic is
  never touched and there is no user-space TCP stack to break. AdVault itself and excluded apps are
  `addDisallowedApplication`, which also prevents routing loops for upstream DNS and downloads.
- TCP to the virtual DNS address is answered with RST (fast fallback). MTU 16384 so large answers fit in UDP;
  truncated upstream answers are retried over TCP.
- `UnderlyingNetworkMonitor` (NetworkCallback on NOT_VPN networks) sets underlying networks, refreshes
  the system resolver list, flushes the cache on network change, and drives `WaitingForNetwork`.
- `ProtectionWatchdog` resumes protection on every process start and every 15 min (WorkManager) when the
  user wants it on, because Android does not restart a killed VpnService unless Always-on VPN is set.
- Stats are aggregated in memory and flushed to Room every 5 s / 200 events (no per-query disk writes).

## Filtering engine
- Rules are stored as 64-bit hashes of domains in open-addressing `LongArray` tables with a per-entry
  source bitmask (≈8 MB for 250k domains, vs 35–50 MB for a HashMap/trie). A query is hashed right-to-left
  once, yielding every suffix hash with no allocation; three tables cover "domain + subdomains",
  "subdomains only" (`*.x`) and "exact" (`=x`).
- Precedence: custom ALLOW > custom BLOCK > built-in allow exceptions > list/provider matches, where a match
  blocks only if its category is enabled globally and included by the app's mode (Balanced = ads, game ads,
  malicious; Aggressive = all six).
- Snapshots are rebuilt off-thread (debounced) when lists, rules, categories or provider data change and
  swapped atomically; the hot path is lock-free.
- Blocklists: HOSTS / DOMAINS / ADBLOCK (`||domain^` only) parsers with validation, de-duplication,
  invalid-rule counts and size limits. Updates over HTTPS with ETag/If-Modified-Since, atomic file replace,
  old data kept on failure. Downloaded content is parsed as text only — never executed.

## Provider intelligence & Reward Assistant
- `assets/intelligence/providers.json` (versioned: `schemaVersion`, `dataVersion`) holds providers,
  domains (category, associated ad formats, `blockable`) and SDK signatures. Updates download from this
  repository's `main` branch over HTTPS, are strictly validated, and are used only if newer.
  Shared-infrastructure domains (e.g. `graph.facebook.com`) are classification-only and never blocked.
- SDK detection reads installed apps' declared components and manifest meta-data (offline, no code
  inspection). Game detection uses the Play category / `FLAG_IS_GAME` plus engine heuristics.
- Reward Assistant combines detected SDKs, observed provider traffic, and the (initially empty, versioned)
  `games.json` compatibility data into a profile with an honest explanation. Per-game behaviour:
  *Block all ads* (= Aggressive) or *Allow rewarded ads* (= excluded from the VPN).

## Data stored on device
Room DB `advault.db`, DataStore `settings` and `reward_assistant`, compiled lists in `filesDir/blocklists`,
provider override in `filesDir/intelligence`. Backups are disabled (`allowBackup=false`).
