package com.smsbridge.gateway.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "paired_clients")
data class PairedClientEntity(
    @PrimaryKey
    val clientId: String,
    val clientName: String,
    val authToken: String,
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis(),
    val isRevoked: Boolean = false
)
