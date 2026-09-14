# Estado del proyecto

**Fecha:** 11 de septiembre de 2026 (tarde)
**Build:** `Tests run: 283, Failures: 0, Errors: 0` — BUILD SUCCESS (verificado con `clean`)
**Rama:** `depuracion-bff` (sin commitear) · `master` = copia de seguridad previa (`dc6af92`)

> Arquitectura: [`ARQUITECTURA.md`](ARQUITECTURA.md) · Contrato HTTP: [`API-GUIA.md`](API-GUIA.md) · Pruebas con Bruno: [`GUIA-BRUNO.md`](GUIA-BRUNO.md)

---

## 1. Dónde arrancar mañana

```bash
cd ~/Documentos/AuTransactional
git status                                   # rama depuracion-bff, cambios sin commitear
./mvnw clean test                            # 283 verdes
KIRA_WEBHOOK_SECRET=secreto-webhook-local ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"
```

Colección de Bruno: `docs/bruno/AuTransactional/`. Ejecutada entera el 11-sep: **84/84 peticiones,
46/46 tests** (sin credenciales de Kira).

**Antes de tocar un flujo de Kira, verificar su contrato en la documentación oficial**
(`https://docs.kirafin.ai/api-reference-2026-06-01/…`), no sólo en
`~/Descargas/kirafin-flujos-arquitectura.md`. El 11-sep el documento de diseño tenía mal la ruta de
países y le faltaban tipos de respuesta de RFI y el bloqueo por depósito.

---

## 2. Principio de diseño

**El BFF es una capa delgada sobre Kira.** El negocio vive en Kira y el front lo consume a través
del BFF. El BFF sólo añade sesión y roles por empresa, traducción de ids, maker-checker,
validación temprana de reglas de Kira y el espejo de lo que la API no devuelve.

---

## 3. Qué se hizo el 11-sep

### 3.1 Depuración
| Eliminado | Por qué |
|---|---|
| Verificación biométrica propia (reto de voz, liveness AWS, OCR, rostro): 8 endpoints `/api/v1/*`, 29 clases, 33 pruebas, tabla `verification_sessions` | Kira hace la liveness de los UBO con su enlace; duplicarla no servía al BFF |
| Colección Postman (`docs/postman`) | Sustituida por Bruno |
| Thymeleaf (3 dependencias) | El BFF sólo devuelve JSON; no había ninguna plantilla |
| `KiraApiClient.listUsers()`, `listDeposits()` global | Listarían recursos de todos los clientes del integrador |

### 3.2 Fallo grave corregido: ids del portal enviados a Kira
**Cotizar y pagar enviaban a Kira los ids locales del BFF** (`virtual_account_id` y `recipient_id`)
en vez de `kiraAccountId` / `kiraRecipientId`. Contra el sandbox real habrían fallado siempre. Las
pruebas no lo veían porque simulan Kira. Ahora ambos servicios traducen los ids justo antes de enviar.

### 3.3 Defectos D1–D6 corregidos
| # | Defecto | Arreglo |
|---|---|---|
| D1 | `rehydrate` perdía `createdAt` en **7 agregados** (Tenant, Ubo, VirtualAccount, Deposit, Recipient, Payout, Quotation); `activationDelayed` nunca saltaba | fecha conservada + prueba |
| D2 | Crear un pago aceptaba cuenta/destinatario inexistentes o de otra empresa y `kiraUserId` del cuerpo | se valida todo dentro de la empresa; `kiraUserId` sale de la empresa y ya no está en el contrato |
| D3 | Sin credenciales de Kira: `500` (y `NullPointerException` si faltaba `client_id`/`password`) | `503 kira_not_configured` para las tres credenciales |
| D4 | `/actuator/health` daba `500` | dependencia de Actuator añadida: `{"status":"UP"}` |
| D5 | Parte multipart o parámetro ausente, JSON ilegible y ruta inexistente daban `500` | `400 validation_error` / `404 not_found` / `413 file_too_large` |
| D6 | La semilla dejaba las empresas en `VERIFIED` sin existir en Kira | nacen en `CREATED` (y se corrigieron las 3 filas de la base de dev) |

### 3.4 Rutas de Kira ahora expuestas al front (contratos verificados en docs.kirafin.ai)
| Endpoint del BFF | Kira |
|---|---|
| `POST /api/rfis/{id}/items/{itemId}/documents` (multipart `files`) | `POST /v1/rfis/{id}/items/{item}/documents` |
| `DELETE /api/rfis/{id}/items/{itemId}/documents/{documentId}` | `DELETE …/documents/{doc}` |
| `GET /api/rfis/{id}/items/{itemId}/documents/{documentId}/link` | `GET …/documents/{doc}` |
| `POST /api/payouts/preview` | `POST /v1/virtual-accounts/{id}/payout/preview` |
| `GET /api/payouts/{id}/events` | `events[]` de `GET /v1/payouts/{id}` |
| `GET /api/payouts/kira` | `GET /v1/payouts` (filtrado por empresa) |
| `GET /api/recipients/kira`, `GET /api/recipients/{id}/kira` | `GET /v1/recipients`, `GET /v1/recipients/{id}` |
| `POST /api/virtual-accounts/{id}/deposits/sync` | `GET /v1/virtual-accounts/{id}/deposits` |
| `GET /api/reference/countries` (cache 24 h) | `GET /v1/countries` |

Además `PayoutView.blockedByRfiId` marca el pago detenido por un RFI abierto.

### 3.5 Reconciliación, idempotencia y versión de la cotización *(11-sep, noche)*

**Workers de reconciliación** en `infrastructure/reconciliation/`, todos con intervalo configurable e
interruptor `bff.reconciliation.enabled` (apagado en las pruebas):

| Worker | Cada | Qué hace |
|---|---|---|
| `PayoutReconciliationWorker` | 10 min | `findInFlight` → `GET /v1/payouts/{id}` → estado y comprobante |
| `QuotationReconciliationWorker` | 5 min | Cotizaciones `ACTIVE` vencidas → `EXPIRED` (sin llamar a Kira) |
| `LivenessReconciliationWorker` | 1 h | Enlaces de liveness vencidos → `EXPIRED` (sin llamar a Kira) |
| `RfiReconciliationWorker` | 15 min | `AnswerRfiService.syncForTenant` por empresa registrada |
| `WebhookReprojectionWorker` | 30 min | Filas de `webhooks_log` con `processed = false` → se vuelven a proyectar; **5 intentos como tope** |

Un fallo en un elemento no detiene el lote y, sin credenciales de Kira, el lote se corta con un aviso
en vez de repetir el error por fila. **Efecto lateral medido en dev:** como el lote va de más antiguo
a más nuevo y corta en la primera fila que necesita Kira, un `rfi.*` pendiente sin credenciales deja
bloqueada toda la cola de reproyección. Con credenciales (cert y prod) no se da; si se quisiera
evitar del todo, habría que saltar esa fila en lugar de cortar el lote.

**Verificación en vivo (11-sep):** con `scripts/verificar-reproyeccion-dev.sh` el worker reproyectó
la fila de prueba (`processed = 1`, `processing_error` limpiado) y, de paso, recuperó un
`virtual_account.deposit_funds_received` real que llevaba atascado desde primera hora de la tarde. Verificado con la app arrancada: una cotización y un enlace de
liveness vencidos pasaron a `EXPIRED` en la base, y los de pagos y RFIs registraron el aviso.

**Defecto F1 corregido:** `IdempotencyKeyStore` (`application/shared`) consolida la clave en una
transacción propia (`REQUIRES_NEW`) antes de llamar a Kira, así que el rollback del caso de uso ya no
la borra. Cubierto por `IdempotencyKeyPersistenceTest`, que es de integración con H2 porque con mocks
el defecto no se ve.

**Cotización con el desglose:** `createQuotation` viaja con `X-Api-Version: 2026-06-01`. La respuesta
itemizada (`fees[]`, `totals`) —de donde salen las comisiones reales que hereda el pago— sólo existe
desde esa versión; con `2026-04-14` Kira devuelve una forma simple.

### 3.6 RFIs alineados con el contrato oficial
- `answerValue` acepta **texto, número o booleano** (antes sólo texto).
- `blocking` también puede ser un **depósito** (`virtual_account_deposit_uuid`) → `depositId`.
- Se reconocen los 9 `answer_type`.

---

## 4. Qué falta

### 4.1 Commit de la rama
Los cambios están en `depuracion-bff` sin commitear. Revisar con `git diff master` y commitear
cuando se dé el visto bueno.

### 4.2 Reconciliación: los cinco workers están hechos
La fila envenenada ya está resuelta con `webhooks_log.retry_count` y un tope de 5 intentos
(§3.7). Queda una sola decisión, y no es urgente: si conviene un sexto worker que llame a
`RecordDepositService.syncFromKira()` por cuenta.

**Ojo al desplegar:** cert y prod van con `ddl-auto: validate` y no hay Flyway, así que la columna
nueva se aplica a mano ANTES de subir la versión, o el arranque falla la validación:

```sql
ALTER TABLE webhooks_log ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
```

### 4.3 Menor
- `POST /v1/versioning/upgrade` no se expone: es una operación de cuenta, no de portal.
- `resolution_reason` de un RFI cerrado (`expired` / `rejected`) no se guarda.
- La cotización detallada también es exclusiva de `2026-06-01`; `CreateQuoteService` sigue en
  `2026-04-14`. Sin verificar qué campos se pierden.
- No hay endpoint de gestión de operadores.

---

## 5. Decisiones abiertas

### 5.1 *(nueva)* Tablas huérfanas en la base de dev
`audit_log`, `operator_user` (18 filas), `payout`, `tenant` (3 filas) y `webhook_event` no las usa
ninguna entidad: son restos del esquema anterior a la reorganización. ¿Se borran?

### 5.2 ¿Se permite aprobar un pago sin cotización?
**Hoy: sí.** Prohibirlo es una línea en `ExecutePayoutService.loadQuotation()`.

### 5.3 ¿`recipients` con 29 columnas o con un JSON?
Cambio contenido al `RecipientMapper`.

### 5.4 ¿`memo` obligatorio en WIRE?
**Hoy: no.** Si falta, lo rechaza Kira.

---

## 6. Riesgos conocidos

| Riesgo | Detalle | Mitigación actual |
|---|---|---|
| **Sin credenciales de Kira** | Nada del flujo real se ha probado contra el sandbox | Contratos verificados en la documentación oficial; pedir `api_key` a Kira |
| **Webhook perdido** | Entrega única, sin reintentos | `refresh` / `sync` manuales hasta los workers |
| **`rfi.*` no suscrito** | Exige suscripción explícita en Kira | `POST /api/rfis/sync`; pedirla a Kira |
| **Atribución por `user_id`** | Documentado en list/get RFI, pagos y destinatarios; no probado en sandbox | Se descarta y registra lo que no se puede atribuir |
| **Forma de `account_details` y `fees`** | Kira no documenta sus campos | Se enmascara `account_number`/`address` si viene; `fees` va tal cual al front |
| **App antigua corriendo** | IntelliJ tenía arrancado el código anterior contra la misma base (sin `verification_sessions`) | Reiniciarla |
| **`ddl-auto: validate` en prod** | Cambios de esquema aplicados a mano | §7 |

---

## 7. Cambios de esquema para prod/cert (aplicar a mano)

```sql
-- RFIs: lo que bloquean (10-sep/11-sep)
ALTER TABLE rfis
  ADD COLUMN blocking_type VARCHAR(30) NULL,
  ADD COLUMN blocking_resource_id VARCHAR(100) NULL,
  ADD INDEX idx_rfis_blocking (blocking_resource_id);

-- Verificacion biometrica propia eliminada (11-sep)
DROP TABLE IF EXISTS verification_sessions;
```

Para obtener el DDL exacto que espera Hibernate, crear un test temporal:

```java
@SpringBootTest
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:ddl;DB_CLOSE_DELAY=-1;MODE=MySQL",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
    "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/schema-mysql.sql",
    "spring.jpa.properties.jakarta.persistence.schema-generation.create-source=metadata",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect"
})
class TempDdlDumpTest { @Test void dump() {} }
```

`./mvnw test -Dtest=TempDdlDumpTest -Dsurefire.failIfNoSpecifiedTests=false`, revisar
`target/schema-mysql.sql` y **borrar el test**.

---

## 8. Cifras

| | |
|---|---|
| Clases de producción | 169 (185 antes de depurar) |
| Clases de prueba | 40 |
| Pruebas | 283 |
| Tablas | 12 |
| Endpoints REST | 47 operaciones sobre 40 rutas |
| Colección Bruno | 84 peticiones en 11 carpetas |
