package com.debarun.cretaremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripGeoTest {
    @Test fun findsStartAndEndFromLatLonFields() {
        val g = TripGeoParser.parse("""{"tripTime":"20261024122000","startLat":13.1,"startLon":77.5,"endLat":13.0,"endLon":77.6}""")!!
        assertEquals(13.1, g.start!!.first, 1e-9)
        assertEquals(77.6, g.end!!.second, 1e-9)
    }

    @Test fun findsNestedCoordObjects() {
        val g = TripGeoParser.parse("""{"startPos":{"coord":{"lat":13.1,"lon":77.5}},"goalPos":{"coord":{"lat":13.0,"lon":77.6}}}""")!!
        assertEquals(77.5, g.start!!.second, 1e-9)
        assertEquals(13.0, g.end!!.first, 1e-9)
    }

    @Test fun readsPointListsEitherWayRound() {
        val g = TripGeoParser.parse("""{"route":[[77.5,13.1],[77.55,13.05],[77.6,13.0]]}""")!!
        assertEquals(3, g.path.size)
        assertEquals(13.1, g.path[0].first, 1e-9)
        assertEquals(77.5, g.path[0].second, 1e-9)
    }

    @Test fun decodesAnEncodedRouteLine() {
        val enc = Router.geometryOf(listOf(13.1 to 77.5, 13.12 to 77.52, 13.15 to 77.55))
        val g = TripGeoParser.parse("""{"tripPolyline":"$enc"}""")!!
        assertEquals(3, g.path.size)
    }

    @Test fun ignoresTripsWithNoPositions() {
        assertNull(TripGeoParser.parse("""{"tripTime":"20261024122000","tripDist":17,"tripDrvTime":42}"""))
        assertNull(TripGeoParser.parse(null))
        assertNotNull(TripGeoParser.parse("""{"lat":13.1,"lon":77.5,"endLat":13.0,"endLon":77.6}"""))
        assertTrue(TripGeoParser.describe("{}").contains("no positions"))
    }
}
