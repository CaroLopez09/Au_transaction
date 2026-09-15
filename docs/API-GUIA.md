# AuTransactional BFF — Guía de APIs

Contrato de todos los endpoints del BFF y de la API de KiraFin que consume por detrás, con el
orden en que hay que llamarlos para que el flujo funcione.

**El BFF es una capa delgada sobre Kira.** Todo el negocio (KYB, cuentas, destinatarios,
cotizaciones, pagos, RFIs) vive en Kira. El BFF custodia las credenciales, aplica la sesión y los
roles de cada empresa, traduce ids del portal a ids de Kira y guarda sólo lo que la API de Kira no
devuelve o no ofrece: maker-checker, cuestionario KYB, espejo de depósitos y bitácora de webhooks.

- **Base local:** `http://localhost:8080`
- **Swagger UI:** `http://localhost:8080/swagger-ui.html` — OpenAPI en `/v3/api-docs`
- **Colección Bruno:** `docs/bruno/AuTransactional/` — guía paso a paso en [`GUIA-BRUNO.md`](GUIA-BRUNO.md)

---

## 1. Preparar el entorno

### 1.1 Requisitos

| Requisito | Detalle |
|---|---|
| Java | 21 |
| MySQL | Base `autransactional` en `localhost:3306` (usuario `root`; exporta `DB_PASSWORD` antes de arrancar) |
| Perfil | `dev` (por defecto) |

### 1.2 Variables de entorno

Sin las tres credenciales de Kira, todo lo que llama a Kira responde `503 kira_not_configured`.
Las entrega Kira por canal seguro (support@kirafin.ai); no hay panel para generarlas.

**En local** viven en `.env` en la raiz del repositorio, que **esta en `.gitignore` y nunca se
commitea**. Se crea copiando la plantilla:

```bash
cp .env.example .env     # y rellenar los valores
chmod 600 .env
```

```properties
KIRA_API_KEY=...         # cabecera x-api-key de toda peticion, incluida /auth
KIRA_CLIENT_ID=...       # UUID del cliente integrador
KIRA_PASSWORD=...        # el secreto de Cognito
KIRA_WEBHOOK_SECRET=...  # el mismo que webhookSecret en Bruno
DB_PASSWORD=...
BFF_JWT_SECRET=...       # minimo 32 bytes
```

Lo lee Spring por `spring.config.import: optional:file:./.env[.properties]`, declarado **solo en
`application-dev.yaml`**: en `cert` y `prod` el fichero no se lee nunca y las variables las inyecta
el gestor de secretos del entorno (§1.2.1). Al ser `optional:`, si el fichero no existe el arranque
no falla y las variables se toman del entorno como antes.

#### 1.2.1 Despliegue: donde van los secretos

| Entorno | Origen de los secretos |
|---|---|
| `dev` (local) | `.env` en la raiz, permisos `600`, fuera de git |
| `cert` / `prod` | Gestor de secretos del entorno inyectado como variables de entorno del proceso |

Reglas que no se negocian al desplegar:

- **Nunca** en `application*.yaml`, en la imagen de contenedor, en un `ENV` del `Dockerfile` ni en
  el historial de git: la imagen y el repositorio se copian, el gestor de secretos no.
- **Nunca** en el navegador. Es el requisito central del BFF: el front habla con el BFF, y solo el
  BFF conoce las credenciales de Kira.
- En cert y prod, `RequiredSecretsValidator` aborta el arranque si falta cualquiera de las tres.
- Los secretos no se escriben en logs: `KiraCredentialManager` registra que pide un token, no el
  token ni la clave.

El banco de las cuentas virtuales depende del entorno y **no es intercambiable**: usar el
de producción contra el sandbox devuelve `400 "Invalid bank"`.

| Variable | `dev` / `cert` | `prod` |
|---|---|---|
| `KIRA_BANK` | `slovak_savings_bank` *(por defecto)* | `portage` |
| `KIRA_SANDBOX` | `true` *(habilita simular depósitos)* | `false` *(fijo)* |

### 1.3 Arrancar

```bash
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"
```

Al arrancar, `DevDataSeeder` crea (de forma idempotente) el catálogo `roles`, 3 organizaciones
en estado `CREATED` —todavía no existen en Kira— y un operador por rol en cada una (3 × 5):

- Organizaciones (`tenantId`): `juriscop`, `bankvision`, `au-colombia`
- Correo de cada operador: `<rol en minúsculas con puntos>@<organizacion>.test`
- Contraseña de todos: `Dev12345!` (`BFF_DEV_SEED_PASSWORD`)

El rol tiene dos nombres: el de la tabla `roles` (que es el del negocio) y la constante
que usan el JWT y `@PreAuthorize`.

| Rol (`roles.name`) | Constante | Correo de ejemplo | Puede |
|---|---|---|---|
| `admin` | `ADMIN` | `admin@juriscop.test` | crear y aprobar |
| `tesoreria_maker` | `TREASURY_MAKER` | `treasury.maker@juriscop.test` | crear pagos |
| `tesoreria_approver` | `TREASURY_APPROVER` | `treasury.approver@juriscop.test` | aprobar / rechazar |
| `compliance_internal` | `COMPLIANCE_INTERNAL` | `compliance.internal@juriscop.test` | Ficha 360, UBOs, liveness y RFIs |
| `read_only` | `READ_ONLY` | `read.only@juriscop.test` | sólo lectura |

### 1.4 Abrir la colección de Bruno

**Collection → Open Collection** → carpeta `docs/bruno/AuTransactional` → entorno **local**.
La colección guarda sola los tokens e identificadores: ejecuta las carpetas en orden. Detalle
en [`GUIA-BRUNO.md`](GUIA-BRUNO.md).

---

## 2. Autenticación

Dos mecanismos distintos, y no se mezclan:

| Superficie | Mecanismo |
|---|---|
| `/api/**` salvo login y webhook | JWT propio del BFF (`Authorization: Bearer <token>`), HS256, 8 h |
| `/api/auth/login`, `/actuator/health`, Swagger | Públicos |
| `/api/webhooks/kira` | HMAC-SHA256 en la cabecera `x-signature-sha256` |

El token de KiraFin **nunca sale del servidor**: el BFF lo obtiene y lo cachea internamente.

El JWT lleva los claims `uid`, `tenant_id` y `role`. Un token inválido o caducado devuelve
`401 {"code":"unauthorized"}` desde el filtro, antes de llegar al controlador. **Sin cabecera
`Authorization`** la respuesta es `403` con **cuerpo vacío** (verificado el 11-sep).

---

## 3. Forma de los errores

Todas las respuestas de error tienen la misma forma (`RestExceptionHandler`):

```json
{ "code": "validation_error", "message": "Datos invalidos.", "details": { "amount": "must not be null" } }
```

| Situación | HTTP | `code` |
|---|---|---|
| Regla de negocio (credenciales, rol, pago no encontrado) | 422 | `business_rule_violation` |
| Validación de campos | 400 | `validation_error` (+ `details`) |
| Sin permiso / rol insuficiente | 403 | `forbidden` |
| Sin cabecera `Authorization` | 403 | *(cuerpo vacío)* |
| Token inválido o caducado | 401 | `unauthorized` |
| Respuestas de RFI inválidas | 422 | `rfi_answer_rejected` (+ `details` por `item_id`) |
| Parámetro o parte multipart ausente, JSON ilegible | 400 | `validation_error` |
| Archivo de más de 30 MB | 413 | `file_too_large` |
| Ruta inexistente | 404 | `not_found` |
| Faltan `KIRA_API_KEY`, `KIRA_CLIENT_ID` o `KIRA_PASSWORD` | 503 | `kira_not_configured` |
| Error de Kira | 422, o 502 si Kira dio 5xx/401 | `kira_<codigo>` |
| No controlado | 500 | `internal_error` |

`details` sólo aparece en los errores de validación y en `rfi_answer_rejected`
(`default-property-inclusion: non_null`).

---

## 4. Endpoints del BFF

### 4.1 Sesión — `/api/auth`

#### `POST /api/auth/login` *(público)*

```json
{ "email": "treasury.maker@juriscop.test", "password": "Dev12345!" }
```

`200`:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresIn": 28800,
  "email": "treasury.maker@juriscop.test",
  "role": "TREASURY_MAKER",
  "tenantId": "juriscop",
  "tenantName": "Juriscop"
}
```

Usuario inexistente y contraseña incorrecta devuelven **el mismo** `422
business_rule_violation` / "Credenciales invalidas.": no se revela qué cuentas existen.

#### `GET /api/auth/me` *(JWT)*

`200` → `{ "userId", "email", "tenantId", "role" }`.

---

### 4.2 Onboarding KYB — `/api/onboarding` *(JWT)*

Da de alta a la empresa cliente en Kira y la lleva de `CREATED` a `VERIFIED`.

**No es una llamada, es un bucle.** El alta crea el registro pero *no* dispara la
verificación; a partir de ahí se repite `PUT` + `refresh` hasta que no falte ningún campo
para el producto objetivo (`usa-virtual-accounts`).

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/onboarding` | cualquiera autenticado |
| `POST` | `/api/onboarding` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `PUT` | `/api/onboarding` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/onboarding/refresh` | cualquiera autenticado |

#### `POST /api/onboarding` — alta mínima

```json
{
  "businessLegalName": "Juriscop S.A.S.",
  "email": "finanzas@juriscop.co",
  "sourceOfFunds": "sales_of_goods_and_services"
}
```

`sourceOfFunds` es obligatorio aquí aunque Kira acepte el alta sin él: sin ese campo el
KYB **no arranca nunca**, por completo que esté el resto del formulario, y el fallo es
silencioso. Valores válidos (lista de *business*, disjunta de la de *individual*):
`business_loans`, `inter_company_funds`, `investment_proceeds`, `owners_capital`,
`sales_of_goods_and_services`, `tax_refund`, `third_party_funds`, `treasury_reserves`,
`company_funds`, `investments_loans`.

Repetir la llamada **no vuelve a llamar a Kira**: si la empresa ya tiene `kiraUserId`, se
devuelve el estado actual. La `Idempotency-Key` se persiste *antes* de la primera llamada,
así que un timeout seguido de reintento no crea dos empresas.

#### `PUT /api/onboarding` — completar el perfil

```json
{ "profile": { "business_type": "sa_de_cv", "formation_date": "2019-04-02" } }
```

Los campos van **con el nombre que usa Kira**, porque el formulario se dibuja desde
`pendingFields` y traducirlos exigiría mantener un diccionario que cambia cada vez que
Kira pide un campo nuevo.

El BFF guarda el objeto enviado y en cada `PUT` reenvía **la fusión completa**: un `PUT`
parcial borra en silencio campos como `nationality`. La fusión es superficial —una clave
nueva reemplaza entera a la guardada—, así que **`associated_persons` debe viajar siempre
completo**, con todos los UBOs.

#### Respuesta (`OnboardingView`)

```json
{
  "tenantId": "juriscop", "name": "Juriscop", "kiraUserId": "usr_9c1f",
  "status": "CREATED", "verificationTriggered": false,
  "pendingFields": ["business_type", "formation_date", "expected_monthly_volume"],
  "eligibleProducts": [
    { "productCode": "usa-virtual-accounts", "eligible": false,
      "missingFields": ["expected_monthly_volume"], "unsupportedReason": null }
  ],
  "readyForVirtualAccounts": false,
  "enhancedDueDiligenceRequired": false
}
```

`pendingFields` es `missing_fields.general` ∪ `missing_fields["usa-virtual-accounts"]`, ya
combinados. **Es la fuente de verdad del formulario**: la pantalla no debe tener campos
fijos.

`readyForVirtualAccounts` exige las dos condiciones a la vez —`status: VERIFIED` **y** el
producto `eligible: true`—, porque una empresa verificada puede seguir sin poder abrir
cuenta. Si `enhancedDueDiligenceRequired` es `true`, Kira pide diligencia reforzada
(`file_proof_of_address`): pasa con ~51 países y con empresas constituidas hace menos de
180 días.

#### `POST /api/onboarding/documents` — adjuntar documentos corporativos

`multipart/form-data`. **Kira no tiene endpoint de subida**: el archivo viaja en base64
dentro del `PUT /v1/users`, anidado en `identifying_information[].documents[]`.

| Parte | Obligatoria | Qué es |
|---|---|---|
| `files` | sí, repetible | Los archivos. JPEG, PNG o PDF |
| `types` | sí, repetible | El papel de cada archivo, **en el mismo orden que `files`** |
| `informationType` | sí | Qué registro es: `business_formation`, `ein`, `passport`… |
| `issuingCountry` | sí | País emisor, **ISO alpha-3** (`COL`, `USA`) |
| `number` | no | Número del identificador o del documento |
| `expiration` | no | Vencimiento, `YYYY-MM-DD` |

`types` admite `front`, `back` (obligatorio en todo ID con foto salvo `passport` y `visa`),
`selfie`, y los `file_*`: `file_proof_of_address`, `file_business_formation`,
`file_source_of_wealth`, `file_ein_letter`, `file_bylaws`, `file_corporate_resolution`,
`file_certificate_of_registration`, `file_certificate_of_good_standing`,
`file_board_minutes`, `file_portfolio_statement`, `file_fatca`,
`file_company_fiscal_registration`.

**Límites.** Máximo 10 archivos y **7 MB en total por petición**. No es un capricho: Kira
limita el cuerpo entero a 10 MB y el base64 infla ~⅓, así que 7 MB de archivos son ~9,4 MB
de cuerpo. Para lotes grandes, varias peticiones.

`types` se lee de la query **o** del cuerpo del multipart: un `FormData` del navegador lo
manda en el cuerpo, pero Bruno no envía los campos de texto de un multipart como campos de
formulario y allí hay que ponerlo en la query.

```bash
curl -X POST "$BFF/api/onboarding/documents" -H "Authorization: Bearer $JWT" \
  -F "files=@acta.pdf;type=application/pdf" -F "types=file_business_formation" \
  -F "informationType=business_formation" -F "issuingCountry=COL"
```

Devuelve el `OnboardingView` refrescado, con `pendingFields` ya actualizado.

**El archivo no se guarda en el BFF**: lo custodia Kira. Del payload de onboarding se
limpian los `documents` antes de persistirlo, porque guardarlos significaría reenviarlos en
cada `PUT` posterior hasta reventar el tope de 10 MB. Reenviar el registro sin archivos no
los borra: *"a missing file works differently: sending other fields will not clear it"*.

---

#### Estados

`CREATED` → `VERIFYING` → (`REVIEW`) → `VERIFIED` | `REJECTED`. La revisión manual puede
tardar **hasta 24 h** en producción. El **motivo de un rechazo llega solo por el webhook**
`user.verification.failed`; `GET /v1/users/{id}` nunca lo expone, así que `refresh` dirá
`REJECTED` sin decir por qué.

---

### 4.3 Beneficiarios finales — `/api/ubos` *(JWT)*

Los UBOs se registran **primero en local** y se sincronizan en bloque. Kira fusiona
`associated_persons` **por email** y no devuelve todos los datos de cada persona, así que la
tabla `ubos` es la única copia completa. **Dos beneficiarios con el mismo correo no se
admiten** (`422`): para Kira serían la misma persona y aquí sumarían dos veces su participación.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/ubos` | cualquiera autenticado |
| `POST` | `/api/ubos` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `DELETE` | `/api/ubos/{id}` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/ubos/sync` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/ubos/liveness-links` | `ADMIN` o `COMPLIANCE_INTERNAL` |

#### `POST /api/ubos` — alta o edición local

```json
{
  "firstName": "María", "lastName": "Pérez",
  "documentType": "national_id", "documentNumber": "1020304050",
  "hasOwnership": true, "ownershipPercentage": 60.00,
  "hasControl": true, "isSigner": true,
  "politicallyExposed": false,
  "countryOfBirth": "COL",
  "roleInCompany": "Socia fundadora",
  "birthDate": "1985-04-12", "nationality": "COL", "documentCountry": "COL",
  "occupation": "Abogada", "gender": "female", "phoneNumber": "+573001234567",
  "address": { "streetName": "Calle 1 # 2-3", "city": "Bogota", "state": "DC",
               "postalCode": "110111", "country": "COL" }
}
```

Sin `id` crea; con `id` actualiza **todo**, incluidos nombre, apellido y cargo (hasta el
15-sep la edición los exigía pero no los aplicaba).

**Datos de identidad (opcionales):** `birthDate` (pasada), `nationality` y `documentCountry`
(ISO-3), `occupation`, `gender` (`male`/`female`/`other`), `phoneNumber` (E.164) y `address`
(país ISO-3). Kira los pide **según el banco** en `missing_fields`
(`associated_persons:birth_date`, `:nationality`, `:occupation`, `:gender`, `:phone_number`),
así que el BFF no los hace obligatorios. Viajan a Kira como `birth_date`, `nationality`,
`document_country`, `occupation`, `gender`, `phone_number` y la dirección **plana**
`address_street`/`address_city`/`address_state`/`address_zip_code`/`address_country`, que es
como Kira la guarda y la devuelve (verificado en sandbox el 15-sep).

La vista añade `firstName`, `lastName`, esos campos y **`knownToKira`**: `true` desde que la
persona se sincronizó, subió un documento o tiene enlace de liveness. Los cuatro booleanos y `countryOfBirth` son
**obligatorios**, no opcionales de cortesía:

- **`hasOwnership`** es un booleano explícito. `roleInCompany` es una etiqueta legible y
  **no** identifica al beneficiario: un "Director" con el 40 % pero sin `hasOwnership` no
  cuenta para Kira. Omitirlo deja el KYB bloqueado pidiendo
  `associated_persons:has_ownership`.
- **`politicallyExposed`** (`pep_status`) es obligatorio sin excepciones.
- **`countryOfBirth`** es ISO-3 y no admite vacío.
- Si `documentType` se omite, Kira asume `national_id` y a partir de ahí **exige el
  reverso** del documento.

#### `DELETE /api/ubos/{id}` — quitar a quien Kira aún no conoce

Devuelve el grupo actualizado. Si `knownToKira` es `true` responde `422`: Kira fusiona por
email y quitar a la persona del array **no la borra allí**, así que borrarla aquí dejaría las
dos listas descuadradas.

#### `GET /api/ubos` — el grupo, no sólo la lista

```json
{
  "members": [ { "id": "...", "fullName": "María Pérez", "beneficialOwner": true, "...": "..." } ],
  "totalOwnership": 100.00,
  "hasBeneficialOwner": true,
  "livenessComplete": false
}
```

Kira valida reglas sobre el **conjunto**: hace falta **al menos una persona con
`hasOwnership: true` y `ownershipPercentage ≥ 5`**, y la suma no puede pasar de 100. Es la
causa más común de un KYB atascado, así que el BFF lo comprueba antes de llamar —fallar
aquí es más barato que descubrirlo tres pantallas después.

#### `POST /api/ubos/sync`

Envía el array completo a Kira (vía `PUT /v1/users/{id}`) y devuelve el `OnboardingView`
actualizado. Si no hay beneficiario final, responde `422` **sin llamar a Kira**.

#### `POST /api/ubos/liveness-links`

```json
{ "successUrl": "https://portal.juriscop.co/kyb/ok", "rejectUrl": "https://portal.juriscop.co/kyb/ko" }
```

Body opcional; si se envía, **ambas** URLs son obligatorias y deben estar preautorizadas
por Kira (un `redirect` a medias es un `400`). Devuelve un enlace **por cada beneficiario
final**, con vigencia de **7 días**. Repetir la llamada devuelve los mismos enlaces
mientras no cambien las URLs, así que reintentar es seguro.

Requiere que la verificación ya esté disparada: si no, Kira responde
`422 "No verification is in progress"` y el BFF lo corta antes de gastar la llamada.

> ⚠️ **La landing de redirección no es fuente de verdad.** El resultado real de la prueba
> llega por el webhook `user.liveness_completed`. Que la persona vuelva a `successUrl` no
> significa que haya aprobado.

#### `POST /api/ubos/{id}/documents` — documento de identidad de una persona

Mismo multipart que el de la empresa (`files` + `types` emparejados por índice), pero el
registro se anida **dentro de la entrada de esa persona** en `associated_persons[]`.

> ⚠️ **El UBO necesita `email`.** Kira empareja las personas de `associated_persons[]` por
> email: sin él no hay forma de decirle a qué persona pertenece el documento, y le crearía
> una persona nueva en vez de actualizar la que ya tiene. El BFF corta con `422` antes de
> llamar. `email` es opcional en `POST /api/ubos` para no romper los UBO ya registrados,
> pero es obligatorio para esto.

**Mandar la selfie junto al documento** (`types=front&types=selfie`) le basta a Kira para
hacer el face match **sin sesión interactiva**: el informe biométrico se adjunta al usuario.
No sustituye al enlace de liveness, pero adelanta esa parte.

```bash
curl -X POST "$BFF/api/ubos/$UBO/documents?informationType=passport&issuingCountry=COL" \
  -H "Authorization: Bearer $JWT" \
  -F "files=@frente.png;type=image/png" -F "types=front" \
  -F "files=@selfie.png;type=image/png" -F "types=selfie"
```

---

### 4.4 Cuentas virtuales — `/api/virtual-accounts` *(JWT)*

Cuentas en bancos de EE. UU. Es el paso que habilita todo lo demás: sin una cuenta capaz de
mover fondos no hay cotización ni pago.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/virtual-accounts` | cualquiera autenticado |
| `GET` | `/api/virtual-accounts/{id}` | cualquiera autenticado |
| `POST` | `/api/virtual-accounts` | `ADMIN`, `TREASURY_MAKER` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/virtual-accounts/{id}/refresh` | cualquiera autenticado |
| `POST` | `/api/virtual-accounts/{id}/balance` | cualquiera autenticado |
| `POST` | `/api/virtual-accounts/{id}/simulate-deposit` | `ADMIN` o `TREASURY_MAKER` *(sólo sandbox)* |

#### `POST /api/virtual-accounts`

```json
{ "description": "Operativa Juriscop", "mode": "fiat", "currency": "USD" }
```

**No se pide el banco**: lo fija la configuración del entorno (`kira.bank`), porque el
valor válido depende de a dónde se apunte — `slovak_savings_bank` en sandbox, `portage` en
producción — y equivocarlo devuelve `400 "Invalid bank"`.

`mode` (`fiat` | `crypto`) es **inmutable** una vez creada: cambiarlo significa abrir otra
cuenta.

Requiere **dos condiciones a la vez**: `status: VERIFIED` **y** el producto
`usa-virtual-accounts` con `eligible: true` y sin campos pendientes. Es exactamente lo que
devuelve `readyForVirtualAccounts` en `GET /api/onboarding`.

Si Kira responde `409` (ya existía una cuenta para ese user), **se reutiliza la existente**
y se devuelve `201` con sus datos: no es un error.

#### Respuesta (`VirtualAccountView`)

```json
{
  "id": "9c1f...", "kiraAccountId": "kva_1",
  "status": "PENDING", "mode": "FIAT",
  "bank": "slovak_savings_bank", "bankName": null,
  "description": "Operativa Juriscop",
  "accountNumber": null, "routingNumber": null,
  "currency": "USD",
  "availableBalance": 0.0000, "balanceRefreshedAt": null,
  "balanceStale": true,
  "fundsReady": false,
  "activationDelayed": false,
  "createdAt": "2026-09-10T18:00:00Z"
}
```

> ⚠️ **`status` no responde a la pregunta que importa.** La API colapsa *activating* y
> *active* en `approved`, así que una cuenta "aprobada" puede seguir sin poder mover fondos.
> Usa **`fundsReady`**, que es `true` sólo con un `accountNumber` real (ni `null` ni el
> centinela `PENDING-ACT-ACCOUNT`) o tras el webhook `virtual_account.activated`.

`activationDelayed: true` significa que la cuenta lleva más de **5 minutos** sin activarse.
En el sandbox la activación puede quedarse colgada indefinidamente sin que llegue nunca el
evento: el portal debe mostrar *"activación demorada — contactar a Kira"*, **no un spinner
infinito**.

`accountNumber` + `routingNumber` son las **instrucciones de depósito**: el portal las
muestra de forma copiable.

#### `POST /api/virtual-accounts/{id}/balance`

Refresca el saldo desde Kira. Durante la activación, `GET /balance` puede devolver `400`:
eso **no es un error** sino *"todavía calculando"*, y el BFF devuelve el último saldo
conocido en vez de fallar.

> Aquí el saldo llega como decimal (`5000.00`), **no** en unidades menores como en las
> cotizaciones. En sandbox además es un valor fijo del proveedor que no se mueve con la
> actividad.

#### `POST /api/virtual-accounts/{id}/simulate-deposit`

```json
{ "amount": 5000.00, "paymentType": "wire" }
```

Sólo existe con `kira.sandbox=true`; fuera de ahí responde `422` **sin llamar a Kira** (la
API devolvería `403`). Acredita saldo de inmediato y refresca el saldo local.

> El monto `11` es un valor mágico de Kira que devuelve estado `refunded`, útil para probar
> esa rama.

---

### 4.5 Depósitos — `/api/deposits` *(JWT)*

Historial de fondeos. Los depósitos no se crean desde aquí: se proyectan desde los webhooks y,
como red de seguridad, se sincronizan desde Kira.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/deposits?limit=50` | cualquiera autenticado |
| `GET` | `/api/virtual-accounts/{id}/deposits?limit=50` | cualquiera autenticado |
| `POST` | `/api/virtual-accounts/{id}/deposits/sync` | cualquiera autenticado |

`POST …/deposits/sync` trae de Kira (`GET /v1/virtual-accounts/{id}/deposits`, `limit`+`offset`)
los depósitos de la cuenta y los asienta con la misma proyección que los webhooks, así que
convergen en las mismas filas. Es la red de seguridad de un webhook perdido. Un depósito de otra
cuenta que venga en la lista se ignora. `KYT_PENDING` se guarda como `PENDING` y `KYT_REJECTED`
como `FAILED`.

> ⚠️ **En sandbox, un depósito entrante NO aparece en `GET /deposits` de Kira.** Este espejo
> local es la única constancia que va a existir de que ese dinero llegó. Por eso la
> proyección del webhook no es un lujo.

#### Respuesta (`DepositView`)

```json
{
  "id": "9c1f...", "kiraDepositId": "dep_1", "virtualAccountId": "va_demo_001",
  "grossAmount": 5000.0000, "feeAmount": 25.0000, "netAmount": 4975.0000,
  "currency": "USD",
  "senderName": "Acme Corp", "senderAccount": "****7890", "rail": "WIRE",
  "status": "COMPLETED",
  "microdeposit": false,
  "creditsBalance": true,
  "held": false,
  "createdAt": "2026-09-10T18:00:00Z", "updatedAt": "2026-09-10T18:00:00Z"
}
```

Los tres importes van por separado porque son tres hechos distintos: lo que envió el
ordenante (`grossAmount`), lo que cobró el banco (`feeAmount`) y lo que quedó disponible
(`netAmount`). Derivar uno de los otros pierde el desglose contable.

| Estado | Cuándo |
|---|---|
| `PENDING` | `deposit_scheduled`, `deposit_funds_in_transit`, `deposit_in_review`, o un estado desconocido (nunca acredita) |
| `COMPLETED` | `deposit_funds_received`, `deposit_funds_in_destination`, `microdeposit_funds_received` — **no es final**: puede retenerse o devolverse |
| `KYT_PENDING` | retenido por un control de cumplimiento (`held: true`) |
| `KYT_REJECTED` | congelado tras el control (`held: true`); una decisión de cumplimiento lo pasa a `REFUNDED` o lo libera a `COMPLETED` |
| `FAILED` | `deposit_funds_failed` |
| `REFUNDED` | `deposit_funds_refunded` |

**`held: true` bloquea todos los pagos de esa cuenta** mientras dure (`compliance/holds`).
`senderAccount` sale enmascarada: el número completo no llega al navegador.

`microdeposit: true` es un depósito de verificación de cuenta, no un ingreso: no cuenta
como saldo (`creditsBalance: false`).

#### Cómo afectan al saldo

**Un depósito acreditado no suma al saldo local.** La autoridad es Kira, y en sandbox el
saldo es además un valor fijo del proveedor: inventar aquí una suma sería mostrar un número
que el banco no reconoce.

Lo que hace la proyección es marcar el saldo cacheado como desactualizado. `GET
/api/virtual-accounts` lo expone como **`balanceStale: true`**, y el portal responde
llamando a `POST /api/virtual-accounts/{id}/balance`.

Los **seis eventos** de la familia describen el mismo depósito en momentos distintos y
convergen en **una sola fila**, deduplicada por `kira_deposit_id`. Un evento tardío no
retrocede desde un estado terminal: un `in_transit` que llega tarde no resucita un depósito
ya devuelto.

> Las respuestas de depósito **mezclan `snake_case` y `camelCase`** en el mismo objeto
> (`deposit_id` junto a `internalPaymentId`). Cada campo se busca en las dos formas.

---

### 4.6 Destinatarios — `/api/recipients` *(JWT)*

**Un destinatario = un riel**, y de ese riel sale el riel de todos sus pagos: ni la
cotización ni el pago lo determinan. Es el punto de decisión más importante del flujo.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/recipients` | cualquiera autenticado |
| `GET` | `/api/recipients/{id}` | cualquiera autenticado |
| `POST` | `/api/recipients` | `TREASURY_MAKER` o `ADMIN` |
| `POST` | `/api/recipients/{id}/archive` | `TREASURY_MAKER` o `ADMIN` |
| `GET` | `/api/recipients/kira` | cualquiera autenticado |
| `GET` | `/api/recipients/{id}/kira` | cualquiera autenticado |

`GET /api/recipients/kira` lista los destinatarios de la empresa **tal como los tiene Kira**
(`GET /v1/recipients?user_id=…`, sin paginar) para conciliar con el directorio:

```json
[ { "kiraRecipientId": "krec_1", "localRecipientId": "9c1f…", "type": "business",
    "name": "Acme Corp", "accountType": "WIRE", "maskedDestination": "****7890",
    "email": "pagos@acme.com", "createdAt": "2026-09-10T18:00:00Z" } ]
```

`localRecipientId: null` significa que existe en Kira pero no se dio de alta desde el portal.
`GET /api/recipients/{id}/kira` hace lo mismo con uno solo del directorio.

**No hay `PUT`.** Kira no expone actualización ni borrado de destinatarios: para corregir
uno se da de alta el reemplazo y se archiva el anterior apuntando al nuevo.

#### `POST /api/recipients`

**Cabecera opcional `Idempotency-Key` (UUID)**, una por alta que se intenta: repetir la petición
con la misma clave (doble clic, reintento de red) devuelve el destinatario ya registrado en vez de
guardar otro. Una clave que no sea UUID responde `422`. Sin cabecera, el BFF genera una.

El bloque de campos cambia según el riel. Enviar los de dos rieles a la vez se rechaza.

```json
// WIRE — bank_address es un OBJETO
{
  "rail": "WIRE",
  "business": true, "companyName": "Acme Corp",
  "email": "pagos@acme.com", "phone": "+13055551234",
  "address": { "streetName": "1 Main St", "city": "New York",
               "state": "NY", "postalCode": "10001", "country": "US" },
  "routingNumber": "021000021", "swiftCode": "EXAMUS33XXX",
  "accountNumber": "1234567890", "accountKind": "checking",
  "bankName": "Example Bank, N.A.",
  "bankAddress": { "streetName": "1 Bank Plaza", "city": "New York",
                   "state": "NY", "postalCode": "10001", "country": "US" },
  "docType": "ein", "docNumber": "12-3456789"
}
```

```json
// ACH — bank_address es TEXTO PLANO
{ "rail": "ACH", "business": true, "companyName": "Acme Corp",
  "address": { "...": "..." },
  "routingNumber": "021000021", "accountNumber": "1234567890",
  "accountKind": "savings", "bankName": "Example Bank",
  "bankAddressText": "1 Bank Plaza, NY",
  "docType": "ein", "docNumber": "12-3456789" }
```

```json
// WALLET — sin datos bancarios ni dirección postal
{ "rail": "WALLET", "business": false, "firstName": "Ana", "lastName": "Pérez",
  "token": "USDT", "network": "tron", "walletAddress": "T9y...",
  "docType": "passport", "docNumber": "AB1234567" }
```

Validaciones que se hacen **antes** de llamar a Kira:

| Regla | Detalle |
|---|---|
| `routingNumber` | exactamente 9 dígitos |
| `swiftCode` | 8 u 11 caracteres |
| `accountKind` | `checking` o `savings` |
| Par token/red | `USDC` → polygon, solana · `USDT` → polygon, solana, tron · `COPm` → polygon |
| Dirección | obligatoria para ACH/WIRE; el país va en **ISO-2** (`US`), no ISO-3 |
| Titular | empresa → `companyName`; persona → `firstName` + `lastName` |

> ⚠️ **`USDC` no está soportado en `tron`.** El par inválido se rechaza aquí; si llegara a
> Kira, el problema aparecería con un pago ya retenido.

> ⚠️ El país del **destinatario** es ISO-2 (`US`) y el de la **empresa** en el KYB es ISO-3
> (`USA`). Mezclarlos es un error de validación.

#### Respuesta (`RecipientView`)

```json
{
  "id": "9c1f...", "kiraRecipientId": "krec_1",
  "name": "Acme Corp", "rail": "WIRE", "network": null,
  "bankName": "Example Bank, N.A.",
  "maskedDestination": "****7890",
  "status": "ACTIVE", "registeredInKira": true,
  "alreadyExisted": false,
  "replacedByRecipientId": null,
  "bankAddress": { "streetName": "1 Bank Plaza", "city": "New York",
                   "state": "NY", "postalCode": "10001", "country": "US" },
  "createdAt": "2026-09-10T18:00:00Z"
}
```

`alreadyExisted: true` significa que Kira respondió `202` — *"ya existía, te devuelvo el
registro existente"*. Se muestra como **"destino ya registrado"**, no como error.

> `bankAddress.state` y `postalCode` salen del **espejo local**: Kira los acepta y luego
> los devuelve **vacíos**. Si no se guardaran aquí, se perderían al primer guardado.

`maskedDestination` muestra sólo los últimos 4 dígitos: el directorio no necesita el número
completo, y cada pantalla que lo muestre es una copia más de un dato bancario.

#### `POST /api/recipients/{id}/archive`

```json
{ "replacedByRecipientId": "otro-id" }
```

Cuerpo opcional. El reemplazo debe existir y ser de la misma organización. Un destinatario
archivado deja de admitir pagos, pero su registro remoto sigue vivo en Kira.

---

### 4.7 Cotizaciones — `/api/quotations` *(JWT)*

Precio en firme de una transferencia, con **TTL de 15 minutos exactos**.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/quotations?limit=50` | cualquiera autenticado |
| `GET` | `/api/quotations/{id}` | cualquiera autenticado |
| `POST` | `/api/quotations` | `TREASURY_MAKER` o `ADMIN` |

#### `POST /api/quotations`

```json
{
  "virtualAccountId": "va_demo_001",
  "recipientId": "rec_demo_001",
  "amount": 1000.00,
  "rail": null,
  "targetCurrency": null
}
```

**`amount` es lo que RECIBE el destinatario**, no lo que sale de la cuenta. Se cotiza con
`inverse: true`: las comisiones se suman por encima y el débito es mayor. El operador
teclea 1.000 y el destinatario cobra 1.000 exactos.

`rail` es opcional y normalmente sobra: se deriva del `account_type` del destinatario, que
es la **única fuente válida**. Enviarlo sirve sólo para elegir entre `ACH_STANDARD` y
`ACH_SAME_DAY`.

| `recipient.rail` | Rieles válidos |
|---|---|
| `ACH` | `ACH_STANDARD`, `ACH_SAME_DAY` |
| `WIRE` | `WIRE_DOMESTIC` |
| `WALLET` | `TRON`, `SOLANA`, `POLYGON` según la red del destinatario |

Un riel que no corresponde se rechaza con `422` **antes de llamar a Kira**. No es celo: la
API sólo lo detecta al **ejecutar el pago** (`422 RECIPIENT_ACCOUNT_TYPE_MISMATCH`), y para
entonces la cotización ya se gastó.

#### Respuesta (`QuotationView`)

```json
{
  "id": "9c1f...", "kiraQuoteId": "qt_a1b2",
  "virtualAccountId": "va_demo_001", "recipientId": "rec_demo_001",
  "rail": "WIRE_DOMESTIC",
  "originAmount": 1000.0000,
  "destinationAmount": 1000.0000, "destinationCurrency": "USD",
  "exchangeRate": 1.000000,
  "kiraFee": 15.0000, "platformFee": 15.0000, "totalFee": 30.0000,
  "totalDebitAmount": 1030.0000,
  "balanceSufficient": true,
  "fallbackRate": false,
  "status": "ACTIVE",
  "expiresAt": "2026-09-10T18:15:00Z",
  "secondsToExpiry": 900
}
```

| Campo | Qué significa |
|---|---|
| `originAmount` | lo que recibe el destinatario — lo que tecleó el operador |
| `totalDebitAmount` | el **bruto** que sale de la cuenta virtual (`source.amount` de Kira) |
| `kiraFee` | ingreso de Kira (`totals.kira_revenue_total`) |
| `platformFee` | **ingreso de la plataforma** (`totals.client_markup_total`) |
| `balanceSufficient` | si es `false`, **bloquea el pago** y ofrece "fondear cuenta" |
| `fallbackRate` | `true` si la tasa no vino del mercado: mostrar aviso de tasa de contingencia |
| `secondsToExpiry` | alimenta el contador; al llegar a 0, **deshabilitar el botón de pago** |

> ⚠️ Los 15 USD de Kira son el caso típico de `WIRE_DOMESTIC`, **no una constante**. Su
> tarifa depende del riel y lleva tramos porcentuales, así que `kiraFee` es siempre lo que
> Kira liquidó, nunca un valor asumido. El único importe fijo es `platformFee`, que se pide
> como `client_markup`.

Una cotización vencida **no se reutiliza**: hay que recotizar. `POST /api/payouts` con
`quotationId` hereda las comisiones reales de la cotización y rechaza una vencida.

---

### 4.8 Pagos — `/api/payouts` *(JWT)*

Control interno **maker-checker**: quien prepara un pago no puede autorizarlo. La API de
Kira no ofrece esto a los integradores, así que vive aquí.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/payouts?limit=50` | cualquiera autenticado |
| `GET` | `/api/payouts/{id}` | cualquiera autenticado |
| `POST` | `/api/payouts` | `TREASURY_MAKER` o `ADMIN` |
| `POST` | `/api/payouts/{id}/approve` | `TREASURY_APPROVER` o `ADMIN` |
| `POST` | `/api/payouts/{id}/reject` | `TREASURY_APPROVER` o `ADMIN` |
| `POST` | `/api/payouts/{id}/refresh` | cualquiera autenticado |
| `GET` | `/api/payouts/{id}/events` | cualquiera autenticado |
| `POST` | `/api/payouts/preview` | `TREASURY_MAKER` o `ADMIN` |
| `GET` | `/api/payouts/kira?status=&page=1&limit=20&fromDate=&toDate=` | cualquiera autenticado |

`limit` se recorta a 100 en el servidor. Todas las consultas filtran por `tenantId` dentro
de la propia query: un `id` de otra organización devuelve "Pago no encontrado", no un 403.

#### `POST /api/payouts`

**Cabecera opcional `Idempotency-Key` (UUID)**, una por pago que se intenta crear: con la misma
clave el BFF devuelve el pago ya creado. Es también la clave que viaja a Kira al aprobar. Una clave
usada por otra organización responde `422`.

```json
{
  "virtualAccountId": "9c1f…",
  "recipientId": "4b7e…",
  "amount": "125.50",
  "currency": "USD",
  "quotationId": null
}
```

`virtualAccountId` y `recipientId` son **ids del portal** (los de `/api/virtual-accounts` y
`/api/recipients`), no los de Kira: el BFF los traduce al enviar. Validaciones antes de guardar:

- `virtualAccountId`, `recipientId` y `currency` no vacíos; `amount` ≥ `0.00000001`;
- la empresa está verificada y registrada en Kira;
- la cuenta y el destinatario **existen y son de la empresa** (si no: `422 "La cuenta virtual no
  existe."` / `"El destinatario no existe."`), la cuenta está abierta en Kira y el destinatario
  no está archivado;
- con `quotationId`, la cotización es de esa misma cuenta y ese mismo destinatario.

El `user_id` de Kira sale de la empresa del operador; ya no se acepta en el cuerpo.
Respuesta `201` con la proyección `PayoutView`:

```json
{
  "id": "9c1f...", "virtualAccountId": "va_demo_001", "recipientId": "rec_demo_001",
  "quotationId": "q_9c1f",
  "amount": 125.5000, "currency": "USD",
  "kiraFee": 15.0000, "platformFee": 15.0000, "totalFee": 30.0000,
  "totalDebitAmount": 155.5000,
  "approvalState": "PENDING_APPROVAL", "status": "NOT_SUBMITTED", "terminal": false,
  "makerUserId": "juriscop:treasury_maker", "approverUserId": null,
  "priceLocked": true,
  "kiraPayoutId": null, "referenceNumber": null, "paymentMethod": null, "errorCode": null,
  "blockedByRfiId": null,
  "createdAt": "2026-09-08T18:00:00Z", "updatedAt": "2026-09-08T18:00:00Z"
}
```

`amount` es lo que **recibe** el destinatario; `totalDebitAmount` el bruto que sale de la
cuenta virtual. `priceLocked` indica si hay cotización detrás: sin ella el precio se cierra
al ejecutar, y las comisiones podrían no ser las mostradas.

`referenceNumber` (IMAD / ACH trace / UETR) es **el comprobante que reclama el cliente
final**. Sólo aparece tras el envío.

Al crear se genera y persiste una `Idempotency-Key` (UUID v4): un reintento replica el
mismo pago en Kira en lugar de crear uno nuevo.

#### `POST /api/payouts/{id}/approve`

Cuerpo **opcional**:

```json
{
  "comment": "revisado por tesorería",
  "natureOfPayment": "vendor",
  "memo": "Factura 42",
  "documents": [ { "type": "invoice", "file": "data:application/pdf;base64,JVBERi0=" } ]
}
```

`natureOfPayment` ∈ `vendor`, `pobo`, `first_party`, `spot_3p`, `spot_1p`,
`related_entities`, `other`. `memo` (máx. 255) es **obligatorio para WIRE** en algunos
bancos corresponsales. `documents` admite **1 o 2** archivos, siempre como **data URI
base64** de máx. 3 MB — aquí, a diferencia del KYB, **no se aceptan URLs**.

Antes de llamar a Kira se comprueba que la cotización siga **vigente y con saldo
suficiente**, y que quien aprueba no sea quien creó el pago. La cotización se marca como
consumida **al enviar**, no al preparar: si el envío falla con `400`, Kira no la ha
gastado y sigue siendo redimible.

> ⚠️ **Kira descuenta las comisiones del monto enviado, no las suma encima.** Por eso el
> BFF envía como `amount` el **bruto** (`totalDebitAmount`), no el importe que tecleó el
> operador: mandar 1.000 con 30 de comisión dejaría al destinatario cobrando 970.

Aprueba **y ejecuta** contra Kira (`POST /v1/virtual-accounts/{id}/payout`). Casos:

- El mismo operador que lo creó → rechazado (además, un `TREASURY_MAKER` recibe `403` en
  la cadena de seguridad antes de llegar a la regla de dominio).
- Sin credenciales de Kira **configuradas** (`KIRA_API_KEY` vacía) → `500 internal_error`
  (verificado el 11-sep). Si Kira rechaza la llamada → `502`/`422` con `code` `kira_*`. **Ojo:**
  el método es transaccional, así que la aprobación se revierte y el pago sigue en
  `PENDING_APPROVAL`. Es el comportamiento esperado en local sin sandbox.

#### `POST /api/payouts/{id}/reject`

```json
{ "reason": "Beneficiario no validado por cumplimiento." }
```

`reason` es obligatorio.

`blockedByRfiId` no es nulo cuando un RFI abierto de Kira tiene el pago detenido: el portal lo
marca como "detenido" y enlaza a `/api/rfis/{blockedByRfiId}`.

#### `POST /api/payouts/preview`

```json
{ "virtualAccountId": "9c1f…", "recipientId": "4b7e…", "amount": 1000.00, "recipientReceivesAmount": true }
```

Coste del pago **sin reservar precio** (`POST /v1/virtual-accounts/{id}/payout/preview`), para
mostrarlo mientras el operador teclea. Por defecto `amount` es lo que recibe el destinatario, como
al cotizar. Viaja el mismo margen de plataforma que en un pago sin cotización:

```json
{ "amount": "1030.00", "currency": "USD", "recipientAmount": "1000.00",
  "recipientCurrency": "USD", "fees": { "…": "desglose de Kira, depende del riel" } }
```

Para cerrar el precio, cotiza con `POST /api/quotations`.

#### `GET /api/payouts/{id}/events`

Línea de tiempo del pago (`events[]` de `GET /v1/payouts/{id}`). Lista vacía si aún no se envió:

```json
[ { "eventId": "e1", "status": "CREATED", "message": null, "createdAt": "2026-09-11T10:00:00Z" },
  { "eventId": "e2", "status": "PROCESSING", "message": "Enviado al banco", "createdAt": "2026-09-11T10:01:00Z" } ]
```

#### `GET /api/payouts/kira`

Historial de la empresa **en Kira** (`GET /v1/payouts?user_id=…`), incluidos movimientos que no
nacieron en el portal (`origin: deposit | api`). Pagina por `page` (desde 1) y `limit` (1–100).
`status` admite sólo `CREATED`, `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`IN_REVIEW`, `KYT_PENDING` (otro valor: `422` sin llamar, porque Kira rechaza con `400` cualquier
parámetro que no conoce). `fromDate`/`toDate` en ISO 8601 o `AAAA-MM-DD`. Se descarta cualquier
fila de otro `user_id`.

```json
{ "items": [ { "kiraPayoutId": "pay_1", "shortId": "P-1A2B", "localPayoutId": "9c1f…",
               "status": "COMPLETED", "origin": "payout", "fromAmount": "1030.00", "fromCurrency": "USD",
               "toAmount": "1000.00", "toCurrency": "USD", "paymentMethod": "wire",
               "recipientName": "Acme Corp", "createdAt": "2026-09-11T10:00:00Z", "…": "…" } ],
  "page": 1, "limit": 20, "total": 1, "totalPages": 1 }
```

#### `POST /api/payouts/{id}/refresh`

Reconciliación puntual contra `GET /v1/payouts/{id}`. Si el pago aún no tiene
`kiraPayoutId`, devuelve el estado local sin llamar a Kira.

#### Estados

`approvalState` (sólo del BFF, Kira no lo conoce): `PENDING_APPROVAL` → `REJECTED`, o
`PENDING_APPROVAL` → `APPROVED` → `SUBMITTED` una vez que Kira acepta el pago.

`status` (vocabulario de Kira): `NOT_SUBMITTED`, `CREATED`, `PENDING`, `PROCESSING`,
`KYT_PENDING`, `IN_REVIEW`, `COMPLETED`, `FAILED`, `EXPIRED`, `UNKNOWN`.
Terminales: `COMPLETED`, `FAILED`, `EXPIRED`. `returned` y `cancelled` se normalizan a
`FAILED`; un estado desconocido cae en `UNKNOWN` y se trata como no terminal.

---

### 4.9 Webhooks de Kira — `POST /api/webhooks/kira` *(HMAC)*

Kira corta a los 30 s y **reintenta 4 veces** (1, 5, 15 y 60 min) ante `408`, `429`, `5xx` o
falta de respuesta; después el evento sólo se recupera reenviándolo desde el dashboard. El
controlador verifica la firma y **guarda el evento antes de responder**: si la base falla sale un
`5xx` y Kira reintenta. La proyección ocurre después. Responde `200` incluso ante un evento
desconocido: un `4xx` es lo único que Kira no reintenta.

Cabecera `x-signature-sha256`: HMAC-SHA256 en hexadecimal sobre los **bytes crudos** del
cuerpo, con `KIRA_WEBHOOK_SECRET`. **Al rotar el secreto** en el dashboard, Kira sigue firmando con
el anterior cerca de un minuto: pon el viejo en `KIRA_WEBHOOK_SECRET_PREVIOUS` durante la rotación
y bórralo después. No re-serialices el JSON antes de firmar: cambian los
espacios y el orden de las claves y la firma deja de cuadrar.

| Respuesta | Cuándo |
|---|---|
| `200 {"status":"received"}` | firma válida, evento guardado |
| `200 {"status":"duplicate"}` | ese `data.event_id` ya estaba guardado |
| `400 {"error":"invalid_json"}` | firma válida pero cuerpo ilegible (reintentar daría lo mismo) |
| `401 {"error":"invalid_signature"}` | firma incorrecta |
| `503 {"error":"webhook_secret_not_configured"}` | falta `KIRA_WEBHOOK_SECRET` |

**Dos envolturas activas.** El identificador de deduplicación está siempre en
`data.event_id`, nunca en la raíz:

```jsonc
// Plana
{ "event": "payout.completed",
  "data": { "event_id": "evt_1", "payout_id": "pay_1", "status": "completed" } }

// V2 de payout.status_changed
{ "data": { "event_id": "evt_2", "event_type": "payout.status_changed",
            "created_at": "2026-09-08T18:00:00Z",
            "data": { "payout_id": "pay_1", "status": "KYT_PENDING",
                      "previous_status": "PENDING" } } }
```

Se proyectan los prefijos `payout.`, `virtual_account.`, `user.` y `rfi.`; el resto se
almacena para revisión. La idempotencia se apoya en la unicidad de `data.event_id` en base
de datos.

**Dos eventos `user.*` son la única fuente de su dato.** Si se pierden, no se recuperan
por `GET`:

| Evento | Qué proyecta | Por qué no hay alternativa |
|---|---|---|
| `user.verification.failed` | `status: REJECTED` + `rejectionReason` | `GET /v1/users/{id}` **nunca** expone el motivo |
| `user.liveness_completed` | resultado del UBO (`COMPLETED` / `FAILED`) | la landing de redirección no lo confirma |
| `user.verification.accepted` | `status: VERIFIED` | también llegaría por `POST /api/onboarding/refresh` |
| `user.status_changed`, `user.updated` | estado del KYB | ídem |
| `virtual_account.activated` | marca la cuenta como **fondos-listos** | `status` por sí solo no lo dice (G4) |
| `virtual_account.deposit_*` | la fila del depósito y `balanceStale` | en sandbox no aparecen en `GET /deposits` |

En la familia `user.*` el estado va en `data.status` y **en mayúsculas**; en los eventos
planos de payout y VA va en minúsculas. Todo se compara sin distinguir mayúsculas.

Ojo con un detalle: en `user.liveness_completed` el `status` (`approved` / `rejected`) se
refiere **a la persona**, no a la empresa, y se atribuye por `person_reference_id`. Un
evento de esa familia sin `status` no mueve el estado de la empresa: interpretarlo como
`CREATED` degradaría a una empresa ya verificada.

**Firmar desde Bruno** — el pre-request script de la carpeta `10 Webhooks de Kira` ya lo hace:

```javascript
const CryptoJS = require("crypto-js");
const body = JSON.stringify({ event: "payout.completed",
  data: { event_id: "evt_" + Date.now(), payout_id: "pay_demo_001", status: "completed" } });
req.setBody(body);
req.setHeader("x-signature-sha256",
  CryptoJS.HmacSHA256(body, bru.getEnvVar("webhookSecret")).toString(CryptoJS.enc.Hex));
```

Desde la terminal:

```bash
BODY='{"event":"payout.completed","data":{"event_id":"evt_1","payout_id":"pay_1","status":"completed"}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$KIRA_WEBHOOK_SECRET" -hex | awk '{print $2}')
curl -sS -X POST http://localhost:8080/api/webhooks/kira \
  -H 'Content-Type: application/json' -H "x-signature-sha256: $SIG" -d "$BODY"
```

---

### 4.10 Operación

`GET /actuator/health` *(público)* → `{"status":"UP"}`. Durante el primer segundo tras el
arranque puede responder `503 OUT_OF_SERVICE`: la app aún no acepta tráfico (la semilla de dev
corre justo después de "Started"). Sólo se exponen `health` e `info`, sin detalle.

---

### 4.11 Solicitudes de información (RFI) — `/api/rfis` *(JWT)*

Kira genera los RFIs —casi siempre para detener una transferencia o un KYB en revisión— y el
portal **nunca los crea**: los sincroniza, los muestra y responde sus items. Su plazo (`due_at`,
unas dos semanas) **no se prorroga**; al vencer, el RFI cierra en `not_resolved` y lo bloqueado
sigue bloqueado.

| Método | Ruta | Rol requerido |
|---|---|---|
| `GET` | `/api/rfis` (`?open=true` sólo abiertos) | cualquiera autenticado |
| `GET` | `/api/rfis/{id}` | cualquiera autenticado |
| `POST` | `/api/rfis/sync` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/rfis/{id}/refresh` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `PATCH` | `/api/rfis/{id}/items` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `POST` | `/api/rfis/{id}/items/{itemId}/documents` *(multipart)* | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `DELETE` | `/api/rfis/{id}/items/{itemId}/documents/{documentId}` | `ADMIN` o `COMPLIANCE_INTERNAL` |
| `GET` | `/api/rfis/{id}/items/{itemId}/documents/{documentId}/link` | cualquiera autenticado |

#### Respuesta (`RfiView`)

```json
{
  "id": "rfi-demo-1", "kiraRfiId": "rfi_demo_1",
  "status": "PENDING", "open": true, "overdue": false,
  "dueDate": "2026-09-25T14:14:50Z",
  "totalItems": 2, "pendingItems": 2,
  "items": [
    { "item_id": "i-ein", "answer_type": "identifier", "answer_spec": { "format": "ein" }, "status": "pending" },
    { "item_id": "i-doc", "answer_type": "document", "status": "pending" }
  ],
  "blocking": { "type": "transfer", "kiraResourceId": "kpo_9", "payoutId": "4752fa74-…", "payoutStatus": "IN_REVIEW",
                "depositId": null, "depositStatus": null },
  "createdAt": "2026-09-11T14:14:50Z", "updatedAt": "2026-09-11T14:14:50Z"
}
```

| Campo | Qué significa |
|---|---|
| `status` | `PENDING` (te toca), `ANSWERED` (Kira revisa), `RESOLVED` ✅, `NOT_RESOLVED` ❌ |
| `open` | admite respuestas: `PENDING` o `ANSWERED` (Kira puede devolver un item) |
| `overdue` | abierto y con el plazo vencido |
| `items` | tal cual los entrega Kira (`item_id`, `prompt`, `answer_type`, `answer_spec`, `status`, `documents[]`, `review_note`…). El formulario se dibuja desde `answer_type` y `answer_spec` |
| `blocking` | lo detenido: `type: "transfer"` (→ `payoutId`) o `type: "virtual_account_deposit"` (→ `depositId`). Los ids del portal van nulos si Kira bloquea algo que el portal no conoce |

`answer_type` puede ser `text_long`, `text_short`, `number`, `date`, `boolean`, `choice`,
`identifier`, `document` o `ubo_link`.

#### `POST /api/rfis/sync`

Lee `GET /v1/rfis?user_id=…&limit=100&offset=…` (paginación por `offset`, no `page`) y asienta
cada RFI. **Descarta** cualquier entrada que no pueda atribuirse a la empresa —por `user_id`, o
por el pago o depósito bloqueado—, aunque Kira la devuelva: el aislamiento lo imponemos nosotros. Si una
entrada llega sin items, se completa con `GET /v1/rfis/{id}`. Requiere la empresa dada de alta
en Kira; si no, `422` sin llamar.

#### `PATCH /api/rfis/{id}/items`

```json
{ "items": [ { "itemId": "i-ein", "answerValue": "12-3456789" },
             { "itemId": "i-empleados", "answerValue": 42 },
             { "itemId": "i-pep", "answerValue": false } ] }
```

`answerValue` es **texto, número o booleano** según el `answer_type`; viaja a Kira con su tipo.
Responder un subconjunto es válido. Antes de llamar a Kira se rechaza, **por item**:

- un item `document` con texto (esos se responden subiendo archivos, nunca con `answer_value`);
- un valor que no sea texto, número o booleano (un objeto o un texto vacío);
- un `itemId` que no es del RFI, o repetido;
- un RFI cerrado (`422 "Este RFI ya esta cerrado (…)"`).

```json
{
  "code": "rfi_answer_rejected",
  "message": "Hay respuestas invalidas; no se envio ninguna.",
  "details": { "i-doc": "Este item se responde subiendo documentos, no con texto." }
}
```

El `PATCH` de Kira es **all-or-nothing**: su `422` se devuelve con la misma forma
(`"Kira rechazo las respuestas; no se guardo ninguna."`) y ningún item debe marcarse como
guardado. Un `409` de Kira (RFI ya cerrado) actualiza el RFI local y responde `422`. Tras un
`PATCH` aceptado **se relee el RFI**: si la respuesta fue parcial, sigue `PENDING`.

#### `POST /api/rfis/{id}/items/{itemId}/documents`

`multipart/form-data` con la parte **`files`** repetida, una por archivo. Antes de llamar a Kira se
valida, y el error va por archivo o por item:

| Regla | Límite |
|---|---|
| Tipo de item | sólo `answer_type: document` (si no: `422 "El item no es de tipo documento."`) |
| Cantidad | `answer_spec.max_files` o, si no lo trae, 20 |
| Tamaño | 30 MB por archivo (el servidor corta la petición a 100 MB: `413 file_too_large`) |
| MIME | `answer_spec.mime_types` o, si no, `application/pdf`, `image/jpeg`, `image/png`, `image/heic`, `image/webp` |

```bash
curl -X POST "$BASE/api/rfis/$RFI/items/$ITEM/documents" -H "Authorization: Bearer $TOKEN" \
  -F "files=@acta.pdf;type=application/pdf" -F "files=@anexo.pdf;type=application/pdf"
```

```json
{ "code": "rfi_answer_rejected", "message": "Hay archivos invalidos; no se subio ninguno.",
  "details": { "nota.txt": "Tipo no admitido (text/plain). Permitidos: application/pdf." } }
```

Tras subir se relee el RFI y se devuelve el `RfiView` actualizado. `409` de Kira (RFI cerrado) →
`422` con el RFI ya actualizado; `422` de Kira → `rfi_answer_rejected` por item.

#### `DELETE /api/rfis/{id}/items/{itemId}/documents/{documentId}`

Borra un archivo y devuelve el RFI releído. Kira **no deja borrar el último** archivo de un item
ya respondido: `422` con `details["<itemId>"]: "The last file cannot be removed"`.

#### `GET /api/rfis/{id}/items/{itemId}/documents/{documentId}/link`

```json
{ "downloadUrl": "https://…", "expiresAt": "2026-09-11T15:05:00Z" }
```

La URL es una **credencial al portador que caduca en minutos**: ábrela al momento, no la guardes
ni la registres; si caduca, pide otra. El BFF tampoco la guarda ni la escribe en logs.

#### `POST /api/rfis/{id}/items/{itemId}/ubo-link` — verificación de un beneficiario

Para ítems `ubo_link`. Si el `answer_spec` ya trae `url`, se devuelve esa; si trae
`applicant_id` y `person_id`, se acuña con `POST /v1/rfis/{rfi}/items/{item}/ubo-link`.

```json
{ "url": "https://…", "expiresAt": "2026-09-15T20:00:00Z" }
```

Caduca en torno a una hora: pídelo cuando la persona pulsa, no al pintar la página. No se guarda.
`422` si el ítem no es `ubo_link`; si Kira responde `409` (RFI cerrado) o `404` (retirado), el BFF
asienta el cierre y responde `422`.

#### Estados y cierre

`status` añade **`WITHDRAWN`**: un RFI retirado responde `404` en todas las rutas de Kira y
desaparece del listado; el BFF lo cierra al refrescarlo, al sincronizar o al recibir su webhook.
`resolutionReason` trae `expired` o `rejected` en un `NOT_RESOLVED`, y `withdrawn` en un retirado.

#### Webhook `rfi.*`

Se proyecta releyendo el RFI en Kira (el evento no trae items, plazo ni bloqueo). Exige
suscripción explícita en Kira; si no llega, `sync` lo cubre.

---

### 4.12 Catálogos — `/api/reference` *(JWT)*

#### `GET /api/reference/countries`

Países soportados por Kira (`GET /v1/countries`), **cacheados 24 h** en el BFF: el catálogo es
estable y lo usa cada formulario de dirección. Un fallo no se cachea.

```json
[ { "name": "Colombia", "alpha3": "COL", "postalCodeFormat": "\\A\\d{6}\\Z",
    "subdivisions": [ { "name": "Antioquia", "code": "ANT" } ] } ]
```

`alpha3` es el código ISO-3 que piden la empresa y sus UBOs. Ojo: los **destinatarios** usan ISO-2
(`US`), no este catálogo. `postalCodeFormat` es la expresión regular con la que Kira valida el
código postal (sintaxis Ruby `\A…\Z`; en JavaScript equivale a `^…$`).

---

## 5. API de KiraFin que consume el BFF

Estas rutas **no** las llamas tú: las hace `KiraApiClient` contra
`https://api.balampay.com/sandbox` (perfil `dev`). Van aquí para saber qué se dispara al
usar cada endpoint del BFF y qué mockear si quieres probar sin sandbox.

| Método | Ruta de Kira | La dispara |
|---|---|---|
| `POST` | `/auth` | cualquier llamada, cuando no hay token vigente |
| `POST`/`PUT`/`GET` | `/v1/users`, `/v1/users/{id}` | `POST`/`PUT /api/onboarding`, `POST /api/onboarding/refresh`, `POST /api/ubos/sync` |
| `POST` | `/v1/users/{id}/liveness-link` | `POST /api/ubos/liveness-links` |
| `POST`/`GET` | `/v1/virtual-accounts`, `/v1/virtual-accounts/{id}` | `POST /api/virtual-accounts`, `POST …/{id}/refresh` |
| `GET` | `/v1/virtual-accounts/{id}/balance` | `POST /api/virtual-accounts/{id}/balance` |
| `POST` | `/v1/virtual-accounts/{id}/simulate-deposit` | `POST …/{id}/simulate-deposit` *(sólo sandbox)* |
| `GET` | `/v1/virtual-accounts/{id}/deposits` | `POST /api/virtual-accounts/{id}/deposits/sync` |
| `POST` | `/v1/recipients` | `POST /api/recipients` |
| `GET` | `/v1/recipients`, `/v1/recipients/{id}` | `GET /api/recipients/kira`, `GET /api/recipients/{id}/kira` |
| `POST` | `/v1/quotations` | `POST /api/quotations` |
| `POST` | `/v1/virtual-accounts/{id}/payout/preview` | `POST /api/payouts/preview` |
| `POST` | **`/v1/virtual-accounts/{id}/payout`** | `POST /api/payouts/{id}/approve` |
| `GET` | `/v1/payouts/{id}` | `POST /api/payouts/{id}/refresh`, `GET …/{id}/events` |
| `GET` | `/v1/payouts` | `GET /api/payouts/kira` |
| `GET`/`PATCH` | `/v1/rfis`, `/v1/rfis/{id}`, `/v1/rfis/{id}/items` | `/api/rfis` — **`X-Api-Version: 2026-06-01`** |
| `POST`/`DELETE`/`GET` | `/v1/rfis/{id}/items/{item}/documents[/{doc}]` | documentos de RFI — **`2026-06-01`** |
| `GET` | `/v1/countries` | `GET /api/reference/countries` (cache 24 h) |

**Cuerpos exactos** de cada una de estas llamadas, capturados de los servicios reales:
[`kira-cuerpos-peticiones.json`](kira-cuerpos-peticiones.json).

**No se usan:** `GET /v1/users` (listaría las empresas de todos los clientes del integrador),
`GET /v1/virtual-accounts/deposits` global (se usa el de cada cuenta, que ya filtra por empresa) y
`POST /v1/versioning/upgrade` (operación de cuenta, no de portal).

`Idempotency-Key` (UUID v4) en `POST /v1/users`, `/v1/recipients`, `/v1/virtual-accounts` y
`/v1/virtual-accounts/{id}/payout`. El token vive 3600 s sin refresh: se renueva con 300 s
de margen, y ante un `401` el cliente reautentica y reintenta una vez.

Versión de API enviada en cada petición: `2026-04-14` (`KIRA_API_VERSION`), **salvo las rutas
de RFI** (incluidos sus documentos), que sólo existen en `2026-06-01` y la sobrescriben por
petición (`KiraApiClient.RFI_API_VERSION`).

**Ids:** el portal sólo ve y envía ids propios del BFF. El BFF los traduce a los de Kira
(`kiraAccountId`, `kiraRecipientId`, `kiraUserId`) justo antes de llamar.

---

## 6. Recorrido completo con curl *(requiere credenciales de Kira)*

```bash
BASE=http://localhost:8080
tok() { curl -sS -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1@juriscop.test\",\"password\":\"Dev12345!\"}" | jq -r .accessToken; }
COMPLIANCE=$(tok compliance.internal); MAKER=$(tok treasury.maker); APPROVER=$(tok treasury.approver)
J='Content-Type: application/json'

# KYB
curl -sS -X POST $BASE/api/onboarding -H "Authorization: Bearer $COMPLIANCE" -H "$J" \
  -d '{"businessLegalName":"Juriscop S.A.S.","email":"finanzas@juriscop.co","sourceOfFunds":"sales_of_goods_and_services"}' | jq
# ... PUT /api/onboarding + POST /api/onboarding/refresh hasta readyForVirtualAccounts: true

# Cuenta, destinatario, vista previa, cotizacion y pago
VA=$(curl -sS -X POST $BASE/api/virtual-accounts -H "Authorization: Bearer $MAKER" -H "$J" \
  -d '{"description":"Operativa","mode":"fiat","currency":"USD"}' | jq -r .id)
REC=$(curl -sS -X POST $BASE/api/recipients -H "Authorization: Bearer $MAKER" -H "$J" -d @recipient-wire.json | jq -r .id)
curl -sS -X POST $BASE/api/payouts/preview -H "Authorization: Bearer $MAKER" -H "$J" \
  -d "{\"virtualAccountId\":\"$VA\",\"recipientId\":\"$REC\",\"amount\":1000}" | jq
Q=$(curl -sS -X POST $BASE/api/quotations -H "Authorization: Bearer $MAKER" -H "$J" \
  -d "{\"virtualAccountId\":\"$VA\",\"recipientId\":\"$REC\",\"amount\":1000}" | jq -r .id)
P=$(curl -sS -X POST $BASE/api/payouts -H "Authorization: Bearer $MAKER" -H "$J" \
  -d "{\"virtualAccountId\":\"$VA\",\"recipientId\":\"$REC\",\"amount\":1000,\"currency\":\"USD\",\"quotationId\":\"$Q\"}" | jq -r .id)
curl -sS -X POST $BASE/api/payouts/$P/approve -H "Authorization: Bearer $APPROVER" -H "$J" \
  -d '{"natureOfPayment":"vendor","memo":"Factura 42"}' | jq
curl -sS $BASE/api/payouts/$P/events -H "Authorization: Bearer $APPROVER" | jq
```

---

## 7. Problemas frecuentes

| Síntoma | Causa |
|---|---|
| `401 unauthorized` en todo | token caducado (8 h) o `Bearer ` mal formado |
| `403` sin cuerpo | falta la cabecera `Authorization` |
| `503 kira_not_configured` | arrancaste sin `KIRA_API_KEY`, `KIRA_CLIENT_ID` o `KIRA_PASSWORD` |
| `503 OUT_OF_SERVICE` en `/actuator/health` | la app acaba de arrancar; vuelve a consultar en un segundo |
| `403 forbidden` al crear un pago | estás usando el token del approver |
| `422 "La cuenta virtual no existe."` al crear un pago | mandaste un id de Kira o de otra empresa: usa el `id` de `/api/virtual-accounts` |
| `422 "La organizacion ... no supero la verificacion"` | el KYB aún no está `VERIFIED` |
| `404 not_found` | la ruta no existe (revisa método y path) |
| `503 webhook_secret_not_configured` | falta `KIRA_WEBHOOK_SECRET` en el arranque |
| `401 invalid_signature` | el cuerpo enviado no es la cadena firmada, o el secreto no coincide |
| `502 kira_*` al aprobar | Kira rechazó la llamada; la aprobación se revierte |
| `413 file_too_large` | un archivo de RFI supera 30 MB |

---

## 5. Seguridad, actividad y consola *(15-sep)*

### 5.1 Verificación en dos pasos (TOTP) — `/api/auth/mfa/*`

| Método | Ruta | Autenticación |
|---|---|---|
| `POST` | `/api/auth/login` | pública — puede devolver un reto en vez de sesión |
| `POST` | `/api/auth/mfa/verify` | reto del login |
| `POST` | `/api/auth/mfa/setup` | sesión, o reto con `mfaSetupRequired` |
| `POST` | `/api/auth/mfa/enable` | sesión, o reto con `mfaSetupRequired` |
| `POST` | `/api/auth/mfa/disable` | sesión (solo si el entorno no lo exige) |

Con segundo factor, el login responde **sin `accessToken`**:

```json
{ "expiresIn": 300, "email": "ana@juriscop.test", "mfaChallenge": "eyJ…", "mfaRequired": true, "mfaSetupRequired": false }
```

- `mfaRequired: true` → `POST /api/auth/mfa/verify` con `{ "challenge": "…", "code": "123456" }` devuelve la sesión.
- `mfaSetupRequired: true` (entorno con `BFF_MFA_ENFORCED=true` y cuenta sin MFA) → `POST /api/auth/mfa/setup`
  con `{ "challenge": "…" }` devuelve `{ "secret", "otpauthUri" }`, y `POST /api/auth/mfa/enable` con
  `{ "challenge": "…", "code": "…" }` activa el segundo factor y devuelve la sesión.

El reto **no autentica ninguna otra ruta** (401). Cinco códigos erróneos agotan el reto; un código ya
usado no vale dentro de su ventana de 30 s. `GET /api/auth/me` añade `tenantName`, `mfaEnabled` y
`mfaEnforced`.

### 5.2 Avisos, eventos y auditoría

| Método | Ruta | Rol |
|---|---|---|
| `GET` | `/api/notifications?limit=50` | cualquiera → `{ items[], unread }` |
| `GET` | `/api/notifications/unread-count` | cualquiera → `{ unread }` |
| `POST` | `/api/notifications/read` | cualquiera → `204` (marca todo como visto para ese usuario) |
| `GET` | `/api/events?limit=100` | `ADMIN`, `COMPLIANCE_INTERNAL` — eventos de Kira **sin payload** |
| `GET` | `/api/audit?limit=100` | `ADMIN`, `COMPLIANCE_INTERNAL` — bitácora con el actor por nombre |

Los avisos se generan al proyectar webhooks, solo cuando el estado cambia. `severity`: `info`,
`success`, `attention` o `critical`; `resourceType`/`resourceId` indican a qué pantalla llevar.

### 5.3 Consola de operaciones — `/api/platform/*` *(solo `PLATFORM_OPERATOR`)*

| Método | Ruta | Qué devuelve |
|---|---|---|
| `GET` | `/api/platform/tenants` | Resumen de cada organización: estado KYB, faltantes, beneficiarios, cuentas, RFIs abiertas y vencidas, pagos retenidos |
| `GET` | `/api/platform/tenants/{id}` | Ficha 360: resumen, `OnboardingView`, beneficiarios, cuentas, 20 pagos y 20 depósitos recientes, RFIs. **Queda auditado** |
| `POST` | `/api/platform/tenants/{id}/refresh` | Relee la empresa y sus cuentas en Kira y devuelve la ficha |
| `GET` | `/api/platform/review-queue` | Lo que pide atención en todas las organizaciones, lo crítico primero |

El operador de la plataforma no pertenece a ninguna empresa (`tenantId: "__platform__"`): en las
rutas de empresa no ve datos de nadie. Un rol de empresa en `/api/platform/*` recibe `403`.
