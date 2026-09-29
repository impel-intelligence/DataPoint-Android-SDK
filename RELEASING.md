# Releasing the DataPoint Android SDK

Maven coordinates: `com.trydatapoint:sdk`. Published to Maven Central through the
OSSRH staging API with an automatic Central Portal handoff (see `datapoint-sdk/build.gradle.kts`).

## 1. Decide the version

Semantic versioning. Public API or behavior change → minor; fixes only → patch.

The version is declared in three places that must always match:

| File | Key |
| --- | --- |
| `gradle.properties` | `sdk.version` |
| `datapoint-sdk/src/main/java/com/datapoint/sdk/internal/SdkConstants.kt` | `SDK_VERSION` |
| `README.md` | `implementation("com.trydatapoint:sdk:X.Y.Z")` |

## 2. Pre-release checks

```bash
./gradlew :datapoint-sdk:testDebugUnitTest :app:compileDebugKotlin
```

Then a device pass with the sample app (`app`) on the Sandbox environment. Point
`PRODUCTION_TASK_URL` at a task-wall build that exercises the release, and revert
that override before committing (`TRUSTED_HOSTS` too).

Minimum device matrix:

- Android 11+ phone with Chrome as default browser.
- One device on API 23 (exercises the deprecated `shouldOverrideUrlLoading`).
- One device or emulator with no Custom Tabs browser (fallback path).

Things to confirm on every release:

- Init → task wall opens, task completes, reward callback fires.
- Session expiry while the wall is open silently re-initializes.
- `noTaskAvailable` reaches the host callback.
- Links: `in_app` opens a Custom Tab and closing it returns to the task;
  `external` opens the system browser; `mailto:`/`tel:` go to their app;
  a `target="_blank"` link opens outside; a custom-creative iframe cannot
  open a browser on its own.

## 3. Cut the release

1. Bump the three version strings and update the README notes.
2. Commit as `release: vX.Y.Z`, tag `vX.Y.Z`, push branch and tag.
3. Write release notes (GitHub release). Call out any behavior change host apps
   will notice.

## 4. Publish

Credentials live only in the root `local.properties` (never in `gradle.properties`):
Central Portal user token as the repository username/password, plus
`signing.enabled=true` and the GPG key fields described in `gradle.properties`.

```bash
# Dry run: build, sign, install locally, and inspect the artifact.
./gradlew :datapoint-sdk:publishToMavenLocal
unzip -p ~/.m2/repository/com/trydatapoint/sdk/X.Y.Z/sdk-X.Y.Z.aar AndroidManifest.xml | head -40

# Real publish: uploads, then the finalize task hands the deployment to the Portal.
./gradlew :datapoint-sdk:publishReleasePublicationToReleasesRepository
```

Check the deployment at https://central.sonatype.com/publishing/deployments. If
`maven.central.publishingType` is `user_managed`, press Publish there. Sync to
Maven Central usually completes within an hour.

## 5. Verify and announce

- From a fresh sample project, resolve `com.trydatapoint:sdk:X.Y.Z` and run the wall once.
- Update the README version line if not already done.
- Notify integrating publishers with the release notes.

---

## 1.2.0 checklist

Changes since v1.1.0:

- `DataPoint.checkTaskAvailability(callback)` returning `TaskAvailability`
  (`isAvailable`, `reason`, `message`).
- `showTasks()` pre-checks availability (3 s budget, fails open) and fires
  `noTaskAvailable()` without opening a screen when nothing is available.
- Sample app button for the availability check.

Device pass specific to this release (sample app: Initialize → Check Task Availability → Show Tasks):

- Inventory present: check says available, Show Tasks opens the wall.
- No inventory: check says `no_task`; Show Tasks fires `noTaskAvailable()` and **no screen flashes**.
- Airplane mode: check reports an error; Show Tasks still opens the screen with its offline message.
- Daily limit reached (if reproducible): check says `daily_limit_reached`.

Status:

- [x] Version bumped to 1.2.0 in all three places
- [x] Changelog entry
- [x] SDK + sample app compile, unit tests pass
- [ ] Device pass (above)
- [ ] PR merged, tag `v1.2.0` pushed
- [ ] `publishToMavenLocal` dry run, then `publishReleasePublicationToReleasesRepository`
- [ ] Portal deployment published
- [ ] Resolved from a fresh project
- [ ] GitHub release published

---

## 1.1.0 checklist

Changes since v1.0.1:

- `DataPoint.clearPersistedState()` to reset SDK preferences and state.
- CTA link handling: `DataPointTask.openExternalUrl(url[, mode])`; off-domain
  links open in a Custom Tab (`in_app`, default) or the system browser
  (`external`). **Behavior change:** off-domain navigations used to be blocked
  silently and now open in a browser over the task screen.
- Trusted host check tightened to exact-or-subdomain match.
- Android 11+ package-visibility `<queries>` added to the SDK manifest.

Status:

- [x] Version bumped to 1.1.0 in all three places
- [x] Unit tests: `UrlPolicyTest`, `BrowserLauncherOpenModeTest`
- [ ] Device pass (matrix above)
- [ ] Tag `v1.1.0` and push
- [ ] `publishToMavenLocal` dry run, manifest shows the `<queries>` block
- [ ] `publishReleasePublicationToReleasesRepository`
- [ ] Portal deployment validated / published
- [ ] Resolved from a fresh project
- [ ] Release notes published, publishers notified

Related rollouts (independent of the SDK; the SDK is backward compatible):

- dl-dippy: merge and deploy the open-mode work so the task wall sends `mode`.
- Backend: return `cta_open_mode` on completion views.
