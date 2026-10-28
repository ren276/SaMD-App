package com.example.samdapp.data.local

import androidx.room.withTransaction
import javax.inject.Inject

/** One Room transaction around [run]'s block: every DAO write inside it commits together or not at
 *  all. The facility claim and its audit row use it (facility-scope memo 3.2, FS-R5). */
interface LocalTransaction {
    suspend fun <T> run(block: suspend () -> T): T
}

class RoomLocalTransaction @Inject constructor(private val db: AppDatabase) : LocalTransaction {
    override suspend fun <T> run(block: suspend () -> T): T = db.withTransaction(block)
}
