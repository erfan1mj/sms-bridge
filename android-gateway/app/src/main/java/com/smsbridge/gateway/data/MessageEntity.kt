package com.smsbridge.gateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["syncedToClient"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val subscriptionId: Int = -1,
    val isMultipart: Boolean = false,
    val partCount: Int = 1,
    val direction: String = "INCOMING", // "INCOMING" or "OUTGOING"
    val syncedToClient: Boolean = false
)
