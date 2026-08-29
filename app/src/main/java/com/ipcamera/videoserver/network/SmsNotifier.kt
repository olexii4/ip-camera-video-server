package com.ipcamera.videoserver.network

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun send(number: String, message: String, simSlot: Int = 0) {
        if (number.isBlank()) return
        try {
            val smsManager = resolveSmsManager(simSlot)
            smsManager.sendTextMessage(number, null, message, null, null)
        } catch (_: Exception) {}
    }

    private fun resolveSmsManager(simSlot: Int): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val subId = resolveSubscriptionId(simSlot)
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return context.getSystemService(SmsManager::class.java)
                    .createForSubscriptionId(subId)
            }
            return context.getSystemService(SmsManager::class.java)
        }
        @Suppress("DEPRECATION")
        return SmsManager.getDefault()
    }

    private fun resolveSubscriptionId(simSlot: Int): Int {
        return try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            sm?.activeSubscriptionInfoList
                ?.firstOrNull { it.simSlotIndex == simSlot }
                ?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        } catch (_: SecurityException) {
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }
}
