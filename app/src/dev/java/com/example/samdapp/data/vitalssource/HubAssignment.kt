package com.example.samdapp.data.vitalssource

import com.example.samdapp.domain.vitalssource.Instrument

/** The transport an instrument is assigned to, with the hub that serves it. */
sealed interface HubRoute {
    val hubId: String

    data class Wifi(override val hubId: String) : HubRoute

    data class Ble(override val hubId: String) : HubRoute
}

/**
 * Which hub, over which wire, serves each instrument. Parsed from `BuildConfig.PI_HUB_ASSIGNMENT`.
 *
 * Grammar: `entry ( "," entry )*`, where `entry = INSTRUMENT "=" TRANSPORT ":" HUB_ID`, for example
 * `SPO2=wifi:kernelhub1,BP=ble:kernelhub2`. [Instrument] is an [Instrument] name, TRANSPORT is
 * `wifi` or `ble`, and HUB_ID fullmatches `^[a-z0-9-]{3,32}$`, the rule SaMDPi's profile loader
 * applies. No whitespace anywhere.
 *
 * Fail closed, with no partial assignment: [parse] throws on the first broken rule, naming it, and
 * [parseOrNone] turns that into an assignment that serves nothing, so every acquisition is then
 * refused rather than routed on a half-understood value. The same rules run at configuration time
 * in `app/build.gradle.kts` (the `samd.dev.kernelFallback` precedent), which also holds the one
 * default, and a test parses the generated `BuildConfig` value with this class so the two layers
 * cannot drift apart. This class has no default of its own: the runtime only ever parses what
 * Gradle generated.
 *
 * All Wi-Fi entries must name one hub: there is one gateway base URL, derived from that hub.
 */
class HubAssignment private constructor(private val routes: Map<Instrument, HubRoute>) {

    /** Null means the instrument is unassigned and an acquisition for it is refused. */
    fun routeFor(instrument: Instrument): HubRoute? = routes[instrument]

    /** The one Wi-Fi hub, or null when no instrument is assigned to Wi-Fi. */
    val wifiHubId: String? = routes.values.filterIsInstance<HubRoute.Wifi>().firstOrNull()?.hubId

    companion object {
        private val HUB_ID = Regex("^[a-z0-9-]{3,32}$")

        /** Instruments a BLE hub profile may serve (SaMDPi PR #3 STOP 2 ruling 3). */
        private val BLE_INSTRUMENTS = setOf(Instrument.BP, Instrument.SPO2, Instrument.THERMOMETER)

        /** An assignment that serves nothing. */
        val NONE = HubAssignment(emptyMap())

        fun parseOrNone(raw: String): HubAssignment = try {
            parse(raw)
        } catch (_: IllegalArgumentException) {
            NONE
        }

        /** @throws IllegalArgumentException naming the first rule [raw] breaks. */
        fun parse(raw: String): HubAssignment {
            require(raw.isNotEmpty()) { "PI_HUB_ASSIGNMENT is empty" }
            require(raw.none { it.isWhitespace() }) { "PI_HUB_ASSIGNMENT contains whitespace" }

            val routes = linkedMapOf<Instrument, HubRoute>()
            for (entry in raw.split(",")) {
                val sides = entry.split("=")
                require(sides.size == 2) { "malformed entry '$entry', expected INSTRUMENT=TRANSPORT:HUB_ID" }
                val (instrumentName, target) = sides
                val parts = target.split(":")
                require(parts.size == 2) { "malformed entry '$entry', expected INSTRUMENT=TRANSPORT:HUB_ID" }
                val (transport, hubId) = parts

                val instrument = Instrument.entries.firstOrNull { it.name == instrumentName }
                requireNotNull(instrument) { "unknown instrument '$instrumentName'" }
                require(transport == "wifi" || transport == "ble") { "unknown transport '$transport', expected wifi or ble" }
                require(HUB_ID.matches(hubId)) { "hub_id '$hubId' does not match ^[a-z0-9-]{3,32}$" }
                require(instrument !in routes) { "duplicate instrument $instrumentName" }
                require(transport == "wifi" || instrument in BLE_INSTRUMENTS) {
                    "ble is not allowed for $instrumentName, only BP, SPO2 and THERMOMETER"
                }
                routes[instrument] = if (transport == "wifi") HubRoute.Wifi(hubId) else HubRoute.Ble(hubId)
            }

            val wifiHubs = routes.values.filterIsInstance<HubRoute.Wifi>().map { it.hubId }.toSet()
            require(wifiHubs.size <= 1) { "more than one Wi-Fi hub_id: ${wifiHubs.sorted()}" }
            return HubAssignment(routes)
        }
    }
}
