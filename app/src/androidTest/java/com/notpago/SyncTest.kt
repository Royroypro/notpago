package com.notpago

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.notpago.data.SyncManager
import com.notpago.data.local.AppDatabase
import com.notpago.data.local.NotificationEntity
import com.notpago.util.PrefsManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncTest {

    private lateinit var db: AppDatabase
    private lateinit var prefs: PrefsManager
    private lateinit var syncManager: SyncManager

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = AppDatabase.getDatabase(context)
        prefs = PrefsManager(context)
        syncManager = SyncManager(context)
        
        // Configuración mínima para el test
        if (prefs.host.isEmpty()) prefs.host = "https://estacion24.sehuacho.com"
        if (prefs.restaurantId.isEmpty()) prefs.restaurantId = "test_res_1"
        if (prefs.agentToken.isEmpty()) prefs.agentToken = "test_token"
    }

    @Test
    fun testSyncNotification() = runBlocking {
        // 1. Insertar una notificación de prueba
        val entity = NotificationEntity(
            amount = 1.0,
            senderName = "TEST USER",
            approvalCode = "0000",
            message = "Test message",
            operationReference = "TEST_" + System.currentTimeMillis(),
            rawPayload = "TEST_PAYLOAD",
            status = "PENDING"
        )
        val id = db.notificationDao().insert(entity)
        assertTrue(id > 0)

        // 2. Ejecutar sincronización
        syncManager.syncPending()

        // 3. Verificar si el estado cambió (a SENT o FAILED)
        // Nota: Esto dependerá de si el servidor responde o no, 
        // pero al menos verificamos que la lógica de red no crashee.
        val notifications = db.notificationDao().getPendingNotifications()
        val isStillPending = notifications.any { it.senderName == "TEST USER" && it.status == "PENDING" }
        
        assertFalse("La notificación debería haber intentado sincronizarse", isStillPending)
    }
}
