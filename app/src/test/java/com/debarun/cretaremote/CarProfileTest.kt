package com.debarun.cretaremote

import com.debarun.cretaremote.data.DueState
import com.debarun.cretaremote.data.Powertrain
import com.debarun.cretaremote.data.Service
import com.debarun.cretaremote.data.ServiceLog
import com.debarun.cretaremote.data.Vin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarProfileTest {
    @Test fun vinYearAndMaker() {
        val i = Vin.decode("malb3161lnm012345")
        assertTrue(i.valid)
        assertEquals("Hyundai Motor India Ltd.", i.maker)
        assertEquals(2022, i.modelYear) // 10th char 'N'
        assertEquals("012345", i.serial)
    }

    @Test fun vinRejectsBadInput() {
        assertFalse(Vin.decode("MALB3161LN").valid)
        assertEquals("A VIN never contains I, O or Q", Vin.decode("MALB3161ONM012345").problem)
        assertNull(Vin.decode("").problem)
    }

    @Test fun serviceDue() {
        val day = 86_400_000L
        val now = 1_800_000_000_000L
        val last = ServiceLog(now - 100 * day, 20_000.0, "", null)
        assertEquals(DueState.OK, Service.due(last, 22_000.0, now, 10_000, 12).state)
        assertEquals(DueState.SOON, Service.due(last, 29_500.0, now, 10_000, 12).state)
        assertEquals(DueState.DUE, Service.due(last, 30_100.0, now, 10_000, 12).state)
        val old = ServiceLog(now - 400 * day, 20_000.0, "", null)
        assertEquals(DueState.DUE, Service.due(old, 21_000.0, now, 10_000, 12).state)
        assertEquals(DueState.NONE, Service.due(null, 1.0, now, 10_000, 12).state)
    }

    @Test fun everyPowertrainHasATransmission() {
        assertTrue(Powertrain.entries.all { it.transmissions.isNotEmpty() })
        assertEquals(Powertrain.DIESEL_CRDI, Powertrain.of("DIESEL_CRDI"))
    }
}
