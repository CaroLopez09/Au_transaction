# Revisión de la documentación nueva de Kira

**Fecha:** 15 de septiembre de 2026
**Fuente:** `https://docs.kirafin.ai` leída con el MCP `kira-docs`
**Estado (15-sep, noche):** corregido todo lo de §1, §2 y §3, verificado contra el sandbox. Detalle en [`ESTADO.md`](ESTADO.md) §3.11 y §3.12.

> Volver a [`ESTADO.md`](ESTADO.md) · Contrato HTTP: [`API-GUIA.md`](API-GUIA.md)

---

## 0. Cómo se hizo y qué no dice

La documentación **no publica un registro de cambios**, así que no se puede afirmar qué cambió
exactamente respecto a la versión anterior. Lo que sí se hizo es comparar lo que documenta hoy
contra lo que hace el código.

Páginas leídas:

| Página | Para qué |
|---|---|
| `webhooks/overview`, `best-practices`, `validate-signatures` | Reintentos, acuse, firma |
| `webhooks/event-catalog`, `notification-examples` | Nombres de evento y forma exacta de cada payload |
| `lifecycles/states`, `onboarding-and-verification` | Estados de cada recurso y cuáles son finales |
| `compliance/holds` | Retenciones `KYT_*` e `IN_REVIEW` |
| `reference/idempotency`, `pagination` | Claves de idempotencia y las cuatro formas de listado |
| `reference/virtual-accounts/values`, `payouts/values`, `rfis/values` | Valores por versión |
| `using-the-api/versioning`, `conventions`, `rate-limits` | Versión, convenciones, límites |
| `get-started/go-live-checklist` | Qué tiene que ser verdad antes de producción |
| `sandbox/forcing-outcomes`, `test-value-tables` | Valores que fuerzan cada resultado |
| `api-reference/*` (ambas versiones) | Inventario de rutas |

**Por qué las pruebas no lo ven:** simulan los webhooks con la forma antigua de los datos. Todo
lo de §1 pasa con los tests en verde.

---

## 1. 🔴 Defectos: webhooks que se procesan mal sin dar error

| # | Evento | Payload documentado | Qué hace el código | Efecto |
|---|---|---|---|---|
| W1 | `virtual_account.deposit_funds_refunded` | Sin `status`; trae `return_details { code, reason, refunded_at }` | `DepositStatus.fromEventName` espera `virtual_account.deposit_returned`, que no existe (`DepositStatus.java:55`). Cae a `fromWire(null)` → `COMPLETED` (`DepositStatus.java:26`) | **Un depósito devuelto se registra como acreditado** y suma saldo |
| W2 | `virtual_account.deposit_scheduled`, `virtual_account.deposit_in_review` | Sin `status` | Mismo camino → `COMPLETED` | Dinero programado o en revisión aparece como disponible |
| W3 | `user.status_changed` | `previous_status` y `new_status` | Sólo mueve el estado si hay `status` (`ProcessWebhookUseCase.java:276`) | **Se ignora el evento que Kira pide suscribir**, el único que llega en cada transición |
| W4 | `user.verification.failed` | `reasons[]` (array) | Lee `reason`, `rejection_reason`, `message` (`ProcessWebhookUseCase.java:271-273`) | Se guarda el texto genérico. El motivo real **no se puede recuperar**: ninguna lectura del usuario lo devuelve |
| W5 | `user.liveness_completed` | `result: "approved"` y `person_reference_id` (`null` si la prueba era de la empresa) | Lee `status` → `LivenessStatus.PENDING` (`ProcessWebhookUseCase.java:252-254`) | **La prueba de vida de los UBO nunca queda completada por webhook** |
| W6 | `virtual_account.deposit_funds_received` | Ordenante y riel anidados: `source.sender_name`, `source.payment_rail` | Los busca en el primer nivel (`KiraDepositEvent.java:44-46`) | Ordenante y riel vacíos en cada depósito |
| W7 | `rfi.not_resolved` | `resolution_reason` (`expired` / `rejected`) | No se lee | Ya estaba en ESTADO §4.4 y en AUT-034 |

### Payloads de referencia (copiados de `webhooks/notification-examples`)

```json
// virtual_account.deposit_funds_refunded
{
  "event_id": "…", "virtual_account_id": "…", "deposit_id": "…",
  "amount": "100.00", "currency": "USD", "created_at": "…",
  "return_details": { "code": "R01", "reason": "Insufficient funds at the sending bank", "refunded_at": "…" }
}

// user.status_changed
{ "event_id": "…", "user_id": "…", "previous_status": "VERIFYING", "new_status": "REJECTED" }

// user.verification.failed
{ "event_id": "…", "user_id": "…", "verification_status": "rejected", "reasons": ["Verification session expired"] }

// user.liveness_completed
{ "event_id": "…", "user_id": "…", "person_reference_id": "0123…", "result": "approved" }

// virtual_account.deposit_funds_received
{
  "event_id": "…", "user_id": "…", "virtual_account_id": "…", "deposit_id": "…",
  "amount": "100.00", "currency": "USD", "created_at": "…",
  "source": { "payment_rail": "wire", "description": "Invoice 4471", "sender_name": "Northwind Trading LLC",
              "trace_number": "…", "sender_bank_routing_number": "000000001" }
}

// rfi.not_resolved
{ "event_id": "…", "rfi_id": "…", "user_id": "…", "to_status": "not_resolved", "resolution_reason": "expired" }
```

**Cómo arreglarlo bien:** una prueba por evento que alimente `ProcessWebhookUseCase` con el
payload exacto de arriba, no con uno construido a mano.

---

## 2. 🟠 Lo que hay que actualizar

### 2.1 Estados de depósito

Kira documenta 6: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED`, `KYT_PENDING`, `KYT_REJECTED`.
**Sólo `FAILED` y `REFUNDED` son finales.**

| Problema | Dónde |
|---|---|
| `KYT_REJECTED` se convierte en `FAILED`, que en el código es final. Kira puede liberarlo a `COMPLETED` si retira el rechazo, y `Deposit.applyRemoteStatus` bloquearía esa transición | `DepositStatus.java:36` |
| `KYT_PENDING` se convierte en `PENDING`: se pierde que es una retención de cumplimiento | `DepositStatus.java:37` |
| Un valor desconocido cae a `COMPLETED`. Es el fallo más peligroso posible: mejor `PENDING` | `DepositStatus.java:38` |
| `COMPLETED` **no es final** para Kira: una retención o una devolución bancaria lo mueven | Documentar en el dominio |

Además, **un depósito retenido bloquea todos los pagos de esa cuenta** mientras dure
(`compliance/holds`). El portal debería avisarlo en vez de dejar un pago que no arranca sin
explicación.

### 2.2 Estados de pago

| Problema | Dónde |
|---|---|
| `CANCELLED` es un estado final propio ("detenido antes de enviarse"). El código lo convierte en `FAILED` y el comentario dice que no existe | `PayoutStatus.java:9` y `:32` |
| `COMPLETED` **no es final**: una devolución bancaria lo pasa a `FAILED`. El código lo trata como final y el reconciliador deja de consultarlo; sólo se entera por `payout.returned` | `PayoutStatus.java:23` |
| `IN_REVIEW` y `KYT_PENDING` sólo llegan por `payout.status_changed` | Ya soportado; confirmar que la URL está suscrita a ese evento |

### 2.3 Versión de la API

- Hoy conviven `2026-04-14` (por defecto, `application.yaml:36`) y `2026-06-01` (RFIs y
  cotizaciones). El checklist de producción pide **la misma versión en todas las peticiones**.
- En `2026-06-01` la cuenta virtual devuelve `pending`, `activating`, `active`, `failed`,
  `deactivated`. Eso elimina el apaño de `approved`, que colapsa `activating` y `active`
  (`VirtualAccountStatus.java`, `VirtualAccountEntity.java:69`).
- `lifecycles/states` añade `frozen`: congelada por un operador, **bloquea los pagos**. Hoy caería
  a `PENDING`.
- **Propuesta:** pasar todo el cliente a `2026-06-01` y recorrer Bruno de nuevo. Encaja con AUT-028.

### 2.4 Ingreso de webhooks

| Documentación | Código hoy | Qué hacer |
|---|---|---|
| **Kira reintenta** 4 veces (1, 5, 15, 60 min, ~80 min en total) ante `408`, `429`, `5xx`, sin respuesta o si pasan 30 s. Además se pueden reenviar a mano desde el dashboard | El controlador y Swagger dicen "entrega única, sin reintentos" (`KiraWebhookController.java:23` y `:30`); ESTADO §6 también | Corregir comentarios y la tabla de riesgos |
| "Verifica la firma, **escribe el evento en tu tabla**, responde `2xx`, procesa desde ahí" | Se encola en memoria con `@Async` antes de guardar (`KiraWebhookController.java:64`). Si la app cae tras el 200, el evento se pierde | Guardar la fila en `webhooks_log` antes de responder; si la base falla, responder `5xx` para que Kira reintente. El `WebhookReprojectionWorker` ya procesa lo pendiente |
| Firma inválida → responder error y no procesar | Responde `401` | Correcto: un `4xx` no se reintenta |
| Al rotar el secreto, durante ~1 min llegan entregas con la firma anterior | `KiraWebhookVerifier` acepta un único secreto | Admitir un secreto anterior opcional (`KIRA_WEBHOOK_SECRET_PREVIOUS`) |
| Cada evento sólo puede ir a **una** URL. Una URL sin filtro recibe todo **menos `rfi.*`**, que necesita una segunda URL que lo nombre | — | Configuración en el dashboard, no código. Anotado en AUT-049 |

---

## 3. 🟢 Lo que hay que implementar

### 3.1 Enlace de verificación de un beneficiario desde un RFI (ruta nueva)

`POST /v1/rfis/{rfi_id}/items/{item_id}/ubo-link` — sólo en `2026-06-01`.

Un item `ubo_link` llega con una de dos formas de `answer_spec`:

| Claves | Qué hacer |
|---|---|
| `url` | Enlace ya hecho: mostrarlo tal cual |
| `applicant_id` y `person_id`, sin `url` | Acuñar el enlace con la ruta nueva **en el momento del clic** |

- `201` → `{ url, expires_at }`. Caduca en ~1 h: **no cachearlo**.
- `404` RFI de otro cliente o retirado · `409` RFI cerrado · `422` el item ya trae `url`.
- **Propuesta BFF:** `POST /api/rfis/{id}/items/{itemId}/ubo-link`, sin persistir la URL (igual que
  el enlace de descarga de documentos).

### 3.2 RFI retirado (`withdrawn`)

Un RFI `withdrawn` responde **`404` en todas sus rutas** (lectura, respuesta, documentos). El
`RfiReconciliationWorker` fallaría con él en cada pasada y el RFI quedaría abierto en local para
siempre. Hay que tratar ese `404` como cierre.

### 3.3 Menores

- **Listado global de depósitos:** existe `GET /v1/virtual-accounts/deposits` (paginado por `page`).
  El listado global se retiró en ESTADO §3.1; sólo sirve si se filtra por empresa.
- **Elegibilidad:** `eligible_products[].unsupported_reason` sustituye a `missing_fields` cuando
  ningún dato puede habilitar el producto. Comprobar que el portal lo muestra primero.
- **Límites:** 20 peticiones/s con ráfaga de 50 por cuenta, compartidas entre todos los
  endpoints. Ante un `429`, esperar con retroceso exponencial. Los cinco workers deberían
  espaciar sus lotes.

---

## 4. Decisiones abiertas que la documentación resuelve

| ESTADO | Respuesta |
|---|---|
| §5.4 ¿`memo` obligatorio en WIRE? | **Sí** cuando el banco de la cuenta es `austin_capital_trust`: el pago WIRE exige `extra_info.memo`. Con `jp_morgan`, no |

---

## 5. Valores del sandbox para los caminos difíciles

Para añadir a la colección de Bruno. Sólo funcionan en sandbox.

**Alta de empresa** (por el `ein`):

| `ein` | Resultado |
|---|---|
| `111111111` | Rechazo definitivo |
| `111111113` | Rechazo por datos: nombra el campo que falta |
| `222221006` | Aceptada y rechazada después en revisión |
| `222221005` | Aceptada y aprobada después |
| Cualquier otro | Aprobada al instante |

**Pagos** (por los céntimos del importe):

| Céntimos | Resultado |
|---|---|
| `.02` | Retenido por cumplimiento y luego aprobado |
| `.03` | Retenido por cumplimiento y luego rechazado |
| `.04` | Aceptado y fallido segundos después |
| Otros | Aprobado sin retención |

**Depósito simulado:** importe `11` → devuelto en vez de acreditado. Sirve justo para reproducir W1.

---

## 6. Estimación e impacto en el cronograma

| Bloque | Días |
|---|---|
| W1–W6 y estados de depósito y pago, con pruebas sobre los payloads documentados | 1 |
| Guardar el evento antes del `2xx` y segundo secreto de firma | 0,5 |
| `ubo-link` y RFI retirado | 0,5 |
| **Total** | **1,5 – 2** |

Cabe en los viernes de corrección. Lo razonable es el **viernes 18 de septiembre**, junto con
AUT-022, porque W3–W5 afectan al onboarding que se recorre en sandbox el jueves 17.

Pendiente de crear en ClickUp cuando se renueve el cupo diario de la integración.
