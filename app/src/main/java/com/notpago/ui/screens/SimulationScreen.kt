package com.notpago.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notpago.data.local.database.AppDatabase
import com.notpago.data.local.entity.YapeNotificationEntity
import com.notpago.worker.SyncHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import android.os.Build

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimulationScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    
    var senderName by remember { mutableStateOf("JUAN PEREZ") }
    var amount by remember { mutableStateOf("15.50") }
    var approvalCode by remember { mutableStateOf("") }
    var isSimulating by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        var uniqueCode = ""
        var isUnique = false
        while (!isUnique) {
            val randomNum = (100000..999999).random().toString()
            val existing = db.yapeNotificationDao().getNotificationByReference(randomNum)
            if (existing == null) {
                uniqueCode = randomNum
                isUnique = true
            }
        }
        approvalCode = uniqueCode
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Simular Pago", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                "Ingresa datos ficticios para probar el flujo de captura y envío.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = senderName,
                onValueChange = { senderName = it },
                label = { Text("Nombre del Remitente") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Monto (S/)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = approvalCode,
                onValueChange = { approvalCode = it },
                label = { Text("Código de Aprobación") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        isSimulating = true
                        
                        val opReference = approvalCode.trim().ifEmpty { "SIM_" + System.currentTimeMillis() }
                        
                        // Check if exists
                        val existing = db.yapeNotificationDao().getNotificationByReference(opReference)
                        if (existing != null) {
                            launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(context, "El código '$opReference' ya existe.", android.widget.Toast.LENGTH_LONG).show()
                            }
                            isSimulating = false
                            return@launch
                        }

                        val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
                        val entity = YapeNotificationEntity(
                            amount = amount.toDoubleOrNull() ?: 0.0,
                            senderName = senderName,
                            senderPhone = null,
                            approvalCode = if (approvalCode.isBlank()) null else approvalCode.trim(),
                            message = "$senderName te yapeó S/ $amount",
                            operationReference = opReference,
                            transactionType = "yape",
                            receivedAt = currentTime,
                            syncStatus = com.notpago.data.local.entity.SyncStatus.PENDING
                        )
                        db.yapeNotificationDao().insertNotification(entity)
                        SyncHelper.scheduleSync(context)
                        isSimulating = false
                        launch(Dispatchers.Main) { onBack() }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isSimulating
            ) {
                if (isSimulating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Enviar Inserción Directa", fontWeight = FontWeight.Bold)
                }
            }
            
            OutlinedButton(
                onClick = {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val channel = NotificationChannel("test_channel", "Test", NotificationManager.IMPORTANCE_DEFAULT)
                        manager.createNotificationChannel(channel)
                    }
                    val builder = NotificationCompat.Builder(context, "test_channel")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("Yape")
                        .setContentText("$senderName te yapeó S/ $amount")
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    manager.notify((System.currentTimeMillis() % 10000).toInt(), builder.build())
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Emitir Notificación Real (Probar Lector)", fontWeight = FontWeight.Bold)
            }
        }
    }
}
