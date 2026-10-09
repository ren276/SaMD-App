package com.example.samdapp.data.vitalssource.ble

import org.junit.Assert.assertEquals
import org.junit.Test

/** G-B13, plan contents: what the adapter runs on every connection (section 15, G2). The order
 *  over two connections on a fake link is Phase 2's half of G-B13. */
class HubInitPlanTest {

    @Test
    fun `the plan is exactly MTU 517, Control Point indications, Envelope indications`() {
        assertEquals(
            listOf(
                InitStep.RequestMtu(517),
                InitStep.EnableIndications(IndicationTarget.CONTROL_POINT),
                InitStep.EnableIndications(IndicationTarget.ENVELOPE),
            ),
            HubInitPlan.steps(),
        )
    }

    @Test
    fun `the SIG characteristic cannot be named in a plan`() {
        assertEquals(
            listOf(IndicationTarget.CONTROL_POINT, IndicationTarget.ENVELOPE),
            IndicationTarget.entries.toList(),
        )
    }

    @Test
    fun `the MTU requested is 517`() {
        assertEquals(517, HubInitPlan.MTU)
    }
}
