package com.example.samdapp.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.example.samdapp.data.local.entity.DoctorEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DoctorDao {
    /** Small, fixed-size reference table (9 mock doctors) — loading it whole is fine, same
     *  posture as the Referrals/Audit "observeAll" reads elsewhere in this app. */
    @Query("SELECT * FROM doctors")
    fun observeAll(): Flow<List<DoctorEntity>>

    @Query("SELECT * FROM doctors WHERE id = :id")
    fun observeById(id: String): Flow<DoctorEntity?>
}
