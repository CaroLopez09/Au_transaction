# Estado del proyecto

**Fecha:** 11 de septiembre de 2026
**Build:** `Tests run: 254, Failures: 0, Errors: 0` — BUILD SUCCESS (verificado con `clean`)

> Guía de arquitectura: [`ARQUITECTURA.md`](ARQUITECTURA.md) · Contrato HTTP: [`API-GUIA-POSTMAN.md`](API-GUIA-POSTMAN.md) · Pruebas con Bruno: [`GUIA-BRUNO.md`](GUIA-BRUNO.md)

---

## 1. Dónde arrancar mañana

```bash
cd ~/Documentos/AuTransactional
./mvnw clean test                                              # debe dar 254 verdes
KIRA_WEBHOOK_SECRET=secreto-webhook-local ./mvnw spring-boot:run   # perfil dev, http://localhost:8080/swagger-ui.html
```

Login de prueba: `treasury.maker@juriscop.test` / `Dev12345!`
(la semilla crea `juriscop`, `bankvision`, `au-colombia` × 5 roles).

Para probar a mano: colección de Bruno en `docs/bruno/AuTransactional/`, guía en
[`GUIA-BRUNO.md`](GUIA-BRUNO.md). Ejecutada entera el 11-sep: **83/83 peticiones, 59/59 tests**
(Developer Mode, sin credenciales de Kira).

**Antes de tocar código de un flujo de Kira**, leer su sección en
`~/Descargas/kirafin-flujos-arquitectura.md` **y** `~/Descargas/brecha-kirafin.html`. La última
trampa apareció en la segunda: los RFIs sólo existen en la versión de API `2026-06-01`, y el
cliente enviaba `2026-04-14` en todas las peticiones.

---

## 2. Qué está terminado

### Reorganización arquitectónica *(aprobada 10-sep)*
Estructura `domain / application / infrastructure / interfaces` por contextos acotados,
esquema alineado con el DDL v2, roles B2B con tabla `roles` y FK.

### Flujo funcional completo — 8 casos de uso

| # | Caso de uso | Endpoints | Pruebas |
|---|---|---|---|
| 1 | `SubmitOnboarding` | `/api/onboarding` (GET, POST, PUT, POST /refresh) | 22 |
| 2 | `SyncUbos` | `/api/ubos` (GET, POST, POST /sync, POST /liveness-links) | 29 |
| 3 | `RegisterRecipient` | `/api/recipients` (GET, GET /{id}, POST, POST /{id}/archive) | 24 |
| 4 | `OpenVirtualAccount` | `/api/virtual-accounts` (+ refresh, balance, simulate-deposit) | 15 |
| 5 | `RecordDeposit` | `/api/deposits`, `/api/virtual-accounts/{id}/deposits` | 11 |
| 6 | `CreateQuote` | `/api/quotations` (GET, GET /{id}, POST) | 26 |
| 7 | `ExecutePayout` | `/api/payouts` (+ approve, reject, refresh) | 13 |
| 8 | **`AnswerRfi`** *(11-sep)* | `/api/rfis` (GET, GET /{id}, POST /sync, POST /{id}/refresh, PATCH /{id}/items) | 25 |

Más lo que ya existía: sesión JWT, verificación biométrica propia (reto de voz + liveness +
documento) e ingress de webhooks firmado con HMAC.

### RFIs *(11-sep)* — qué se hizo y qué se corrigió por el camino

- `AnswerRfiService` en `application/compliance`: bandeja, sincronización paginada por `offset`,
  refresco, respuesta por item y proyección del webhook `rfi.*`.
- Errores **por `item_id`** (`rfi_answer_rejected`): un item `document` nunca envía
  `answer_value`; el `422` de Kira no marca nada como guardado; un `409` asienta el cierre.
- El RFI enlaza con el pago que bloquea (`blocking.transfer_uuid` → `payoutId`).
- Aislamiento: se descarta cualquier RFI que Kira devuelva y no se pueda atribuir a la empresa.
- **Corregido — cabecera de versión:** `KiraApiClient` envía `X-Api-Version: 2026-06-01` en las
  rutas de RFI. Con `2026-04-14`, los tres métodos "ya existentes" no habrían funcionado nunca.
- **Corregido — estados:** `RfiStatus` tenía `RETURNED` (no existe en Kira) y no tenía
  `NOT_RESOLVED`: un RFI cerrado sin resolver se habría mostrado como pendiente.
- **Corregido — fechas:** `Rfi.rehydrate` ignoraba `createdAt` y cada guardado lo pisaba con la
  hora actual, desordenando la bandeja.

### Proyección de webhooks
Familias `user.*`, `virtual_account.*`, `virtual_account.deposit_*`, `payout.*` y ahora `rfi.*`.

---

## 3. Qué falta

### 3.1 Workers de reconciliación — *lo siguiente en la lista*
`infrastructure/reconciliation/` tiene sólo un `package-info.java`. **Los puertos están listos:**

| Qué reconciliar | Puerto disponible |
|---|---|
| Pagos en vuelo | `PayoutRepository.findInFlight(limit)` |
| Cotizaciones vencidas | `QuotationRepository.findActiveExpiredBefore(cutoff)` |
| Enlaces de liveness caducados (7 días) | `UboRepository.findPendingLivenessExpiredBefore(cutoff)` |
| Eventos almacenados sin proyectar | `webhooks_log.processed = false` + `processing_error` |
| RFIs abiertos | `RfiRepository.findOpenByTenant()` + `AnswerRfiService.sync()` |

`@EnableScheduling` ya está activo en `AsyncConfig`.

### 3.2 Defectos encontrados el 11-sep al probar todas las rutas *(anteriores a los RFIs, sin tocar)*

| # | Defecto | Dónde | Efecto |
|---|---|---|---|
| D1 | `rehydrate` ignora `createdAt` | `VirtualAccount.rehydrate()` | **`activationDelayed` nunca se activa**; `createdAt` de la cuenta es siempre "ahora". Mismo fallo que tenía `Rfi` |
| D2 | Crear un pago no valida cuenta ni destinatario | `ExecutePayoutService.create()` | Acepta ids inexistentes o de otra empresa (`201`), y toma `kiraUserId` del cuerpo de la petición. Falla tarde, al aprobar |
| D3 | Sin `KIRA_API_KEY`, cualquier llamada a Kira da `500` | `KiraCredentialManager` → `IllegalStateException` sin mapear | El operador ve "error inesperado" en vez de "integración no configurada" |
| D4 | `/actuator/health` responde `500` | `pom.xml` sin `spring-boot-starter-actuator` | Un balanceador o un *liveness probe* marcaría la app como caída |
| D5 | Una parte multipart ausente da `500` | `RestExceptionHandler` no mapea `MissingServletRequestPartException` | `validate` sin documento: `500` en vez de `400`/`422` |
| D6 | La semilla deja las empresas en `VERIFIED` sin `kiraUserId` | `DevDataSeeder` | En local la empresa parece verificada pero nada de tesorería funciona |

D1 y D2 son los que merecen arreglo antes de producción; D4 antes de desplegar detrás de un balanceador.

### 3.3 Menor
- **Subida de documentos a un RFI** (`POST /v1/rfis/{id}/items/{item_id}/documents`, multipart):
  no implementada. Hoy un item `document` se ve pero no se puede responder desde el portal.
- La vista de un **pago** no muestra aún que está detenido por un RFI (el enlace existe desde el
  RFI; falta el badge en `PayoutView`).
- `GET /v1/deposits` de Kira no se consulta.
- `events[]` de `GET /v1/payouts/{id}` no se guarda (línea de tiempo del pago).
- No hay endpoint de gestión de operadores.
- La **cotización detallada** también es exclusiva de `2026-06-01` (según `brecha-kirafin.html`);
  `CreateQuoteService` sigue enviando `2026-04-14`. Sin verificar qué campos se pierden.

---

## 4. Decisiones abiertas

### 4.1 ¿Se permite aprobar un pago sin cotización?
**Hoy: sí.** Prohibirlo es una línea en `ExecutePayoutService.loadQuotation()`.

### 4.2 ¿`recipients` con 29 columnas o con un JSON?
Cambio contenido al `RecipientMapper`.

### 4.3 ¿`memo` obligatorio en WIRE?
**Hoy: no.** Si falta, lo rechaza Kira.

### 4.4 *(decidida 11-sep)* Bloqueo de un RFI en columnas propias
`rfis.blocking_type` y `rfis.blocking_resource_id` (+ índice), en lugar de guardar el RFI entero
en `items_payload`. Permite consultar qué RFI detiene cada pago.

---

## 5. Riesgos conocidos

| Riesgo | Detalle | Mitigación actual |
|---|---|---|
| **Webhook perdido** | Entrega única, sin reintentos | Ninguna hasta los workers (§3.1). Hoy: `refresh` / `sync` manual |
| **`rfi.*` no suscrito** | Es la única familia que exige suscripción explícita en Kira | `POST /api/rfis/sync`. **Pedirla a Kira** |
| **Atribución de RFIs** | Se asume que Kira devuelve `user_id` en cada RFI (no verificado contra el sandbox) | Si no viene, sólo se importan los que bloquean un pago local; el resto se descarta con `WARN` |
| **Activación colgada** | En sandbox la VA puede no activarse nunca | `activationDelayed` — **roto por D1** |
| **Sandbox miente sobre el saldo** | Valor fijo del proveedor | Espejo local en `deposits` |
| **Compilación incremental de Maven** | `test-compile` ha dado verde con firmas rotas | `./mvnw clean test` |
| **`ddl-auto: validate` en prod** | Columnas nuevas deben existir en el DDL aplicado a mano | Regenerar el DDL antes de desplegar (§6). **Pendiente: `rfis.blocking_type`, `rfis.blocking_resource_id`, `idx_rfis_blocking`** |

---

## 6. Cómo regenerar el DDL de producción

El esquema de prod se aplica a mano y `ddl-auto: validate` falla si no coincide. Para obtener
el DDL exacto que espera Hibernate, crear un test temporal:

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
`target/schema-mysql.sql` y **borrar el test**. Comprobar que no aparezca ningún
`enum ('...')`: si aparece, falta un `@JdbcTypeCode(SqlTypes.VARCHAR)`.

Cambio de esquema del 11-sep, para aplicar a mano en prod/cert:

```sql
ALTER TABLE rfis
  ADD COLUMN blocking_type VARCHAR(30) NULL,
  ADD COLUMN blocking_resource_id VARCHAR(100) NULL,
  ADD INDEX idx_rfis_blocking (blocking_resource_id);
```

(Verificado contra lo que genera Hibernate en dev; confirmar con el volcado de arriba antes de aplicar.)

---

## 7. Cifras

| | |
|---|---|
| Clases de producción | 185 |
| Clases de prueba | 34 |
| Pruebas | 254 (de 78 al empezar) |
| Tablas | 13 |
| Endpoints REST | 45 operaciones sobre 38 rutas (14 controladores) |
| Casos de uso implementados | 8 de 8 |
| Colección Bruno | 83 peticiones en 11 carpetas |
