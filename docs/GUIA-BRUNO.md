# Guía de pruebas con Bruno — AuTransactional BFF

**Fecha:** 11 de septiembre de 2026 · **Bruno:** 4.1.0 · **Colección:** `docs/bruno/AuTransactional/`

Esta guía te lleva de cero a haber probado **todas las rutas del BFF** (47 operaciones sobre 40
rutas, 84 peticiones en la colección), paso a paso, con lo que tienes que ver en cada una.

**Todo lo que dice esta guía se ejecutó el 11-sep contra la app en marcha** con la CLI de Bruno y
con `curl`: **84/84 peticiones, 46/46 tests**, sin credenciales de Kira.

> El BFF es una capa delgada sobre Kira: todo el negocio vive en Kira y el BFF custodia
> credenciales, sesión y roles. Contrato campo a campo en [`API-GUIA.md`](API-GUIA.md),
> arquitectura en [`ARQUITECTURA.md`](ARQUITECTURA.md), estado en [`ESTADO.md`](ESTADO.md).

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
13. [Problemas frecuentes](#13-problemas-frecuentes)
14. [Crear una petición nueva a mano](#14-crear-una-petición-nueva-a-mano)

---

## 1. Qué se puede probar y qué no

Casi todo pasa por Kira. **Sin las tres credenciales del sandbox** (`KIRA_API_KEY`,
`KIRA_CLIENT_ID`, `KIRA_PASSWORD`) sólo funciona lo local, y lo que llama a Kira responde
`503 kira_not_configured`.

| Área | Modo A — sin credenciales de Kira | Modo B — con credenciales del sandbox |
|---|---|---|
| Sesión (login, `me`, roles) | ✅ completo | ✅ |
| Onboarding KYB | ⚠️ `GET`, validaciones y roles; el alta da `503` | ✅ |
| Beneficiarios finales (UBOs) | ⚠️ alta/edición local y validaciones | ✅ |
| RFIs y sus documentos | ⚠️ bandeja, detalle y todas las validaciones **con un RFI insertado por SQL** (§8) | ✅ |
| Cuentas virtuales, depósitos | ⚠️ listados vacíos y errores | ✅ |
| Destinatarios, cotizaciones, pagos | ⚠️ listados vacíos, validaciones y roles | ✅ |
| Catálogo de países | ❌ `503` | ✅ |
| Webhooks | ✅ firma y recepción; la proyección necesita datos locales que casen | ✅ |

> **Por qué casi nada de tesorería funciona sin Kira:** las tres empresas de la semilla nacen en
> `CREATED` y sin `kiraUserId`, porque todavía no existen en Kira. Hasta completar el KYB (§9),
> tesorería responde `422 "La organizacion Juriscop todavia no supero la verificacion (estado CREATED)."`.

---

## 2. Requisitos

| Requisito | Cómo comprobarlo | Valor esperado |
|---|---|---|
| Java 21 | `java -version` | `21.x` |
| MySQL en marcha | `ss -ltn \| grep 3306` | una línea `LISTEN ... 127.0.0.1:3306` |
| Base `autransactional` | `mysql -uroot -p -e "show databases like 'autransactional'"` | la base listada (la contraseña ya no está en el repo: es la de tu MySQL local, la que exportas en `DB_PASSWORD`) |
| Bruno de escritorio | `snap list bruno` | `bruno 4.1.0` |
| Puerto 8080 libre | `ss -ltn \| grep 8080` | ninguna línea (si IntelliJ tiene la app corriendo, párala o reiníciala) |

El perfil `dev` usa `ddl-auto: update`: crea las tablas y columnas que falten al arrancar.

---

## 3. Arrancar el BFF

### 3.1 Comando

Desde la raíz del proyecto, en una terminal que vas a dejar abierta:

```bash
cd ~/Documentos/AuTransactional
KIRA_WEBHOOK_SECRET=secreto-webhook-local ./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"
```

- `KIRA_WEBHOOK_SECRET` **debe valer exactamente** `secreto-webhook-local`, el `webhookSecret` del
  entorno de Bruno. Sin él, los webhooks dan `503 webhook_secret_not_configured`; con otro valor,
  `401 invalid_signature`.
- `-Xmx768m` limita la memoria de la JVM. Sin límite, con IntelliJ, DataGrip y el navegador
  abiertos, el sistema llegó a matar la app por falta de memoria.

**Modo B (con Kira):** añade las tres credenciales:

```bash
KIRA_WEBHOOK_SECRET=secreto-webhook-local \
KIRA_API_KEY='...' KIRA_CLIENT_ID='...' KIRA_PASSWORD='...' \
./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"
```

Las credenciales las entrega Kira por canal seguro (support@kirafin.ai); no hay panel para
generarlas. **Pruébalas antes** sin arrancar nada:

```bash
read -rsp "api_key: " K; echo; read -rp "client_id: " C; read -rsp "password: " P; echo
curl -s -o /dev/null -w "POST /auth -> HTTP %{http_code}\n" -X POST https://api.balampay.com/sandbox/auth \
  -H "x-api-key: $K" -H 'Content-Type: application/json' -d "{\"client_id\":\"$C\",\"password\":\"$P\"}"
```

`200` = correctas · `401` = api_key o password inválidos · `500` = `client_id` desconocido.

### 3.2 Cómo saber que ya arrancó

Espera a ver (unos 10–25 s):

```
Started AuTransactionalApplication in 8.235 seconds
```

### 3.3 Comprobación rápida

```bash
curl -s http://localhost:8080/actuator/health        # {"status":"UP"}
```

Durante el primer segundo puede responder `503` con `{"status":"OUT_OF_SERVICE"}`: la semilla de
dev aún está corriendo. Repite y saldrá `UP`.

En el navegador, `http://localhost:8080/swagger-ui.html` debe mostrar los grupos `0. Catalogos` …
`6. Webhooks de Kira`. **No debe aparecer nada bajo `/api/v1/`**: la verificación biométrica
propia se eliminó. Si aparece, estás viendo una app arrancada con el código anterior.

---

## 4. Abrir la colección en Bruno

> Los nombres de menús y botones son los de Bruno 4.1; en otra versión pueden cambiar de sitio.

### 4.1 Abrir

1. Abre Bruno.
2. **Collection → Open Collection** (o el botón **Open Collection** de la pantalla inicial).
3. Selecciona la **carpeta** `~/Documentos/AuTransactional/docs/bruno/AuTransactional` (la que
   contiene `bruno.json`, no un fichero).
4. En la barra lateral aparece **AuTransactional BFF** con 11 carpetas, de `00` a `10`.

Si ya la tenías abierta de antes, **ciérrala y vuelve a abrirla**: la carpeta `09` cambió y
Bruno puede seguir mostrando la antigua.

### 4.2 Seleccionar el entorno

1. Arriba a la derecha, en el desplegable de entornos (**No Environment**), elige **local**.
2. Compruébalo: al pasar el ratón por `{{baseUrl}}` en cualquier URL debe verse `http://localhost:8080`.

| Variable | Valor | Para qué |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | raíz de todas las URLs |
| `empresa` | `juriscop` | organización con la que se prueba |
| `otraEmpresa` | `bankvision` | organización ajena, para probar el aislamiento |
| `password` | `Dev12345!` | contraseña de todos los operadores de la semilla |
| `webhookSecret` | `secreto-webhook-local` | firma HMAC de los webhooks |

### 4.3 Safe Mode

La colección funciona entera en **Safe Mode**, el modo por defecto de Bruno. No hace falta
Developer Mode: los scripts sólo usan `crypto-js` (incluida) y variables.

---

## 5. Cómo está construida la colección

### 5.1 Estructura

```
docs/bruno/AuTransactional/
├── bruno.json                    ← define la colección
├── collection.bru                ← documentación general
├── environments/local.bru        ← variables del entorno "local"
├── muestras/documento.pdf        ← PDF mínimo para subir a un RFI
├── 00 Sesion/                    ← 12 peticiones
├── 01 Onboarding KYB/            ←  6
├── 02 Beneficiarios finales/     ←  6
├── 03 RFIs/                      ← 14
├── 04 Cuentas virtuales/         ←  8
├── 05 Depositos/                 ←  3
├── 06 Destinatarios/             ← 11
├── 07 Cotizaciones/              ←  4
├── 08 Pagos/                     ← 12
├── 09 Catalogos/                 ←  1
└── 10 Webhooks de Kira/          ←  7
```

### 5.2 Las pestañas de cada petición

| Pestaña | Qué contiene aquí |
|---|---|
| **Params** | parámetros de *query* (`limit`, `open`, `page`…) |
| **Body** | el JSON de ejemplo, o el multipart con `files` |
| **Auth** | `Bearer Token` con la variable del rol que debe llamar, p. ej. `{{tokenMaker}}` |
| **Script** | *Pre Request*: prepara el cuerpo (pagos, webhooks). *Post Response*: guarda ids |
| **Tests** | comprueba el código HTTP cuando no depende de Kira |
| **Docs** | qué esperar y por qué |

**Ctrl+Enter** envía. La pestaña **Tests** del panel de respuesta muestra ✓ o ✗.

### 5.3 Variables que se guardan solas

Viven en memoria: si cierras Bruno, vuelve a ejecutar `00 Sesion` y la petición que crea cada id.
Para verlas: icono 👁 junto al selector de entorno → *Runtime Variables*.

| Variable | La guarda | La usan |
|---|---|---|
| `tokenAdmin`, `tokenMaker`, `tokenApprover`, `tokenCompliance`, `tokenReadOnly` | `00/01`–`00/05` | cada petición según su rol |
| `tokenOtraEmpresa` | `00/06` | pruebas de aislamiento |
| `uboId` | `02/02 Alta UBO` | `02/03 Editar UBO` |
| `rfiId` | `03/01 Bandeja` (primero de la lista) | `03/04`–`03/14` |
| `rfiTextItemId`, `rfiDocItemId`, `rfiDocumentId` | `03/04 Detalle` | responder, subir, descargar y borrar |
| `vaId` | `04/01 Listar` (si hay) o `04/02 Abrir cuenta` | cuentas, depósitos, cotización, pagos |
| `recipientId` | `06/02 Alta WIRE` | detalle, cotización, pagos |
| `recipientAchId` | `06/03 Alta ACH` | `06/06 Archivar` |
| `quotationId` | `07/01 Cotizar` | `07/03`, `08/01` |
| `payoutId` | `08/01 Crear pago` | `08/02`–`08/12` |

> ⚠️ Si una variable no existe, Bruno deja `{{variable}}` **literal**: en la ruta el BFF responde
> `422 "... no encontrado."`; en la query, Tomcat responde `400` con una página HTML. En los dos
> casos, ejecuta antes la petición que guarda esa variable.

---

## 6. Usuarios, roles y forma de los errores

### 6.1 Operadores de la semilla

Correo `<rol>@<organización>.test`, contraseña `Dev12345!`, en `juriscop`, `bankvision` y `au-colombia`.

| Correo (en `juriscop`) | Rol (`role`) | Puede |
|---|---|---|
| `admin@juriscop.test` | `ADMIN` | todo |
| `treasury.maker@juriscop.test` | `TREASURY_MAKER` | abrir cuentas, destinatarios, cotizar, vista previa y **crear** pagos |
| `treasury.approver@juriscop.test` | `TREASURY_APPROVER` | **aprobar / rechazar** pagos |
| `compliance.internal@juriscop.test` | `COMPLIANCE_INTERNAL` | onboarding, UBOs, **RFIs y sus documentos**, abrir cuentas |
| `read.only@juriscop.test` | `READ_ONLY` | lecturas, `refresh` y sincronizaciones de lectura |

### 6.2 Forma de los errores (verificada)

```json
{ "code": "validation_error", "message": "Datos invalidos.", "details": { "amount": "debe ser mayor que o igual a 0.01" } }
```

| Situación | HTTP | Cuerpo |
|---|---|---|
| Regla de negocio (no encontrado, estado, credenciales de login) | `422` | `code: business_rule_violation` |
| Validación de campos | `400` | `code: validation_error` + `details` por campo |
| Parte multipart o parámetro ausente | `400` | `validation_error`, p. ej. "Falta la parte 'files'." |
| Respuestas o archivos de RFI inválidos | `422` | `code: rfi_answer_rejected` + `details` por item o por archivo |
| Rol sin permiso | `403` | `code: forbidden` |
| **Sin cabecera `Authorization`** | **`403`** | **cuerpo vacío** |
| Token mal formado o caducado | `401` | `code: unauthorized` |
| Ruta inexistente | `404` | `code: not_found` |
| Archivo de más de 30 MB | `413` | `code: file_too_large` |
| **Kira sin configurar** | **`503`** | `code: kira_not_configured` |
| Error devuelto por Kira | `422`, o `502` si Kira dio 5xx/401 | `code: kira_<codigo>` |
| Webhook con firma incorrecta | `401` | `{"error":"invalid_signature"}` |

Un id de **otra organización** nunca da `403`: da `422 "... no encontrado."`, igual que uno inexistente.

---

## 7. Recorrido carpeta por carpeta

Formato: **método y ruta** · rol · qué enviar · **qué responde** en modo A (sin Kira) y, cuando
cambia, en modo B.

### 7.0 `00 Sesion` — ejecútala siempre primero

Clic derecho sobre **00 Sesion → Run → Run Collection**. Deja guardados los 6 tokens.

| # | Petición | Qué envía | Responde |
|---|---|---|---|
| 01–05 | `POST /api/auth/login` | `{"email":"<rol>@{{empresa}}.test","password":"{{password}}"}` | `200` + guarda `token*` |
| 06 | `POST /api/auth/login` | compliance de `{{otraEmpresa}}` | `200` + `tokenOtraEmpresa` |
| 07 | `GET /api/auth/me` | token maker | `200` |
| 08 | `POST /api/auth/login` | password incorrecta | `422` "Credenciales invalidas." |
| 09 | `POST /api/auth/login` | email `no-es-un-email`, password vacía | `400` con `details` |
| 10 | `GET /api/auth/me` | **sin** Authorization | `403`, cuerpo vacío |
| 11 | `GET /api/auth/me` | `Bearer abc.def.ghi` | `401` "Token invalido o expirado." |
| 12 | `GET /api/no-existe` | token read.only | `404` `{"code":"not_found","message":"La ruta no existe."}` |

Respuesta de `01`:

```json
{ "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9....", "expiresIn": 28800,
  "email": "admin@juriscop.test", "role": "ADMIN", "tenantId": "juriscop", "tenantName": "Juriscop" }
```

`expiresIn` 28800 = 8 h. Pasado ese tiempo todo da `401`: vuelve a ejecutar esta carpeta.

---

### 7.1 `01 Onboarding KYB`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/onboarding` | read.only | `200`, `status: CREATED` | `200` |
| 02 | `POST /api/onboarding` | compliance | **`503 kira_not_configured`** | `201` con `kiraUserId` |
| 03 | `PUT /api/onboarding` | compliance | `422` "no dada de alta en Kira" | `200` con `pendingFields` |
| 04 | `POST /api/onboarding/refresh` | read.only | `422` "no dada de alta en Kira" | `200` con el estado real |
| 05 | `POST /api/onboarding` | **maker** | `403` | `403` |
| 06 | `POST /api/onboarding` sin `sourceOfFunds` | compliance | `400`, `details.sourceOfFunds` | `400` |

Cuerpo de `02`:

```json
{ "businessLegalName": "Juriscop S.A.S.", "email": "finanzas@juriscop.co", "sourceOfFunds": "sales_of_goods_and_services" }
```

Cuerpo de `03` (ajústalo a lo que pida `pendingFields`, con los nombres de Kira):

```json
{ "profile": { "business_type": "corporation", "formation_date": "2019-04-02", "formation_country": "COL",
  "expected_monthly_volume": "100000_to_500000", "expected_transaction_count": "26_to_50",
  "account_purpose": "operating_a_company",
  "additional_info": { "has_us_bank_account": "No", "has_denied_bank_account": "No" } } }
```

**El onboarding es un bucle:** `PUT` → `refresh` → mirar `pendingFields` → `PUT`, hasta que
`pendingFields` quede vacío y `readyForVirtualAccounts` sea `true` (§9).

---

### 7.2 `02 Beneficiarios finales`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/ubos` | read.only | `200` con el grupo | `200` |
| 02 | `POST /api/ubos` (sin `id`) | compliance | `200` + `uboId` | `200` |
| 03 | `POST /api/ubos` (con `id`) | compliance | `200`, `ownershipPercentage: 55.0` | `200` |
| 04 | `POST /api/ubos/sync` | compliance | `422` "no dada de alta en Kira" | `200` con `OnboardingView` |
| 05 | `POST /api/ubos/liveness-links` | compliance | `422` "no dada de alta en Kira" | `200` con enlaces (7 días) |
| 06 | `POST /api/ubos` sólo `firstName` | compliance | `400` con 7 campos en `details` | `400` |

Cuerpo de `02`:

```json
{ "firstName": "María", "lastName": "Pérez", "documentType": "national_id", "documentNumber": "1020304050",
  "hasOwnership": true, "ownershipPercentage": 60.00, "hasControl": true, "isSigner": true,
  "politicallyExposed": false, "countryOfBirth": "COL", "roleInCompany": "Socia fundadora" }
```

> ⚠️ **Cada ejecución de `02` crea un UBO nuevo.** Dos ejecuciones suman 110 % y `04` responde
> `422 "La suma de participaciones no puede superar el 100 %"`, antes de mirar Kira. Edita con
> `03` o limpia (§12).

---

### 7.3 `03 RFIs`

Kira genera los RFIs; el portal los sincroniza y responde. Sin Kira la bandeja está vacía:
**inserta el RFI de prueba de §8** para ver el detalle y las validaciones.

| # | Petición | Rol | Sin RFI | Con RFI de prueba (§8) | Modo B |
|---|---|---|---|---|---|
| 01 | `GET /api/rfis` | read.only | `200 []` | `200` + guarda `rfiId` | `200` |
| 02 | `GET /api/rfis?open=true` | read.only | `200 []` | `200` | `200` |
| 03 | `POST /api/rfis/sync` | compliance | `422` "no dada de alta en Kira" | ídem | `200` |
| 04 | `GET /api/rfis/{{rfiId}}` | read.only | `422` "RFI no encontrado." | `200` + guarda ids de items | `200` |
| 05 | `POST /api/rfis/{{rfiId}}/refresh` | compliance | `422` | `503` | `200` |
| 06 | `PATCH …/items` item de texto | compliance | `422` | `503` (validó y llamó a Kira) | `200` |
| 07 | `PATCH …/items` item documento + item inventado | compliance | `422` | **`422 rfi_answer_rejected`** | `422` |
| 08 | `PATCH …/items` con `items: []` | compliance | `400` | `400` | `400` |
| 09 | `PATCH …/items` | **maker** | `403` | `403` | `403` |
| 10 | `GET /api/rfis/{{rfiId}}` | **otra empresa** | `422` | `422` "RFI no encontrado." | `422` |
| 11 | `POST …/items/{{rfiDocItemId}}/documents` (PDF) | compliance | `422` | `503` (validó y llamó a Kira) | `200` con el RFI releído |
| 12 | `GET …/documents/{{rfiDocumentId}}/link` | read.only | `422` | `503` | `200` `{downloadUrl, expiresAt}` |
| 13 | `DELETE …/documents/{{rfiDocumentId}}` | compliance | `422` | `503` | `200`, o `422` si es el último |
| 14 | `POST …/items/{{rfiTextItemId}}/documents` | compliance | `422` | **`422`** "El item no es de tipo documento." | `422` |

Respuesta de `04` con el RFI de prueba:

```json
{
  "id": "rfi-demo-1", "kiraRfiId": "rfi_demo_1", "status": "PENDING", "open": true, "overdue": false,
  "dueDate": "2026-09-25T17:36:55Z", "totalItems": 2, "pendingItems": 1,
  "items": [
    { "item_id": "i-ein", "answer_type": "identifier", "answer_spec": { "format": "ein" },
      "status": "pending", "prompt": "EIN de la empresa" },
    { "item_id": "i-doc", "answer_type": "document", "status": "answered", "prompt": "Acta de constitucion",
      "answer_spec": { "mime_types": ["application/pdf"], "max_files": 2 },
      "documents": [ { "document_id": "doc-1", "file_name": "acta.pdf", "mime_type": "application/pdf", "size_bytes": 1024 } ] }
  ],
  "blocking": { "type": "transfer", "kiraResourceId": "kpo_demo", "payoutId": null, "payoutStatus": null,
                "depositId": null, "depositStatus": null },
  "createdAt": "2026-09-11T17:36:55Z", "updatedAt": "2026-09-11T17:36:55Z"
}
```

| Campo | Significado |
|---|---|
| `status` | `PENDING` (te toca), `ANSWERED` (Kira revisa), `RESOLVED` ✅, `NOT_RESOLVED` ❌ |
| `open` / `overdue` | admite respuestas / abierto con el plazo vencido (el plazo no se prorroga) |
| `items[].answer_type` | `text_long`, `text_short`, `number`, `date`, `boolean`, `choice`, `identifier`, `document`, `ubo_link` |
| `blocking` | lo detenido: `transfer` → `payoutId`, `virtual_account_deposit` → `depositId` |

Cuerpo de `06` — `answerValue` es **texto, número o booleano** según el tipo del item:

```json
{ "items": [ { "itemId": "{{rfiTextItemId}}", "answerValue": "12-3456789" } ] }
```

Respuesta de `07` — el error va por item y **no se envía nada a Kira**:

```json
{ "code": "rfi_answer_rejected", "message": "Hay respuestas invalidas; no se envio ninguna.",
  "details": { "i-inventado": "El item no pertenece a este RFI.",
               "i-doc": "Este item se responde subiendo documentos, no con texto." } }
```

#### Subir documentos (`11`)

**Body → Multipart Form**, fila `files` de tipo fichero → `muestras/documento.pdf`. Para subir
varios, añade más filas con el **mismo nombre** `files`. Validaciones locales verificadas:

| Qué envías | Respuesta |
|---|---|
| Un `.txt` a un item que sólo admite PDF | `422`, `details["nota.txt"]: "Tipo no admitido (text/plain). Permitidos: application/pdf."` |
| 3 archivos a un item con `max_files: 2` | `422`, `details["i-doc"]: "Este item admite como maximo 2 archivos."` |
| La parte con otro nombre (no `files`) | `400` "Falta la parte 'files'." |
| Un archivo a un item de texto (`14`) | `422` "El item no es de tipo documento." |
| Un archivo de más de 30 MB | `413 file_too_large` |

El enlace de `12` es una **credencial al portador que caduca en minutos**: ábrelo al momento, no lo
guardes. Si caduca, vuelve a pedirlo.

---

### 7.4 `04 Cuentas virtuales`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/virtual-accounts` | read.only | `200 []` | `200` + `vaId` si hay |
| 02 | `POST /api/virtual-accounts` | maker | `422` "La empresa todavia no puede abrir cuentas: revisa el estado del KYB…" | `201` + `vaId` |
| 03–06 | detalle, `refresh`, `balance`, `simulate-deposit` | read.only / maker | `422` "Cuenta virtual no encontrada." | `200` |
| 07 | `POST /api/virtual-accounts` | **approver** | `403` | `403` |
| 08 | `simulate-deposit` con `amount: 0`, `paymentType: "swift"` | maker | `400` con 2 campos | `400` |

Cuerpos: `02` → `{"description":"Operativa Juriscop","mode":"fiat","currency":"USD"}` ·
`06` → `{"amount":5000.00,"paymentType":"wire"}` (monto **`11`** = depósito `refunded`).

**Modo B:** la cuenta mueve dinero sólo con **`fundsReady: true`**. Si pasan más de 5 minutos sin
activarse, `activationDelayed: true`: el sandbox se colgó, no es un fallo del BFF.

---

### 7.5 `05 Depositos`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/deposits?limit=50` | read.only | `200 []` | `200` |
| 02 | `GET /api/virtual-accounts/{{vaId}}/deposits` | read.only | `422` "Cuenta virtual no encontrada." | `200` |
| 03 | `POST /api/virtual-accounts/{{vaId}}/deposits/sync` | read.only | `422` "Cuenta virtual no encontrada." | `200` con los depósitos sincronizados |

`03` trae de Kira los depósitos de la cuenta y los asienta junto a los del webhook, en las mismas
filas. Es la red de seguridad de un webhook perdido. En el sandbox Kira no devuelve depósitos aquí.

---

### 7.6 `06 Destinatarios`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/recipients` | read.only | `200 []` | `200` |
| 02–04 | `POST /api/recipients` WIRE / ACH / WALLET | maker | `422` "…no supero la verificacion (estado CREATED)." | `201` + ids |
| 05 | `GET /api/recipients/{{recipientId}}` | read.only | `422` "Destinatario no encontrado." | `200` |
| 06 | `POST …/{{recipientAchId}}/archive` | maker | `422` | `200` |
| 07 | WIRE con `routingNumber: "123"` | maker | `400` "El routing number debe tener 9 digitos" | `400` |
| 08 | WALLET **USDC en tron** | maker | `422` "…no supero la verificacion" ⚠️ | `422` "USDC no esta soportado en la red 'tron'…" |
| 09 | WIRE | **approver** | `403` | `403` |
| 10 | `GET /api/recipients/kira` | read.only | `422` "no dada de alta en Kira" | `200` lista tal como la tiene Kira |
| 11 | `GET /api/recipients/{{recipientId}}/kira` | read.only | `422` | `200` |

Respuesta de `10` (modo B): `localRecipientId: null` = existe en Kira pero no se dio de alta desde el portal.

```json
[ { "kiraRecipientId": "krec_1", "localRecipientId": "4b7e…", "type": "business", "name": "Acme Corp",
    "accountType": "WIRE", "maskedDestination": "****7890", "email": "pagos@acme.com", "createdAt": "…" } ]
```

Cuerpo de `02` (WIRE — `bankAddress` **objeto**, país **ISO-2**):

```json
{ "rail": "WIRE", "business": true, "companyName": "Acme Corp", "email": "pagos@acme.com", "phone": "+13055551234",
  "address": { "streetName": "1 Main St", "city": "New York", "state": "NY", "postalCode": "10001", "country": "US" },
  "routingNumber": "021000021", "swiftCode": "EXAMUS33XXX", "accountNumber": "1234567890", "accountKind": "checking",
  "bankName": "Example Bank, N.A.",
  "bankAddress": { "streetName": "1 Bank Plaza", "city": "New York", "state": "NY", "postalCode": "10001", "country": "US" },
  "docType": "ein", "docNumber": "12-3456789" }
```

ACH usa `bankAddressText` (texto); WALLET usa `token`, `network` y `walletAddress`, sin datos
bancarios. Los tres cuerpos completos están en la colección.

---

### 7.7 `07 Cotizaciones`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `POST /api/quotations` | maker | `422` "…no supero la verificacion" | `201` + `quotationId` |
| 02 | `GET /api/quotations?limit=50` | read.only | `200 []` | `200` |
| 03 | `GET /api/quotations/{{quotationId}}` | read.only | `422` "Cotizacion no encontrada." | `200` |
| 04 | `POST /api/quotations` con `{"amount":0}` | maker | `400` con 3 campos | `400` |

Cuerpo de `01`: `{"virtualAccountId":"{{vaId}}","recipientId":"{{recipientId}}","amount":1000.00}`.
`amount` es lo que **recibe** el destinatario; `totalDebitAmount`, lo que sale de la cuenta.
`secondsToExpiry` empieza en 900 (15 min).

---

### 7.8 `08 Pagos`

Maker-checker: el maker prepara, el approver autoriza.

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `POST /api/payouts` | maker | `422` "…no supero la verificacion" | `201` + `payoutId` |
| 02 | `GET /api/payouts?limit=50` | read.only | `200 []` | `200` |
| 03 | `GET /api/payouts/{{payoutId}}` | read.only | `422` "Pago no encontrado." | `200` |
| 04 | `POST …/approve` | **maker** | `403` | `403` |
| 05 | `POST …/approve` | approver | `422` | `200`, `approvalState: SUBMITTED` |
| 06 | `POST …/refresh` | read.only | `422` | `200` con el estado de Kira |
| 07 | `POST …/reject` con `{}` | approver | `400`, `details.reason` | `400` |
| 08 | `POST …/reject` | approver | `422` | `200` o `422` si ya se envió |
| 09 | `GET /api/payouts/{{payoutId}}` | **otra empresa** | `422` | `422` |
| 10 | `POST /api/payouts/preview` | maker | `422` "La cuenta virtual no existe." | `200` con el coste |
| 11 | `GET /api/payouts/kira?page=1&limit=20` | read.only | `422` "no dada de alta en Kira" | `200` historial en Kira |
| 12 | `GET /api/payouts/{{payoutId}}/events` | read.only | `422` | `200` línea de tiempo |

Cuerpo de `01` — **ids del portal**, no de Kira (el script quita `quotationId` si no cotizaste):

```json
{ "virtualAccountId": "{{vaId}}", "recipientId": "{{recipientId}}", "amount": 125.50, "currency": "USD", "quotationId": "{{quotationId}}" }
```

Al crear se comprueba que la cuenta y el destinatario **existen, son de tu empresa**, la cuenta está
abierta en Kira, el destinatario no está archivado y, si hay cotización, es de esa misma cuenta y
destinatario. Un id ajeno da `422 "La cuenta virtual no existe."` sin guardar nada.

En `03`, **`blockedByRfiId`** no nulo significa que un RFI abierto tiene el pago detenido.

Cuerpo de `05`:

```json
{ "comment": "Revisado por tesoreria", "natureOfPayment": "vendor", "memo": "Factura 42" }
```

Cuerpo de `10` (vista previa, **no reserva precio**):

```json
{ "virtualAccountId": "{{vaId}}", "recipientId": "{{recipientId}}", "amount": 1000.00 }
```

Respuesta de `12` (modo B):

```json
[ { "eventId": "e1", "status": "CREATED", "message": null, "createdAt": "2026-09-11T10:00:00Z" },
  { "eventId": "e2", "status": "PROCESSING", "message": "Enviado al banco", "createdAt": "2026-09-11T10:01:00Z" } ]
```

`11` admite `status` (`CREATED`, `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED`,
`IN_REVIEW`, `KYT_PENDING`), `fromDate` y `toDate`. Otro `status` da `422` antes de llamar a Kira.

---

### 7.9 `09 Catalogos`

| # | Petición | Rol | Modo A | Modo B |
|---|---|---|---|---|
| 01 | `GET /api/reference/countries` | read.only | `503 kira_not_configured` | `200` (cacheado 24 h) |

```json
[ { "name": "Colombia", "alpha3": "COL", "postalCodeFormat": "\\A\\d{6}\\Z",
    "subdivisions": [ { "name": "Antioquia", "code": "ANT" } ] } ]
```

`alpha3` (ISO-3) es el código de la empresa y sus UBOs. Los destinatarios usan ISO-2.

---

### 7.10 `10 Webhooks de Kira` *(firma HMAC, sin JWT)*

Cada petición construye y firma su cuerpo en el *Pre Request* script. **No edites la pestaña
Body**: la reemplaza el script. Para cambiar el evento, edita `payload` en el script.

| # | Evento | Responde | Qué deja en `webhooks_log` (modo A) |
|---|---|---|---|
| 01 | `payout.completed` | `200 {"status":"received"}` | `COMPLETED`; sin pago local que case |
| 02 | `payout.status_changed` (sobre V2) | `200` | `KYT_PENDING` |
| 03 | `user.verification.failed` | `200` | `REJECTED`; sin empresa local con ese `user_id` |
| 04 | `virtual_account.activated` | `200` | `approved` |
| 05 | `virtual_account.deposit_funds_received` | `200` | `COMPLETED` |
| 06 | `rfi.created` | `200` | `processed = 0`, `processing_error: "Falta KIRA_API_KEY…"` (relee el RFI en Kira) |
| 07 | firma `0000` | `401 {"error":"invalid_signature"}` | nada |

El `200` sólo significa **firma válida y evento encolado**. Para saber si se proyectó, mira §10.

---

## 8. Probar RFIs sin Kira

### 8.1 Insertar un RFI de prueba

Con la app arrancada:

```bash
mysql -uroot -p autransactional <<'SQL'
INSERT INTO rfis (id, tenant_id, kira_rfi_id, status, items_payload, due_date,
                  blocking_type, blocking_resource_id, created_at, updated_at)
VALUES ('rfi-demo-1', 'juriscop', 'rfi_demo_1', 'PENDING',
 '[{"item_id":"i-ein","answer_type":"identifier","answer_spec":{"format":"ein"},"status":"pending","prompt":"EIN de la empresa"},{"item_id":"i-doc","answer_type":"document","answer_spec":{"mime_types":["application/pdf"],"max_files":2},"status":"answered","prompt":"Acta de constitucion","documents":[{"document_id":"doc-1","file_name":"acta.pdf","mime_type":"application/pdf","size_bytes":1024}]}]',
 UTC_TIMESTAMP() + INTERVAL 14 DAY, 'transfer', 'kpo_demo', UTC_TIMESTAMP(), UTC_TIMESTAMP());
SQL
```

Usa **`UTC_TIMESTAMP()`**, no `NOW()`: la app lee las fechas como UTC y `NOW()` es hora local (5 h de desfase).

### 8.2 Ejecutar

`00 Sesion` → Run, y después `03 RFIs` → Run. Resultado verificado: la tabla de §7.3, columna
"Con RFI de prueba".

### 8.3 Variantes

- **Cerrado:** `UPDATE rfis SET status='NOT_RESOLVED' WHERE id='rfi-demo-1';` → `02` devuelve `[]`,
  `open: false`, y responder da `422 "Este RFI ya esta cerrado (NOT_RESOLVED) y no admite respuestas."`.
- **Vencido:** `UPDATE rfis SET due_date = UTC_TIMESTAMP() - INTERVAL 1 DAY WHERE id='rfi-demo-1';` → `overdue: true`.
- **Valor no escalar:** `PATCH` con `{"items":[{"itemId":"i-ein","answerValue":{"x":1}}]}` →
  `422`, `details["i-ein"]: "La respuesta debe ser texto, numero o booleano."`.

### 8.4 Borrarlo

```sql
DELETE FROM rfis WHERE id = 'rfi-demo-1';
```

---

## 9. Recorrido completo con el sandbox de Kira

Arranca con las credenciales (§3.1) y sigue este orden:

| Paso | Petición | Rol | Continúa cuando… |
|---|---|---|---|
| 1 | `00 Sesion` → Run | — | 6 tokens guardados |
| 2 | `09/01 Paises` | read.only | `200`: las credenciales funcionan |
| 3 | `01/02 Alta minima en Kira` | compliance | la respuesta trae `kiraUserId` |
| 4 | `01/03 Completar perfil` | compliance | — |
| 5 | `01/04 Refrescar` | read.only | mira `pendingFields`; si no está vacío, vuelve al paso 4 |
| 6 | `02/02 Alta UBO` (una vez) | compliance | `hasBeneficialOwner: true` |
| 7 | `02/04 Sincronizar con Kira` | compliance | `200` |
| 8 | `02/05 Enlaces de liveness` | compliance | abre cada enlace y completa la prueba en el sandbox |
| 9 | `01/04 Refrescar` (repetir) | read.only | `status: VERIFIED` **y** `readyForVirtualAccounts: true` |
| 10 | `04/02 Abrir cuenta` | maker | `201` |
| 11 | `04/04 Refrescar estado` (repetir) | read.only | **`fundsReady: true`** |
| 12 | `04/06 Simular deposito` → `04/05 Refrescar saldo` | maker / read.only | `availableBalance` > 0 |
| 13 | `06/02 Alta WIRE` | maker | `recipientId` guardado |
| 14 | `08/10 Vista previa` | maker | el coste cuadra |
| 15 | `07/01 Cotizar` | maker | `balanceSufficient: true`; tienes 15 min |
| 16 | `08/01 Crear pago` | maker | `PENDING_APPROVAL`, `priceLocked: true` |
| 17 | `08/05 Aprobar y enviar` | **approver** | `SUBMITTED`, `kiraPayoutId` |
| 18 | `08/12 Linea de tiempo` y `08/06 Refrescar` | read.only | estado terminal y `referenceNumber` |
| 19 | `08/11 Historial en Kira` | read.only | el pago aparece con `localPayoutId` |
| 20 | `03/03 Sincronizar RFIs` | compliance | si hay RFIs, la bandeja se llena |

> **Webhooks reales:** Kira sólo llama a una URL pública registrada. En local no llegarán sin un
> túnel; mientras tanto los `refresh`/`sync` hacen su trabajo y la carpeta `10` los simula.

---

## 10. Webhooks: comprobar qué pasó

```sql
SELECT event_type, resource_id, normalized_status, processed,
       LEFT(processing_error, 80) AS error, created_at
FROM webhooks_log ORDER BY created_at DESC LIMIT 10;
```

`processed = 1`: proyectado sin error. `processed = 0` + `processing_error`: guardado pero no
proyectado. Para que un webhook **cambie datos** tiene que existir el recurso local con ese id de
Kira. Ejemplo con un depósito (verificado):

```sql
INSERT INTO virtual_accounts (id, tenant_id, kira_account_id, bank, status, mode, currency,
                              balance_available, activated_event_seen, created_at, updated_at)
VALUES ('va-demo-1', 'juriscop', 'kva_demo_001', 'slovak_savings_bank', 'PENDING', 'FIAT', 'USD',
        0, false, UTC_TIMESTAMP(), UTC_TIMESTAMP());
```

`bank` es obligatorio: sin él la proyección falla con "La cuenta virtual necesita banco.". Lanza
`10/05`, espera 1–2 s y `05/01` mostrará el depósito (`grossAmount 5000`, `feeAmount 25`,
`netAmount 4975`); `04/01` mostrará la cuenta con `balanceStale: true`. Limpia:

```sql
DELETE FROM deposits WHERE virtual_account_id = 'va-demo-1';
DELETE FROM virtual_accounts WHERE id = 'va-demo-1';
```

---

## 11. Ejecutar todo de golpe

### 11.1 Runner de Bruno

Clic derecho sobre **AuTransactional BFF → Run → Run Collection**. Resultado verificado en modo A:
**84 peticiones, 84 ✓; 46/46 tests**. Las peticiones que dependen de Kira no tienen test de código.

### 11.2 CLI

```bash
cd ~/Documentos/AuTransactional/docs/bruno/AuTransactional
npx --yes @usebruno/cli@4.1.0 run -r --env local
npx --yes @usebruno/cli@4.1.0 run "00 Sesion" "03 RFIs" --env local          # algunas carpetas
npx --yes @usebruno/cli@4.1.0 run -r --env local --env-var baseUrl=http://localhost:8081   # otro puerto
```

Código de salida `0` = todos los tests pasaron.

---

## 12. Limpiar los datos de prueba

Vuelve al estado de la semilla **sólo en `juriscop`**:

```sql
DELETE FROM rfis             WHERE tenant_id = 'juriscop';
DELETE FROM payouts          WHERE tenant_id = 'juriscop';
DELETE FROM quotations       WHERE tenant_id = 'juriscop';
DELETE FROM recipients       WHERE tenant_id = 'juriscop';
DELETE FROM deposits         WHERE tenant_id = 'juriscop';
DELETE FROM virtual_accounts WHERE tenant_id = 'juriscop';
DELETE FROM ubos             WHERE tenant_id = 'juriscop';
```

> ⚠️ En **modo B** esto borra el espejo local de recursos que **siguen existiendo en Kira**. Kira no
> borra destinatarios: al recrearlos responde `202` (`alreadyExisted: true`).

`webhooks_log` y `audit_logs` son bitácoras: no hace falta limpiarlas.

---

## 13. Problemas frecuentes

| Síntoma | Causa y solución |
|---|---|
| `403` sin cuerpo | falta `Authorization`: ejecuta `00 Sesion` (las variables se pierden al cerrar Bruno) |
| `401 unauthorized` | token caducado (8 h) o la app se reinició con otro secreto: `00 Sesion` |
| `503 kira_not_configured` | arrancaste sin `KIRA_API_KEY`, `KIRA_CLIENT_ID` o `KIRA_PASSWORD` (esperado en modo A) |
| `503 OUT_OF_SERVICE` en `/actuator/health` | la app acaba de arrancar; repite en un segundo |
| `400` con HTML de Tomcat, o `422 "... no encontrado."` inesperado | una variable `{{…}}` sin definir (§5.3) |
| `404 not_found` | método o ruta mal escritos |
| `422 "La suma de participaciones no puede superar el 100 %"` | ejecutaste `02/02` varias veces (§7.2) |
| `422 "…no supero la verificacion (estado CREATED)"` | el KYB no está `VERIFIED`: modo A, o no completaste §9 |
| `422 "La cuenta virtual no existe."` al crear un pago | id de Kira o de otra empresa: usa el `id` de `/api/virtual-accounts` |
| Swagger muestra rutas `/api/v1/…` | corre la app antigua (p. ej. desde IntelliJ): párala y arranca con §3.1 |
| `401 invalid_signature` / `503 webhook_secret_not_configured` | secreto distinto del entorno / arrancaste sin `KIRA_WEBHOOK_SECRET` |
| `413 file_too_large` | un archivo de RFI supera 30 MB |
| Bruno no encuentra `muestras/documento.pdf` | vuelve a seleccionarlo en **Body → Multipart Form** |
| La app se cae sola | falta memoria: arranca con `-Xmx768m` (§3.1) y cierra aplicaciones pesadas |

---

## 14. Crear una petición nueva a mano

Ejemplo: RFIs abiertos con el rol de cumplimiento.

1. Clic derecho sobre **03 RFIs → New Request** · **Name:** `15 Abiertos como compliance` ·
   **URL:** `{{baseUrl}}/api/rfis` → **Create**.
2. Método `GET`. **Params** → `open` = `true`.
3. **Auth** → **Bearer Token** → `{{tokenCompliance}}`.
4. **Tests**:
   ```javascript
   test("responde 200", function () { expect(res.getStatus()).to.equal(200); });
   ```
5. **Ctrl+S** guarda (crea el `.bru`) y **Ctrl+Enter** envía.

Para guardar un id de la respuesta: **Script → Post Response**:

```javascript
if (res.getStatus() < 300 && res.getBody().id) { bru.setVar("miId", res.getBody().id); }
```
