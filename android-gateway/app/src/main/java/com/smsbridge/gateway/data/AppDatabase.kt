package com.smsbridge.gateway.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AppDatabase private constructor(context: Context) {
    private val dbHelper = DatabaseHelper(context.applicationContext)

    private val messagesStateFlow = MutableStateFlow<List<MessageEntity>>(emptyList())
    private val requestsStateFlow = MutableStateFlow<List<SentRequestEntity>>(emptyList())
    private val clientsStateFlow = MutableStateFlow<List<PairedClientEntity>>(emptyList())

    init {
        refreshAllFlows()
    }

    private fun refreshAllFlows() {
        try {
            messagesStateFlow.value = queryAllMessagesSync()
            requestsStateFlow.value = queryAllRequestsSync()
            clientsStateFlow.value = queryActiveClientsSync()
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing database StateFlows", e)
        }
    }

    fun messageDao(): MessageDao = object : MessageDao {
        override suspend fun insertMessage(message: MessageEntity): Long = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("messageId", message.messageId)
                put("sender", message.sender)
                put("body", message.body)
                put("timestamp", message.timestamp)
                put("subscriptionId", message.subscriptionId)
                put("isMultipart", if (message.isMultipart) 1 else 0)
                put("partCount", message.partCount)
                put("direction", message.direction)
                put("syncedToClient", if (message.syncedToClient) 1 else 0)
            }
            val rowId = db.insertWithOnConflict("messages", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            messagesStateFlow.value = queryAllMessagesSync()
            rowId
        }

        override suspend fun updateMessage(message: MessageEntity): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("sender", message.sender)
                put("body", message.body)
                put("timestamp", message.timestamp)
                put("subscriptionId", message.subscriptionId)
                put("isMultipart", if (message.isMultipart) 1 else 0)
                put("partCount", message.partCount)
                put("direction", message.direction)
                put("syncedToClient", if (message.syncedToClient) 1 else 0)
            }
            db.update("messages", values, "messageId = ?", arrayOf(message.messageId))
            messagesStateFlow.value = queryAllMessagesSync()
        }

        override suspend fun getMessageById(id: String): MessageEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM messages WHERE messageId = ? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursorToMessage(cursor) else null
            }
        }

        override fun getAllMessagesFlow(): Flow<List<MessageEntity>> = messagesStateFlow.asStateFlow()

        override suspend fun getRecentMessages(limit: Int): List<MessageEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val list = mutableListOf<MessageEntity>()
            db.rawQuery("SELECT * FROM messages ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToMessage(cursor))
                }
            }
            list
        }

        override suspend fun getMessagesSince(sinceTimestamp: Long, limit: Int): List<MessageEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val list = mutableListOf<MessageEntity>()
            db.rawQuery("SELECT * FROM messages WHERE timestamp > ? ORDER BY timestamp ASC LIMIT ?", arrayOf(sinceTimestamp.toString(), limit.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToMessage(cursor))
                }
            }
            list
        }

        override suspend fun getUnsyncedMessages(): List<MessageEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val list = mutableListOf<MessageEntity>()
            db.rawQuery("SELECT * FROM messages WHERE syncedToClient = 0 ORDER BY timestamp ASC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToMessage(cursor))
                }
            }
            list
        }

        override suspend fun markMessagesAsSynced(messageIds: List<String>): Unit = withContext(Dispatchers.IO) {
            if (messageIds.isEmpty()) return@withContext
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply { put("syncedToClient", 1) }
            val placeholders = messageIds.joinToString(",") { "?" }
            db.update("messages", values, "messageId IN ($placeholders)", messageIds.toTypedArray())
            messagesStateFlow.value = queryAllMessagesSync()
        }

        override suspend fun deleteAllMessages(): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.delete("messages", null, null)
            messagesStateFlow.value = emptyList()
        }
    }

    fun sentRequestDao(): SentRequestDao = object : SentRequestDao {
        override suspend fun insertRequest(request: SentRequestEntity): Long = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("requestId", request.requestId)
                put("recipient", request.recipient)
                put("body", request.body)
                put("subscriptionId", request.subscriptionId)
                put("status", request.status)
                put("partsCount", request.partsCount)
                put("createdAt", request.createdAt)
                put("updatedAt", request.updatedAt)
                put("errorCode", request.errorCode)
                put("errorMessage", request.errorMessage)
            }
            val rowId = db.insertWithOnConflict("sent_requests", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            requestsStateFlow.value = queryAllRequestsSync()
            rowId
        }

        override suspend fun updateRequest(request: SentRequestEntity): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("recipient", request.recipient)
                put("body", request.body)
                put("subscriptionId", request.subscriptionId)
                put("status", request.status)
                put("partsCount", request.partsCount)
                put("updatedAt", request.updatedAt)
                put("errorCode", request.errorCode)
                put("errorMessage", request.errorMessage)
            }
            db.update("sent_requests", values, "requestId = ?", arrayOf(request.requestId))
            requestsStateFlow.value = queryAllRequestsSync()
        }

        override suspend fun getRequestById(id: String): SentRequestEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM sent_requests WHERE requestId = ? LIMIT 1", arrayOf(id)).use { cursor ->
                if (cursor.moveToFirst()) cursorToRequest(cursor) else null
            }
        }

        override suspend fun updateStatus(
            requestId: String,
            status: String,
            updatedAt: Long,
            errorCode: String?,
            errorMessage: String?
        ): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("status", status)
                put("updatedAt", updatedAt)
                put("errorCode", errorCode)
                put("errorMessage", errorMessage)
            }
            db.update("sent_requests", values, "requestId = ?", arrayOf(requestId))
            requestsStateFlow.value = queryAllRequestsSync()
        }

        override fun getAllRequestsFlow(): Flow<List<SentRequestEntity>> = requestsStateFlow.asStateFlow()

        override suspend fun getPendingOrRecentSent(): List<SentRequestEntity> = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            val list = mutableListOf<SentRequestEntity>()
            db.rawQuery("SELECT * FROM sent_requests WHERE status IN ('QUEUED', 'SUBMITTED', 'SENT') ORDER BY createdAt DESC", null).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToRequest(cursor))
                }
            }
            list
        }

        override suspend fun deleteAllRequests(): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            db.delete("sent_requests", null, null)
            requestsStateFlow.value = emptyList()
        }
    }

    fun pairedClientDao(): PairedClientDao = object : PairedClientDao {
        override suspend fun insertOrUpdate(client: PairedClientEntity): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("clientId", client.clientId)
                put("clientName", client.clientName)
                put("authToken", client.authToken)
                put("pairedAt", client.pairedAt)
                put("lastSeenAt", client.lastSeenAt)
                put("isRevoked", if (client.isRevoked) 1 else 0)
            }
            db.insertWithOnConflict("paired_clients", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            clientsStateFlow.value = queryActiveClientsSync()
        }

        override suspend fun update(client: PairedClientEntity): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply {
                put("clientName", client.clientName)
                put("authToken", client.authToken)
                put("lastSeenAt", client.lastSeenAt)
                put("isRevoked", if (client.isRevoked) 1 else 0)
            }
            db.update("paired_clients", values, "clientId = ?", arrayOf(client.clientId))
            clientsStateFlow.value = queryActiveClientsSync()
        }

        override suspend fun getClientById(clientId: String): PairedClientEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM paired_clients WHERE clientId = ? LIMIT 1", arrayOf(clientId)).use { cursor ->
                if (cursor.moveToFirst()) cursorToClient(cursor) else null
            }
        }

        override suspend fun getActiveClientByToken(token: String): PairedClientEntity? = withContext(Dispatchers.IO) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT * FROM paired_clients WHERE authToken = ? AND isRevoked = 0 LIMIT 1", arrayOf(token)).use { cursor ->
                if (cursor.moveToFirst()) cursorToClient(cursor) else null
            }
        }

        override fun getActiveClientsFlow(): Flow<List<PairedClientEntity>> = clientsStateFlow.asStateFlow()

        override suspend fun getActiveClients(): List<PairedClientEntity> = withContext(Dispatchers.IO) {
            queryActiveClientsSync()
        }

        override suspend fun revokeClient(clientId: String): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply { put("isRevoked", 1) }
            db.update("paired_clients", values, "clientId = ?", arrayOf(clientId))
            clientsStateFlow.value = queryActiveClientsSync()
        }

        override suspend fun revokeAllClients(): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply { put("isRevoked", 1) }
            db.update("paired_clients", values, null, null)
            clientsStateFlow.value = emptyList()
        }

        override suspend fun updateLastSeen(clientId: String, timestamp: Long): Unit = withContext(Dispatchers.IO) {
            val db = dbHelper.writableDatabase
            val values = ContentValues().apply { put("lastSeenAt", timestamp) }
            db.update("paired_clients", values, "clientId = ?", arrayOf(clientId))
            clientsStateFlow.value = queryActiveClientsSync()
        }
    }

    private fun queryAllMessagesSync(): List<MessageEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<MessageEntity>()
        db.rawQuery("SELECT * FROM messages ORDER BY timestamp DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToMessage(cursor))
            }
        }
        return list
    }

    private fun queryAllRequestsSync(): List<SentRequestEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<SentRequestEntity>()
        db.rawQuery("SELECT * FROM sent_requests ORDER BY createdAt DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToRequest(cursor))
            }
        }
        return list
    }

    private fun queryActiveClientsSync(): List<PairedClientEntity> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<PairedClientEntity>()
        db.rawQuery("SELECT * FROM paired_clients WHERE isRevoked = 0 ORDER BY lastSeenAt DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursorToClient(cursor))
            }
        }
        return list
    }

    private fun cursorToMessage(cursor: Cursor): MessageEntity {
        return MessageEntity(
            messageId = cursor.getString(cursor.getColumnIndexOrThrow("messageId")),
            sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
            body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp")),
            subscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow("subscriptionId")),
            isMultipart = cursor.getInt(cursor.getColumnIndexOrThrow("isMultipart")) == 1,
            partCount = cursor.getInt(cursor.getColumnIndexOrThrow("partCount")),
            direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
            syncedToClient = cursor.getInt(cursor.getColumnIndexOrThrow("syncedToClient")) == 1
        )
    }

    private fun cursorToRequest(cursor: Cursor): SentRequestEntity {
        return SentRequestEntity(
            requestId = cursor.getString(cursor.getColumnIndexOrThrow("requestId")),
            recipient = cursor.getString(cursor.getColumnIndexOrThrow("recipient")),
            body = cursor.getString(cursor.getColumnIndexOrThrow("body")),
            subscriptionId = cursor.getInt(cursor.getColumnIndexOrThrow("subscriptionId")),
            status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
            partsCount = cursor.getInt(cursor.getColumnIndexOrThrow("partsCount")),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updatedAt")),
            errorCode = cursor.getString(cursor.getColumnIndexOrThrow("errorCode")),
            errorMessage = cursor.getString(cursor.getColumnIndexOrThrow("errorMessage"))
        )
    }

    private fun cursorToClient(cursor: Cursor): PairedClientEntity {
        return PairedClientEntity(
            clientId = cursor.getString(cursor.getColumnIndexOrThrow("clientId")),
            clientName = cursor.getString(cursor.getColumnIndexOrThrow("clientName")),
            authToken = cursor.getString(cursor.getColumnIndexOrThrow("authToken")),
            pairedAt = cursor.getLong(cursor.getColumnIndexOrThrow("pairedAt")),
            lastSeenAt = cursor.getLong(cursor.getColumnIndexOrThrow("lastSeenAt")),
            isRevoked = cursor.getInt(cursor.getColumnIndexOrThrow("isRevoked")) == 1
        )
    }

    private class DatabaseHelper(context: Context) : SQLiteOpenHelper(context, "sms_bridge.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS messages (
                    messageId TEXT PRIMARY KEY,
                    sender TEXT,
                    body TEXT,
                    timestamp INTEGER,
                    subscriptionId INTEGER,
                    isMultipart INTEGER,
                    partCount INTEGER,
                    direction TEXT,
                    syncedToClient INTEGER
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_ts ON messages(timestamp)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_sync ON messages(syncedToClient)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sent_requests (
                    requestId TEXT PRIMARY KEY,
                    recipient TEXT,
                    body TEXT,
                    subscriptionId INTEGER,
                    status TEXT,
                    partsCount INTEGER,
                    createdAt INTEGER,
                    updatedAt INTEGER,
                    errorCode TEXT,
                    errorMessage TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sent_created ON sent_requests(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sent_status ON sent_requests(status)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS paired_clients (
                    clientId TEXT PRIMARY KEY,
                    clientName TEXT,
                    authToken TEXT,
                    pairedAt INTEGER,
                    lastSeenAt INTEGER,
                    isRevoked INTEGER
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS messages")
            db.execSQL("DROP TABLE IF EXISTS sent_requests")
            db.execSQL("DROP TABLE IF EXISTS paired_clients")
            onCreate(db)
        }
    }

    companion object {
        private const val TAG = "AppDatabase"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: AppDatabase(context).also { instance = it }
            }
        }
    }
}
