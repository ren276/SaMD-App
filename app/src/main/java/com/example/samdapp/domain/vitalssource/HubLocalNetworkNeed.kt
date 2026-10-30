package com.example.samdapp.domain.vitalssource

/**
 * Whether starting [instrument] must first ask for ACCESS_LOCAL_NETWORK (memo Q3, P2-R10). Only a
 * build that has an instrument hub binds an implementation; where none is bound the answer is "no
 * request". Asked each time Start is clicked, because the grant can change between two clicks.
 */
interface HubLocalNetworkNeed {
    fun needsRequest(instrument: Instrument): Boolean
}
