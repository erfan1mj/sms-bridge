package com.smsbridge.gateway.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sent_requests",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
data class SentRequestEntity(
    @PrimaryKey
    val requestId: String,
    val recipient: String,
    val body: String,
    val subscriptionId: Int = -1,
    val status: String, // QUEUED, SUBMITTED, SENT, DELIVERED, FAILED
    val partsCount: Int = 1,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val errorCode: String? = null,
    val errorMessage: String? = null
)
