package com.notpago.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import com.notpago.data.local.NotificationEntity
import com.notpago.ui.theme.*
import com.notpago.util.PrefsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    notifications: List<NotificationEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    prefs: PrefsManager,
    onRefresh: () -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    selectedTab: Int,
    onTabChange: (Int) -> Unit,
    onDeleteNotification: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    var showDeletePinDialog by remember { mutableStateOf(false) }
    var deletePinInput by remember { mutableStateOf("") }
    var deletePinError by remember { mutableStateOf(false) }
    var notificationToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("¿Eliminar todo el historial?", color = MaterialTheme.colorScheme.error) },
            text = { Text("Esta acción es irreversible y vaciará el historial completo en el servidor y en todos los dispositivos.", color = Ink2) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Sí, eliminar todo")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancelar", color = Ink3)
                }
            }
        )
    }

    if (showDeletePinDialog) {
        AlertDialog(
            onDismissRequest = { 
                showDeletePinDialog = false
                deletePinInput = ""
                deletePinError = false
                notificationToDelete = null
            },
            title = { Text("Eliminar Registro", color = Ink) },
            text = {
                Column {
                    Text("Ingresa el PIN de seguridad para borrar este registro.", color = Ink2)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = deletePinInput,
                        onValueChange = { 
                            if (it.length <= 6) deletePinInput = it.trim() 
                            deletePinError = false
                        },
                        label = { Text("PIN de 6 dígitos") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        isError = deletePinError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (deletePinError) {
                        Text(
                            "PIN incorrecto.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val savedPin = prefs.yapeAppPin?.trim()
                        val effectivePin = if (savedPin.isNullOrBlank()) "123456" else savedPin
                        
                        if (deletePinInput.trim() == effectivePin) {
                            notificationToDelete?.let { ref ->
                                onDeleteNotification(ref)
                            }
                            showDeletePinDialog = false
                            deletePinInput = ""
                            notificationToDelete = null
                        } else {
                            deletePinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Borrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeletePinDialog = false
                    notificationToDelete = null
                }) {
                    Text("Cancelar", color = Ink3)
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Historial de Operaciones", fontWeight = FontWeight.Bold, color = Ink) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ink2)
                    }
                    if (prefs.operationMode == "EMISOR") {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ink2)
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Borrar Todo el Historial", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirm = true
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            SearchAndTabs(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                selectedTab = selectedTab,
                onTabChange = onTabChange
            )

            if (notifications.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(64.dp), tint = Ink3)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("No hay notificaciones", style = MaterialTheme.typography.titleMedium, color = Ink2)
                        Text("Esperando nuevas transferencias...", style = MaterialTheme.typography.bodyMedium, color = Ink3)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(notifications, key = { it.operationReference ?: UUID.randomUUID().toString() }) { notification ->
                        NotificationCard(
                            notification = notification,
                            isEmisor = prefs.operationMode == "EMISOR",
                            onDeleteClick = {
                                notificationToDelete = notification.operationReference
                                showDeletePinDialog = true
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchAndTabs(
    query: String, 
    onQueryChange: (String) -> Unit,
    selectedTab: Int,
    onTabChange: (Int) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar por nombre o referencia…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Ink3) },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Border,
                focusedBorderColor = Accent,
                unfocusedContainerColor = Surface,
                focusedContainerColor = Surface
            ),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TabItem("Todos", active = selectedTab == 0, onClick = { onTabChange(0) })
            TabItem("Vinculados", active = selectedTab == 2, onClick = { onTabChange(2) })
            TabItem("Descartados", active = selectedTab == 3, onClick = { onTabChange(3) })
        }
    }
}

@Composable
private fun TabItem(text: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) Accent else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = text,
            color = if (active) Color.White else Ink2,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}
