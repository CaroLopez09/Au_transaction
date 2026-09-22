# AuTransactional — Guía de arquitectura

BFF (Backend for Frontend) de la integración con **KiraFin** para el portal B2B multiempresa.
Spring Boot 4.1.1 · Java 21 · MySQL 8 · arquitectura hexagonal con DDD.

> Este documento explica **cómo está construido** el sistema y **por qué**.
> Para el contrato HTTP endpoint por endpoint, ver [`API-GUIA.md`](API-GUIA.md).
> Para el estado del trabajo y qué sigue, ver [`ESTADO.md`](ESTADO.md).

---

## 1. Qué es esto y por qué existe

Kira es una API REST de movimiento de dinero con modelo *white-label*: nosotros somos el
cliente autenticado y creamos **sub-clientes** (`users`) a los que Kira verifica (KYB), les
abre **cuentas virtuales** en bancos de EE. UU., recibe **depósitos** y desde ese saldo
ejecuta **payouts** hacia **recipients** por rail ACH / WIRE / WALLET.

El BFF existe porque un SPA no puede hacer ese trabajo:

| Motivo | Detalle |
|---|---|
| **Custodia de credenciales** | La API key y el token de Kira nunca salen del servidor. |
| **Webhooks** | Kira entrega **una sola vez, sin reintentos**. Un navegador no puede recibirlos, y hay datos que sólo llegan por ahí. |
| **Idempotencia** | La clave se persiste *antes* de la primera llamada. En `localStorage` se pierde en incógnito o en otro dispositivo. |
| **Maker-checker** | Kira no ofrece aprobación en dos pasos a los integradores. Es control interno nuestro. |
| **Espejo local** | La API tiene campos que acepta y no devuelve, y motivos de error que pasan una sola vez. |

**Principio de diseño (11-sep): el BFF es una capa delgada sobre Kira.** Todo el negocio vive en
Kira y el front lo consume a través del BFF. El BFF sólo añade lo que Kira no puede dar: sesión y
roles por empresa, traducción de ids del portal a ids de Kira, maker-checker, validación temprana de
reglas de Kira y el espejo de lo que la API no devuelve. Nada que no sirva a eso tiene sitio aquí;
por eso se eliminaron la verificación biométrica propia (Kira hace la liveness de los UBO) y
Thymeleaf (el BFF sólo devuelve JSON).

---

## 2. Estructura de paquetes

`com.example.autransactional` — **169 clases** en main, 40 en test.

```
domain/                     Lógica pura. Sin anotaciones de framework.
  tenant/     (14)          Tenant, OperatorUser, Role, Ubo, UboRoster, MissingFields, EligibleProduct
  account/     (8)          VirtualAccount, VirtualAccountReadiness, Deposit + estados
  treasury/   (18)          Payout, Quotation, Recipient (oneOf sellado), FeeBreakdown, rieles
  compliance/  (5)          Rfi, AuditLog + puertos
  shared/      (7)          TenantId, Money, IdempotencyKey, Rail, PostalAddress, DomainException

application/                Casos de uso. Orquestan dominio + puertos.
  tenant/      (7)          SubmitOnboardingService, SyncUbosService
  account/     (6)          OpenVirtualAccountService, RecordDepositService (+ sync desde Kira)
  treasury/   (14)          CreateQuoteService, ExecutePayoutService (+ preview, eventos, historial),
                            RegisterRecipientService (+ vistas de Kira)
  compliance/  (5)          AnswerRfiService (bandeja, respuestas, documentos)
  reference/   (2)          ReferenceCatalogService (países, cache 24 h)
  shared/      (1)          IdempotencyKeyStore (clave de idempotencia en transaccion propia)
  webhook/     (2)          ProcessWebhookUseCase, KiraWebhookEnvelope
  auth/        (1)          LoginUseCase

infrastructure/             Adaptadores técnicos.
  persistence/ (36)         Entidades JPA, repositorios Spring Data y adaptadores de puertos
  kira/        (12)         Cliente HTTP (JSON y multipart), token cache, verificador HMAC, importes
  security/     (6)         JWT propio, filtro de tenant, RBAC
  bootstrap/    (3)         Semilla de desarrollo, validador de secretos
  config/       (2)         Async (pool de webhooks), OpenAPI
  audit/        (1)         AuditTrail
  reconciliation/ (5)       Workers: pagos, cotizaciones, liveness, RFIs y eventos no proyectados

interfaces/                 Entrada HTTP.
  rest/        (11)         10 controladores + manejador de errores
  webhook/      (1)         Ingress firmado con HMAC
```

**Regla de dependencias:** `interfaces → application → domain`, e `infrastructure` implementa
los puertos que declara `domain`. El dominio no importa nada de Spring ni de JPA.

Una excepción consciente: los casos de uso importan `KiraApiClient` y `AuthenticatedOperator`
desde `infrastructure`. Es deuda conocida — lo limpio sería un puerto `KiraGateway` en
`domain` — pero el cliente devuelve `JsonNode` y esconderlo tras un puerto obligaría a
duplicar 25 DTOs sin ganancia real hoy.

---

## 3. Los siete agregados

### `Tenant` (domain/tenant)
La empresa cliente. Contraparte local del `user` de Kira. Guarda el **estado del bucle de
onboarding**, que no es una línea recta: alta mínima → `PUT` completo → `GET`, repetido
hasta que no falte nada.

- `missingFields` es la **fuente de verdad del formulario**: la pantalla no tiene campos
  estáticos, se dibuja desde aquí.
- `isReadyFor(producto)` exige **dos condiciones**: `VERIFIED` **y** producto elegible.
- `rejectionReason` sólo se puede capturar del webhook: `GET /v1/users/{id}` nunca lo expone.

### `Ubo` + `UboRoster` (domain/tenant)
Beneficiarios finales. `UboRoster` valida lo que Kira comprueba **sobre el grupo**: al menos
una persona con `hasOwnership` y ≥ 5 %, suma ≤ 100. Es la causa más común de un KYB atascado,
y se comprueba antes de llamar.

`hasOwnership` es un booleano explícito: **el cargo no identifica al beneficiario**.

### `VirtualAccount` + `Deposit` (domain/account)
`fundsReady` ≠ `status`. La API colapsa *activating* y *active* en `approved`, así que la
cuenta se declara operativa sólo con un número de cuenta real (ni `null` ni el centinela
`PENDING-ACT-ACCOUNT`) o tras el webhook `virtual_account.activated`.

Los depósitos **no suman al saldo local**: marcan el saldo como caduco (`balanceStale`) y el
portal vuelve a preguntar. La autoridad es Kira.

### `Recipient` (domain/treasury)
**Un destinatario = un riel.** Modelado como jerarquía sellada `Ach | Wire | Wallet`, que es
lo que hace imposible un ACH con `swift_code` o una wallet con número de cuenta.

Kira no permite actualizar ni borrar: corregir uno es crear un reemplazo y archivar el
anterior enlazado a él.

### `Quotation` (domain/treasury)
Precio en firme, **TTL de 900 segundos exactos**. Se cotiza con `inverse: true`, así que
`originAmount` (lo que recibe el destinatario) y `totalDebitAmount` (lo que sale de la
cuenta) son cifras distintas y ambas se guardan.

`feesSnapshot` conserva `fees[]` y `totals` tal como llegaron: es la única prueba de que el
precio mostrado al tesorero es el que se cobró.

### `Payout` (domain/treasury)
Concentra el **maker-checker** que Kira no ofrece. `approve()` rechaza que el creador o quien
registró el destinatario sea el aprobador y que la cotización esté vencida; con dos firmas
requeridas, la primera deja el pago pendiente y la segunda tiene que ser de otra persona.
`replaceQuotation()` renueva una cotización vencida y anula la primera firma. `grossAmountToSend()` resuelve la regla que más
dinero puede costar: Kira **descuenta** las comisiones del monto enviado.

### `Rfi` (domain/compliance)
Solicitud de información de Kira. **Nunca se crea aquí**: se sincroniza (`POST /api/rfis/sync`,
webhook `rfi.*`) y se responde por item. Estados de Kira y ninguno más: `PENDING` (te toca),
`ANSWERED` (Kira revisa), `RESOLVED`, `NOT_RESOLVED`. Un item devuelto vuelve a `PENDING`, no
crea un estado propio.

- Los items se guardan tal cual (JSON): el formulario se dibuja desde `answer_spec`.
- Un RFI cerrado **no se reabre** con un evento tardío.
- `blocking` enlaza el RFI con lo que detiene (hoy, `transfer_uuid` → pago local).
- Kira trata los RFIs como globales del integrador: el aislamiento por empresa lo impone
  `AnswerRfiService` atribuyendo cada uno por `user_id`.

---

## 4. El flujo completo

```
Login  →  Onboarding KYB  →  UBOs + liveness  →  Cuenta virtual  →  Depósito
                                                                        ↓
                              Pago ejecutado  ←  Aprobación  ←  Cotización  ←  Destinatario
                                                (maker-checker)   (TTL 15 min)
```

| Paso | Endpoint BFF | Kira | Precondición |
|---|---|---|---|
| 1 | `POST /api/onboarding` | `POST /v1/users` | — |
| 2 | `PUT /api/onboarding` (bucle) | `PUT /v1/users/{id}` | alta hecha |
| 3 | `POST /api/ubos` + `/sync` | `PUT /v1/users/{id}` | ≥1 beneficiario ≥5 % |
| 4 | `POST /api/ubos/liveness-links` | `POST /v1/users/{id}/liveness-link` | verificación disparada |
| 5 | `POST /api/virtual-accounts` | `POST /v1/virtual-accounts` | `VERIFIED` **y** producto elegible |
| 6 | *(webhook)* | `virtual_account.activated` | — |
| 7 | `POST /api/recipients` | `POST /v1/recipients` | KYB aprobado |
| 8 | `POST /api/quotations` | `POST /v1/quotations` | VA *funds-ready* + destinatario activo |
| 9 | `POST /api/payouts` | — | cotización vigente |
| 10 | `POST /api/payouts/{id}/approve` | `POST /v1/virtual-accounts/{id}/payout` | otro operador + saldo + TTL |

---

## 5. Esquema de base de datos

12 tablas. Base `autransactional`, `ddl-auto: update` en dev, **`validate` en prod**.

| Tabla | Columnas | Papel |
|---|---|---|
| `tenants` | 15 | Empresas cliente + estado del bucle de onboarding |
| `roles` | 6 | Catálogo RBAC (FK desde `users`) |
| `users` | 21 | Operadores de cada empresa + estado de identidad |
| `ubos` | 21 | Beneficiarios finales + liveness |
| `virtual_accounts` | 19 | Cuentas en bancos de EE. UU. |
| `deposits` | 19 | Fondeos entrantes |
| `recipients` | 29 | Directorio de destinos (espejo completo) |
| `quotations` | 28 | Precios en firme + snapshot de comisiones |
| `payouts` | 29 | Pagos + control maker-checker |
| `rfis` | 11 | Requerimientos de compliance + lo que bloquean |
| `webhooks_log` | 12 | Bitácora inmutable de eventos |
| `audit_logs` | 11 | Quién hizo qué |

**Convenciones aplicadas**

- Enums a `VARCHAR(50)`, nunca `ENUM` nativo de MySQL: añadir un estado a un `ENUM` exige
  `ALTER TABLE` sobre la tabla entera. Se fuerza con `@JdbcTypeCode(SqlTypes.VARCHAR)`
  (la propiedad global `hibernate.type.preferred_enum_jdbc_type` **no** funciona en
  Hibernate 7).
- Importes `DECIMAL(18,4)`. Nunca `float`.
- Columnas JSON reales (`eligible_products`, `missing_fields`, `fees_snapshot`, …).
- Ids `VARCHAR(36)`, UUID.

### 5.1 Desviaciones del DDL v2 y por qué

Cada una tapa un hueco documentado de la API. **Ninguna es cosmética.**

| Tabla | Columnas añadidas | Motivo |
|---|---|---|
| `payouts` | `approval_state`, `quotation_expires_at`, `rejection_reason`, `error_code` | El maker-checker no existe en Kira |
| `payouts` | `reference_number`, `payment_method` | IMAD/ACH trace/UETR: el comprobante que reclama el cliente final |
| `tenants` | `missing_fields` | Fuente de verdad del formulario dinámico |
| `tenants` | `verification_triggered` | El `GET` no lo devuelve; sin él, pedir liveness da `422` |
| `tenants` | `onboarding_payload` | El `GET` no devuelve el cuestionario (G6) y el `PUT` debe ir completo (G8) |
| `tenants` | `onboarding_idempotency_key` | Se persiste antes de la primera llamada (D4) |
| `tenants` | `rejection_reason` | Única fuente: webhook `user.verification.failed` |
| `users` | `identity_status`, `kira_person_reference_id`, metadatos de documento, consentimiento y fechas | Verificación individual por operador sin guardar archivos, enlace de liveness ni número completo del documento |
| `ubos` | `has_ownership`, `has_control`, `is_signer`, `politically_exposed`, `country_of_birth` | El cargo no identifica al beneficiario; omitirlos bloquea el KYB en silencio |
| `virtual_accounts` | `mode`, `bank`, `description`, `activated_event_seen`, `balance_refreshed_at`, `opening_idempotency_key` | Modo inmutable, banco por entorno, señal de fondos-listos |
| `recipients` | 21 columnas (espejo completo) | Kira no permite actualizar: corregir obliga a reconstruir el alta entera |
| `quotations` | `rail`, `destination_currency`, `balance_sufficient`, `rate_source`, `fees_snapshot` | Auditoría del precio mostrado |
| `deposits` | `microdeposit`, `updated_at` | Un microdepósito no es un ingreso |
| `webhooks_log` | `resource_id`, `normalized_status`, `processing_error`, `retry_count` | Reconciliar sin reparsear el payload; el contador corta la fila envenenada |
| `rfis` | `blocking_type`, `blocking_resource_id` (+ índice `idx_rfis_blocking`) | Enlazar el RFI con el pago que detiene; la UI debe marcarlo como "detenido" |

Una desviación en sentido contrario: **`payouts.quotation_id` es nullable**, no `NOT NULL`.
El operador arma el borrador con calma; obligarlo a cotizar antes vencería el TTL de 15
minutos mientras el tesorero revisa.

---

## 6. Roles y seguridad

Los roles tienen **dos nombres**: el técnico de la tabla `roles` (el del negocio) y la
constante Java que usan `@PreAuthorize` y el JWT.

| `roles.name` | Constante | Puede |
|---|---|---|
| `admin` | `ADMIN` | todo: crea y aprueba pagos, gestiona cumplimiento y operadores |
| `tesoreria_approver` | `TREASURY_APPROVER` | aprobar / rechazar pagos (maker-checker) |
| `platform_operator` | `PLATFORM_OPERATOR` | consola multiempresa de solo lectura y métricas; sin empresa (`TenantId.PLATFORM`) |

- **JWT propio del BFF** (HMAC256, 8 h). El token de Kira nunca sale del servidor.
- **Verificación en dos pasos (TOTP, RFC 6238)**: con MFA, el login devuelve un reto de 5 min
  que no vale como sesión y `/api/auth/mfa/verify` lo canjea. El secreto se guarda cifrado con
  AES-256-GCM (`BFF_MFA_ENCRYPTION_KEY`). Obligatoria en cert y prod (`BFF_MFA_ENFORCED`).
- **Segregación de funciones en pagos**: quien crea no aprueba, quien registró el destinatario
  no aprueba pagos hacia él, y desde el umbral de la empresa (`bff.payouts.approval`) hacen falta
  dos aprobadores distintos.
- **Consentimientos auditados**: aceptación de términos (`tos_accepted_version`) y consentimiento
  biométrico antes de una selfie o un enlace de prueba de vida.
- **Archivos**: el tipo se comprueba por la firma de los primeros bytes (`FileSignature`), no por
  el que declara el navegador.
- `JwtTenantFilter` fija el tenant en un `ThreadLocal` y **siempre lo limpia en un
  `finally`**: sin eso, el siguiente request reutiliza el hilo del pool y hereda el tenant
  equivocado.
- Toda consulta filtra por tenant **dentro del query**, no después: un id manipulado no
  cruza organizaciones.
- Endpoints públicos: `/api/auth/login`, los pasos de MFA del login, `/api/webhooks/**` (HMAC),
  `/actuator/health` y Swagger. El resto de `/actuator/**` es solo para `PLATFORM_OPERATOR`.
- **Correlación**: `RequestIdFilter` pone `X-Request-Id` en cada respuesta, en cada línea de log
  y en la auditoría. Métricas de la integración: `kira.api.requests`, `kira.webhooks.received`,
  `kira.webhooks.projection.failures` (API-GUIA §5.4).
- **Ids del portal hacia fuera, ids de Kira hacia dentro.** El front sólo ve ids del BFF; los
  servicios los resuelven dentro de la empresa del operador y los traducen a `kiraAccountId`,
  `kiraRecipientId` o `kiraUserId` justo antes de llamar. Un id de otra empresa no existe.
- Las lecturas que pasan directamente a Kira (`/api/payouts/kira`, `/api/recipients/kira`, sync de
  RFIs y depósitos) **descartan cualquier fila de otro `user_id`**: Kira trata los recursos como
  globales del integrador y su filtro no es la frontera.
- En `cert` y `prod` el arranque **falla** si falta un secreto, en vez de descubrirlo con la
  primera llamada.

---

## 7. Webhooks

Kira aborta a los 30 s y **reintenta 4 veces** (1, 5, 15 y 60 min) ante `408`, `429`, `5xx`
o falta de respuesta; un `4xx` no se reintenta. Por eso el controlador verifica la firma HMAC
sobre los **bytes crudos**, **guarda el evento antes de responder** `2xx` (si la base falla sale
un `5xx` y Kira reintenta) y proyecta después, en otro hilo. Durante la rotación del secreto se
acepta también el anterior (`KIRA_WEBHOOK_SECRET_PREVIOUS`). Los eventos que no se proyectan los
retoma `WebhookReprojectionWorker`, y los workers de reconciliación releen empresas, cuentas,
pagos y RFIs por si un evento se perdió del todo.

La idempotencia se apoya en `UNIQUE(event_id)` en `webhooks_log`.

**Cuatro eventos son la única fuente de su dato.** Si se pierden tras los reintentos, no se recuperan:

| Evento | Qué proyecta | Por qué no hay alternativa |
|---|---|---|
| `user.verification.failed` | motivo del rechazo | el `GET` **nunca** lo expone |
| `user.liveness_completed` | resultado del UBO | la landing de redirección no lo confirma |
| `virtual_account.activated` | fondos-listos | `status` por sí solo no lo dice |
| `payout.status_changed` | `KYT_PENDING` / `IN_REVIEW` | el `GET` puede no mostrarlos |

**`rfi.*`** no es fuente única (el RFI se puede releer), pero **exige suscripción explícita** en
Kira: sin ella no llega nunca. Su proyección siempre relee el RFI con `GET /v1/rfis/{id}`,
porque el evento no trae items, plazo ni bloqueo; si esa lectura falla, el evento queda con
`processing_error`. La red de seguridad es `POST /api/rfis/sync`.

Detalle fino: en la familia `user.*` el estado va en **MAYÚSCULAS**; en los eventos planos de
payout y VA, en minúsculas. Todo se compara sin distinguir mayúsculas.

---

## 8. Las trampas de Kira, y dónde viven codificadas

Esta es la tabla más útil del documento. Cada fila es un fallo silencioso evitado.

| Trampa | Dónde está resuelta |
|---|---|
| Comisiones **descontadas** del monto, no sumadas | `Payout.grossAmountToSend()` |
| Importes en unidades menores + precisión (`amount / 10^precision`) | `KiraAmounts` |
| `client_markup`: enteros+bps en quotations, strings decimales en payout | `KiraAmounts.markupFor{Quotation,Payout}` |
| El saldo en `/balance` es decimal, no unidades menores | `OpenVirtualAccountService` |
| `approved` ≠ fondos disponibles | `VirtualAccountReadiness` |
| El riel sale del destinatario, no del quote | `QuotationRail.assertMatches()` |
| `USDC` no existe en `tron` | `WalletToken.assertSupportedOn()` |
| `bank_address`: texto en ACH, objeto en WIRE | `RecipientAccount` |
| `bank_address.state`/`postal_code` vuelven vacíos | espejo local en `recipients` |
| El banco depende del entorno (`400 "Invalid bank"`) | `KiraProperties.bank` |
| El `PUT` parcial borra campos (G8) | `SubmitOnboardingService.merge()` |
| El cuestionario no vuelve en el `GET` (G6) | `tenants.onboarding_payload` |
| `has_ownership` es explícito; el cargo no cuenta | `Ubo.describeRole()` |
| País ISO-2 (destinatario) vs ISO-3 (empresa) | `PostalAddress.assertIso2Country()` |
| `202` en recipients = ya existía, es éxito | `KiraResponse.alreadyExisted()` |
| `409` en VA = reutilizar la existente | `OpenVirtualAccountService.adoptExisting()` |
| `400` en `/balance` = "calculando", no error | `OpenVirtualAccountService.refreshBalance()` |
| La cotización se consume al enviar, no al preparar | `ExecutePayoutService.submitToKira()` |
| Estados con casing mixto | `StatusNormalizer` |
| Depósitos con `snake_case` y `camelCase` mezclados | `KiraDepositEvent.from()` |
| Un evento sin `status` no debe degradar el estado | `ProcessWebhookUseCase.applyUserEvent()` |
| El liveness se cuelga en sandbox | `VirtualAccount.isActivationDelayed()` |
| Los RFIs sólo existen en la versión `2026-06-01` | `KiraApiClient.RFI_API_VERSION` (cabecera por petición) |
| `not_resolved` es un cierre, no un RFI pendiente | `RfiStatus.fromWire()` |
| Un item `document` nunca lleva `answer_value` | `AnswerRfiService.validate()` |
| El `PATCH` de items es all-or-nothing: `422` por `item_id` | `RfiAnswerRejectedException` |
| `GET /v1/rfis` pagina con `limit`+`offset`, no con `page` | `AnswerRfiService.sync()` |
| El desglose de la cotización (`fees[]`, `totals`) sólo existe en `2026-06-01` | `KiraApiClient.QUOTATION_API_VERSION` |
| El rollback del caso de uso borraba la clave de idempotencia ya reservada | `IdempotencyKeyStore` (`REQUIRES_NEW`) |
| `answer_value` es texto, número o booleano según `answer_type` | `RfiCommands.ItemAnswer`, `AnswerRfiService.isScalar()` |
| Un RFI puede bloquear un pago **o un depósito** | `AnswerRfiService.applyDetail()` / `ownerOf()` |
| Documentos de RFI: parte `files`, máx. 20 × 30 MB, MIME del `answer_spec` | `AnswerRfiService.validateFiles()` |
| No se puede borrar el último archivo de un item respondido (`422`) | `AnswerRfiService.callItemWrite()` |
| Kira sólo conoce sus ids: enviar el id del portal es un `404` seguro | `ExecutePayoutService.submitToKira()`, `CreateQuoteService.buildBody()` |
| `GET /v1/payouts` rechaza con `400` cualquier parámetro que no conoce | `ExecutePayoutService.KIRA_PAYOUT_STATUSES` |
| Depósitos por REST: `sender{}`, `fees.total_fees`, `payment_rail` (no la forma del webhook) | `KiraDepositEvent.fromResource()` |
| `KYT_PENDING` / `KYT_REJECTED` en depósitos | `DepositStatus.fromWire()` |
| Países en `/v1/countries` (`/countries` da `403`) | `KiraApiClient.listCountries()` |
| La fecha de alta se perdía al leer de la base (activación demorada nunca saltaba) | `rehydrate()` de los 8 agregados |

---

## 9. Pruebas

**283 pruebas, todas verdes.** Sin mocks del propio dominio: las de dominio son puras y las
de aplicación usan Mockito sólo para `KiraApiClient` y los repositorios.

Nombres en español y en indicativo, describiendo la **regla de negocio**, no el método:
`elCreadorNoPuedeAprobarSuPropioPago`, `usdcNoExisteEnTron`,
`seEnviaElBrutoParaQueElDestinatarioRecibaLoPrometido`.

```bash
./mvnw test                          # las 283
./mvnw test -Dtest=PayoutTest        # una clase
./mvnw clean test                    # ante cambios de firma (el incremental miente)
```

Las pruebas de integración levantan H2 en memoria (`MODE=MySQL`), sin MySQL arrancado.

---

## 10. Perfiles y configuración

| Perfil | Base de datos | Kira | Secretos | Swagger |
|---|---|---|---|---|
| `dev` | MySQL local, `ddl-auto: update` | sandbox | valores por defecto | sí |
| `cert` | según entorno | sandbox | **exigidos al arrancar** | sí |
| `prod` | `ddl-auto: validate` | producción | **exigidos al arrancar** | no |

Variables por entorno (el BFF no arranca con un banco distinto de `jp_morgan` ni con otra versión que `2026-06-01`):

| Variable | dev / cert | prod |
|---|---|---|
| `KIRA_BANK` | `jp_morgan` | `jp_morgan` |
| `KIRA_SANDBOX` | `true` | `false` |

`bff.dev.seed=true` (sólo `dev`) crea 3 empresas × 5 roles, idempotente. Un operador por rol
para poder probar de verdad el maker-checker: hacen falta dos personas distintas.

---

## 11. Convenciones de código

- **El dominio no sabe de frameworks.** Ni JPA, ni Spring, ni Jackson.
- **Rehidratación explícita**: cada agregado tiene un `rehydrate(...)` estático; los mappers
  viven en `infrastructure/persistence`.
- **Los comentarios explican el porqué**, no el qué. Si un comentario describe lo que hace la
  línea siguiente, sobra.
- **Sin valores mágicos sueltos**: `Quotation.TTL_SECONDS`, `Ubo.BENEFICIAL_OWNER_THRESHOLD`,
  `VirtualAccountReadiness.ACT_PENDING_SENTINEL`.
- **Tolerancia a lo desconocido**: un estado nuevo de Kira nunca rompe una máquina de
  estados; se normaliza a `UNKNOWN` / no terminal y se registra.
- **Nada retrocede desde un estado terminal.** Los eventos llegan sin orden garantizado.

---

## 12. Referencias

| Documento | Contenido |
|---|---|
| [`API-GUIA.md`](API-GUIA.md) | Contrato HTTP completo y ejemplos |
| [`GUIA-BRUNO.md`](GUIA-BRUNO.md) | Pruebas paso a paso con la colección de Bruno (`docs/bruno/`) |
| [`ESTADO.md`](ESTADO.md) | Estado del trabajo y qué sigue |
| `~/Descargas/kirafin-flujos-arquitectura.md` | **Contrato de Kira.** Fuente de verdad de los flujos F0–F7 |
| `~/Descargas/db-schema-design-v2.md` | DDL de referencia del esquema B2B |
| https://docs.kirafin.ai | Documentación oficial de Kira |
