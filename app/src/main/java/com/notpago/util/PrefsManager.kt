package com.notpago.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PrefsManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            context,
            "notpago_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // En caso de corrupción de Keystore o fallo al desencriptar (ej. backup restaurado sin las llaves)
        android.util.Log.e("PrefsManager", "Error al abrir preferencias encriptadas. Se resetearán los datos.", e)
        
        // Eliminar los archivos corruptos
        val dataDir = context.applicationInfo.dataDir
        val prefsFile = java.io.File(dataDir, "shared_prefs/notpago_prefs.xml")
        val keysFile = java.io.File(dataDir, "shared_prefs/__androidx_security_crypto_encrypted_prefs__.xml")
        if (prefsFile.exists()) prefsFile.delete()
        if (keysFile.exists()) keysFile.delete()

        // Crear de nuevo desde cero
        EncryptedSharedPreferences.create(
            context,
            "notpago_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var host: String
        get() = sharedPreferences.getString("host", "") ?: ""
        set(value) = sharedPreferences.edit().putString("host", value).apply()

    var agentToken: String
        get() = sharedPreferences.getString("agent_token", "") ?: ""
        set(value) = sharedPreferences.edit().putString("agent_token", value).apply()

    var monitoredPackages: Set<String>
        get() = sharedPreferences.getStringSet("monitored_packages", setOf("com.bcp.innovacxion.yapeapp", "com.bcp.innovacxion.yape", "com.notpago")) ?: setOf("com.bcp.innovacxion.yapeapp", "com.bcp.innovacxion.yape", "com.notpago")
        set(value) = sharedPreferences.edit().putStringSet("monitored_packages", value).apply()

    var operationMode: String
        get() = sharedPreferences.getString("operation_mode", "EMISOR") ?: "EMISOR"
        set(value) = sharedPreferences.edit().putString("operation_mode", value).apply()

    var restaurantId: String
        get() = sharedPreferences.getString("restaurant_id", "") ?: ""
        set(value) = sharedPreferences.edit().putString("restaurant_id", value).apply()

    var clearedNotificationIds: Set<String>
        get() = sharedPreferences.getStringSet("cleared_notification_ids", emptySet()) ?: emptySet()
        set(value) = sharedPreferences.edit().putStringSet("cleared_notification_ids", value).apply()

    var isReceptorActive: Boolean
        get() = sharedPreferences.getBoolean("is_receptor_active", true)
        set(value) = sharedPreferences.edit().putBoolean("is_receptor_active", value).apply()

    var isEmisorActive: Boolean
        get() = sharedPreferences.getBoolean("is_emisor_active", true)
        set(value) = sharedPreferences.edit().putBoolean("is_emisor_active", value).apply()

    var yapeAppPin: String?
        get() = sharedPreferences.getString("yape_app_pin", null)
        set(value) = sharedPreferences.edit().putString("yape_app_pin", value).apply()

    var notificationSound: String
        get() = sharedPreferences.getString("notification_sound", "default") ?: "default"
        set(value) = sharedPreferences.edit().putString("notification_sound", value).apply()

    // Timestamp ISO 8601 UTC del último sync exitoso del RECEPTOR.
    // Null = primer sync (fetches todos los activos sin filtro de fecha).
    var lastReceptorSyncTime: String?
        get() = sharedPreferences.getString("last_receptor_sync_time", null)
        set(value) = sharedPreferences.edit().putString("last_receptor_sync_time", value).apply()

    fun isConfigured(): Boolean {
        return host.isNotEmpty() && agentToken.isNotEmpty()
    }
}
