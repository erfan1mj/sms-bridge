package com.smsbridge.gateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedClientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(client: PairedClientEntity)

    @Update
    suspend fun update(client: PairedClientEntity)

    @Query("SELECT * FROM paired_clients WHERE clientId = :clientId LIMIT 1")
    suspend fun getClientById(clientId: String): PairedClientEntity?

    @Query("SELECT * FROM paired_clients WHERE authToken = :token AND isRevoked = 0 LIMIT 1")
    suspend fun getActiveClientByToken(token: String): PairedClientEntity?

    @Query("SELECT * FROM paired_clients WHERE isRevoked = 0")
    fun getActiveClientsFlow(): Flow<List<PairedClientEntity>>

    @Query("SELECT * FROM paired_clients WHERE isRevoked = 0")
    suspend fun getActiveClients(): List<PairedClientEntity>

    @Query("UPDATE paired_clients SET isRevoked = 1 WHERE clientId = :clientId")
    suspend fun revokeClient(clientId: String)

    @Query("UPDATE paired_clients SET isRevoked = 1")
    suspend fun revokeAllClients()

    @Query("UPDATE paired_clients SET lastSeenAt = :timestamp WHERE clientId = :clientId")
    suspend fun updateLastSeen(clientId: String, timestamp: Long = System.currentTimeMillis())
}
