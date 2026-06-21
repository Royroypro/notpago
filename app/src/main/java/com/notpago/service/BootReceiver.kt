package com.notpago.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.notpago.util.PrefsManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            val serviceClass = if (prefs.operationMode == "EMISOR") {
                YapeNotificationService::class.java
            } else {
                ReceptorService::class.java
            }
            
            val serviceIntent = Intent(context, serviceClass)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
