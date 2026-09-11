# Importar la colección AuTransactional

## Bruno

La carpeta `docs/bruno/AuTransactional` **ya es** una colección Bruno nativa (tiene `bruno.json`), así que no hay que convertir nada:

1. Abre Bruno → **Collection** → **Open Collection**.
2. Selecciona la carpeta `docs/bruno/AuTransactional` (la que contiene `bruno.json`).
3. Arriba a la derecha, elige el entorno **local**.
4. Ejecuta primero la carpeta `00 Sesion` completa: guarda los tokens de los 5 roles (8 h).

> El script de `09 Verificacion de identidad / 01 Crear reto de voz` escribe `muestras/recording.txt` con `fs`. Eso solo funciona con Bruno en **Developer Mode** (Collection Settings → Script → Developer Mode). En Safe Mode el script avisa por consola y el fichero hay que escribirlo a mano.

## Postman

1. Postman → **Import** → arrastra los dos ficheros de esta carpeta:
   - `AuTransactional.postman_collection.json`
   - `AuTransactional.postman_environment.json`
2. Arriba a la derecha, selecciona el entorno **AuTransactional local**.
3. Ejecuta la carpeta `00 Sesion` en orden (o con el Collection Runner).

### Qué cambió en la conversión

| Bruno | Postman |
|---|---|
| `bru.setVar` / `bru.getVar` | `pm.collectionVariables.set` / `.get` |
| `bru.getEnvVar` | `pm.environment.get` |
| `res.getStatus()` / `res.getBody()` | `pm.response.code` / `pm.response.json()` |
| `test(...)` / `expect(...)` | `pm.test(...)` / `pm.expect(...)` |
| `req.setHeader(k, v)` | `pm.request.headers.upsert({key, value})` |
| `req.setBody(x)` | `pm.collectionVariables.set("rawBody", …)` y el body de la petición es `{{rawBody}}` |
| bloque `docs` | `description` de la petición/carpeta |
| `auth: bearer` + `auth:bearer { token }` | auth tipo *Bearer Token* por petición |

Las variables que los scripts van guardando (`tokenAdmin`, `tokenMaker`, `rfiId`, `vaId`, `payoutId`…) quedan declaradas como **variables de colección**, no de entorno — igual que hace `bru.setVar`.

### Dos cosas que hay que tocar a mano en Postman

1. **Multipart** (`09 Verificacion de identidad`: `03 Verificar reto de voz`, `08 Veredicto`, `09 Error - veredicto repetido`). Postman no acepta rutas de fichero importadas desde un JSON por seguridad: abre cada petición → Body → form-data y vuelve a seleccionar los ficheros de `docs/bruno/AuTransactional/muestras/`.
2. **`recording.txt`**. Postman no tiene acceso al sistema de ficheros: en `01 Crear reto de voz` el script imprime el `challengeNumber` por consola y hay que escribir `muestras/recording.txt` a mano antes de lanzar `03 Verificar reto de voz`.

Los webhooks (`10 Webhooks de Kira`) sí funcionan tal cual: el pre-request script construye el JSON, lo firma con `CryptoJS.HmacSHA256` (`require("crypto-js")` está disponible en el sandbox de Postman) y lo envía por `{{rawBody}}`. Arranca el BFF con `KIRA_WEBHOOK_SECRET=secreto-webhook-local`.

### Regenerar

Si cambia la colección Bruno, `bru2postman.py` vuelve a producir los dos JSON desde `docs/bruno/AuTransactional`.
