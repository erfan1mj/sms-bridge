package com.smsbridge.gateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SentRequestDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRequest(request: SentRequestEntity): Long

    @Update
    suspend fun updateRequest(request: SentRequestEntity)

    @Query("SELECT * FROM sent_requests WHERE requestId = :id LIMIT 1")
    suspend fun getRequestById(id: String): SentRequestEntity?

    @Query("UPDATE sent_requests SET status = :status, updatedAt = :updatedAt, errorCode = :errorCode, errorMessage = :errorMessage WHERE requestId = :requestId")
    suspend fun updateStatus(
        requestId: String,
        status: String,
        updatedAt: Long = System.currentTimeMillis(),
        errorCode: String? = null,
        errorMessage: String? = null
    )

    @Query("SELECT * FROM sent_requests ORDER BY createdAt DESC")
    fun getAllRequestsFlow(): Flow<List<SentRequestEntity>>

    @Query("SELECT * FROM sent_requests WHERE status IN ('QUEUED', 'SUBMITTED', 'SENT') ORDER BY createdAt DESC")
    suspend fun getPendingOrRecentSent(): List<SentRequestEntity>

    @Query("DELETE FROM sent_requests")
    suspend fun deleteAllRequests()
}
