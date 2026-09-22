# Estado del proyecto

**Fecha:** 22 de septiembre de 2026
**Build:** `Tests run: 468, Failures: 0, Errors: 0` — BUILD SUCCESS (`./mvnw clean test`, 22-sep)
**Rama:** `develop` (se sube a GitHub por SSH) · `main`, `certificacion` y `produccion` detrás

> **Corte del 22-sep:** [`INFORME-2026-09-22.md`](INFORME-2026-09-22.md) — estado de los dos repos
> frente a los requisitos, con el trabajo del 17 al 22 (identidad biométrica, importación de
> empresas del Sandbox, ajustes por empresa, financiación cripto) que **todavía no está recogido en
> las secciones 3 y 4 de este documento**.

> Arquitectura: [`ARQUITECTURA.md`](ARQUITECTURA.md) · Contrato HTTP: [`API-GUIA.md`](API-GUIA.md) · Pruebas con Bruno: [`GUIA-BRUNO.md`](GUIA-BRUNO.md)

---

## 1. Dónde arrancar mañana

```bash
cd ~/Documentos/AuTransactional
git status                                   # rama develop
./mvnw clean test                            # 468 verdes (22-sep)
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"   # los secretos salen de .env (§3.7)
```

Colección de Bruno: `docs/bruno/AuTransactional/`. Última pasada completa contra el sandbox
(15-sep, noche, tras los P1): **104/104 peticiones, 67/67 tests**.

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

### 3.7 Credenciales de Kira y secretos *(14-sep)*

Ya hay credenciales del **sandbox** y **funcionan**: `POST /auth` devuelve `200`, y con la app
arrancada `GET /api/reference/countries` devolvió el catálogo real de Kira.

**Dónde viven.** Ningún `application*.yaml` tiene valor por defecto para un secreto: todos salen de
variables de entorno, así que no hacía falta sacar nada del repositorio. Lo que faltaba era una
forma local de suministrarlas sin exportarlas a mano:

| | |
|---|---|
| `.env` (raíz, `chmod 600`) | Valores reales de local. **En `.gitignore`**, nunca se commitea |
| `.env.example` | Plantilla con las claves vacías. Sí se commitea |
| `application-dev.yaml` | `spring.config.import: optional:file:./.env[.properties]` |
| `cert` / `prod` | **No leen `.env`**: las variables vienen del gestor de secretos del entorno |

El import es `optional:`, así que sin el fichero el arranque no falla. `RequiredSecretsValidator`
sigue abortando el arranque en cert y prod si falta alguna de las tres.

**Detalle del token:** el sandbox devuelve `expires_in: 86400` (24 h), no 3600. `KiraClientConfig`
no lee `expires_in`: cachea `token-ttl-seconds - token-refresh-margin-seconds` = **3300 s** fijos.
Es conservador y por tanto seguro (reautentica de más, nunca de menos), pero si algún día Kira
acorta el TTL por debajo de 3300 s el token caducaría antes que la caché; el 401 lo cubre
(`invalidate()` y reintento), así que no es urgente. Lo correcto sería honrar `expires_in`.

**Pendiente de rotar:** la clave y el secreto de Cognito actuales se compartieron por un canal no
seguro (chat). Antes de pasar a producción, pedir a Kira credenciales nuevas.

### 3.8 Documentos KYB: la vinculación ya puede completarse *(14-sep)*

Era el bloqueador nº 1 del MVP: **no habia forma de mandar un solo documento del expediente**.
El unico multipart del BFF era el de los RFI. Ahora hay dos rutas nuevas:

| Endpoint | Qué adjunta |
|---|---|
| `POST /api/onboarding/documents` | Registros de la empresa: acta de constitución, carta EIN, prueba de domicilio… |
| `POST /api/ubos/{id}/documents` | Identidad de UNA persona: anverso, reverso y selfie |

**Cómo lo pide Kira** (verificado en `/compliance/required-documents` y `/api-reference/users/*`):
no hay endpoint de subida. El archivo va dentro del `PUT /v1/users`, anidado en
`identifying_information[].documents[]`, como data URI en base64 o como URL https que Kira
descarga. Se eligió **base64** porque la URL exige un dominio preautorizado por Kira y un host
público, que hoy no hay. El precio es el tope de 10 MB del cuerpo: el BFF corta en **10
archivos y 7 MB** por petición, porque el base64 infla ~⅓.

**El base64 no se guarda en ningún sitio.** `KybDocuments.withoutFiles` limpia los archivos del
payload antes de persistirlo. Guardarlos habría significado reenviarlos en cada `PUT` posterior
hasta reventar el tope, y además no hace falta: reenviar el registro sin archivos **no los
borra** en Kira.

**Corrección a un supuesto del código:** `completeProfile` afirmaba que "Kira exige el objeto
COMPLETO en cada PUT: lo que no viaje se borra en silencio". La documentación dice lo contrario
— *"Only the fields you send are written — everything else is left alone"*. El comentario sigue
ahí y el comportamiento no se tocó (fuera de alcance), pero conviene revisarlo: si es falso,
reenviar el payload entero en cada PUT es trabajo y riesgo de más.

**Defecto de emparejamiento corregido de paso.** Kira empareja `associated_persons[]` **por
`email`**, y la tabla `ubos` no tenía esa columna: cada sincronización probablemente le creaba
personas duplicadas en vez de actualizarlas. `SyncUbosService` mandaba además
`person_reference_id`, que **no es un campo de entrada documentado** (sólo aparece en enlaces de
liveness y webhooks); se dejó como estaba, pero está sin justificar. Ahora `ubos.email` existe,
es opcional en el alta (no rompe el contrato de `POST /api/ubos`) y obligatorio para subir
documentos de esa persona.

**Detalle que costó un 500:** `types` declarado como `@RequestPart` revienta — una parte de
texto sin content-type llega como `application/octet-stream` y Spring no tiene convertidor. Va
como `@RequestParam`, que lee tanto la query como los campos de formulario. Cubierto por
`KybDocumentUploadTest`, que pasa por la capa web; las pruebas de servicio no lo veían.

**Verificado:** `./mvnw clean test` → **305 verdes** (283 antes). Colección de Bruno con la app
arrancada → **86/86 peticiones, 48/48 tests**.

### 3.9 Verificación contra el sandbox real *(14-sep)*

Recorrido completo con la empresa `juriscop` (`2bf7504c-…`), ya dada de alta en Kira:

| Comprobación | Resultado |
|---|---|
| `POST /api/onboarding/documents` con `board_minutes` | `200`; `identifying_information:file_board_minutes` **desaparece** de `missing_fields` |
| El archivo en Kira | `GET /v1/users/{id}` lo devuelve con `uploaded_at` y `content_type: application/pdf` |
| Fusión del array | El `business_formation` subido antes **sobrevive** al añadir `board_minutes`: 2 registros coexisten |
| Payload persistido | **312 bytes**, sin una sola cadena `base64` |
| PUT posterior sin archivos | `200`, y los dos archivos **siguen ahí** — la asunción del diseño es correcta |
| `POST /api/ubos/{id}/documents` con `front` + `selfie` | `200`; ambos anidados en el `passport` de esa persona |
| Emparejamiento por email | 5 UBO locales duplicados con el mismo email → **1 sola persona** en Kira |

### 3.10 Defecto encontrado en el sandbox: `completeProfile` responde 400 *(14-sep)*

**`PUT /api/onboarding` falla siempre contra Kira real.** El cuerpo del alta se guarda entero en
`onboarding_payload` y `completeProfile` lo fusiona en cada PUT, así que reenvía `type` y
`external_id`, que sólo valen en el POST. Bisección contra el sandbox:

| Cuerpo enviado | Kira |
|---|---|
| `{"business_description": "..."}` | **200** |
| `+ "type": "business"` | **400** `Invalid request data` |
| `+ "external_id": "juriscop"` | **400** `Invalid request data` |
| `+ "source_of_funds"` / `+ "business_legal_name"` | 200 |

No lo veía nadie porque las pruebas simulan Kira. **Arreglo:** excluir `type` y `external_id`
del cuerpo del PUT (pertenecen sólo al alta). No toca el contrato del BFF.

`attachDocuments` **no** tiene este problema: construye el cuerpo sólo con
`identifying_information`, no con el payload fusionado.

De paso: `business_type` es un enum cerrado — `sociedad_por_acciones_simplificada` da 400,
`llc` da 200. El portal debería ofrecer la lista, no un texto libre.

### 3.11 Alineación con Kira: vinculación *(15-sep, tarde)*

Todo verificado contra el sandbox con `juriscop`, primero con `curl` directo a Kira (bisección
campo a campo) y después de extremo a extremo por el BFF y con Bruno.

| Qué | Antes | Ahora |
|---|---|---|
| **B1** Campos del alta en el `PUT /v1/users` | `representative_date_of_birth`, `business_trade_name`, `has_material_intermediary_ownership`, `registered_address` → **400 Unrecognized key(s)** | `SubmitOnboardingService.forUpdate` traduce a `representative_birth_date`, `doing_business_as`, `address_*` y descarta lo que no existe. `PUT /api/onboarding` con el cuerpo del asistente → **200** |
| **AUT-015** `type` y `external_id` en el PUT | 400 | Se quitan en `forUpdate` |
| **B6** `pendingFields` sumaba la clave `general` | `general` es la **unión de todos los productos**: se pedían 23 requisitos de otros bancos y `readyForVirtualAccounts` no llegaba nunca | `MissingFields.forProduct` usa solo la lista del producto |
| **B2** Sincronizar beneficiarios | Heredaba el 400 | **200** por el BFF |
| **B4** Datos de persona | No existían | `birth_date`, `nationality`, `occupation`, `gender`, `phone_number`, `document_country` y dirección plana. Con ellos desaparecen `associated_persons:birth_date` y `:nationality` |
| Persona duplicada | 5 filas con el mismo correo (pruebas del 14-sep) sumaban 275 % y bloqueaban la sincronización | El alta rechaza un correo repetido; se fusionaron las 5 filas de dev en una |
| G-17 / G-21 | No se podía borrar ni corregir el nombre | `DELETE /api/ubos/{id}` (solo si Kira aún no la conoce, marca `synced_to_kira`) y la edición aplica nombre, apellido y cargo |
| **W1–W6** Webhooks | Devoluciones como acreditadas, `user.status_changed` ignorado, motivo de rechazo y liveness mal leídos | Corregido con pruebas sobre los payloads de `notification-examples`. `rejectionReason` expuesto en `OnboardingView` |

**Faltantes reales de `juriscop` para `usa-virtual-accounts` tras la tarde:** solo
`identifying_information:file_certificate_of_good_standing` y `file_portfolio_statement`.
Los campos de empresa que Kira pedía (`document_number`, `document_country`, `pep_status`,
`international_entity_type`, `additional_info:has_us_bank_account` y `:has_denied_bank_account`)
se aceptan en el PUT pero **Kira no los devuelve**: el payload guardado es su única copia.

**Front (`au-transactional-web`):** commit inicial `1af1dcc`. El asistente pide industria (93
valores NAICS), identificador tributario, país emisor, tipo de entidad (empresas no
estadounidenses), PEP y las dos preguntas de cuentas bancarias; quita la pregunta de sociedades
intermedias (el PUT no la acepta). El formulario de beneficiario pide los datos de identidad,
permite corregir el nombre y borrar, y el tipo de documento es un selector (G-20). Se muestran el
motivo del rechazo y el de producto no disponible (`unsupported_reason`).

**Cifras:** BFF 330 pruebas verdes · front 120/120, lint limpio · Bruno `00` + `02`: 21/21, 20/20.

### 3.12 Alineación con Kira: tesorería, RFIs y robustez *(15-sep, noche)*

| Qué | Cambio |
|---|---|
| Estados de depósito | `KYT_PENDING` y `KYT_REJECTED` propios (antes se plegaban en `PENDING`/`FAILED`; `KYT_REJECTED` puede liberarse a `COMPLETED`). Un estado desconocido ya no acredita. `DepositView.held` avisa de que la cuenta no puede pagar |
| Cuenta del ordenante (G-25) | `DepositView.senderAccount` sale enmascarada (`****1234`) |
| Pagos | `CANCELLED` es estado final propio (antes `FAILED`) |
| Cuentas | `FROZEN`: nunca `fundsReady`, aunque se hubiera visto `virtual_account.activated` |
| Idempotencia (G-07) | `POST /api/payouts` y `POST /api/recipients` aceptan `Idempotency-Key` (UUID). Repetirla devuelve el pago o destinatario ya creado. Un 202 "ya existía" de Kira ya no guarda una segunda fila local |
| RFIs | `POST /api/rfis/{id}/items/{itemId}/ubo-link` (G-24). `resolutionReason` en la vista. Un RFI retirado (404) pasa a `WITHDRAWN` al refrescar, sincronizar o recibir su webhook |
| Webhooks | El evento se **guarda antes de responder 2xx** (si la base falla, 5xx y Kira reintenta); JSON ilegible con firma válida → 400. `KIRA_WEBHOOK_SECRET_PREVIOUS` para rotar el secreto sin perder entregas |
| Reconciliación | Dos workers nuevos: `TenantReconciliationWorker` (30 min, empresas que aún no pueden operar) y `VirtualAccountReconciliationWorker` (1 h; `failed`, `deactivated` y `frozen` no tienen webhook) |

**Front:** estados nuevos con sus textos, aviso de depósitos retenidos, clave de idempotencia por
intención en pagos y destinatarios, botón para generar el enlace de verificación de un beneficiario
y motivo de cierre de los RFIs.

**Cifras:** BFF **351** pruebas verdes · front 120/120, lint limpio, **E2E 31/31** (1 omitida por
diseño) · Bruno colección completa **88/88 peticiones, 51/51 tests** contra el sandbox.

**Variable de entorno nueva (opcional):** `KIRA_WEBHOOK_SECRET_PREVIOUS`, solo durante la rotación.

### 3.13 Alcance ampliado de la arquitectura: MFA, actividad y consola *(15-sep, noche)*

Decisiones de Carolina (15-sep): segundo factor **TOTP con QR**, consola de **solo lectura para un
rol interno AU** y avisos **dentro de la app** (sin correo).

| Pieza | Qué hay |
|---|---|
| **MFA (TOTP)** | RFC 6238 sin dependencias (`Totp`, verificado con los vectores del RFC). Secreto cifrado con AES-256-GCM (`MfaSecretCipher`, clave `BFF_MFA_ENCRYPTION_KEY`). Con MFA, `POST /api/auth/login` devuelve un **reto** de 5 min (`mfaChallenge`) que no sirve como sesión; `POST /api/auth/mfa/verify` lo canjea. Tope de 5 códigos erróneos por reto y un código no vale dos veces. `bff.security.mfa-enforced` (true en cert/prod): quien no lo tiene lo configura al entrar (`mfaSetupRequired`). `/api/auth/mfa/setup`, `/enable`, `/disable`. Front: paso del código y alta con QR en el ingreso, y página «Seguridad» |
| **Avisos** | Tabla `notifications` alimentada al proyectar webhooks (vinculación aprobada/rechazada/en revisión, liveness, cuenta operativa/congelada, depósito recibido/devuelto/retenido, pago completado/fallido/retenido/cancelado, RFIs). No leídos por usuario con `users.notifications_seen_at`. `GET /api/notifications`, `/unread-count`, `POST /read`. Front: contador en la navegación (cada 60 s) y página «Avisos» |
| **Centro de eventos** | `webhooks_log.tenant_id` al proyectar. `GET /api/events` (Administración y Cumplimiento), **sin payload** |
| **Auditoría** | `GET /api/audit` con el actor por nombre. Página «Auditoría» |
| **Consola de operaciones** | Rol `PLATFORM_OPERATOR` (alcance `SYSTEM`, sin empresa: `TenantId.PLATFORM` hace que las rutas de empresa devuelvan vacío). `/api/platform/tenants`, `/tenants/{id}` (ficha 360), `/tenants/{id}/refresh`, `/review-queue`. Cada ficha consultada queda auditada. Front: «Operaciones» con bandeja de revisión, listado filtrable y ficha 360; la plataforma solo ve la consola y Seguridad. Dev: `operaciones@au.test` |

**Verificado:** MFA de extremo a extremo contra el BFF con códigos generados aparte en Python (12
comprobaciones: reto no sirve como sesión, código repetido rechazado, desactivar exige código).
Consola: plataforma ve todo, empresa recibe 403, plataforma en ruta de empresa ve vacío.

**Dependencia nueva en el front:** `qrcode@1.5.4` (+ `@types/qrcode`). Revisión de riesgo:
*aprobada con cautela* — sin fuente Endor disponible en este equipo; repetirla cuando haya
herramientas. El QR se genera solo en memoria y la URI `otpauth://` no se guarda nunca.

**Variables de entorno nuevas:** `BFF_MFA_ENCRYPTION_KEY` (obligatoria en cert/prod, el arranque
falla sin ella) y `BFF_MFA_ENFORCED` (por defecto `true` en cert/prod, `false` en dev).

**Cifras finales del día:** BFF **370** pruebas verdes · front **144** unitarias, lint limpio,
**E2E 33/33** (1 omitida por diseño) · Bruno **88/88 + 26/26** contra el sandbox.

### 3.14 P0 de la entrega *(15-sep, noche)*

Decisiones de Carolina tras [`REVISION-REQUISITOS-VS-CODIGO.md`](REVISION-REQUISITOS-VS-CODIGO.md):
**sin pagos cripto**, **límites por monto fijos en configuración** y empezar por los P0.

| P0 | Qué cambió |
|---|---|
| **G-09** permisos | `refresh`, `balance`, `deposits/sync` y `payouts/{id}/refresh` exigen `ADMIN` o `TREASURY_APPROVER` (gastan cuota de Kira). El enlace de descarga de un documento RFI es solo de `ADMIN` y queda auditado (`compliance.rfi_document_link_issued`). Front: capacidad `provider.refresh` |
| **D3** banco | `slovak_savings_bank` y `portage` **no existen** en la documentación: solo `jp_morgan` y `austin_capital_trust`, válidos en sandbox y producción. Por defecto `jp_morgan` (producto `usa-virtual-accounts`; `-act` es Austin). `KiraProperties` **no arranca** con otro banco. El alta declara `capabilities.requested_banks`. No se pudo abrir una cuenta de prueba: ningún user del sandbox está `VERIFIED` y Kira valida el estado antes que el banco |
| **V1** versión única | `2026-06-01` en todas las peticiones; `KiraProperties` rechaza otra. Entre las dos versiones solo cambian los estados de cuenta (y la cotización, que ya iba en `2026-06-01`). `activating` ya no cuenta como activa; `active` habilita fondos. Verificado en sandbox: refresco de empresa, sincronización de beneficiarios y de RFIs responden 200 |
| **D4** consentimiento | `GET/POST /api/onboarding/terms`: la empresa acepta la versión vigente (`BFF_TERMS_VERSION`, `BFF_TERMS_URL`), viaja como `tos_accepted_version` y queda auditada. Kira la acepta pero **no la devuelve** en el GET. Consentimiento biométrico obligatorio para pedir enlaces de prueba de vida y para subir una selfie (`tenant.biometric_consent_recorded`). Front: casillas en «Enviar», «Verificación» y el cajón de documentos |

**Pendiente de P0 que no depende del código:** la versión real de los términos (hoy no hay
ninguna configurada; en el sandbox de `juriscop` quedó `au-sandbox-2026-09` de la prueba), rotar
las credenciales de Kira y el despliegue a cert (§4.8).

**Cifras:** BFF **383** pruebas · front unitarias verdes, lint limpio, **E2E 33/33** (1 omitida)
· Bruno **103/103, 66/66** contra el sandbox.

### 3.15 P1 de la entrega *(15-sep, noche)*

| P1 | Qué cambió |
|---|---|
| **Tipo real de los archivos** | `FileSignature` compara la firma de los primeros bytes con el tipo declarado (PDF, PNG, JPEG, WebP, HEIC) en documentos KYB, archivos de RFI y soportes de pago. Un ejecutable renombrado a `.pdf` se rechaza antes de llamar a Kira |
| **D5** EIN | `PUT /api/onboarding` rechaza `ein` si `formation_country` no es `USA` (Kira: *do NOT send for non-US businesses*). Front: la carta de EIN solo se ofrece a empresas de EE. UU. |
| **Límites y segregación** | `bff.payouts.approval`: umbral general (`BFF_DUAL_APPROVAL_THRESHOLD`, **10.000 por defecto, sin confirmar con negocio**) y por empresa. Desde el umbral, dos aprobadores distintos; la primera firma no envía el pago. Quien registró un destinatario no aprueba pagos hacia él. DDL en §7 |
| **D9** recotizar | `POST /api/payouts/{id}/requote`: cotización nueva con el mismo importe cuando la anterior venció esperando aprobación; anula una primera firma. Front: «Recotizar» en el detalle del pago |
| **Observabilidad** | `X-Request-Id` en respuesta, logs y auditoría; métricas `kira.api.requests`, `kira.webhooks.received`, `kira.webhooks.projection.failures`; `/actuator/metrics` solo para `PLATFORM_OPERATOR`. Front: «Código para soporte» en los errores. **Sin dependencia nueva**: exportar a Prometheus u OTLP queda por decidir |
| **Documentación** | `ARQUITECTURA.md`, `API-GUIA.md` §5.4, cabecera y deudas de `DOCUMENTACION-CODIGO.md` (sus anexos siguen siendo del 11-sep), documentos del front |

**Cifras:** BFF **400** pruebas · front **155** unitarias, lint limpio, **E2E 33/33** · Bruno
**104/104, 67/67** contra el sandbox.

---

### 3.16 Operadores, `401` y reintento de apertura *(16-sep)*

| Cambio | Qué se hizo |
|---|---|
| **G-13** gestión de operadores | `/api/operators`: `GET`, `POST` y `DELETE` (todos exigen `ADMIN`). Hasta ahora los usuarios sólo entraban por la semilla de `dev` o por SQL. Tres reglas viven en `ManageOperatorsService`, no en el controlador: la empresa sale de la sesión y nunca del cuerpo; un `ADMIN` no puede crear otro `ADMIN` ni un `PLATFORM_OPERATOR`; nadie se desactiva a sí mismo. La baja deja `SUSPENDED`, no borra: la persona sigue siendo el actor de lo que ya firmó. Contrato en `API-GUIA.md` §5.5 |
| **F7** `401` sin credencial | `UnauthorizedEntryPoint`: sin cabecera `Authorization` la respuesta era un `403` con cuerpo vacío y el portal tenía que adivinar por el hueco si era falta de sesión o de permiso. Ahora es `401 unauthorized`, el mismo código que ya emitía `JwtTenantFilter` para un token inválido, y el `403` queda sólo para el rol sin permiso |
| **G-23** reintento de apertura | Una apertura de cuenta virtual que reservó la clave de idempotencia pero que Kira nunca confirmó se retoma en el siguiente intento (misma fila, misma clave) en vez de crear otra. Si lo que se perdió fue la respuesta y la cuenta sí existía, una clave nueva habría abierto una segunda |

**Cifras:** **424** pruebas (24 nuevas), 0 fallos · 72 operaciones REST.

**Pendiente de esta pieza:** la colección de Bruno no cubre todavía `/api/operators`.

### 3.17 Nombres en la vista de pago *(16-sep)*

| Cambio | Qué se hizo |
|---|---|
| **G-03** maker y aprobadores | `PayoutView` lleva `makerName`, `approverName` y `firstApproverName`. El portal no tiene directorio de operadores: mostraba «tú» u «otra persona», que no sirve para auditar quién firmó qué. Los nombres se resuelven en `ExecutePayoutService` contra `OperatorUserRepository`, con una libreta por petición (un `findById` por id, no uno por fila) |
| **G-26** destinatario del pago | `PayoutView.recipientName` sale de `recipients.findByIdAndTenant`, que también devuelve los archivados: `GET /api/recipients` solo lista los activos, así que un pago antiguo aparecía como «No disponible en el directorio» |

Un id que ya no existe deja el nombre ausente y conserva el id: el portal muestra el id y no repite
la consulta. La consola de plataforma (`Tenant360`) sigue proyectando los pagos sin nombres, que allí
no se muestran.

### 3.18 Capacidades del entorno *(16-sep)*

**G-18.** `GET /api/capabilities` devuelve `sandbox`, `providerConfigured`, `bank`,
`providerApiVersion` y `dualApprovalThreshold` (el de la empresa de la sesión; nulo para el
operador de plataforma). El portal decidía por su propia compilación si mostrar «Simular depósito»,
así que una compilación equivocada ofrecía un botón que siempre fallaba. No expone ningún secreto:
dice **si** hay credenciales de Kira, no cuáles. El front eliminó `environment.sandboxTools`.

**Cifras:** **430** pruebas (6 nuevas), 0 fallos · 73 operaciones REST · front: 178 unitarias,
lint limpio, E2E 34/34 (1 omitida).

### 3.19 Reducción de roles a 2 (ADMIN, TREASURY_APPROVER) e identidad biométrica *(sesión actual)*

**Simplificación de RBAC.** Se eliminaron `TREASURY_MAKER`, `COMPLIANCE_INTERNAL` y `READ_ONLY`
del enum `Role`: el modelo de empresa queda en **2 roles** (`ADMIN` hace todo — crea pagos,
gestiona cumplimiento y operadores —, `TREASURY_APPROVER` sólo aprueba/rechaza pagos,
maker-checker). `PLATFORM_OPERATOR` sigue igual (consola multiempresa de solo lectura). Se
actualizaron 11 controladores (`@PreAuthorize`), `ManageOperatorsService` (sólo se puede asignar
`TREASURY_APPROVER`), el seeder de `dev` y 16 archivos de test. Se limpiaron los usuarios y roles
obsoletos de la base de datos de desarrollo y se actualizó toda la colección de Bruno (login files,
tokens, casos negativos). **468/468 pruebas de backend en verde**; Bruno **96/97 peticiones, 61/62
tests** (la única falla restante es una condición preexistente del sandbox de Kira sin relación con
roles: `usa-virtual-accounts` sigue `eligible:false` para `juriscop` por documentos KYB
faltantes).

**Se eliminaron 3 endpoints sin uso** desde el front (depuración solicitada antes de esta sesión).

**Identidad biométrica.** Se corrigió `IdentityVerificationService` para consumir el servicio real
de BankVision (`legacy-digital-validation-face`): usa el `RestClient` inyectado (antes construía
uno inline), envía el multipart con `LinkedMultiValueMap` + `HttpEntity` (se cambió desde
`MultipartBodyBuilder` por un `NoClassDefFoundError: org/reactivestreams/Publisher` al testear con
`MockRestServiceServer`), agrega `clientId`, `documentType` y `countryCode`, y lee el número de
documento desde `customerDocumentData`. Nuevo `IdentityVerificationServiceTest` (3 pruebas:
`APPROVED`→`VERIFIED`, `REJECTED`→`PENDING_IDENTITY`, sin consentimiento biométrico→rechazo).
**Pendiente:** aún no se probó contra una instancia real de BankVision (queda a la espera de que
se comparta una URL/API key alcanzable); por ahora sólo está validado a nivel de contrato con
`MockRestServiceServer`.

**Pendiente de esta sesión:** actualizar el front (`au-transactional-web`: `capabilities.ts`,
formularios de creación de operadores, fixtures de Playwright) al modelo de 2 roles, y hacer commit
+ push de todo el trabajo.

---

## 4. Qué falta

### 4.8 *(15-sep)* Lista para desplegar en certificación

1. Aplicar el SQL de §7 (no hay Flyway y cert/prod validan el esquema).
2. Variables: `BFF_MFA_ENCRYPTION_KEY` (obligatoria), `BFF_TERMS_VERSION` y `BFF_TERMS_URL`
   (la versión real de AU), `BFF_DUAL_APPROVAL_THRESHOLD` (el límite que decida negocio),
   `KIRA_WEBHOOK_SECRET` y credenciales de Kira **rotadas**.
3. `KIRA_BANK` y `KIRA_API_VERSION`: no definirlas (valen `jp_morgan` y `2026-06-01`). Si el
   entorno arrastra `slovak_savings_bank`, `portage` o `2026-04-14`, el BFF no arranca.
4. Pedir a Kira la suscripción a `rfi.*` y fijar la cuenta a `2026-06-01`
   (`POST /v1/versioning/upgrade`; el pin solo avanza, y la cabecera ya se manda siempre).


### 4.2 Reconciliación: los cinco workers están hechos
La fila envenenada ya está resuelta con `webhooks_log.retry_count` y un tope de 5 intentos
(§3.7). Queda una sola decisión, y no es urgente: si conviene un sexto worker que llame a
`RecordDepositService.syncFromKira()` por cuenta.

**Ojo al desplegar:** cert y prod van con `ddl-auto: validate` y no hay Flyway, así que la columna
nueva se aplica a mano ANTES de subir la versión, o el arranque falla la validación:

```sql
ALTER TABLE webhooks_log ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
```

### 4.3 Documentos KYB: lo que queda de esa pieza
- ~~Sin probar contra el sandbox.~~ **Probado end-to-end el 14-sep** (§3.9).
- `user.document.download.failed` no se procesa. Sólo importa si algún día se manda por URL en
  vez de base64; con base64 no hay descarga que falle.
- El `warnings[]` de la respuesta de Kira no se propaga al portal.
- No hay forma de listar ni borrar un documento ya subido: Kira sólo ofrece eso para los RFI.

### 4.4 Menor
- `POST /v1/versioning/upgrade` no se expone: es una operación de cuenta, no de portal.

### 4.7 *(15-sep)* Revisión integral front + BFF + arquitectura + Kira

**Documento completo: [`REVISION-INTEGRAL-FRONT-BFF.md`](REVISION-INTEGRAL-FRONT-BFF.md).** Lo
esencial: la vinculación fallará contra Kira real por cuatro motivos (B1 campos del PUT, B2 UBOs
por el mismo PUT, B3 webhooks, B4 datos de persona), y el repo del front no tiene ningún commit.
Plan de 7 días priorizado en su §5.

### 4.6 *(pendiente 16-sep)* Completar el cronograma en ClickUp

**Estados por actualizar (15-sep, noche):** el cupo diario de ClickUp volvió a agotarse antes de
poder cambiarlos. [`cronograma/clickup-actualizacion-estados.csv`](cronograma/clickup-actualizacion-estados.csv)
trae las 20 tareas con su estado nuevo y el comentario de evidencia: 11 a completada y 9 en curso
(las de Bruno de tesorería esperan un user `VERIFIED` en el sandbox). En ClickUp la lista usa
estados en español (`pendiente`…): mapear `Complete` y `In Progress` a los de la lista al aplicar.
Faltan además tareas para el trabajo nuevo del 15-sep (P0/P1 de `REVISION-REQUISITOS-VS-CODIGO.md`).

El 15-sep se agotó el cupo diario de la integración (100 llamadas) con 43 de 51 tareas creadas;
el calendario llega sólo al 9-oct. **Al retomar:** crear las 8 de
[`cronograma/clickup-pendientes.csv`](cronograma/clickup-pendientes.csv) (AUT-044 a AUT-051), las
tareas de [`REVISION-DOCS-KIRA.md`](REVISION-DOCS-KIRA.md) §6 en el viernes 18-sep, y las
dependencias entre tareas. Carpeta "AuTransactional — Integración Kira" en *Espacio del equipo [ES]*.

### 4.5 *(15-sep)* Desalineaciones con la documentación nueva de Kira

> **Documento completo, con payloads, líneas de código y estimación:
> [`REVISION-DOCS-KIRA.md`](REVISION-DOCS-KIRA.md).** Abajo, el resumen.

Cruce de `docs.kirafin.ai` (MCP `kira-docs`: event-catalog, notification-examples, states,
holds, idempotency, pagination, go-live-checklist, rfis/values) contra el código. **Sin corregir
todavía.** Todas pasan desapercibidas en las pruebas porque simulan los payloads con la forma
antigua.

**Webhooks: proyecciones que hoy se equivocan en silencio**

| # | Evento | Payload documentado | Qué hace el código | Efecto |
|---|---|---|---|---|
| W1 | `virtual_account.deposit_funds_refunded` | sin `status`, con `return_details` | `DepositStatus.fromEventName` espera `deposit_returned` (no existe); cae a `fromWire(null)` → `COMPLETED` | **Un depósito devuelto se registra como acreditado** |
| W2 | `deposit_scheduled`, `deposit_in_review` | sin `status` | mismo camino → `COMPLETED` | Dinero en revisión mostrado como disponible |
| W3 | `user.status_changed` | `new_status` / `previous_status` | exige `status`; si falta, no mueve nada | **El evento que Kira pide suscribir se ignora** |
| W4 | `user.verification.failed` | `reasons[]` | lee `reason` / `rejection_reason` / `message` | Se guarda el texto genérico; el motivo real se pierde para siempre |
| W5 | `user.liveness_completed` | `result: "approved"` | lee `status` → `PENDING` | La prueba de vida nunca se marca completada por webhook |
| W6 | `deposit_funds_received` | ordenante y riel en `source.sender_name` / `source.payment_rail` | los busca planos | Ordenante y riel vacíos |
| W7 | `rfi.not_resolved` | `resolution_reason` en el evento | no se lee (ya en §4.4) | — |

**Estados**
- Depósito: `KYT_PENDING` y `KYT_REJECTED` se pliegan en `PENDING` / `FAILED`. `FAILED` es
  terminal en el código, pero `KYT_REJECTED` puede volver a `COMPLETED` → esa transición se
  bloquearía. `COMPLETED` tampoco es terminal para Kira (retención o clawback).
- `DepositStatus.fromWire` cae a `COMPLETED` ante un valor desconocido: el fallo peligroso.
- Pago: `CANCELLED` es un estado terminal propio; el código lo convierte en `FAILED`.
  `COMPLETED` no es terminal (una devolución bancaria lo pasa a `FAILED`).
- Cuenta virtual en `2026-06-01`: `pending/activating/active/failed/deactivated` (+ `frozen`, que
  bloquea pagos). Pasar todo el cliente a `2026-06-01` elimina el apaño de `approved`; el
  checklist de salida a producción pide **la misma versión en todas las peticiones** y hoy se
  mezclan `2026-04-14` y `2026-06-01`.

**Ingress de webhooks**
- Kira **sí reintenta** (1, 5, 15 y 60 min ante `408`, `429`, `5xx` o sin respuesta) y permite
  reenviar desde el dashboard. Los comentarios de `KiraWebhookController` y el riesgo "entrega
  única" de §6 están desactualizados.
- Con reintentos, conviene persistir el evento **antes** de responder `2xx` (hoy se encola en
  memoria con `@Async`: si la app cae tras el 200, el evento se pierde) y responder `5xx` si la
  base falla, para que Kira reintente.
- Rotar el secreto de firma deja ~1 min de entregas con la firma anterior: `KiraWebhookVerifier`
  sólo admite un secreto.

**Rutas nuevas / no expuestas**
- `POST /v1/rfis/{rfi}/items/{item}/ubo-link`: items `ubo_link` con `applicant_id` + `person_id`
  (sin `url`) necesitan acuñar el enlace al hacer clic; caduca en ~1 h. El BFF no lo expone.
- `GET /v1/virtual-accounts/deposits`: depósitos de todas las cuentas (paginado por `page`). No
  se usa; como `listDeposits()` global se retiró en §3.1, sólo tendría sentido filtrado.
- Un RFI `withdrawn` responde `404` en todas sus rutas: el reconciliador de RFIs debería cerrarlo
  en local en vez de fallar en cada pasada.

**Resuelve decisiones abiertas**
- §5.4 `memo` en WIRE: es obligatorio (`extra_info.memo`) cuando el banco de la cuenta es
  `austin_capital_trust`.
- Sandbox: valores forzados para probar los caminos difíciles — `ein` `111111111`,
  `111111113`, `222221006`, `222221005`; pagos con céntimos `.02`, `.03`, `.04`; depósito de `11`.

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
| **Cuentas y pagos sin recorrer en real** | Vinculación, beneficiarios y RFIs ya se probaron contra el sandbox (§3.9–§3.14); abrir cuenta, depositar y pagar no, porque ningún user del sandbox está `VERIFIED` | Completar `juriscop` (faltan 2 documentos) o crear un user con los valores forzados del sandbox |
| **Credenciales compartidas por chat** | La `api_key` y el secreto de Cognito del sandbox viajaron por un canal no seguro | Rotarlas con Kira antes de producción |
| **Webhook perdido** | Kira reintenta 4 veces (~80 min) y después lo da por perdido | Evento guardado antes del 2xx, reenvío desde el dashboard y 7 workers de reconciliación |
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

-- Reintentos de la reproyeccion de webhooks (11-sep)
ALTER TABLE webhooks_log ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

-- Documentos KYB: Kira empareja associated_persons[] por email (14-sep)
ALTER TABLE ubos ADD COLUMN email VARCHAR(255) NULL;

-- Datos de identidad por persona y marca de envio a Kira (15-sep)
ALTER TABLE ubos
  ADD COLUMN birth_date DATE NULL,
  ADD COLUMN nationality VARCHAR(3) NULL,
  ADD COLUMN occupation VARCHAR(100) NULL,
  ADD COLUMN gender VARCHAR(10) NULL,
  ADD COLUMN phone_number VARCHAR(20) NULL,
  ADD COLUMN document_country VARCHAR(3) NULL,
  ADD COLUMN address_street VARCHAR(255) NULL,
  ADD COLUMN address_city VARCHAR(100) NULL,
  ADD COLUMN address_state VARCHAR(100) NULL,
  ADD COLUMN address_zip_code VARCHAR(20) NULL,
  ADD COLUMN address_country VARCHAR(3) NULL,
  ADD COLUMN synced_to_kira BOOLEAN NOT NULL DEFAULT FALSE;

-- Quien ya estaba en una empresa dada de alta con correo se envio a Kira: no se puede borrar.
UPDATE ubos u JOIN tenants t ON t.id = u.tenant_id
   SET u.synced_to_kira = TRUE
 WHERE t.kira_user_id IS NOT NULL AND u.email IS NOT NULL;

-- Antes de desplegar: revisar beneficiarios con correo repetido en la misma empresa
-- (ahora el alta lo rechaza; los existentes hay que fusionarlos a mano).
SELECT tenant_id, LOWER(email) AS email, COUNT(*) FROM ubos
 WHERE email IS NOT NULL GROUP BY tenant_id, LOWER(email) HAVING COUNT(*) > 1;

-- RFIs: motivo de cierre sin resolver (expired, rejected, withdrawn) (15-sep)
ALTER TABLE rfis ADD COLUMN resolution_reason VARCHAR(20) NULL;

-- MFA TOTP: secreto cifrado (mas largo que el texto plano) y activacion (15-sep)
ALTER TABLE users
  MODIFY COLUMN mfa_secret VARCHAR(255) NULL,
  ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN notifications_seen_at DATETIME(6) NULL;
-- Si alguna fila tenia mfa_secret en claro del esquema viejo, no es descifrable: se limpia.
UPDATE users SET mfa_secret = NULL WHERE mfa_enabled = FALSE;

-- Operadores de la plataforma: sin empresa (15-sep)
ALTER TABLE users MODIFY COLUMN tenant_id VARCHAR(36) NULL;

-- Avisos por organizacion y atribucion de eventos (15-sep)
CREATE TABLE notifications (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  kind VARCHAR(60) NOT NULL,
  severity VARCHAR(20) NOT NULL,
  title VARCHAR(160) NOT NULL,
  message VARCHAR(500) NULL,
  resource_type VARCHAR(40) NULL,
  resource_id VARCHAR(100) NULL,
  created_at DATETIME(6) NOT NULL,
  INDEX idx_notifications_tenant (tenant_id, created_at)
);
ALTER TABLE webhooks_log ADD COLUMN tenant_id VARCHAR(36) NULL;

-- Doble firma por limite y segregacion destinatario/aprobador (15-sep, P1)
ALTER TABLE payouts ADD COLUMN first_approver_user_id VARCHAR(36) NULL;
ALTER TABLE recipients ADD COLUMN created_by_user_id VARCHAR(36) NULL;
```

Los estados nuevos (`KYT_PENDING`, `KYT_REJECTED`, `CANCELLED`, `FROZEN`, `WITHDRAWN`) caben en las
columnas `status VARCHAR(50)` existentes: no necesitan DDL.

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

Medidas el 22-sep (ver [`INFORME-2026-09-22.md`](INFORME-2026-09-22.md) §1).

| | |
|---|---|
| Pruebas del BFF | 468 en 59 clases, 0 fallos |
| Endpoints REST | 79 operaciones en 17 controladores |
| Colección Bruno | 104 peticiones en 13 carpetas — **sin cubrir lo nuevo** (operadores, capacidades, import-sandbox, identidad, borrador, MFA setup/enable/disable, recotizar, ajustes por empresa, incidencias) |
| Pruebas del front | 205 unitarias en 13 ficheros, lint limpio |
