package com.example.samdapp.data.vitalssource.ble

/** The two characteristics whose indications the phone enables. There is deliberately no SIG
 *  member: the phone never enables the SIG characteristic's CCCD (section 15, G2). */
enum class IndicationTarget { CONTROL_POINT, ENVELOPE }

sealed interface InitStep {
    data class RequestMtu(val mtu: Int) : InitStep

    data class EnableIndications(val target: IndicationTarget) : InitStep
}

/**
 * What runs on EVERY connection, in this order, before the link is reported ready and so before any
 * Control Point write (parent memo 14.5 items 1 and 6, section 15 G2):
 *
 * 1. request MTU 517 again, explicitly;
 * 2. enable indications on the Control Point;
 * 3. enable indications on the Reading Envelope.
 *
 * The CCCDs come before any write because a START answered while the Control Point CCCD is off would
 * leave a stale queued response on the hub (the intruder residual, parent memo 14.4). The adapter
 * executes this list and records each step's failure; the protocol checks that record after connect.
 */
object HubInitPlan {
    const val MTU = 517

    fun steps(): List<InitStep> = listOf(
        InitStep.RequestMtu(MTU),
        InitStep.EnableIndications(IndicationTarget.CONTROL_POINT),
        InitStep.EnableIndications(IndicationTarget.ENVELOPE),
    )
}
