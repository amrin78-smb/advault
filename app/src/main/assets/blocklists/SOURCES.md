# Bundled blocklist snapshots

Snapshots downloaded over HTTPS on 2026-11-20 and stored unmodified (gzip -9n, extension `.gzip` because AGP gunzips and renames `.gz` assets when packaging) so they can be
re-verified against upstream. On first run (and when a newer snapshot ships with an app update)
AdVault parses them with the same validating parser used for downloaded updates
(`com.advault.filtering.parser.BlocklistParser`) and stores the compiled domain list under
`filesDir/blocklists/<id>.txt`. Snapshot date constant: `BuiltInLists.SNAPSHOT_EPOCH_MS` —
bump it whenever these files change.

Downloaded content is only ever parsed as text and validated as hostnames; nothing is executed.

| id | File | Category | Upstream (HTTPS update URL) | Licence | Valid domains |
|----|------|----------|-----------------------------|---------|---------------|
| stevenblack-unified | stevenblack-unified.txt.gzip | ADVERTISING | https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts | MIT (aggregates AdAway CC BY 3.0, add.2o7Net, add.Risk, add.Spam, Dan Pollock, URLHaus, Peter Lowe et al. under their terms) | 76,104 |
| adaway | adaway.txt.gzip | ADVERTISING | https://adaway.org/hosts.txt | CC Attribution 3.0 | 6,540 |
| advault-mobile-ads | advault-mobile-ads.txt | GAME_ADS | — (curated, ships with app) | CC0 1.0 | 31 |
| advault-attribution | advault-attribution.txt | TRACKING | — (curated) | CC0 1.0 | 12 |
| 1hosts-lite | 1hosts-lite.txt.gzip | TRACKING | https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/domains.txt | MPL 2.0 | 202,953 |
| frogeye-firstparty | frogeye-firstparty.txt.gzip | TRACKING | https://hostfiles.frogeye.fr/firstparty-trackers-hosts.txt | MIT | 15,012 |
| perflyst-android | perflyst-android.txt.gzip | TRACKING | https://raw.githubusercontent.com/Perflyst/PiHoleBlocklist/master/android-tracking.txt | MIT | 80 |
| advault-analytics | advault-analytics.txt | ANALYTICS | — (curated) | CC0 1.0 | 26 |
| advault-telemetry | advault-telemetry.txt | TELEMETRY | — (curated) | CC0 1.0 | 27 |
| urlhaus-filter | urlhaus-filter.txt.gzip | MALICIOUS | https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-hosts.txt | CC0 1.0 / MIT; URLhaus data (abuse.ch) CC0 | 2,113 |
| phishing-filter | phishing-filter.txt.gzip | MALICIOUS | https://malware-filter.gitlab.io/malware-filter/phishing-filter-hosts.txt | CC BY-SA 4.0 (sources: OpenPhish, PhishTank — free of charge; IPThreat CC BY-SA 4.0) | 17,022 |

Total: ~297k unique domains, ~2.4 MB of assets.

## Attribution

- StevenBlack/hosts — Copyright (c) Steven Black, MIT License. https://github.com/StevenBlack/hosts
- AdAway hosts — https://github.com/AdAway/adaway.github.io, licensed CC BY 3.0.
- 1Hosts — badmojr, https://github.com/badmojr/1Hosts, MPL 2.0 (unmodified source form; the
  upstream file is available at the URL above).
- Frogeye first-party trackers — Copyright (c) 2019 Geoffrey 'Frogeye' Preud'homme, MIT License.
- Perflyst/PiHoleBlocklist — MIT License.
- malware-filter (urlhaus-filter, phishing-filter) — Ming Di Leom, https://gitlab.com/malware-filter.

## Deliberately not bundled

- GoodbyeAds (MIT): blocks `firebaseinstallations.googleapis.com`, which breaks push notifications.
- hagezi lists (GPL-3.0): permissive equivalents preferred; can be added by the user as custom lists.
- Peter Lowe / Dan Pollock standalone lists: terms are not clearly permissive (both are already
  included in StevenBlack's MIT aggregation under their own terms).
- urlhaus.abuse.ch/downloads/hostfile/: retired upstream (HTTP 410).

## builtin_allow.txt

Allow exceptions that override blocklist and provider-intelligence matches (never the user's own
custom block rules). They exist to keep Android connectivity checks, Play services, push (FCM),
sign-in and major apps working even if an upstream list adds a bad entry. The regression test
`BundledListsTest` loads these exact assets and asserts that a list of essential domains is never
blocked in AGGRESSIVE mode, that shared hosting roots are never listed, and that known ad/tracking
endpoints are blocked. Entries must stay narrow (`=example.com` for exact names) so that ad hosts
under the same parent (e.g. `adservice.google.com`) remain blocked.
