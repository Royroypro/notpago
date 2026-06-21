package com.notpago

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.runtime.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.core.app.NotificationManagerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.notpago.data.local.AppDatabase
import com.notpago.ui.screens.DashboardScreen
import com.notpago.ui.screens.SettingsScreen
import com.notpago.ui.screens.SimulationScreen
import com.notpago.ui.theme.NotPagoTheme
import com.notpago.util.PrefsManager
import android.content.BroadcastReceiver
import android.content.Context
import androidx.lifecycle.viewmodel.compose.viewModel
import com.notpago.ui.MainViewModel
import com.notpago.data.local.NotificationEntity
import com.notpago.service.ReceptorService

class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        // Handle result
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        val prefs = PrefsManager(this)
        
        if (prefs.operationMode == "EMISOR" && isNotificationServiceEnabled()) {
            val serviceIntent = Intent(this, com.notpago.service.YapeNotificationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else if (prefs.operationMode == "RECEPTOR") {
            val serviceIntent = Intent(this, com.notpago.service.ReceptorService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
        
        setContent {
            NotPagoTheme {
                val navController = rememberNavController()
                val viewModel: MainViewModel = viewModel()

                val remoteNotifications by viewModel.remoteNotifications.collectAsState()
                val searchQuery by viewModel.searchQuery.collectAsState()
                val selectedTab by viewModel.selectedTab.collectAsState()
                val authStatus by viewModel.authStatus.collectAsState()

                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                LaunchedEffect(Unit) {
                    viewModel.checkAuthentication()
                }

                if (authStatus == MainViewModel.AuthStatus.UNAUTHENTICATED && currentRoute != "settings") {
                    AlertDialog(
                        onDismissRequest = { /* No se puede cerrar sin configurar */ },
                        title = { Text("Configuración Requerida") },
                        text = { Text("La aplicación no está conectada o el Token es inválido. Por favor, configura el servidor y el Token para continuar.") },
                        confirmButton = {
                            Button(onClick = {
                                navController.navigate("settings")
                            }) {
                                Text("Ir a Configuración")
                            }
                        }
                    )
                }
                
                // Registrar receptores
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            when (intent?.action) {
                                "com.notpago.NEW_PAYMENT" -> {
                                    val entity = NotificationEntity(
                                        senderName = intent.getStringExtra("sender") ?: "Cliente",
                                        amount = intent.getDoubleExtra("amount", 0.0),
                                        operationReference = intent.getStringExtra("reference"),
                                        approvalCode = null,
                                        message = null,
                                        rawPayload = "REALTIME",
                                        status = "PENDING",
                                        receivedAt = System.currentTimeMillis()
                                    )
                                    viewModel.addRemoteNotification(entity)
                                    viewModel.refresh()
                                }
                                "com.notpago.STATUS_UPDATED" -> {
                                    val reference = intent.getStringExtra("reference") ?: ""
                                    val status = intent.getStringExtra("status") ?: "linked"
                                    viewModel.updateNotificationStatus(reference, status.uppercase())
                                }
                                "com.notpago.HISTORY_CLEARED" -> {
                                    viewModel.forceClear()
                                }
                                "com.notpago.REQUEST_REFRESH" -> {
                                    viewModel.refresh()
                                }
                            }
                        }
                    }
                    val filter = android.content.IntentFilter().apply {
                        addAction("com.notpago.NEW_PAYMENT")
                        addAction("com.notpago.STATUS_UPDATED")
                        addAction("com.notpago.HISTORY_CLEARED")
                        addAction("com.notpago.REQUEST_REFRESH")
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                    } else {
                        registerReceiver(receiver, filter)
                    }
                    onDispose { unregisterReceiver(receiver) }
                }

                val localNotifications by viewModel.localNotifications.collectAsState(initial = emptyList())
                val emisorNotifications by viewModel.emisorNotifications.collectAsState(initial = emptyList())
                val operationMode by viewModel.operationMode.collectAsState()
                val rawNotifications = remember(operationMode, emisorNotifications, remoteNotifications, localNotifications) {
                    if (operationMode == "EMISOR") {
                        val localMap = emisorNotifications.associateBy { it.operationReference }
                        val remoteMap = remoteNotifications.associateBy { it.operationReference }

                        val merged = mutableListOf<NotificationEntity>()

                        emisorNotifications.forEach { local ->
                            val remote = remoteMap[local.operationReference]
                            if (remote != null) {
                                merged.add(local.copy(status = remote.status, rawPayload = "REMOTE"))
                            } else {
                                merged.add(local)
                            }
                        }

                        remoteNotifications.forEach { remote ->
                            if (!localMap.containsKey(remote.operationReference)) {
                                merged.add(remote)
                            }
                        }

                        merged.sortedByDescending { it.receivedAt }
                    } else {
                        // RECEPTOR: la DB local es la fuente de verdad.
                        // Persiste aunque el servidor borre el registro.
                        // Se actualiza reactivamente via Room Flow cada vez que llega un pago.
                        localNotifications.sortedByDescending { it.receivedAt }
                    }
                }

                val dashboardNotifications = remember(rawNotifications, searchQuery) {
                    rawNotifications.filter {
                        val matchesSearch = searchQuery.isBlank() || 
                            it.senderName.contains(searchQuery, ignoreCase = true) ||
                            it.operationReference?.contains(searchQuery, ignoreCase = true) == true
                        
                        val status = it.status.uppercase()
                        val matchesTab = status == "PENDING" || status == "SENT" || status == "SYNCED"

                        matchesSearch && matchesTab
                    }
                }

                val historyNotifications = remember(rawNotifications, searchQuery, selectedTab) {
                    rawNotifications.filter {
                        val matchesSearch = searchQuery.isBlank() || 
                            it.senderName.contains(searchQuery, ignoreCase = true) ||
                            it.operationReference?.contains(searchQuery, ignoreCase = true) == true
                        
                        val status = it.status.uppercase()
                        val matchesTab = when (selectedTab) {
                            2 -> status == "LINKED"
                            3 -> status == "DISCARDED"
                            else -> true
                        }

                        matchesSearch && matchesTab
                    }
                }
                
                NavHost(navController = navController, startDestination = "dashboard") {
                    composable("dashboard") {
                        com.notpago.ui.screens.DashboardScreen(
                            notifications = dashboardNotifications,
                            allNotifications = rawNotifications,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                            prefs = prefs,
                            onRefresh = { viewModel.refresh() },
                            onNavigateToHistory = { navController.navigate("history") },
                            onNavigateToSettings = { navController.navigate("settings") },
                            onNavigateToSimulation = { navController.navigate("simulation") },
                            isNotificationEnabled = isNotificationServiceEnabled(),
                            hasPostNotifications = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            } else { true },
                            onRequestPermission = {
                                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            },
                            onRequestPostPermission = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onDeleteNotification = { viewModel.deleteNotificationGlobally(it) }
                        )
                    }
                    composable("history") {
                        com.notpago.ui.screens.HistoryScreen(
                            notifications = historyNotifications,
                            searchQuery = searchQuery,
                            onSearchQueryChange = { viewModel.updateSearchQuery(it) },
                            prefs = prefs,
                            onRefresh = { viewModel.refresh() },
                            onClearHistory = { viewModel.clearNotifications() },
                            onBack = { navController.popBackStack() },
                            selectedTab = selectedTab,
                            onTabChange = { viewModel.updateSelectedTab(it) },
                            onDeleteNotification = { viewModel.deleteNotificationGlobally(it) }
                        )
                    }
                    composable("settings") {
                        val connectionResult by viewModel.connectionResult.collectAsState()
                        SettingsScreen(
                            prefs = prefs,
                            connectionResult = connectionResult,
                            onTestConnection = { h, t -> viewModel.testConnection(h, t) },
                            onClearResult = { viewModel.clearConnectionResult() },
                            onBack = { 
                                val receptorIntent = Intent(this@MainActivity, com.notpago.service.ReceptorService::class.java)
                                val emisorIntent = Intent(this@MainActivity, com.notpago.service.YapeNotificationService::class.java)
                                if (prefs.operationMode == "RECEPTOR") {
                                    stopService(emisorIntent)
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        startForegroundService(receptorIntent)
                                    } else {
                                        startService(receptorIntent)
                                    }
                                } else {
                                    stopService(receptorIntent)
                                    if (isNotificationServiceEnabled()) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            startForegroundService(emisorIntent)
                                        } else {
                                            startService(emisorIntent)
                                        }
                                    }
                                }
                                viewModel.setupWebSocket()
                                viewModel.checkAuthentication()
                                viewModel.refresh()
                                viewModel.updateOperationMode()
                                navController.popBackStack() 
                            }
                        )
                    }
                    composable("simulation") {
                        SimulationScreen(onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val packageName = packageName
        val enabledListeners = NotificationManagerCompat.getEnabledListenerPackages(this)
        return enabledListeners.contains(packageName)
    }
}
