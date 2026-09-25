package com.datapoint.sdk.internal

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle

/**
 * Opens URLs that must leave the task WebView.
 *
 * Two targets are supported:
 * - [OpenMode.IN_APP]: a Chrome Custom Tab hosted by the user's default browser. The tab is
 *   launched with a hand-built intent (ACTION_VIEW + the documented Custom Tabs extras) so the
 *   SDK does not force an `androidx.browser` dependency onto consumer apps.
 * - [OpenMode.EXTERNAL]: a plain ACTION_VIEW handed to whichever app claims the URL.
 *
 * When no Custom Tabs-capable browser is installed, [OpenMode.IN_APP] degrades to
 * [OpenMode.EXTERNAL] rather than failing.
 *
 * Only `http`/`https` URLs may be opened in a Custom Tab; every other scheme
 * (`mailto:`, `tel:`, `market:`, `intent:`, …) goes straight to its handler app.
 */
internal object BrowserLauncher {

    /** Where a URL should be opened. */
    internal enum class OpenMode {
        /** Chrome Custom Tab layered over the host app, falling back to [EXTERNAL]. */
        IN_APP,

        /** The system browser / whichever app handles the URL, as a separate task. */
        EXTERNAL;

        companion object {
            /** Parses the `mode` argument coming from JS; unknown values fall back to [IN_APP]. */
            fun from(raw: String?): OpenMode =
                when (raw?.trim()?.lowercase()) {
                    "external", "browser", "system" -> EXTERNAL
                    else -> IN_APP
                }
        }
    }

    // Custom Tabs intent extras. Mirrors androidx.browser.customtabs.CustomTabsIntent constants;
    // duplicated here so the SDK stays dependency-free. These names are part of the public
    // Custom Tabs protocol and are stable across browser versions.
    private const val ACTION_CUSTOM_TABS_CONNECTION =
        "android.support.customtabs.action.CustomTabsService"
    private const val EXTRA_SESSION = "android.support.customtabs.extra.SESSION"
    private const val EXTRA_TITLE_VISIBILITY = "android.support.customtabs.extra.TITLE_VISIBILITY"
    private const val EXTRA_COLOR_SCHEME = "androidx.browser.customtabs.extra.COLOR_SCHEME"
    private const val EXTRA_SHARE_STATE = "androidx.browser.customtabs.extra.SHARE_STATE"

    private const val SHOW_PAGE_TITLE = 1
    private const val COLOR_SCHEME_SYSTEM = 0
    private const val SHARE_STATE_OFF = 2

    private val WEB_SCHEMES = setOf("http", "https")

    /** Schemes a remote page must never be able to hand to the SDK. */
    private val BLOCKED_SCHEMES = setOf("javascript", "file", "content", "data", "about")

    /** `true` for schemes a Custom Tab can render; every other scheme belongs to another app. */
    fun isWebScheme(scheme: String?): Boolean = scheme?.lowercase() in WEB_SCHEMES

    /**
     * Opens [url] in [mode].
     *
     * @return `true` if some app took the intent, `false` if the URL was malformed or nothing
     *   on the device could handle it. Never throws.
     */
    fun open(context: Context, url: String, mode: OpenMode): Boolean {
        val uri = parseUri(url) ?: run {
            DataPointLogger.w("Refusing to open unusable URL")
            return false
        }

        // Custom Tabs only renders web content; anything else belongs to another app.
        if (mode == OpenMode.IN_APP && isWebScheme(uri.scheme)) {
            val browser = findCustomTabsBrowser(context)
            if (browser != null && launchCustomTab(context, uri, browser)) {
                DataPointLogger.d("Opened in Custom Tab: ${LogSanitizer.urlForLog(url)}")
                return true
            }
            DataPointLogger.d("No Custom Tabs browser available – falling back to external")
        }

        return launchExternal(context, uri, url)
    }

    // ── URL validation ──────────────────────────────────────────────────

    /**
     * Parses [url] and rejects schemes that would let a page reach back into the app —
     * `javascript:`, `file:`, `content:` and friends.
     */
    private fun parseUri(url: String): Uri? {
        if (url.isBlank()) return null
        val uri = try {
            Uri.parse(url.trim())
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme in BLOCKED_SCHEMES) {
            DataPointLogger.w("Blocked attempt to open $scheme URL")
            return null
        }
        // A web URL without a resolvable host (or an opaque one like "http:foo") is unusable.
        if (scheme in WEB_SCHEMES && (uri.isOpaque || uri.host.isNullOrBlank())) return null
        return uri
    }

    // ── Custom Tab ──────────────────────────────────────────────────────

    /**
     * Returns the package of a browser that implements the Custom Tabs service, preferring the
     * user's default browser so the tab reuses its cookies and sign-ins.
     */
    private fun findCustomTabsBrowser(context: Context): String? {
        val pm = context.packageManager

        val capable = try {
            pm.queryIntentServices(Intent(ACTION_CUSTOM_TABS_CONNECTION), 0)
                .mapNotNull { it.serviceInfo?.packageName }
                .toSet()
        } catch (e: Exception) {
            DataPointLogger.d("Custom Tabs service lookup failed: ${LogSanitizer.safeErrorSnippet(e.message)}")
            return null
        }
        if (capable.isEmpty()) return null

        // A browser that handles a generic web intent is the user's default; prefer it.
        val defaultBrowser = try {
            val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                .addCategory(Intent.CATEGORY_BROWSABLE)
            pm.resolveActivity(probe, 0)?.takeUnless { it.isResolverActivity() }
                ?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }

        return defaultBrowser?.takeIf { it in capable } ?: capable.first()
    }

    /** The system chooser resolves as a synthetic activity; it is not a real browser package. */
    private fun ResolveInfo.isResolverActivity(): Boolean =
        activityInfo?.packageName == "android" ||
            activityInfo?.name?.contains("ResolverActivity") == true

    private fun launchCustomTab(context: Context, uri: Uri, browserPackage: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            `package` = browserPackage
            // No CustomTabsSession is bound, but the extra must be present (as a null binder)
            // for browsers to treat this as a Custom Tab rather than a normal navigation.
            putExtras(Bundle().apply { putBinder(EXTRA_SESSION, null) })
            putExtra(EXTRA_TITLE_VISIBILITY, SHOW_PAGE_TITLE)
            putExtra(EXTRA_COLOR_SCHEME, COLOR_SCHEME_SYSTEM)
            putExtra(EXTRA_SHARE_STATE, SHARE_STATE_OFF)
            addFlagsForContext(context)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (e: Exception) {
            DataPointLogger.d("Custom Tab launch failed: ${LogSanitizer.safeErrorSnippet(e.message)}")
            false
        }
    }

    // ── External browser / handler app ──────────────────────────────────

    private fun launchExternal(context: Context, uri: Uri, originalUrl: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            DataPointLogger.d("Opened externally: ${LogSanitizer.urlForLog(originalUrl)}")
            true
        } catch (_: ActivityNotFoundException) {
            DataPointLogger.w("No app can handle ${uri.scheme}: URL")
            false
        } catch (e: Exception) {
            DataPointLogger.e("Failed to open URL: ${LogSanitizer.safeErrorSnippet(e.message)}")
            false
        }
    }

    /**
     * A Custom Tab launched from an Activity stays on the host task, so closing it returns to the
     * task screen. A non-Activity context has no task to attach to and needs its own.
     */
    private fun Intent.addFlagsForContext(context: Context) {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
