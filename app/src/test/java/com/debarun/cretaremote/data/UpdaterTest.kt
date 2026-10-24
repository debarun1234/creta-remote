package com.debarun.cretaremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun higherPatchMinorMajorAreNewer() {
        assertTrue(Updater.isNewer("v1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.1.0", "1.0.9"))
        assertTrue(Updater.isNewer("v2.0.0", "1.99.99"))
    }

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(Updater.isNewer("v1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("1.1", "1.1.0"))
        assertFalse(Updater.isNewer("v1.0.9", "1.1.0"))
    }

    @Test fun numbersCompareAsNumbersNotText() {
        assertTrue(Updater.isNewer("1.0.10", "1.0.9"))
    }

    @Test fun tagsWithoutANumberAreIgnored() {
        assertNull(Updater.parseVersion("latest"))
        assertFalse(Updater.isNewer("latest", "1.0.0"))
        assertEquals(listOf(1, 2, 3), Updater.parseVersion("release-v1.2.3-rc"))
    }
}
