package com.smsbridge.gateway.sms

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.smsbridge.gateway.protocol.SimCardInfo

object SimManager {
    @SuppressLint("MissingPermission")
    fun getActiveSimCards(context: Context): List<SimCardInfo> {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            return emptyList()
        }

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return emptyList()

        val defaultSmsSubId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } else {
            -1
        }

        return try {
            val subList: List<SubscriptionInfo>? = subscriptionManager.activeSubscriptionInfoList
            subList?.map { info ->
                val subId = info.subscriptionId
                SimCardInfo(
                    subscriptionId = subId,
                    displayName = info.displayName?.toString() ?: "SIM ${info.simSlotIndex + 1}",
                    carrierName = info.carrierName?.toString() ?: "Unknown Carrier",
                    slotIndex = info.simSlotIndex,
                    isDefault = (subId == defaultSmsSubId)
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
