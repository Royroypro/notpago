package com.notpago.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notpago.data.local.NotificationEntity
import com.notpago.ui.theme.*
import com.notpago.util.PrefsManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    notifications: List<NotificationEntity>,
    allNotifications: List<NotificationEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    prefs: PrefsManager,
    onRefresh: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSimulation: () -> Unit,
    isNotificationEnabled: Boolean,
    hasPostNotifications: Boolean,
    onRequestPermission: () -> Unit,
    onRequestPostPermission: () -> Unit,
    onDeleteNotification: (String) -> Unit
) {
    android.util.Log.d("DashboardScreenDebug", "Rendering DashboardScreen. Notifications size: ${notifications.size}. Mode: ${prefs.operationMode}")
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isReceptorActive by remember { mutableStateOf(prefs.isReceptorActive) }
    var isEmisorActive by remember { mutableStateOf(prefs.isEmisorActive) }
    val isEmisor = prefs.operationMode == "EMISOR"
    
    var showPinDialog by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onRefresh()
    }

    val pendingList = allNotifications.filter {
        val s = it.status.uppercase(); s == "PENDING" || s == "SYNCED" || s == "SENT"
    }
    val linkedCount = allNotifications.count { it.status.uppercase() == "LINKED" }
    val discardedCount = allNotifications.count { it.status.uppercase() == "DISCARDED" }
    val pendingAmount = pendingList.sumOf { it.amount }
    val totalAmount = allNotifications.sumOf { it.amount }
    val todayCal = Calendar.getInstance()
    val todayYear = todayCal.get(Calendar.YEAR)
    val todayDoy = todayCal.get(Calendar.DAY_OF_YEAR)
    val todayAmount = allNotifications.filter {
        val c = Calendar.getInstance().apply { timeInMillis = it.receivedAt }
        c.get(Calendar.YEAR) == todayYear && c.get(Calendar.DAY_OF_YEAR) == todayDoy
    }.sumOf { it.amount }
    val paymentsByDay = allNotifications
        .groupBy {
            val c = Calendar.getInstance().apply { timeInMillis = it.receivedAt }
            Triple(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
        }
        .entries
        .sortedByDescending { (key, _) ->
            Calendar.getInstance().apply {
                set(Calendar.YEAR, key.first); set(Calendar.MONTH, key.second); set(Calendar.DAY_OF_MONTH, key.third)
            }.timeInMillis
        }
        .take(7)
        .map { (key, list) ->
            val c = Calendar.getInstance().apply {
                set(Calendar.YEAR, key.first); set(Calendar.MONTH, key.second); set(Calendar.DAY_OF_MONTH, key.third)
            }
            Pair(SimpleDateFormat("dd/MM", Locale.getDefault()).format(c.time), list.size)
        }

    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = { 
                showPinDialog = false
                pinInput = ""
                pinError = false
            },
            title = { Text("Acceso Protegido", color = Ink) },
            text = {
                Column {
                    Text("Ingresa el PIN de seguridad para cambiar la configuración.", color = Ink2)
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { 
                            if (it.length <= 6) pinInput = it.trim() 
                            pinError = false
                        },
                        label = { Text("PIN de 6 dígitos") },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                        ),
                        singleLine = true,
                        isError = pinError,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (pinError) {
                        Text(
                            "PIN incorrecto. Revisa el panel administrativo.",
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
                        
                        if (pinInput.trim() == effectivePin) {
                            showPinDialog = false
                            pinInput = ""
                            onNavigateToSettings()
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) {
                    Text("Entrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialog = false }) {
                    Text("Cancelar", color = Ink3)
                }
            }
        )
    }

    var notificationToDelete by remember { mutableStateOf<String?>(null) }
    var showDeletePinDialog by remember { mutableStateOf(false) }
    var deletePinInput by remember { mutableStateOf("") }
    var deletePinError by remember { mutableStateOf(false) }

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
                title = {
                    Column {
                        Surface(
                            color = if (prefs.operationMode == "EMISOR") Color(0xFFE8F5E9) else Color(0xFFE3F2FD),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                if (prefs.operationMode == "EMISOR") "📡 MODO EMISOR ACTIVO" else "📱 MODO RECEPTOR",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (prefs.operationMode == "EMISOR") Color(0xFF2E7D32) else Color(0xFF1565C0),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                letterSpacing = 1.sp
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("not", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
                            Text(" pago", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Accent)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Ink2)
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = Ink2)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Configuración") },
                            leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                showPinDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Historial") },
                            leadingIcon = { Icon(Icons.Default.List, contentDescription = null) },
                            onClick = {
                                showMenu = false
                                onNavigateToHistory()
                            }
                        )
                        if (prefs.operationMode == "EMISOR") {
                            DropdownMenuItem(
                                text = { Text("Simular Pago") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onNavigateToSimulation()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = Surface2
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Check for notification permissions if in EMISOR mode
            if (prefs.operationMode == "EMISOR") {
                if (!isNotificationEnabled || !hasPostNotifications) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Permisos Faltantes", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                if (!isNotificationEnabled) {
                                    Text("• Acceso a Notificaciones (Para leer Yapes)", color = MaterialTheme.colorScheme.onErrorContainer)
                                    Button(
                                        onClick = onRequestPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Text("Activar Acceso a Notificaciones", color = MaterialTheme.colorScheme.onError)
                                    }
                                }
                                
                                if (!hasPostNotifications) {
                                    Text("• Enviar Notificaciones (Servicio en 2do plano)", color = MaterialTheme.colorScheme.onErrorContainer)
                                    Button(
                                        onClick = onRequestPostPermission,
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Text("Activar Enviar Notificaciones", color = MaterialTheme.colorScheme.onError)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Surface3),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = Ink2)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Permiso de Notificación Activo", style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.Bold)
                                    Text("Si no lee las notificaciones, reinicia (apaga y enciende) este permiso.", style = MaterialTheme.typography.labelSmall, color = Ink3)
                                }
                                TextButton(onClick = onRequestPermission) {
                                    Text("Reiniciar", color = Accent, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // Balance Card
            item {
                BalanceCard(pendingAmount = pendingAmount, todayAmount = todayAmount, pendingCount = pendingList.size, totalAmount = totalAmount)
            }
            item {
                StatsRow(
                    pendingCount = pendingList.size,
                    linkedCount = linkedCount,
                    discardedCount = discardedCount,
                    totalCount = allNotifications.size
                )
            }
            if (paymentsByDay.isNotEmpty()) {
                item {
                    PaymentsByDayCard(paymentsByDay = paymentsByDay)
                }
            }

            // Service Toggle
            // Service Toggle
            if (prefs.operationMode == "RECEPTOR") {
                item {
                    ServiceToggleRow(
                        isActive = isReceptorActive,
                        onToggle = { 
                            isReceptorActive = it
                            prefs.isReceptorActive = it
                            // Enviar intent para actualizar el servicio
                            val intent = android.content.Intent(context, com.notpago.service.ReceptorService::class.java)
                            intent.action = "ACTION_UPDATE_TOGGLE"
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            onRefresh() 
                        }
                    )
                }
            } else if (prefs.operationMode == "EMISOR") {
                item {
                    ServiceToggleRow(
                        isActive = isEmisorActive,
                        onToggle = {
                            isEmisorActive = it
                            prefs.isEmisorActive = it
                            // Enviar intent para actualizar el servicio Emisor
                            val intent = android.content.Intent(context, com.notpago.service.YapeNotificationService::class.java)
                            intent.action = "ACTION_UPDATE_TOGGLE"
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                context.startForegroundService(intent)
                            } else {
                                context.startService(intent)
                            }
                            onRefresh()
                        }
                    )
                }
            }

            // Search
            item {
                SearchBarOnly(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange
                )
            }

            // Grouping logic for dates could be added here, for now a simple list
            if (notifications.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(48.dp), tint = Ink3)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("No hay registros en esta categoría", color = Ink3)
                        }
                    }
                }
            } else {
                item {
                    val count = notifications.size
                    SectionLabel(title = "Recientes", badgeText = "$count Activas")
                }
                items(notifications) { notification ->
                    NotificationCard(
                        notification = notification, 
                        isEmisor = isEmisor,
                        onDeleteClick = {
                            if (notification.operationReference != null) {
                                notificationToDelete = notification.operationReference
                                showDeletePinDialog = true
                            }
                        }
                    )
                }
            }
            
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun BalanceCard(pendingAmount: Double, todayAmount: Double, pendingCount: Int, totalAmount: Double) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Accent)
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Pendiente de cobro", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("S/", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 4.dp, end = 4.dp))
                Text("%.2f".format(pendingAmount), color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text("HOY", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    Text("S/ %.2f".format(todayAmount), color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("PENDIENTES", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    Text("$pendingCount pagos", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text("TOTAL ACUM.", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
                    Text("S/ %.2f".format(totalAmount), color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ServiceToggleRow(isActive: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val infiniteTransition = rememberInfiniteTransition()
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1000),
                        repeatMode = RepeatMode.Reverse
                    )
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF22C55E).copy(alpha = alpha) else Ink3)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        if (isActive) "Servicio activo" else "Servicio pausado",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink
                    )
                    Text(
                        if (isActive) "alertas en vivo" else "Alertas desactivadas",
                        style = MaterialTheme.typography.bodySmall,
                        color = Ink3
                    )
                }
            }
            Switch(
                checked = isActive,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = Ink3,
                    uncheckedTrackColor = Surface3
                )
            )
        }
    }
}

@Composable
fun SearchBarOnly(
    query: String, 
    onQueryChange: (String) -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Buscar por nombre o referencia…") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Ink3) },
            trailingIcon = { 
                Box(modifier = Modifier.size(32.dp).background(Surface3, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.List, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Border,
                focusedBorderColor = Accent,
                unfocusedContainerColor = Surface,
                focusedContainerColor = Surface
            ),
            singleLine = true
        )
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (active) Color.White else Ink3,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun StatsRow(pendingCount: Int, linkedCount: Int, discardedCount: Int, totalCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatChip(label = "Pendientes", value = "$pendingCount", valueColor = PendingText, bgColor = PendingBg, modifier = Modifier.weight(1f))
        StatChip(label = "Vinculados", value = "$linkedCount", valueColor = PaidText, bgColor = PaidBg, modifier = Modifier.weight(1f))
        StatChip(label = "Total", value = "$totalCount", valueColor = Ink2, bgColor = Surface3, modifier = Modifier.weight(1f))
    }
}

@Composable
fun StatChip(label: String, value: String, valueColor: Color, bgColor: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = androidx.compose.foundation.BorderStroke(1.dp, valueColor.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, color = valueColor, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = valueColor.copy(alpha = 0.75f))
        }
    }
}

@Composable
fun PaymentsByDayCard(paymentsByDay: List<Pair<String, Int>>) {
    val maxCount = paymentsByDay.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pagos por Día", fontWeight = FontWeight.Bold, color = Ink, style = MaterialTheme.typography.bodyMedium)
                Text("últimos ${paymentsByDay.size} días", style = MaterialTheme.typography.labelSmall, color = Ink3)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                paymentsByDay.reversed().forEach { (date, count) ->
                    val barHeight = if (count > 0) (56 * count / maxCount).coerceAtLeast(6) else 0
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (count > 0) "$count" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(barHeight.dp)
                                .padding(horizontal = 3.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(if (count > 0) Accent.copy(alpha = 0.85f) else Surface3)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = date,
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink3,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionLabel(title: String, badgeText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ink3, letterSpacing = 1.sp)
        Surface(
            color = Surface3,
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Border2)
        ) {
            Text(badgeText, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Accent)
        }
    }
}

@Composable
fun NotificationCard(
    notification: NotificationEntity, 
    isEmisor: Boolean,
    onDeleteClick: () -> Unit
) {
    val status = notification.status.uppercase()
    val isConfirmed = status == "LINKED" || status == "DISCARDED"
    val isSynced = status == "SYNCED"
    val isPending = status == "PENDING"
    val isLocalPending = isPending && notification.rawPayload == "YAPE"
    val isRemotePending = isPending && (notification.rawPayload == "REMOTE" || notification.rawPayload == "REALTIME")
    
    val statusColor = if (isConfirmed) PaidText else if (isSynced || isPending) PendingText else FailedText
    val statusBg = if (isConfirmed) PaidBg else if (isSynced || isPending) PendingBg else FailedBg
    
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    val time = sdf.format(Date(notification.receivedAt))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = Surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, Border)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.width(4.dp).fillMaxHeight().align(Alignment.CenterStart).background(statusColor))
            
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    val initials = if (notification.senderName.length >= 2) 
                        notification.senderName.substring(0, 2).uppercase() 
                        else notification.senderName.take(1).uppercase()
                    Text(initials, color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = notification.senderName, 
                            fontWeight = FontWeight.Bold, 
                            color = Ink, 
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        )
                        Text(
                            text = "S/ %.2f".format(notification.amount), 
                            fontWeight = FontWeight.Bold, 
                            color = Ink, 
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "REF: #${notification.operationReference?.takeLast(5) ?: "---"} · $time", 
                                style = MaterialTheme.typography.bodySmall, 
                                color = Ink3,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                color = statusBg,
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                val statusText = if (isEmisor) {
                                    if (isConfirmed) "Pagado" 
                                    else if (isSynced || isRemotePending) "Pendiente de confirmación en caja" 
                                    else if (isLocalPending) "Enviando a la nube..." 
                                    else "Fallido"
                                } else {
                                    if (isConfirmed) "Vinculado a Caja" 
                                    else if (isPending || isSynced) "Sin Vincular" 
                                    else "Fallido"
                                }
                                Text(
                                    text = statusText, 
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), 
                                    style = MaterialTheme.typography.labelSmall, 
                                    color = statusColor, 
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        
                        if (isEmisor) {
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = onDeleteClick,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
