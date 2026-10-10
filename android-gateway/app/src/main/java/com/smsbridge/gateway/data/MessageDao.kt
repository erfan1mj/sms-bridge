package com.smsbridge.gateway.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE messageId = :id LIMIT 1")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessagesFlow(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE timestamp > :sinceTimestamp ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getMessagesSince(sinceTimestamp: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE syncedToClient = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedMessages(): List<MessageEntity>

    @Query("UPDATE messages SET syncedToClient = 1 WHERE messageId IN (:messageIds)")
    suspend fun markMessagesAsSynced(messageIds: List<String>)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
