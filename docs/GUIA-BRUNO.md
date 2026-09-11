# Guía de pruebas con Bruno — AuTransactional BFF

**Fecha:** 11 de septiembre de 2026 · **Bruno:** 4.1.0 · **Colección:** `docs/bruno/AuTransactional/`

Esta guía te lleva de cero a haber probado **todas las rutas del BFF** (45 operaciones sobre 38 rutas, 83 peticiones
en la colección), paso a paso, con lo que tienes que ver en cada una.

**Todo lo que dice esta guía se ejecutó el 11-sep contra la app en marcha**, con la CLI de Bruno
y con `curl`. Cuando una respuesta es rara (un `500`, un `403` sin cuerpo), es lo que responde
hoy la aplicación, no una errata: está marcado y explicado en §13.

> Complementos: contrato detallado de cada campo en [`API-GUIA-POSTMAN.md`](API-GUIA-POSTMAN.md),
> arquitectura en [`ARQUITECTURA.md`](ARQUITECTURA.md), estado del proyecto en [`ESTADO.md`](ESTADO.md).

---

## Índice

1. [Qué se puede probar y qué no](#1-qué-se-puede-probar-y-qué-no)
2. [Requisitos](#2-requisitos)
3. [Arrancar el BFF](#3-arrancar-el-bff)
4. [Abrir la colección en Bruno](#4-abrir-la-colección-en-bruno)
5. [Cómo está construida la colección](#5-cómo-está-construida-la-colección)
6. [Usuarios, roles y forma de los errores](#6-usuarios-roles-y-forma-de-los-errores)
7. [Recorrido carpeta por carpeta](#7-recorrido-carpeta-por-carpeta)
8. [Probar RFIs sin Kira (RFI de prueba por SQL)](#8-probar-rfis-sin-kira)
9. [Recorrido completo con credenciales del sandbox de Kira](#9-recorrido-completo-con-el-sandbox-de-kira)
10. [Webhooks: comprobar qué pasó en la base de datos](#10-webhooks-comprobar-qué-pasó)
11. [Ejecutar todo de golpe: Runner y CLI](#11-ejecutar-todo-de-golpe)
12. [Limpiar los datos de prueba](#12-limpiar-los-datos-de-prueba)
13. [Problemas frecuentes y comportamientos conocidos](#13-problemas-frecuentes)
14. [Crear una petición nueva a mano](#14-crear-una-petición-nueva-a-mano)

---

## 1. Qué se puede probar y qué no

El BFF habla con KiraFin para casi todo lo que mueve dinero o identidad de empresa. **Sin
credenciales del sandbox de Kira** (`KIRA_API_KEY`, `KIRA_CLIENT_ID`, `KIRA_PASSWORD`) sólo
funciona lo que es 100 % local. Hay dos modos de prueba:

| Área | Modo A — sin credenciales de Kira | Modo B — con credenciales del sandbox |
|---|---|---|
| Sesión (login, `me`, roles) | ✅ completo | ✅ |
| Onboarding KYB | ⚠️ sólo `GET` y errores de validación/rol | ✅ |
| Beneficiarios finales (UBOs) | ⚠️ alta/edición local y validaciones; `sync` y liveness no | ✅ |
| RFIs | ⚠️ bandeja, detalle y errores **con un RFI insertado por SQL** (§8) | ✅ |
| Cuentas virtuales, depósitos | ⚠️ sólo listados vacíos y errores | ✅ |
| Destinatarios, cotizaciones | ⚠️ sólo listados vacíos y errores de validación | ✅ |
| Pagos | ⚠️ crear, listar, rechazar, refrescar; **aprobar no** | ✅ |
| Verificación de identidad | ✅ completo (adaptadores locales deterministas) | ✅ |
| Webhooks | ✅ firma y recepción; la proyección necesita datos locales que casen | ✅ |

> **Por qué casi nada de tesorería funciona sin Kira:** la semilla de desarrollo deja las tres
> empresas en `status: VERIFIED` pero **sin `kiraUserId`**. Cualquier operación que necesite a
> la empresa registrada en Kira responde `422 "La empresa todavia no esta dada de alta en Kira."`,
> y las que llaman a Kira directamente responden `500 internal_error` (§13.3).

---

## 2. Requisitos

| Requisito | Cómo comprobarlo | Valor esperado |
|---|---|---|
| Java 21 | `java -version` | `21.x` |
| MySQL en marcha | `ss -ltn \| grep 3306` | una línea `LISTEN ... 127.0.0.1:3306` |
| Base `autransactional` | `mysql -uroot -p -e "show databases like 'autransactional'"` | la base listada (la contraseña de dev está en `src/main/resources/application-dev.yaml`) |
| Bruno de escritorio | `snap list bruno` | `bruno 4.1.0` |
| Puerto 8080 libre | `ss -ltn \| grep 8080` | ninguna línea |

No hace falta crear tablas: el perfil `dev` usa `ddl-auto: update` y las crea o amplía al
arrancar (incluidas las columnas nuevas de `rfis`).

---

## 3. Arrancar el BFF

### 3.1 Comando

Desde la raíz del proyecto, en una terminal que vas a dejar abierta:

```bash
cd ~/Documentos/AuTransactional
KIRA_WEBHOOK_SECRET=secreto-webhook-local ./mvnw spring-boot:run
```

- `KIRA_WEBHOOK_SECRET` **debe valer exactamente** `secreto-webhook-local`, que es el valor de
  `webhookSecret` en el entorno de Bruno. Si arrancas sin él, los webhooks responden
  `503 webhook_secret_not_configured`; si pones otro valor, `401 invalid_signature`.
- El perfil activo por defecto es `dev`. No hace falta indicarlo.

**Modo B (con Kira):** añade las tres credenciales en el mismo comando:

```bash
KIRA_WEBHOOK_SECRET=secreto-webhook-local \
KIRA_API_KEY='...' KIRA_CLIENT_ID='...' KIRA_PASSWORD='...' \
./mvnw spring-boot:run
```

### 3.2 Cómo saber que ya arrancó

Espera a ver esta línea en la terminal (tarda unos 20–25 s):

```
Started AuTransactionalApplication in 23.072 seconds
```

Si ves `APPLICATION FAILED TO START`, lo más habitual es MySQL parado o el puerto 8080 ocupado.

### 3.3 Comprobación rápida

Abre en el navegador `http://localhost:8080/swagger-ui.html`. Debe cargar Swagger con los grupos
`1. Sesion` … `6. Webhooks de Kira`, incluido el nuevo **`1.3 Solicitudes de informacion (RFI)`**.

> ⚠️ **No uses `/actuator/health` para comprobar que está vivo:** hoy responde
> `500 internal_error` porque el proyecto no incluye la dependencia de Actuator (§13.9).

---

## 4. Abrir la colección en Bruno

### 4.1 Abrir

> Los nombres de menús y botones son los de Bruno 4.1. En otra versión pueden cambiar
> ligeramente de sitio; los ficheros de la colección no dependen de eso.

1. Abre Bruno.
2. Menú **Collection → Open Collection** (o el botón **Open Collection** de la pantalla inicial).
3. Selecciona la **carpeta** `~/Documentos/AuTransactional/docs/bruno/AuTransactional`
   (la que contiene `bruno.json`; no un fichero).
4. En la barra lateral aparece **AuTransactional BFF** con 11 carpetas numeradas de `00` a `10`.

Bruno trabaja directamente sobre los ficheros `.bru` de esa carpeta: lo que edites en la app se
guarda en el repositorio.

### 4.2 Seleccionar el entorno

1. Arriba a la derecha, en el desplegable de entornos (pone **No Environment**), elige **local**.
2. Compruébalo: al pasar el ratón por `{{baseUrl}}` en cualquier URL debe mostrar
   `http://localhost:8080` en lugar de marcarse en rojo.

Variables del entorno `local` (`environments/local.bru`):

| Variable | Valor | Para qué |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | raíz de todas las URLs |
| `empresa` | `juriscop` | organización con la que se prueba |
| `otraEmpresa` | `bankvision` | organización ajena, para probar el aislamiento |
| `password` | `Dev12345!` | contraseña de todos los operadores de la semilla |
| `webhookSecret` | `secreto-webhook-local` | firma HMAC de los webhooks; igual que `KIRA_WEBHOOK_SECRET` |

### 4.3 Safe Mode y Developer Mode

Bruno ejecuta los scripts de las peticiones en un *sandbox* con dos modos:

| Modo | Qué permite | Efecto en esta colección |
|---|---|---|
| **Safe Mode** *(por defecto)* | librerías incluidas (`crypto-js`), variables, tests | todo funciona **salvo** que no puede escribir el fichero de grabación del reto de voz |
| **Developer Mode** | además `require("fs")`, `require("path")` | la petición `09/01` escribe sola `muestras/recording.txt` con el número del reto |

Para cambiarlo: abre la colección y busca el selector **Safe Mode / Developer Mode** en la cabecera
de la pestaña de la colección (al abrir una colección por primera vez, Bruno también pregunta).
Si prefieres quedarte en Safe Mode, en §7.9 tienes el paso manual equivalente, de un solo comando.

---

## 5. Cómo está construida la colección

### 5.1 Estructura

```
docs/bruno/AuTransactional/
├── bruno.json                    ← define la colección
├── collection.bru                ← documentación general
├── environments/local.bru        ← variables del entorno "local"
├── muestras/                     ← ficheros para las peticiones multipart
│   ├── recording.txt             ← "grabación" del reto de voz (se reescribe)
│   ├── selfie.png                ← 70 bytes: el comparador local exige ≥ 64
│   ├── documento-frente.png
│   └── documento-reverso.png
├── 00 Sesion/                    ← 11 peticiones
├── 01 Onboarding KYB/            ←  6
├── 02 Beneficiarios finales/     ←  6
├── 03 RFIs/                      ← 10
├── 04 Cuentas virtuales/         ←  8
├── 05 Depositos/                 ←  2
├── 06 Destinatarios/             ←  9
├── 07 Cotizaciones/              ←  4
├── 08 Pagos/                     ←  9
├── 09 Verificacion de identidad/ ← 11
└── 10 Webhooks de Kira/          ←  7
```

### 5.2 Las pestañas de cada petición

Al abrir una petición verás estas pestañas. Así se usan en la colección:

| Pestaña | Qué contiene aquí |
|---|---|
| **Params** | parámetros de *query* (`limit`, `open`, `clientId`, …) |
| **Body** | el JSON de ejemplo, listo para enviar |
| **Headers** | sólo donde hace falta (`X-Correlation-Id`, firma de webhook de error) |
| **Auth** | `Bearer Token` con la variable del rol que debe hacer la llamada, p. ej. `{{tokenMaker}}` |
| **Vars** | vacía |
| **Script** | *Pre Request*: prepara el cuerpo (pagos, webhooks). *Post Response*: guarda ids en variables |
| **Tests** | comprueba el código HTTP esperado (y alguna condición más) |
| **Docs** | qué esperar y por qué |

Pulsa **Ctrl+Enter** (o el botón de enviar) para lanzar la petición. El resultado aparece a la
derecha; la pestaña **Tests** del panel de respuesta muestra ✓ o ✗.

### 5.3 Variables que se guardan solas (encadenamiento)

Las peticiones guardan en **variables de ejecución** los datos que usan las siguientes. Estas
variables **viven en memoria**: si cierras Bruno, se pierden y hay que volver a ejecutar
`00 Sesion` y la petición que crea cada id.

| Variable | La guarda | La usan |
|---|---|---|
| `tokenAdmin` | `00/01 Login admin` | peticiones con rol admin |
| `tokenMaker` | `00/02 Login treasury.maker` | onboarding (errores), cuentas, destinatarios, cotizaciones, crear pago |
| `tokenApprover` | `00/03 Login treasury.approver` | aprobar y rechazar pagos |
| `tokenCompliance` | `00/04 Login compliance.internal` | onboarding, UBOs, RFIs |
| `tokenReadOnly` | `00/05 Login read.only` | todos los `GET` y `refresh` |
| `tokenOtraEmpresa` | `00/06 Login compliance de otra empresa` | pruebas de aislamiento |
| `uboId` | `02/02 Alta UBO` | `02/03 Editar UBO` |
| `rfiId` | `03/01 Bandeja` (primer RFI de la lista) | `03/04` a `03/10` |
| `vaId` | `04/01 Listar` (si hay) o `04/02 Abrir cuenta` | cuentas, depósitos, cotización, pago |
| `recipientId` | `06/02 Alta WIRE` | detalle, archivado, cotización, pago |
| `recipientAchId` | `06/03 Alta ACH` | `06/06 Archivar` |
| `quotationId` | `07/01 Cotizar` | `07/03 Detalle`, `08/01 Crear pago` |
| `payoutId` | `08/01 Crear pago` | `08/02` a `08/09` |
| `verificationId`, `challengeId`, `challengeNumber` | `09/01 Crear reto de voz` | todo `09` |
| `livenessSessionId` | `09/06 Crear sesión liveness` | `09/07` a `09/09` |

Para **ver el valor actual** de una variable: icono del ojo 👁 junto al selector de entorno →
sección *Runtime Variables*.

> ⚠️ Si una variable no existe, Bruno deja el texto `{{variable}}` **literal** en la URL, y el
> síntoma depende de dónde esté:
>
> - **En la ruta** (`/api/rfis/{{rfiId}}`): el BFF recibe `{{rfiId}}` como id y responde
>   `422 "... no encontrado."`. Parece un error de negocio, pero es una variable vacía.
> - **En la query** (`?livenessSessionId={{livenessSessionId}}`): Tomcat rechaza las llaves y
>   responde **`400` con una página HTML** (no JSON).
>
> En los dos casos, ejecuta antes la petición que guarda esa variable.

---

## 6. Usuarios, roles y forma de los errores

### 6.1 Operadores de la semilla

La semilla crea 3 organizaciones (`juriscop`, `bankvision`, `au-colombia`) con un operador por
rol. Correo: `<rol>@<organización>.test`. Contraseña de todos: `Dev12345!`.

| Correo (en `juriscop`) | Rol (`role` en el token) | Puede |
|---|---|---|
| `admin@juriscop.test` | `ADMIN` | todo |
| `treasury.maker@juriscop.test` | `TREASURY_MAKER` | abrir cuentas, destinatarios, cotizar, **crear** pagos |
| `treasury.approver@juriscop.test` | `TREASURY_APPROVER` | **aprobar / rechazar** pagos |
| `compliance.internal@juriscop.test` | `COMPLIANCE_INTERNAL` | onboarding, UBOs, **RFIs**, abrir cuentas |
| `read.only@juriscop.test` | `READ_ONLY` | lecturas y `refresh` |

### 6.2 Forma de los errores (verificada)

Casi todos los errores tienen esta forma:

```json
{ "code": "validation_error", "message": "Datos invalidos.", "details": { "amount": "debe ser mayor que o igual a 0.01" } }
```

| Situación | HTTP | Cuerpo |
|---|---|---|
| Regla de negocio (no encontrado, estado incorrecto, credenciales) | `422` | `code: business_rule_violation` |
| Validación de campos | `400` | `code: validation_error` + `details` por campo |
| Respuestas de RFI inválidas | `422` | `code: rfi_answer_rejected` + `details` **por `item_id`** |
| Rol sin permiso | `403` | `code: forbidden` |
| **Sin cabecera `Authorization`** | **`403`** | **cuerpo vacío** (no `401`) |
| Token mal formado o caducado | `401` | `code: unauthorized` |
| Error de identidad | `401` / `404` / `422` | `code` = nombre del error (`SESSION_NOT_FOUND`, …) |
| Error devuelto por Kira | `422`, o `502` si Kira dio 5xx/401 | `code: kira_<codigo>` |
| **Falta configurar las credenciales de Kira** | **`500`** | `code: internal_error` |
| Webhook con firma incorrecta | `401` | `{"error":"invalid_signature"}` |

Un id de **otra organización** nunca da `403`: da `422 "... no encontrado."`, igual que un id
inexistente. Así no se revela qué existe en otras empresas.

Los mensajes de validación salen en el idioma del sistema (en esta máquina, español).

---

## 7. Recorrido carpeta por carpeta

Formato de cada petición: **método y ruta** · rol (token) · qué enviar · **qué responde** en el
modo A (sin Kira) y, cuando cambia, en el modo B.

### 7.0 Carpeta `00 Sesion` — ejecútala siempre primero

Ejecuta la carpeta entera: clic derecho sobre **00 Sesion → Run**, y en el Runner, **Run
Collection**. Tarda ~1 s y deja guardados los 6 tokens.

| # | Petición | Qué envía | Responde |
|---|---|---|---|
| 01–05 | `POST /api/auth/login` | `{"email":"<rol>@{{empresa}}.test","password":"{{password}}"}` | `200` + guarda `token*` |
| 06 | `POST /api/auth/login` | compliance de `{{otraEmpresa}}` | `200` + guarda `tokenOtraEmpresa` |
| 07 | `GET /api/auth/me` | token maker | `200` |
| 08 | `POST /api/auth/login` | password incorrecta | `422` "Credenciales invalidas." |
| 09 | `POST /api/auth/login` | email `no-es-un-email`, password vacía | `400` con `details.email` y `details.password` |
| 10 | `GET /api/auth/me` | **sin** Authorization | `403` con cuerpo **vacío** |
| 11 | `GET /api/auth/me` | `Bearer abc.def.ghi` | `401` "Token invalido o expirado." |

Respuesta de `01 Login`:

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9....",
  "expiresIn": 28800,
  "email": "admin@juriscop.test",
  "role": "ADMIN",
  "tenantId": "juriscop",
  "tenantName": "Juriscop"
}
```

Respuesta de `07 Quien soy`:

```json
{ "tenantId": "juriscop", "userId": "juriscop:treasury_maker", "role": "TREASURY_MAKER", "email": "treasury.maker@juriscop.test" }
```

**Qué comprobar:** `expiresIn` es 28800 (8 h). Pasadas 8 h, todo responde `401`: vuelve a
ejecutar esta carpeta.

---

### 7.1 Carpeta `01 Onboarding KYB`

| # | Petición | Rol | Modo A (sin Kira) | Modo B (con Kira) |
|---|---|---|---|---|
| 01 | `GET /api/onboarding` | read.only | `200` (ver abajo) | `200` |
| 02 | `POST /api/onboarding` | compliance | `500 internal_error` | `201` con `kiraUserId` |
| 03 | `PUT /api/onboarding` | compliance | `422` "no dada de alta en Kira" | `200` con `pendingFields` actualizados |
| 04 | `POST /api/onboarding/refresh` | read.only | `422` "no dada de alta en Kira" | `200` con el estado real de Kira |
| 05 | `POST /api/onboarding` | **maker** | `403 forbidden` | `403` |
| 06 | `POST /api/onboarding` sin `sourceOfFunds` | compliance | `400`, `details.sourceOfFunds` | `400` |

`01` en modo A:

```json
{
  "tenantId": "juriscop", "name": "Juriscop", "status": "VERIFIED",
  "verificationTriggered": false, "pendingFields": [], "eligibleProducts": [],
  "readyForVirtualAccounts": false, "enhancedDueDiligenceRequired": false
}
```

> `status: VERIFIED` con `eligibleProducts: []` y sin `kiraUserId` es un artefacto de la semilla:
> la empresa **no** existe en Kira. `readyForVirtualAccounts: false` es lo que manda.

Cuerpo de `02`:

```json
{ "businessLegalName": "Juriscop S.A.S.", "email": "finanzas@juriscop.co", "sourceOfFunds": "sales_of_goods_and_services" }
```

Cuerpo de `03` (modo B: ajústalo a lo que pida `pendingFields`, con los nombres de Kira):

```json
{
  "profile": {
    "business_type": "corporation",
    "formation_date": "2019-04-02",
    "formation_country": "COL",
    "expected_monthly_volume": "100000_to_500000",
    "expected_transaction_count": "26_to_50",
    "account_purpose": "operating_a_company",
    "additional_info": { "has_us_bank_account": "No", "has_denied_bank_account": "No" }
  }
}
```

**El onboarding es un bucle:** `PUT` → `refresh` → mirar `pendingFields` → `PUT` otra vez, hasta
que `pendingFields` quede vacío y `readyForVirtualAccounts` sea `true` (§9).

---

### 7.2 Carpeta `02 Beneficiarios finales`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/ubos` | read.only | `200` con el grupo | `200` |
| 02 | `POST /api/ubos` (sin `id`) | compliance | `200` + guarda `uboId` | `200` |
| 03 | `POST /api/ubos` (con `"id": "{{uboId}}"`) | compliance | `200`, `ownershipPercentage: 55.0` | `200` |
| 04 | `POST /api/ubos/sync` | compliance | `422` (ver nota) | `200` con `OnboardingView` |
| 05 | `POST /api/ubos/liveness-links` | compliance | `422` "no dada de alta en Kira" | `200` con enlaces (7 días) |
| 06 | `POST /api/ubos` sólo con `firstName` | compliance | `400` con 7 campos en `details` | `400` |

Cuerpo de `02`:

```json
{
  "firstName": "María", "lastName": "Pérez",
  "documentType": "national_id", "documentNumber": "1020304050",
  "hasOwnership": true, "ownershipPercentage": 60.00,
  "hasControl": true, "isSigner": true, "politicallyExposed": false,
  "countryOfBirth": "COL", "roleInCompany": "Socia fundadora"
}
```

Respuesta de `01` tras el alta:

```json
{
  "members": [ { "id": "3316c522-…", "fullName": "María Pérez", "documentType": "national_id",
                 "documentNumber": "1020304050", "hasOwnership": true, "ownershipPercentage": 55.0,
                 "beneficialOwner": true, "hasControl": true, "signer": true,
                 "politicallyExposed": false, "countryOfBirth": "COL", "…": "…" } ],
  "totalOwnership": 55.0, "hasBeneficialOwner": true, "livenessComplete": false
}
```

> ⚠️ **Cada ejecución de `02 Alta UBO` crea un UBO nuevo.** Si la ejecutas dos veces, la suma
> pasa de 100 % y `04 Sync` responde `422 "La suma de participaciones no puede superar el 100 %
> (actual: 110.00 %)."` **antes** de mirar si la empresa está en Kira. Es correcto: el BFF valida el
> grupo antes de llamar. Arréglalo editando porcentajes con `03` o limpiando (§12).

---

### 7.3 Carpeta `03 RFIs` *(nuevo)*

Las solicitudes de información las **genera Kira**; el portal nunca las crea. Sin Kira, la
bandeja estará vacía: para probar el detalle y los errores, inserta un RFI de prueba (§8).

| # | Petición | Rol | Sin RFI | Con RFI de prueba (§8) | Modo B |
|---|---|---|---|---|---|
| 01 | `GET /api/rfis` | read.only | `200 []` | `200` con 1 RFI + guarda `rfiId` | `200` |
| 02 | `GET /api/rfis?open=true` | read.only | `200 []` | `200` con 1 RFI | `200` |
| 03 | `POST /api/rfis/sync` | compliance | `422` "no dada de alta en Kira" | ídem | `200` con la bandeja sincronizada |
| 04 | `GET /api/rfis/{{rfiId}}` | read.only | `422` "RFI no encontrado." (no hay `rfiId`) | `200` | `200` |
| 05 | `POST /api/rfis/{{rfiId}}/refresh` | compliance | `422` "RFI no encontrado." | `500 internal_error` | `200` |
| 06 | `PATCH /api/rfis/{{rfiId}}/items` | compliance | `422` "RFI no encontrado." | `500 internal_error` | `200` con el RFI releído |
| 07 | `PATCH …/items` item de documento + item inventado | compliance | `422` "RFI no encontrado." | **`422 rfi_answer_rejected`** | `422` |
| 08 | `PATCH …/items` con `items: []` | compliance | `400 validation_error` | `400` | `400` |
| 09 | `PATCH …/items` | **maker** | `403` | `403` | `403` |
| 10 | `GET /api/rfis/{{rfiId}}` | **otra empresa** | `422` "RFI no encontrado." | `422` "RFI no encontrado." | `422` |

Respuesta de `04 Detalle` (RFI de prueba):

```json
{
  "id": "rfi-demo-1",
  "kiraRfiId": "rfi_demo_1",
  "status": "PENDING",
  "open": true,
  "overdue": false,
  "dueDate": "2026-09-25T14:14:50Z",
  "totalItems": 2,
  "pendingItems": 2,
  "items": [
    { "item_id": "i-ein", "answer_type": "identifier", "answer_spec": { "format": "ein" }, "status": "pending" },
    { "item_id": "i-doc", "answer_type": "document", "status": "pending" }
  ],
  "blocking": { "type": "transfer", "kiraResourceId": "kpo_demo" },
  "createdAt": "2026-09-11T14:14:50Z",
  "updatedAt": "2026-09-11T14:14:50Z"
}
```

Qué significa cada campo:

| Campo | Significado |
|---|---|
| `status` | `PENDING` (te toca responder), `ANSWERED` (Kira revisa), `RESOLVED` ✅, `NOT_RESOLVED` ❌ |
| `open` | `true` mientras admite respuestas (`PENDING` o `ANSWERED`) |
| `overdue` | abierto y con `dueDate` vencido. El plazo **no se prorroga** |
| `items` | tal cual los manda Kira. El formulario se dibuja desde `answer_type` y `answer_spec` |
| `blocking` | lo que el RFI tiene detenido. `payoutId`/`payoutStatus` aparecen si el pago es de este portal |

Cuerpo de `06` (sólo items `identifier`; responder un subconjunto es válido):

```json
{ "items": [ { "itemId": "i-ein", "answerValue": "12-3456789" } ] }
```

Respuesta de `07` — **el error va por item y no se envía nada a Kira**:

```json
{
  "code": "rfi_answer_rejected",
  "message": "Hay respuestas invalidas; no se envio ninguna.",
  "details": {
    "i-doc": "Este item se responde subiendo documentos, no con texto.",
    "i-inventado": "El item no pertenece a este RFI."
  }
}
```

En modo B, si Kira rechaza una respuesta (formato de EIN inválido, por ejemplo) la forma es la
misma, con `message: "Kira rechazo las respuestas; no se guardo ninguna."` y el mensaje de Kira en
`details["<item_id>"]`. Si Kira ya cerró el RFI (`409`), responde `422` y el RFI queda actualizado
con su estado final.

---

### 7.4 Carpeta `04 Cuentas virtuales`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/virtual-accounts` | read.only | `200 []` | `200` + guarda `vaId` si hay |
| 02 | `POST /api/virtual-accounts` | maker | `422` "La empresa todavia no puede abrir cuentas: revisa el estado del KYB y los campos pendientes del producto." | `201` + guarda `vaId` |
| 03 | `GET /api/virtual-accounts/{{vaId}}` | read.only | `422` "Cuenta virtual no encontrada." (no hay `vaId`) | `200` |
| 04 | `POST …/{{vaId}}/refresh` | read.only | `422` "Cuenta virtual no encontrada." | `200` |
| 05 | `POST …/{{vaId}}/balance` | read.only | `422` "Cuenta virtual no encontrada." | `200` |
| 06 | `POST …/{{vaId}}/simulate-deposit` | maker | `422` "Cuenta virtual no encontrada." | `200` |
| 07 | `POST /api/virtual-accounts` | **approver** | `403` | `403` |
| 08 | `POST …/simulate-deposit` con `amount: 0`, `paymentType: "swift"` | maker | `400` con `details.amount` y `details.paymentType` | `400` |

Cuerpos: `02` → `{"description":"Operativa Juriscop","mode":"fiat","currency":"USD"}` ·
`06` → `{"amount":5000.00,"paymentType":"wire"}` (el monto **`11`** simula un depósito `refunded`).

**Modo B — qué mirar en la respuesta:** no te fíes de `status`; la cuenta mueve dinero sólo cuando
**`fundsReady: true`**.

> ⚠️ `activationDelayed` debería pasar a `true` a los 5 minutos sin activarse, pero hoy **nunca
> lo hace** por un defecto conocido (§13.13). Si tras varios minutos `fundsReady` sigue en
> `false`, asume que el sandbox se ha colgado aunque `activationDelayed` diga `false`.

---

### 7.5 Carpeta `05 Depositos`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/deposits?limit=50` | read.only | `200 []` | `200` con los depósitos proyectados |
| 02 | `GET /api/virtual-accounts/{{vaId}}/deposits?limit=50` | read.only | `422` "Cuenta virtual no encontrada." | `200` |

Los depósitos **no se crean con una petición**: aparecen al llegar el webhook
`virtual_account.deposit_*` para una cuenta que exista en local (§10).

---

### 7.6 Carpeta `06 Destinatarios`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/recipients` | read.only | `200 []` | `200` |
| 02 | `POST /api/recipients` WIRE | maker | `422` "no dada de alta en Kira" | `201` + guarda `recipientId` |
| 03 | `POST /api/recipients` ACH | maker | `422` | `201` + guarda `recipientAchId` |
| 04 | `POST /api/recipients` WALLET (USDT/tron) | maker | `422` | `201` |
| 05 | `GET /api/recipients/{{recipientId}}` | read.only | `422` "Destinatario no encontrado." | `200` |
| 06 | `POST /api/recipients/{{recipientAchId}}/archive` | maker | `422` "Destinatario no encontrado." | `200`, `replacedByRecipientId` = el WIRE |
| 07 | WIRE con `routingNumber: "123"` | maker | `400`, "El routing number debe tener 9 digitos" | `400` |
| 08 | WALLET **USDC en tron** | maker | `422` "no dada de alta en Kira" ⚠️ | `422` "USDC no esta soportado en la red 'tron'…" |
| 09 | WIRE | **approver** | `403` | `403` |

> ⚠️ En modo A, `08` no muestra el error de la red porque la comprobación "¿está la empresa en
> Kira?" se hace antes. Para ver el error real necesitas el modo B.

Cuerpo de `02` (WIRE — `bankAddress` es un **objeto**, país en **ISO-2**):

```json
{
  "rail": "WIRE", "business": true, "companyName": "Acme Corp",
  "email": "pagos@acme.com", "phone": "+13055551234",
  "address": { "streetName": "1 Main St", "city": "New York", "state": "NY", "postalCode": "10001", "country": "US" },
  "routingNumber": "021000021", "swiftCode": "EXAMUS33XXX",
  "accountNumber": "1234567890", "accountKind": "checking",
  "bankName": "Example Bank, N.A.",
  "bankAddress": { "streetName": "1 Bank Plaza", "city": "New York", "state": "NY", "postalCode": "10001", "country": "US" },
  "docType": "ein", "docNumber": "12-3456789"
}
```

Cuerpo de `03` (ACH — `bankAddressText` es **texto**):

```json
{
  "rail": "ACH", "business": true, "companyName": "Acme Corp",
  "address": { "streetName": "1 Main St", "city": "New York", "state": "NY", "postalCode": "10001", "country": "US" },
  "routingNumber": "021000021", "accountNumber": "9876543210", "accountKind": "savings",
  "bankName": "Example Bank", "bankAddressText": "1 Bank Plaza, New York, NY",
  "docType": "ein", "docNumber": "12-3456789"
}
```

Cuerpo de `04` (WALLET — sin datos bancarios):

```json
{
  "rail": "WALLET", "business": false, "firstName": "Ana", "lastName": "Pérez",
  "token": "USDT", "network": "tron", "walletAddress": "TXYZ1234567890abcdefghijklmnopqrs",
  "docType": "passport", "docNumber": "AB1234567"
}
```

---

### 7.7 Carpeta `07 Cotizaciones`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `POST /api/quotations` | maker | `422` "La cuenta virtual no existe." | `201` + guarda `quotationId` |
| 02 | `GET /api/quotations?limit=50` | read.only | `200 []` | `200` |
| 03 | `GET /api/quotations/{{quotationId}}` | read.only | `422` "Cotizacion no encontrada." | `200` |
| 04 | `POST /api/quotations` con `{"amount":0}` | maker | `400` con 3 campos en `details` | `400` |

Cuerpo de `01`:

```json
{ "virtualAccountId": "{{vaId}}", "recipientId": "{{recipientId}}", "amount": 1000.00 }
```

**Modo B — qué mirar:** `amount` es lo que **recibe** el destinatario; `totalDebitAmount` es lo que
sale de la cuenta. `secondsToExpiry` empieza en 900: pasados 15 minutos, recotiza.

---

### 7.8 Carpeta `08 Pagos`

Control **maker-checker**: el maker prepara, el approver autoriza.

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `POST /api/payouts` | maker | **`201`** + guarda `payoutId` | `201` |
| 02 | `GET /api/payouts?limit=50` | read.only | `200` con el pago | `200` |
| 03 | `GET /api/payouts/{{payoutId}}` | read.only | `200` | `200` |
| 04 | `POST …/{{payoutId}}/approve` | **maker** | `403` | `403` |
| 05 | `POST …/{{payoutId}}/approve` | approver | **`500 internal_error`**; el pago sigue `PENDING_APPROVAL` | `200`, `approvalState: SUBMITTED` |
| 06 | `POST …/{{payoutId}}/refresh` | read.only | `200` (sin `kiraPayoutId` no llama a Kira) | `200` con el estado de Kira |
| 07 | `POST …/{{payoutId}}/reject` con `{}` | approver | `400`, `details.reason` | `400` |
| 08 | `POST …/{{payoutId}}/reject` | approver | `200`, `approvalState: REJECTED` | `422` si ya se envió |
| 09 | `GET /api/payouts/{{payoutId}}` | **otra empresa** | `422` "Pago no encontrado." | `422` |

Si lanzas `08` dos veces, la segunda responde `422 "Solo puede rechazarse un pago pendiente de
aprobacion."`.

Cuerpo de `01` (el script *Pre Request* quita `quotationId` si no cotizaste, y pone ids de
ejemplo si no hay `vaId` o `recipientId`):

```json
{ "virtualAccountId": "{{vaId}}", "recipientId": "{{recipientId}}", "amount": 125.50, "currency": "USD", "quotationId": "{{quotationId}}" }
```

Respuesta de `01` en modo A:

```json
{
  "id": "e7b253e7-…", "virtualAccountId": "va_demo_001", "recipientId": "rec_demo_001",
  "amount": 125.5, "currency": "USD",
  "kiraFee": 15.0, "platformFee": 15.0, "totalFee": 30.0, "totalDebitAmount": 155.5,
  "approvalState": "PENDING_APPROVAL", "status": "NOT_SUBMITTED", "terminal": false,
  "makerUserId": "juriscop:treasury_maker", "priceLocked": false, "…": "…"
}
```

> ⚠️ Que `01` acepte `va_demo_001` y `rec_demo_001`, que no existen, es un **defecto conocido**
> (§13.10): hoy crear un pago no comprueba que la cuenta y el destinatario existan ni sean de la
> empresa. El error sólo aparece al aprobar.

Cuerpo de `05`:

```json
{ "comment": "Revisado por tesoreria", "natureOfPayment": "vendor", "memo": "Factura 42" }
```

`natureOfPayment` ∈ `vendor`, `pobo`, `first_party`, `spot_3p`, `spot_1p`, `related_entities`,
`other`. Para adjuntar soporte añade
`"documents": [{"type": "invoice", "file": "data:application/pdf;base64,JVBERi0="}]`
(`type` sólo `invoice` u `other`; siempre *data URI*, nunca URL; máximo 2 y 3 MB cada uno).

---

### 7.9 Carpeta `09 Verificacion de identidad` *(pública, sin JWT)*

Funciona **completa sin Kira**. Orden obligatorio, con el **mismo** `verificationId`:
reto de voz → prueba de vida → veredicto.

| # | Petición | Responde | Guarda |
|---|---|---|---|
| 01 | `POST /api/v1/number-challenge/session?clientId={{empresa}}` | `200` | `verificationId`, `challengeId`, `challengeNumber` |
| 02 | `GET /api/v1/number-challenge/{{challengeId}}/status` | `200 {"valid":true,"used":false,"expired":false}` | — |
| 03 | `POST /api/v1/number-challenge/{{challengeId}}/verify` (multipart `recording`) | `200 {"passed":true,…}` | — |
| 04 | `GET /api/v1/liveness/config` | `200 {"enabled":true,"identityPoolId":"","region":""}` | — |
| 05 | `GET /api/v1/liveness/status` | `200 {"enabled":true}` | — |
| 06 | `POST /api/v1/liveness/session?verificationId={{verificationId}}` | `200` | `livenessSessionId` |
| 07 | `GET /api/v1/liveness/session/{{livenessSessionId}}/result` | `200 {"status":"SUCCEEDED","confidence":96.5,"passed":true,…}` | — |
| 08 | `POST /api/v1/identity/validate?…` (multipart selfie + documento) | `200 {"status":"APPROVED",…}` | — |
| 09 | `validate` repetido | `404 SESSION_NOT_FOUND` "Esta sesion de verificacion ya fue resuelta." | — |
| 10 | `POST /api/v1/liveness/session?clientId={{empresa}}` | `422 VERIFICATION_INCOMPLETE` | — |
| 11 | `POST /api/v1/number-challenge/session?clientId=nadie` | `401 UNAUTHORIZED` "La organizacion no existe." | — |

Respuesta de `01`:

```json
{ "challengeId": "f4a29e83-…", "challengeNumber": "1087", "expiresAt": "2026-09-11T14:03:12Z", "verificationId": "6056f062-…" }
```

**El reto dura 120 s.** Lanza `01` → `03` sin pausas largas.

#### El fichero de la grabación (paso clave de `03`)

En `dev` no hay transcripción real: el BFF busca **los primeros 4 dígitos** del fichero
`recording`. El fichero debe contener el `challengeNumber` de **este** reto.

- **Developer Mode:** no hagas nada. `01` escribe `muestras/recording.txt` con el número.
- **Safe Mode:** después de `01`, copia el `challengeNumber` de la respuesta y ejecuta en una
  terminal (cambia `1087` por tu número):

  ```bash
  cd ~/Documentos/AuTransactional/docs/postman
  ./generar-grabacion.sh 1087 ../bruno/AuTransactional/muestras/recording.txt
  ```

  Debe imprimir `Escrito ../bruno/AuTransactional/muestras/recording.txt (32 bytes) con el numero 1087`.
  Después lanza `03`.

Si el número no coincide, `03` responde `200` pero con
`{"passed":false,"normalizedNumber":"0000","failureReason":"INVALID_SPOKEN_NUMBER"}`, y `06`
fallará con `422 VERIFICATION_INCOMPLETE`. Para simular un vídeo pregrabado (sin movimiento de
labios), añade `--nolips` al script: `failureReason: LIP_MOVEMENT_NOT_DETECTED`.

El reto es de **un solo uso** y admite **3 intentos**. Tras un `passed: true`, repetir `03` da
`NUMBER_CHALLENGE_ALREADY_USED`.

#### Multipart en Bruno

`03` y `08` usan **Body → Multipart Form**. Cada fila de tipo fichero apunta a `muestras/…`.
Si Bruno marca el fichero en rojo, pulsa sobre él y vuelve a seleccionarlo desde
`docs/bruno/AuTransactional/muestras/`.

`08 Veredicto` — query y partes:

| Query | Valor |
|---|---|
| `verificationId` | `{{verificationId}}` |
| `challengeId` | `{{challengeId}}` |
| `livenessSessionId` | `{{livenessSessionId}}` |
| `countryCode` | `CO` |
| `documentType` | `NATIONAL_ID` |

| Parte | Fichero |
|---|---|
| `selfieImage` | `muestras/selfie.png` |
| `documentFrontImage` | `muestras/documento-frente.png` (**obligatoria**) |
| `documentBackImage` | `muestras/documento-reverso.png` (opcional) |

Respuesta:

```json
{
  "verificationId": "6056f062-…", "status": "APPROVED",
  "livenessScore": 96.5, "matchScore": 88.0,
  "documentData": { "documentNumber": "1020304050", "firstName": "MARIA", "lastName": "GONZALEZ",
                    "birthDate": "1990-05-15", "expirationDate": "2030-05-15",
                    "issuingCountry": "CO", "nationality": "CO", "gender": "F" }
}
```

> ⚠️ Si quitas `documentFrontImage`, hoy responde `500 internal_error` en vez de un error de
> validación (§13.8).

---

### 7.10 Carpeta `10 Webhooks de Kira` *(firma HMAC, sin JWT)*

Cada petición construye su cuerpo en el *Pre Request* script, lo firma con
`HMAC-SHA256(cuerpo, webhookSecret)` y lo envía con la cabecera `x-signature-sha256`. **No
edites el cuerpo en la pestaña Body**: lo reemplaza el script. Para cambiar el evento, edita el
objeto `payload` del script.

| # | Evento | Responde | Qué deja en `webhooks_log` (modo A) |
|---|---|---|---|
| 01 | `payout.completed` | `200 {"status":"received"}` | `normalized_status: COMPLETED`; sin pago local que case |
| 02 | `payout.status_changed` (sobre V2) | `200` | `KYT_PENDING` |
| 03 | `user.verification.failed` | `200` | `REJECTED`; sin empresa local con ese `user_id` |
| 04 | `virtual_account.activated` | `200` | `approved` |
| 05 | `virtual_account.deposit_funds_received` | `200` | `COMPLETED` |
| 06 | `rfi.created` | `200` | `processed = 0`, `processing_error: "Falta KIRA_API_KEY…"` |
| 07 | firma `0000` | `401 {"error":"invalid_signature"}` | nada |

Script de `01` (los demás son iguales, cambia `payload`):

```javascript
const CryptoJS = require("crypto-js");
const payload = { event: "payout.completed",
  data: { event_id: "evt_" + Date.now(), payout_id: "pay_demo_001", status: "completed" } };
const body = JSON.stringify(payload);
req.setBody(body);
req.setHeader("Content-Type", "application/json");
req.setHeader("x-signature-sha256",
  CryptoJS.HmacSHA256(body, bru.getEnvVar("webhookSecret")).toString(CryptoJS.enc.Hex));
```

El `200` sólo significa **firma válida y evento encolado**: el procesamiento es asíncrono y
**nunca** devuelve error al emisor. Para saber si se proyectó, mira la base de datos (§10).

`event_id` lleva `Date.now()` para que cada envío sea un evento nuevo: si repites un `event_id`,
el BFF lo ignora (deduplicación).

---

## 8. Probar RFIs sin Kira

### 8.1 Insertar un RFI de prueba

Con la app arrancada (la tabla `rfis` ya existe), en una terminal:

```bash
mysql -uroot -p autransactional <<'SQL'
INSERT INTO rfis (id, tenant_id, kira_rfi_id, status, items_payload, due_date,
                  blocking_type, blocking_resource_id, created_at, updated_at)
VALUES ('rfi-demo-1', 'juriscop', 'rfi_demo_1', 'PENDING',
        '[{"item_id":"i-ein","answer_type":"identifier","answer_spec":{"format":"ein"},"status":"pending"},{"item_id":"i-doc","answer_type":"document","status":"pending"}]',
        UTC_TIMESTAMP() + INTERVAL 14 DAY, 'transfer', 'kpo_demo', UTC_TIMESTAMP(), UTC_TIMESTAMP());
SQL
```

- La contraseña es la de `spring.datasource.password` en `application-dev.yaml`.
- Usa **`UTC_TIMESTAMP()`**, no `NOW()`: la app lee las fechas como UTC y `NOW()` da la hora
  local, así que las fechas saldrían desplazadas 5 horas.

### 8.2 Ejecutar

1. `00 Sesion` → Run (si no lo hiciste).
2. `03 RFIs` → Run. Resultado verificado:

| # | Responde |
|---|---|
| 01 Bandeja | `200`, 1 RFI, guarda `rfiId = rfi-demo-1` |
| 02 Solo abiertos | `200`, 1 RFI |
| 03 Sincronizar | `422` "no dada de alta en Kira" |
| 04 Detalle | `200` (JSON de §7.3) |
| 05 Refrescar | `500` (llama a Kira sin credenciales) |
| 06 Responder | `500` (pasa la validación local y llama a Kira) |
| 07 Error documento | `422 rfi_answer_rejected` con `details` por item |
| 08 Lista vacía | `400` |
| 09 Tesorería | `403` |
| 10 Otra empresa | `422` "RFI no encontrado." |

### 8.3 Variantes útiles

- **RFI cerrado:** `UPDATE rfis SET status='NOT_RESOLVED' WHERE id='rfi-demo-1';` →
  `02 Solo abiertos` devuelve `[]`, el detalle muestra `open: false`, y `07` responde
  `422 "Este RFI ya esta cerrado (NOT_RESOLVED) y no admite respuestas."`.
- **RFI vencido:** `UPDATE rfis SET due_date = UTC_TIMESTAMP() - INTERVAL 1 DAY WHERE id='rfi-demo-1';` →
  `overdue: true` (con el RFI abierto; un RFI cerrado nunca está `overdue`).
- **Enlazar con un pago:** crea un pago (`08/01`) y ejecuta
  `UPDATE payouts SET kira_payout_id='kpo_demo' WHERE id='<payoutId>';` → `04 Detalle` muestra
  `blocking.payoutId` y `blocking.payoutStatus`:
  `{"type":"transfer","kiraResourceId":"kpo_demo","payoutId":"4752fa74-…","payoutStatus":"NOT_SUBMITTED"}`.

### 8.4 Borrarlo

```sql
DELETE FROM rfis WHERE id = 'rfi-demo-1';
```

---

## 9. Recorrido completo con el sandbox de Kira

Arranca con las credenciales (§3.1). Orden, con qué mirar en cada paso:

| Paso | Petición | Rol | Continúa cuando… |
|---|---|---|---|
| 1 | `00 Sesion` → Run | — | los 6 tokens guardados |
| 2 | `01/02 Alta minima en Kira` | compliance | la respuesta trae `kiraUserId` |
| 3 | `01/03 Completar perfil` | compliance | — |
| 4 | `01/04 Refrescar desde Kira` | read.only | mira `pendingFields`. Si no está vacío, vuelve al paso 3 con esos campos |
| 5 | `02/02 Alta UBO` (una sola vez) | compliance | `hasBeneficialOwner: true` en `02/01` |
| 6 | `02/04 Sincronizar con Kira` | compliance | `200` |
| 7 | `02/05 Enlaces de liveness` | compliance | cada UBO tiene enlace. Ábrelo y completa la prueba en el sandbox |
| 8 | `01/04 Refrescar` (repetir) | read.only | `status: VERIFIED` **y** `readyForVirtualAccounts: true` |
| 9 | `04/02 Abrir cuenta` | maker | `201`, `vaId` guardado |
| 10 | `04/04 Refrescar estado` (repetir) | read.only | **`fundsReady: true`** (si `activationDelayed: true`, el sandbox se colgó) |
| 11 | `04/06 Simular deposito` | maker | `200` |
| 12 | `04/05 Refrescar saldo` | read.only | `availableBalance` > 0 |
| 13 | `06/02 Alta WIRE` | maker | `recipientId` guardado |
| 14 | `07/01 Cotizar` | maker | `balanceSufficient: true`; tienes 15 min |
| 15 | `08/01 Crear pago` | maker | `approvalState: PENDING_APPROVAL`, `priceLocked: true` |
| 16 | `08/05 Aprobar y enviar` | **approver** | `approvalState: SUBMITTED`, `kiraPayoutId` relleno |
| 17 | `08/06 Refrescar` (repetir) | read.only | `status` terminal (`COMPLETED` / `FAILED`) y `referenceNumber` |
| 18 | `03/03 Sincronizar RFIs` | compliance | si hay RFIs, la bandeja se llena |

> **Webhooks reales de Kira:** Kira sólo puede llamar a una URL pública. En local no llegarán
> salvo que expongas el puerto 8080 con un túnel y Kira tenga registrada esa URL. Mientras tanto,
> los `refresh` hacen el trabajo de los webhooks, y la carpeta `10` simula los eventos.

> **La verificación del KYB en el sandbox** depende de Kira: puede quedar en `REVIEW` hasta
> 24 h. No está verificado cuánto tarda en sandbox.

---

## 10. Webhooks: comprobar qué pasó

Después de lanzar un webhook, consulta la bitácora (espera 1 s, el proceso es asíncrono):

```sql
SELECT event_type, resource_id, normalized_status, processed,
       LEFT(processing_error, 80) AS error, created_at
FROM webhooks_log
ORDER BY created_at DESC
LIMIT 10;
```

| Columna | Qué significa |
|---|---|
| `processed = 1` | proyectado sin error (aunque no hubiera nada local que actualizar) |
| `processed = 0` + `processing_error` | guardado pero **no** proyectado: material del reconciliador |
| `resource_id` | id de Kira del recurso (pago, cuenta, usuario, RFI) |
| `normalized_status` | el estado tal como se interpretó |

Para que un webhook **cambie datos** tiene que existir el recurso local con ese id de Kira. Por
ejemplo, para ver un depósito en `05 Depositos` en modo A:

1) Crea una cuenta local con el id de Kira que usa el webhook `05` (`kva_demo_001`). **`bank` es
obligatorio**: sin él, la proyección falla con `processing_error: "La cuenta virtual necesita
banco."` y `04/01 Listar` responde `422` con ese mismo mensaje.

```sql
INSERT INTO virtual_accounts (id, tenant_id, kira_account_id, bank, status, mode, currency,
                              balance_available, activated_event_seen, created_at, updated_at)
VALUES ('va-demo-1', 'juriscop', 'kva_demo_001', 'slovak_savings_bank', 'PENDING', 'FIAT', 'USD',
        0, false, UTC_TIMESTAMP(), UTC_TIMESTAMP());
```

2) Lanza `10/05 virtual_account.deposit_funds_received`.

3) Espera 1–2 s y lanza `05/01 Todos`:

```json
[ { "id": "06310986-…", "kiraDepositId": "dep_1789136058706", "virtualAccountId": "va-demo-1",
    "grossAmount": 5000, "feeAmount": 25, "netAmount": 4975, "currency": "USD",
    "status": "COMPLETED", "microdeposit": false, "creditsBalance": true, "…": "…" } ]
```

   Y `04/01 Listar` muestra la cuenta con `"balanceStale": true` (`availableBalance` sigue en 0:
   un depósito no suma al saldo local, lo marca como desactualizado).

4) Limpia:

```sql
DELETE FROM deposits WHERE virtual_account_id = 'va-demo-1';
DELETE FROM virtual_accounts WHERE id = 'va-demo-1';
```

---

## 11. Ejecutar todo de golpe

### 11.1 Runner de Bruno (interfaz)

1. Clic derecho sobre **AuTransactional BFF → Run**.
2. **Run Collection**. Ejecuta las carpetas en orden (`00` → `10`).
3. Al acabar, el resumen muestra peticiones y tests pasados.

Resultado verificado **en modo A**:

| Modo del sandbox | Peticiones | Tests | Fallos |
|---|---|---|---|
| Safe Mode | 83 (78 ✓, 5 ✗) | 53/57 | `09/03`, `09/06`, `09/07`, `09/08`, `09/09`: cascada por la grabación (§7.9) |
| Developer Mode | 83 (83 ✓) | 59/59 | ninguno |

Los `422`/`500` de las carpetas que necesitan Kira **no cuentan como fallo**: esas peticiones no
tienen test de código porque su respuesta depende del modo.

### 11.2 CLI (`bru`)

```bash
cd ~/Documentos/AuTransactional/docs/bruno/AuTransactional

# Toda la colección
npx --yes @usebruno/cli@4.1.0 run -r --env local

# Sólo algunas carpetas, en Developer Mode (escribe la grabación sola)
npx --yes @usebruno/cli@4.1.0 run "00 Sesion" "09 Verificacion de identidad" --env local --sandbox developer

# Informe JSON
npx --yes @usebruno/cli@4.1.0 run -r --env local --reporter-json /tmp/bruno.json
```

Código de salida `0` = todos los tests pasaron; `1` = algún test falló.

---

## 12. Limpiar los datos de prueba

Las peticiones de alta dejan filas en la base de dev. Para volver al estado de la semilla
**sólo en `juriscop`**:

```sql
DELETE FROM rfis                WHERE tenant_id = 'juriscop';
DELETE FROM payouts             WHERE tenant_id = 'juriscop';
DELETE FROM quotations          WHERE tenant_id = 'juriscop';
DELETE FROM recipients          WHERE tenant_id = 'juriscop';
DELETE FROM deposits            WHERE tenant_id = 'juriscop';
DELETE FROM virtual_accounts    WHERE tenant_id = 'juriscop';
DELETE FROM ubos                WHERE tenant_id = 'juriscop';
DELETE FROM verification_sessions WHERE tenant_id = 'juriscop';
```

> ⚠️ En **modo B** esto borra también el espejo local de recursos que **siguen existiendo en
> Kira** (cuentas, destinatarios). Kira no permite borrar destinatarios: si los recreas, Kira
> responde `202` "ya existía" (`alreadyExisted: true`). No lo hagas en modo B salvo que sepas lo que
> implica.

`webhooks_log` y `audit_logs` son bitácoras: no hace falta limpiarlas.

---

## 13. Problemas frecuentes

### 13.1 `403` sin cuerpo en cualquier ruta con JWT
Falta la cabecera `Authorization`. Causa habitual: no ejecutaste `00 Sesion`, o cerraste Bruno y
se perdieron las variables. Ejecuta `00 Sesion` otra vez.

### 13.2 `401 unauthorized` "Token invalido o expirado."
El token caducó (8 h), o la app se reinició con otro `BFF_JWT_SECRET`. Ejecuta `00 Sesion`.

### 13.3 `500 internal_error` al llamar a algo que usa Kira
La app arrancó sin `KIRA_API_KEY`. En el log aparece
`IllegalStateException: Falta KIRA_API_KEY`. Es lo esperado en modo A. Afecta a:
`01/02`, `03/05`, `03/06`, `08/05`, y a cualquier llamada a Kira si la empresa ya tuviera
`kiraUserId`. (Defecto conocido: debería ser un `503` con mensaje claro.)

### 13.4 `400` con una página HTML, o `422 "... no encontrado."` inesperado
Una variable `{{…}}` no está definida y quedó literal en la URL: en la query da el HTML de
Tomcat; en la ruta, el "no encontrado". Mira la tabla de §5.3 y ejecuta la petición que la guarda.

### 13.5 `INVALID_SPOKEN_NUMBER` en `09/03`
`muestras/recording.txt` no contiene el número de **este** reto. En Safe Mode genera el fichero a
mano (§7.9) o pasa a Developer Mode. Si pasaron más de 120 s, vuelve a `09/01`.

### 13.6 `422 "La suma de participaciones no puede superar el 100 %"`
Ejecutaste `02/02 Alta UBO` varias veces. Edita los porcentajes o limpia la tabla `ubos` (§12).

### 13.7 Webhooks: `401 invalid_signature` o `503 webhook_secret_not_configured`
- `503`: arrancaste sin `KIRA_WEBHOOK_SECRET`.
- `401`: el secreto de la app no es igual a `webhookSecret` del entorno, o editaste el cuerpo a
  mano en la pestaña Body de una petición con firma manual.

### 13.8 `500` en `validate` sin `documentFrontImage`
Defecto conocido: la parte ausente lanza `MissingServletRequestPartException`, que no está
mapeada a un `400`/`422`.

### 13.9 `500` en `/actuator/health`
Defecto conocido: `application.yaml` configura Actuator pero el `pom.xml` no incluye
`spring-boot-starter-actuator`, así que la ruta no existe y cae en el manejador genérico.

### 13.10 Un pago con cuenta o destinatario inexistentes se crea con `201`
Defecto conocido: `ExecutePayoutService.create()` no comprueba que `virtualAccountId` y
`recipientId` existan ni que sean de la empresa, y toma `kiraUserId` del cuerpo de la petición.

### 13.11 Bruno no encuentra un fichero de `muestras/`
Vuelve a seleccionarlo en **Body → Multipart Form** desde
`docs/bruno/AuTransactional/muestras/`.

### 13.12 Swagger no muestra `1.3 Solicitudes de informacion (RFI)`
La app que corre es anterior al cambio. Párala y arráncala otra vez (§3.1).

### 13.13 `activationDelayed` nunca pasa a `true`; `createdAt` de una cuenta es "ahora"
Defecto conocido: `VirtualAccount.rehydrate()` ignora el `createdAt` guardado y usa la hora de
cada lectura. Como `isActivationDelayed()` compara con esa fecha, la cuenta nunca parece llevar
más de 5 minutos. (El mismo fallo existía en `Rfi` y se corrigió el 11-sep.)

---

## 14. Crear una petición nueva a mano

Ejemplo: listar sólo los RFIs abiertos con el rol de cumplimiento.

1. Clic derecho sobre la carpeta **03 RFIs → New Request**.
2. **Type:** HTTP · **Name:** `11 Abiertos como compliance` · **URL:** `{{baseUrl}}/api/rfis` → **Create**.
3. Método: `GET`.
4. Pestaña **Params** → *Add Param* → nombre `open`, valor `true`.
5. Pestaña **Auth** → modo **Bearer Token** → token `{{tokenCompliance}}`.
6. Pestaña **Tests**:
   ```javascript
   test("responde 200", function () {
     expect(res.getStatus()).to.equal(200);
   });
   ```
7. **Ctrl+S** para guardar (crea `03 RFIs/11 Abiertos como compliance.bru`) y **Ctrl+Enter** para enviar.

Para una petición con cuerpo JSON: pestaña **Body** → **JSON** y pega el cuerpo. Para guardar un id
de la respuesta: pestaña **Script** → *Post Response*:

```javascript
if (res.getStatus() < 300 && res.getBody().id) {
  bru.setVar("miId", res.getBody().id);
}
```
