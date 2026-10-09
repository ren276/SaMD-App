package com.example.samdapp.data.vitalssource.ble

import java.util.UUID

/**
 * Finds the hub's Serial Number String across EVERY Device Information service instance (parent memo
 * 14.5 item 2). BlueZ publishes its own 0x180A, holding only a PnP ID, next to the hub's, so a client
 * that reads "the" Device Information service may get the one with no serial. Exactly one 0x2A25 may
 * exist across all instances; none, or more than one, is [Result.NotUnique] and the caller rejects
 * HUB_MISMATCH.
 *
 * Pure over plain lists. [T] is whatever the GATT adapter uses to name a characteristic for a later
 * read, so this class never touches Bluetooth.
 */
object DisSearch {

    data class Characteristic<T>(val uuid: UUID, val ref: T)

    data class Service<T>(val uuid: UUID, val characteristics: List<Characteristic<T>>)

    sealed interface Result<out T> {
        /** [softwareRevision] is the 0x2A28 of the same service as the serial, or null if it has none. */
        data class Found<T>(val serial: T, val softwareRevision: T?) : Result<T>

        data object NotUnique : Result<Nothing>
    }

    fun <T> pick(services: List<Service<T>>): Result<T> {
        val serials = services
            .filter { it.uuid == SigUuids.DEVICE_INFORMATION_SERVICE }
            .flatMap { service ->
                service.characteristics
                    .filter { it.uuid == SigUuids.SERIAL_NUMBER }
                    .map { service to it }
            }
        val (service, serial) = serials.singleOrNull() ?: return Result.NotUnique
        val softwareRevision = service.characteristics.firstOrNull { it.uuid == SigUuids.SOFTWARE_REVISION }
        return Result.Found(serial.ref, softwareRevision?.ref)
    }
}
