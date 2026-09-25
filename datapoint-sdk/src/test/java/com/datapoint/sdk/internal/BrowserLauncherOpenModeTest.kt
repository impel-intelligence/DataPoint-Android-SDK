package com.datapoint.sdk.internal

import com.datapoint.sdk.internal.BrowserLauncher.OpenMode
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserLauncherOpenModeTest {

    @Test
    fun `external, browser and system select the system browser`() {
        assertEquals(OpenMode.EXTERNAL, OpenMode.from("external"))
        assertEquals(OpenMode.EXTERNAL, OpenMode.from("browser"))
        assertEquals(OpenMode.EXTERNAL, OpenMode.from("system"))
    }

    @Test
    fun `mode parsing ignores case and surrounding whitespace`() {
        assertEquals(OpenMode.EXTERNAL, OpenMode.from(" External "))
        assertEquals(OpenMode.IN_APP, OpenMode.from("  in_app"))
    }

    @Test
    fun `in_app, unknown and missing modes default to the Custom Tab`() {
        assertEquals(OpenMode.IN_APP, OpenMode.from("in_app"))
        assertEquals(OpenMode.IN_APP, OpenMode.from("custom_tab"))
        assertEquals(OpenMode.IN_APP, OpenMode.from(""))
        assertEquals(OpenMode.IN_APP, OpenMode.from(null))
    }
}
