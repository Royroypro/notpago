# Documentación Técnica - APK NotPago

Esta documentación describe la arquitectura, componentes principales, flujos de datos y estado actual de la aplicación Android `NotPago`, diseñada para interceptar y sincronizar notificaciones de pagos (como Yape/Plin) con un backend centralizado.

---

## 🏗️ Arquitectura General

La aplicación sigue un patrón de diseño basado en **MVVM (Model-View-ViewModel)** y se divide funcionalmente en dos modos de operación principales:
1. **Modo Emisor:** Se encarga de "escuchar" las notificaciones del sistema Android, guardarlas localmente y enviarlas al servidor (Backend) para su registro.
2. **Modo Receptor:** Se encarga de consultar periódicamente o mediante WebSockets las transacciones pendientes desde el servidor para mostrarlas en pantalla.

---

## 📂 Estructura de Paquetes (`com.notpago`)

### 1. `ui` (Interfaz de Usuario)
- **`MainViewModel.kt`**: Es el cerebro de la interfaz de usuario. Gestiona el estado de las notificaciones remotas, locales, conexión con WebSockets (Pusher) y la comunicación con el Backend (pruebas de conexión, sincronización).
- **`screens/DashboardScreen.kt`**: Interfaz principal en Jetpack Compose (presumiblemente) donde el usuario puede ver los pagos.

### 2. `service` (Servicios en Segundo Plano)
- **`YapeNotificationService.kt`**: Extiende de `NotificationListenerService`. Es el componente más crítico en Modo Emisor.
  - Intercepta notificaciones del sistema.
  - Filtra por paquetes válidos (`com.bcp.innovacxion.yapeapp`, `com.bcp.innovacxion.yape`).
  - Utiliza Expresiones Regulares (Regex) para extraer el remitente y monto de los mensajes de Yape y Plin.
  - Guarda el registro en la base de datos local y llama a `SyncHelper` para sincronizar.

### 3. `worker` (Tareas Diferidas / Sincronización)
- **`YapeSyncWorker.kt`**: Hereda de `CoroutineWorker` (WorkManager). Se encarga de leer la base de datos local en busca de notificaciones en estado `PENDING` y enviarlas al servidor mediante peticiones HTTP `POST`.
- **`SyncHelper.kt`**: Clase utilitaria para encolar el trabajo de sincronización usando políticas de Backoff Exponencial en caso de fallos de red.

### 4. `data.local` (Base de Datos Room)
Actualmente existe una **deuda técnica con bases de datos duplicadas**:
- **`data.local`**: Contiene `AppDatabase`, `NotificationDao` y `NotificationEntity`. Utilizado principalmente por el Receptor (y partes del `MainViewModel`).
- **`data.local.database`, `data.local.dao`, `data.local.entity`**: Contiene `AppDatabase` (EmisorDatabase), `YapeNotificationDao`, y `YapeNotificationEntity`. Utilizado por el Emisor (Servicio y Worker).

### 5. `data.remote` (Consumo de APIs)
De forma similar a la base de datos local, existen implementaciones duplicadas:
- **`data.remote`**: Contiene `RetrofitClient` y `YapeApi`. (Utilizado por la UI y ViewModel).
- **`data.remote.api`**: Contiene otro `RetrofitClient` y `YapeApiService`. (Utilizado por el `YapeSyncWorker`).

### 6. `util`
- **`PrefsManager.kt`**: Encapsula el uso de `SharedPreferences`. Almacena credenciales críticas como: `agentToken`, `host`, `restaurantId`, `operationMode`, `isEmisorActive`, etc.

---

## 🔄 Flujos de Datos Principales

### Flujo de Captura de Pago (Emisor)
1. Llega notificación push (Yape/Plin) al dispositivo.
2. `YapeNotificationService` intercepta la notificación.
3. El servicio procesa el texto (Regex) y extrae datos.
4. Se guarda en `YapeNotificationEntity` con estado `PENDING`.
5. Se invoca a `SyncHelper.scheduleSync()`.
6. `YapeSyncWorker` despierta, lee los pendientes y envía el POST al backend (`/api/webhooks/yape`).
7. Si el backend responde 200 o 201, se marca como `SYNCED` localmente.

### Flujo de Visualización de Pagos (Receptor / Emisor)
1. `MainViewModel` inicializa la conexión `Pusher` por WebSockets al canal `yape.receptor.{HASH}`.
2. Recibe evento `yape.notification.received` o consulta vía `GET /api/webhooks/yape`.
3. Almacena las notificaciones en la lista reactiva (StateFlow) `_remoteNotifications` para que la UI las dibuje.
4. Si recibe el evento `yape.notification.linked`, actualiza el estado de la notificación a `LINKED` / `PAID` tanto en UI como en Base de Datos local.

---

## ⚠️ Problemas Conocidos y Deuda Técnica Detectada (Diagnóstico Actual)

Al revisar el código frente a la guía del backend, se han detectado los siguientes puntos que requieren corrección:

1. **Host de WebSocket Estático:**
   En `MainViewModel.kt`, la conexión Pusher tiene `setHost("estacion24.sehuacho.com")` codificado. Debe usar la variable dinámica `prefs.host` o la que defina el panel.
   
2. **Lógica Errónea en HTTP 401:**
   En `YapeSyncWorker.kt`, si el token es revocado (HTTP 401), el worker hace un `break` pero retorna `Result.retry()`, causando que intente reenviar repetidamente (bucles de red inútiles). Debería retornar `Result.failure()` para detener la cola.

3. **Arquitectura Duplicada:**
   La convivencia de dos `AppDatabase` (Room) y dos `RetrofitClient` (Network) en distintos paquetes (`data.local` vs `data.local.database`) genera confusión, código repetitivo y obliga al `MainViewModel` a tener que actualizar manualmente dos bases de datos para mantener la UI sincronizada.

4. **Sender Phone Ausente:**
   La API soporta `sender_phone`, pero el sistema Android no puede capturar esto desde una notificación convencional de Yape. El servicio lo setea como `null`, lo cual es aceptable, pero debe tenerse en cuenta si más adelante el backend lo hiciera obligatorio.

---
*Última actualización de la documentación: Mayo 2026.*
