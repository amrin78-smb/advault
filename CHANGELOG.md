# Changelog

## [0.9.0] — 2026-11-20 — Beta
First public build. Covers the planned v0.1–v0.8 milestones in one release; physical-device testing is pending.

### Added
- Local DNS-redirect VPN (IPv4 + IPv6) with foreground notification, stop action, boot/update auto-start,
  network-change handling, airplane-mode "waiting for network", and a protection watchdog.
- Upstream DNS: plain UDP/TCP, DNS-over-TLS, DNS-over-HTTPS, network default; DNS cache; configurable
  timeout and blocked-response type.
- Filtering engine (hash-table matcher, ~8 MB for 250k rules), six categories, Aggressive/Balanced modes.
- 11 bundled blocklists (~297k domains), HTTPS updates (manual + scheduled), custom lists, custom rules.
- Per-app protection (Default/Aggressive/Balanced/Excluded) with per-app statistics and activity.
- Provider intelligence database (29 providers) with SDK detection in installed apps, OTA updates.
- Reward Assistant with honest per-game explanations and Block-all / Allow-rewarded behaviour.
- Dashboard, Apps, Activity, Rewards, Blocklists, Custom rules, Settings, Diagnostics, About, onboarding.
- 187 unit tests, 8 instrumented UI tests, CI workflow (see `ci/README.md` to enable).

### Fixed during integration testing
- Protection silently stayed off after Android killed the process.
- Diagnostics crash on Android 10 (API 30-only call).
- Shared-infrastructure provider domains (e.g. Facebook Login) were blocked.
- Onboarding text contrast; status-bar icon colour; preinstalled apps hidden from the app list.
