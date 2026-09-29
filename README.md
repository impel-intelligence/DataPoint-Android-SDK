# DataPoint Android SDK

Monetize your app with micro-tasks. Drop in the SDK, initialize with your API key, and present the task wall to your users.

**Min SDK:** 23  |  **Language:** Kotlin / Java

---

## Installation

**Step 1.** Add the DataPoint maven repository to your project-level `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

**Step 2.** Add the dependency to your app-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.trydatapoint:sdk:1.2.0")
}
```

**Step 3.** Add internet permission in `AndroidManifest.xml` (if not already present):

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

---

## Quick Start (Kotlin)

### 1. Initialize

Call once in your `Application.onCreate()` or launcher `Activity`:

```kotlin
DataPoint.isLoggingEnabled = true // optional, disable in production

DataPoint.initialize(
    context     = applicationContext,
    apiKey      = "YOUR_API_KEY",
    userId      = "unique_user_id",          // optional
    environment = Environment.PRODUCTION,
    callback    = object : InitCallback {
        override fun onSuccess() {
            Log.d("App", "SDK ready")
        }
        override fun onError(message: String, code: Int) {
            Log.e("App", "Init failed ($code): $message")
        }
    }
)
```

### 2. Listen for Events

```kotlin
DataPoint.setListener(object : DataPointListener {
    override fun onTaskCompleted(payload: String?) {
        // User completed a task — reward them
    }
    override fun onAdRequested() {
        // Show your own ad (AdMob, AppLovin, etc.)
    }
    override fun noTaskAvailable() {
        // No task now — close is handled by SDK; show fallback/native ads if desired
    }
    override fun onClosed() {
        // Task screen was dismissed
    }
    override fun onError(message: String, code: Int) {
        Log.e("App", "Error ($code): $message")
    }
})
```

### 3. Show the Task Wall

```kotlin
DataPoint.showTasks(context)
```

### 4. Close Programmatically (Optional)

```kotlin
DataPoint.closeTasks()
```

### 5. App user ID (when you have it)

If login happens after init, attach your own user id to the DataPoint session any time (must be after a successful `initialize`). Pass an optional `DataPointCallback` if you need the result.

```kotlin
DataPoint.setAppUserId("your_internal_user_id")
```

---

### 6. Check availability before showing an entry point (Optional)

```kotlin
DataPoint.checkTaskAvailability(object : TaskAvailabilityCallback {
    override fun onResult(availability: TaskAvailability) {
        earnButton.isVisible = availability.isAvailable
        // availability.reason: "available" | "no_task" | "daily_limit_reached" | "access_disabled"
        // availability.message: readable form of reason, for your logs
    }

    override fun onError(message: String, code: Int) {
        // Check could not be made (offline, server error). Decide your own fallback.
    }
})
```

The answer is exact for this user — the same rules the task wall applies — and the call is
read-only on the server. Call it when a screen appears rather than on a timer.

`showTasks()` runs the same check itself before opening the screen: when nothing is available
you get `noTaskAvailable()` immediately and no screen is shown. If the check fails or times
out (3 s), the screen opens as before.

## Quick Start (Java)

```java
// Initialize (static methods – no INSTANCE needed)
DataPoint.setLoggingEnabled(true);

DataPoint.initialize(
    getApplicationContext(),
    "YOUR_API_KEY",
    "unique_user_id",       // optional, pass null to skip
    Environment.PRODUCTION,
    new InitCallback() {
        @Override public void onSuccess() {
            Log.d("App", "SDK ready");
        }
        @Override public void onError(@NonNull String message, int code) {
            Log.e("App", "Init failed (" + code + "): " + message);
        }
    }
);

// Listen for events
DataPoint.setListener(new DataPointListener() {
    @Override public void onTaskCompleted(@Nullable String payload) { }
    @Override public void onAdRequested() { }
    @Override public void noTaskAvailable() { }
    @Override public void onClosed() { }
    @Override public void onError(@NonNull String message, int code) { }
});

// Show tasks
DataPoint.showTasks(context);
```

---

## User Attributes

Set user attributes to improve task targeting. All calls are asynchronous and callbacks are optional.

```kotlin
DataPoint.setAge(25)
DataPoint.setAgeRange("18-24")
DataPoint.setGender("male")
DataPoint.setOccupation("Engineer")

// Or set any custom attributes
DataPoint.setUserAttributes(
    mapOf("preferred_language" to "en", "theme" to "dark"),
    object : DataPointCallback {
        override fun onSuccess() { /* saved */ }
        override fun onError(message: String, code: Int) { /* handle */ }
    }
)
```

---

## Environments

| Environment | Behavior |
|---|---|
| `Environment.PRODUCTION` | Connects to the live backend |
| `Environment.SANDBOX` | Connects to the QA API and QA task WebView |


---

## Good to Know

- **All callbacks are delivered on the main thread** — safe to update UI directly from any callback.
- **Sessions are managed automatically** — the SDK refreshes expired tokens in the background. If a token expires while the task wall is open, the SDK silently re-initializes and resumes without closing the screen.
- **Server errors are retried** — if the backend returns a 5xx during initialization, the SDK retries up to 3 times with increasing delay before reporting failure.
- **Off-domain links open in a browser** — see below.

---

## Opening Links in a Browser

Task pages on DataPoint domains render inside the SDK's WebView. Anything else leaves it:

| The page does                                    | Where it opens                                       |
| ------------------------------------------------ | ---------------------------------------------------- |
| Link / redirect to a non-DataPoint `http(s)` host | In-app Chrome Custom Tab                              |
| `target="_blank"` link                            | Custom Tab (or the WebView, if the host is DataPoint) |
| `mailto:` `tel:` `market:` `intent:` …            | The app that handles that scheme                      |
| `javascript:` `file:` `content:` `data:`          | Blocked                                               |

The task screen stays open underneath, so closing the browser returns the user to their task.
Which target a task's CTA uses (in-app or external) is configured per job in DataPoint, not in
your app.

If no Custom Tabs-capable browser is installed, the SDK falls back to the system browser. Nothing
needs to be added to your app's manifest — the SDK ships the Android 11+ `<queries>` entries that
make browser resolution work.

---

## Error Codes

All error codes are available as constants on `ErrorCode`.


| Code | Constant                | Meaning                                 |
| ---- | ----------------------- | --------------------------------------- |
| 1001 | `SDK_NOT_INITIALIZED`   | `initialize()` not called or incomplete |
| 1002 | `INITIALIZATION_FAILED` | Init network request failed             |
| 1003 | `SESSION_EXPIRED`       | Session expired and auto-refresh failed |
| 1004 | `INVALID_CONFIGURATION` | Invalid parameters (e.g. blank apiKey)  |
| 1005 | `TASK_ALREADY_SHOWING`  | Task screen is already visible          |
| 1007 | `NETWORK_ERROR`         | No connectivity / timeout               |
| 1008 | `INVALID_REQUEST`       | Bad request (HTTP 400)                  |
| 1009 | `INVALID_API_KEY`       | Unauthorized API key (HTTP 401)         |
| 1012 | `SERVER_ERROR`          | Server error (HTTP 5xx)                 |


---

## ProGuard

If you use code shrinking, the SDK's consumer ProGuard rules are bundled automatically. No extra configuration needed.

---

## Requirements

- Android API 23+
- Google Play Services (for Advertising ID, optional)

---

## License

Copyright &copy; DataPoint. All rights reserved.