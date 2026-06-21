# 📱 Guía Técnica: Implementación Agente Yape Android v2.5

Esta guía detalla cómo integrar el Agente Android con el Backend Central tras las últimas optimizaciones de seguridad y acceso dual.

---

## 🔐 1. Autenticación Unificada
Toda comunicación entre el Agente y el Servidor debe incluir el siguiente Header:
- **Header:** `X-Yape-Agent-Token`
- **Valor:** El Token generado en el Panel de Administración del Restaurante.

> [!NOTE]
> Ya **no es necesario** enviar el `restaurant_id`. El servidor identifica el restaurante automáticamente mediante el Token.

---

## 📤 2. Modo Emisor (Captura de Notificaciones)
Cuando el Agente detecta una notificación de la App Yape, debe enviar un `POST`.

- **Endpoint:** `POST /api/webhooks/yape`
- **Payload (JSON):**
```json
{
  "amount": 25.50,
  "operation_reference": "982731",
  "sender_name": "JUAN PEREZ",
  "sender_phone": "987654321",
  "received_at": "2026-05-24 18:30:00",
  "approval_code": "482",
  "message": "Pago de almuerzo",
  "transaction_type": "yape"
}
```
*Nota: `amount` y `operation_reference` son **obligatorios**. Los demás campos son opcionales.*
- **Respuesta Exitosa (201):** El pago ha sido registrado y notificado a la caja.

---

## 📥 3. Modo Receptor (Consulta de Pagos)
Para que el personal de salón vea los pagos pendientes en tiempo real sin estar logueado como usuario.

- **Endpoint:** `GET /api/webhooks/yape?only_pending=1`
- **Headers:** Incluir el `X-Yape-Agent-Token`.
- **Respuesta (200 OK):**
```json
{
  "data": [
    {
      "id": 43,
      "amount": 45.90,
      "sender_name": "CLIENTE TEST",
      "operation_reference": "TEST-INT-123",
      "status": "pending",
      "received_at": "2026-05-24T21:34:33.000000Z"
    }
  ]
}
```

---

## 🧹 3.1 Limpieza Global del Historial (Clear History)
El Agente puede solicitar la limpieza global del historial, moviendo las notificaciones al archivo y vaciando las pantallas de los demás dispositivos vinculados.

- **Endpoint:** `DELETE /api/webhooks/yape/clear`
- **Headers:** Incluir el `X-Yape-Agent-Token`.
- **Respuesta Exitosa (200 OK):**
```json
{
  "status": "success",
  "message": "Historial eliminado correctamente"
}
```

---

## ⚡ 4. Sincronización Real-Time (WebSockets)
Para evitar saturar el servidor con consultas constantes, el Agente debe conectarse al servicio de WebSockets.

### Configuración de Conexión (Pusher/Echo SDK):
- **Host:** `estacion24.sehuacho.com`
- **Puerto:** `443` (WSS)
- **Key:** `restaurante_key`
- **Cluster:** `mt1` (o el configurado)

### Canal a Escuchar:
- **Tipo:** Canal Público (Channel).
- **Nombre:** `yape.receptor.{HASH_SECRETO}`
  - El `{HASH_SECRETO}` se calcula en el Agente de la siguiente forma:
    `SHA256(AGENT_TOKEN + RESTAURANT_ID)`
  - Ejemplo en Kotlin/Java: 
    `val channelName = "yape.receptor." + sha256(token + restaurantId)`
- **Eventos:**
  1. `yape.notification.received`: Se dispara cuando llega un nuevo Yape. (Acción: Mostrar en lista y sonar "Ding").
  2. `yape.notification.linked`: Se dispara cuando el cajero vincula el pago a un pedido. (Acción: Quitar de la lista de pendientes).
  3. `yape.history.cleared`: Se dispara cuando un Emisor limpia el historial globalmente mediante el endpoint `DELETE`. (Acción: Vaciar la lista de la pantalla en tiempo real).

---

## 🛠 5. Recomendaciones de Implementación Android
1. **Deduplicación:** Antes de enviar el POST, verifica si la `operation_reference` ya fue enviada recientemente para evitar duplicados por reintentos de red.
2. **Foreground Service:** La captura de notificaciones debe correr como un `Service` persistente para que Android no lo mate en segundo plano.
3. **Manejo de Errores:**
   - **401 Unauthorized:** El Token es inválido. Notificar al usuario para que lo revise en el panel.
   - **422 Unprocessable Entity:** Datos del Yape incompletos o mal formateados.
