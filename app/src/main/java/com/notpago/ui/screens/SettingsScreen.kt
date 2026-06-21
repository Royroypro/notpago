package com.notpago.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.notpago.util.PrefsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs: PrefsManager,
    connectionResult: String?,
    onTestConnection: (String, String) -> Unit,
    onClearResult: () -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var host by remember { mutableStateOf(prefs.host) }
    var agentToken by remember { mutableStateOf(prefs.agentToken) }
    var restaurantId by remember { mutableStateOf(prefs.restaurantId) }
    var monitoredPackages by remember { mutableStateOf(prefs.monitoredPackages) }
    var operationMode by remember { mutableStateOf(prefs.operationMode) }
    var notificationSound by remember { mutableStateOf(prefs.notificationSound) }
    
    data class AppOption(val name: String, val packages: List<String>)
    val supportedApps = listOf(
        AppOption("Yape", listOf("com.bcp.innovacxion.yapeapp", "com.bcp.innovacxion.yape")),
        AppOption("App BCP", listOf("com.bcp.bank.bcp")),
        AppOption("Plin (Interbank)", listOf("pe.com.interbank.mobilebanking")),
        AppOption("Plin (Scotiabank)", listOf("com.scotiabank.banca.movil")),
        AppOption("Plin (BBVA)", listOf("com.bbva.nxt_peru")),
        AppOption("Izipay", listOf("com.izipay.app")),
        AppOption("Ligo", listOf("com.tarjetaligo.app")),
        AppOption("Simulador NotPago", listOf("com.notpago"))
    )

    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        onDispose { onClearResult() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Configuración", fontWeight = FontWeight.Bold) },
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Conexión", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Host (Servidor)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = agentToken,
                onValueChange = { agentToken = it },
                label = { Text("Agent Token") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = restaurantId,
                onValueChange = { restaurantId = it },
                label = { Text("ID del Restaurante (para WebSockets)") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Button(
                onClick = { onTestConnection(host, agentToken) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Probar Conexión")
            }

            if (connectionResult != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (connectionResult.contains("Exitosa")) 
                            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        connectionResult,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            HorizontalDivider()

            Text("Captura de Pagos (Selecciona las apps)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    supportedApps.forEach { appOption ->
                        val isChecked = monitoredPackages.containsAll(appOption.packages)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    val newSet = monitoredPackages.toMutableSet()
                                    if (checked) {
                                        newSet.addAll(appOption.packages)
                                    } else {
                                        newSet.removeAll(appOption.packages.toSet())
                                    }
                                    monitoredPackages = newSet
                                }
                            )
                            Text(appOption.name, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }

            Text("Persistencia", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Desactivar Optimización de Batería")
            }
            Text(
                "Recomendado para que el sistema no cierre la App en segundo plano.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Preferencias", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Sonido de Notificación", style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = notificationSound == "default", onClick = { notificationSound = "default" })
                    Text("Predeterminado")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = notificationSound == "cash_register", onClick = { notificationSound = "cash_register" })
                    Text("Caja Registradora")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = notificationSound == "chime", onClick = { notificationSound = "chime" })
                    Text("Timbre")
                }
            }

            HorizontalDivider()

            Text("Modo de Operación", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = operationMode == "EMISOR",
                            onClick = { operationMode = "EMISOR" }
                        )
                        Text("Emisor (Captura de notificaciones)")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = operationMode == "RECEPTOR",
                            onClick = { operationMode = "RECEPTOR" }
                        )
                        Text("Receptor (Consulta remota)")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    prefs.host = host.trim().removeSuffix("/")
                    prefs.agentToken = agentToken.trim()
                    prefs.restaurantId = restaurantId.trim()
                    prefs.monitoredPackages = monitoredPackages
                    prefs.operationMode = operationMode
                    prefs.notificationSound = notificationSound
                    onBack()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Guardar Cambios", fontWeight = FontWeight.Bold)
            }
        }
    }
}
