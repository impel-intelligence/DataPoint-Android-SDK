package com.datapoint.sdk.internal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun `web schemes are http and https, case-insensitive`() {
        assertTrue(UrlPolicy.isWebScheme("http"))
        assertTrue(UrlPolicy.isWebScheme("HTTPS"))
        assertFalse(UrlPolicy.isWebScheme("mailto"))
        assertFalse(UrlPolicy.isWebScheme("intent"))
        assertFalse(UrlPolicy.isWebScheme(null))
    }

    @Test
    fun `schemes that can reach back into the app are blocked`() {
        for (scheme in listOf("javascript", "file", "content", "data", "about", "JavaScript")) {
            assertTrue(scheme, UrlPolicy.isBlockedScheme(scheme))
        }
        assertFalse(UrlPolicy.isBlockedScheme("https"))
        assertFalse(UrlPolicy.isBlockedScheme("market"))
        assertFalse(UrlPolicy.isBlockedScheme(null))
    }

    @Test
    fun `trusted hosts match exactly or as subdomains`() {
        assertTrue(UrlPolicy.isTrustedHost("trydatapoint.com"))
        assertTrue(UrlPolicy.isTrustedHost("task.trydatapoint.com"))
        assertTrue(UrlPolicy.isTrustedHost("api.trydatapoint.com"))
        assertTrue(UrlPolicy.isTrustedHost("TASK.TRYDATAPOINT.COM"))
        assertTrue(UrlPolicy.isTrustedHost("trydatapoint.ai"))
    }

    @Test
    fun `look-alike and unrelated hosts are not trusted`() {
        assertFalse(UrlPolicy.isTrustedHost("evil-trydatapoint.com"))
        assertFalse(UrlPolicy.isTrustedHost("trydatapoint.com.evil.io"))
        assertFalse(UrlPolicy.isTrustedHost("example.com"))
        assertFalse(UrlPolicy.isTrustedHost(""))
        assertFalse(UrlPolicy.isTrustedHost("   "))
        assertFalse(UrlPolicy.isTrustedHost(null))
    }
}
