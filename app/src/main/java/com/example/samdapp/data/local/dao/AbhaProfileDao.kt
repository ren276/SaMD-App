package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.samdapp.data.local.entity.AbhaProfileEntity

@Dao
interface AbhaProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AbhaProfileEntity)

    @Query("SELECT * FROM abha_profiles WHERE abhaId = :abhaId")
    suspend fun getByAbhaId(abhaId: String): AbhaProfileEntity?
}
