# Changelog

All notable changes to the DataPoint Android SDK.

## 1.2.0 — 2026-09-29

### Added

- `DataPoint.checkTaskAvailability(callback)`: ask whether `showTasks` would have a task
  right now, before rendering an entry point. Returns `TaskAvailability` with
  `isAvailable`, a machine-readable `reason` (`available`, `no_task`,
  `daily_limit_reached`, `access_disabled`) and a readable `message`. Read-only on the
  server; errors are reported rather than guessed.

### Changed

- `showTasks()` pre-checks availability before opening the task screen. When nothing is
  available, `noTaskAvailable()` fires immediately and no screen is shown. A failed or slow
  (> 3 s) pre-check opens the screen as before.

### Compatibility

- No changes to existing public APIs. Works against backends that return only
  `task_available`; `reason` is then derived from it and `message` is empty.

### Upgrade

```kotlin
implementation("com.trydatapoint:sdk:1.2.0")
```

## 1.1.0 — 2026-09-25

### Added

- **Link opening from the task wall.** Task pages can hand a URL to the host with
  `DataPointTask.openExternalUrl(url)` or `DataPointTask.openExternalUrl(url, mode)`.
  `mode` is `"in_app"` (default), which opens a Chrome Custom Tab layered over the
  task screen, or `"external"`, which opens the system browser. The task screen stays
  open underneath, so closing the browser returns the user to their task.
- `DataPoint.clearPersistedState()` to reset the SDK's stored preferences and state.
- Android 11+ package-visibility `<queries>` in the SDK manifest, so browser
  resolution works without any change to the host app's manifest.

### Changed

- **Off-domain links now open in a browser instead of being blocked.** Previously a
  navigation to a host outside DataPoint's domains was dropped silently. It now opens
  in a Custom Tab (web links) or the app that handles the scheme (`mailto:`, `tel:`,
  `market:`, `intent:`). `target="_blank"` links are honored on a user gesture.
  Custom-creative iframes remain confined to DataPoint hosts and cannot open a browser.
- Trusted host matching is now exact or subdomain only. A host such as
  `evil-trydatapoint.com` no longer passes.
- Web console output from the task page is mirrored into the SDK log when logging is
  enabled with `DataPoint.setLoggingEnabled(true)`.

### Security

- `javascript:`, `file:`, `content:`, `data:` and `about:` URLs handed to the SDK are
  refused.

### Compatibility

- No changes to existing public APIs. Task-wall builds that do not call the new bridge
  method behave as before. Minimum SDK remains 23.

### Upgrade

```kotlin
implementation("com.trydatapoint:sdk:1.1.0")
```

## 1.0.1

First Maven Central release.
