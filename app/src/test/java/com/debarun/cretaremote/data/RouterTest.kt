package com.debarun.cretaremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterTest {
    @Test fun decodesGoogleReferencePolyline() {
        val pts = Router.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].first, 1e-6)
        assertEquals(-120.2, pts[0].second, 1e-6)
        assertEquals(40.7, pts[1].first, 1e-6)
        assertEquals(-120.95, pts[1].second, 1e-6)
        assertEquals(43.252, pts[2].first, 1e-6)
        assertEquals(-126.453, pts[2].second, 1e-6)
    }

    @Test fun emptyStringGivesNoPoints() {
        assertTrue(Router.decode("").isEmpty())
    }

    @Test fun cacheKeyIgnoresTinyDifferences() {
        val a = Router.key(12.97161 to 77.59461, 13.0 to 77.6)
        val b = Router.key(12.97163 to 77.59462, 13.0 to 77.6)
        assertEquals(a, b)
    }
}
