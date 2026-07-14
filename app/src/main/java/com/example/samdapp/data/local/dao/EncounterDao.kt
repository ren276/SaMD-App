package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.samdapp.data.local.entity.EncounterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(encounter: EncounterEntity)

    @Query("SELECT * FROM encounters WHERE id = :encounterId")
    fun observeById(encounterId: String): Flow<EncounterEntity?>
}
