# AuTransactional BFF — Documentación técnica del código

**Fecha:** 11 de septiembre de 2026 · **Rama:** `main` (commit base `ed853d3` + cambios sin commitear de `percentage_fee`)
**Stack:** Java 21 · Spring Boot 4.1.1 · MySQL 8 · Maven · **169 clases de producción (11.697 líneas)** · **40 clases de prueba, 283 pruebas**

Este documento describe **todo** el código del repositorio: arquitectura, configuración, seguridad,
integración con Kira, dominio, casos de uso, API, webhooks, persistencia, errores, auditoría y pruebas.
Las secciones 1–17 explican cómo funciona y por qué; los anexos A–C son la referencia exhaustiva
generada desde el propio código (cada clase y método no privado, cada prueba y el DDL completo).

> Documentos relacionados: [`API-GUIA.md`](API-GUIA.md) (contrato HTTP campo a campo) ·
> [`GUIA-BRUNO.md`](GUIA-BRUNO.md) (pruebas manuales) · [`ARQUITECTURA.md`](ARQUITECTURA.md) (visión resumida) ·
> [`ESTADO.md`](ESTADO.md) (estado y pendientes) · [`kira-cuerpos-peticiones.json`](kira-cuerpos-peticiones.json) (cuerpos exactos enviados a Kira).

---

## Índice

1. [Propósito y alcance](#1-propósito-y-alcance)
2. [Stack y dependencias](#2-stack-y-dependencias)
3. [Estructura del repositorio](#3-estructura-del-repositorio)
4. [Arquitectura](#4-arquitectura)
5. [Configuración y arranque](#5-configuración-y-arranque)
6. [Seguridad](#6-seguridad)
7. [Integración con Kira](#7-integración-con-kira)
8. [Modelo de dominio](#8-modelo-de-dominio)
9. [Casos de uso (capa de aplicación)](#9-casos-de-uso-capa-de-aplicación)
10. [API REST](#10-api-rest)
11. [Webhooks de Kira](#11-webhooks-de-kira)
12. [Persistencia](#12-persistencia)
13. [Manejo de errores](#13-manejo-de-errores)
14. [Auditoría y logging](#14-auditoría-y-logging)
15. [Pruebas](#15-pruebas)
16. [Herramientas, documentación y operación](#16-herramientas-documentación-y-operación)
17. [Deuda técnica, defectos conocidos y riesgos](#17-deuda-técnica-defectos-conocidos-y-riesgos)
- [Anexo A. Referencia clase por clase](#anexo-a-referencia-clase-por-clase)
- [Anexo B. Catálogo de pruebas](#anexo-b-catálogo-de-pruebas)
- [Anexo C. DDL que espera Hibernate (MySQL)](#anexo-c-ddl-que-espera-hibernate-mysql)

---

## 1. Propósito y alcance

**AuTransactional** es un *Backend for Frontend* (BFF) para un portal B2B multiempresa que opera sobre
**KiraFin** (API `api.balampay.com`). Kira es una plataforma *white-label* de movimiento de dinero: el
integrador (nosotros) da de alta **sub-clientes** (empresas, `users` en Kira), Kira las verifica (KYB), les
abre **cuentas virtuales** en bancos de EE. UU., recibe **depósitos** y ejecuta **pagos** (payouts) hacia
**destinatarios** (recipients) por ACH, WIRE o WALLET (stablecoins). Además genera **RFIs** (solicitudes de
información) que pueden bloquear pagos o depósitos.

**Principio de diseño: el BFF es una capa delgada sobre Kira.** Todo el negocio vive en Kira y el front lo
consume a través del BFF. El BFF sólo aporta lo que Kira no puede dar a un navegador:

| Responsabilidad del BFF | Por qué no puede hacerlo el front ni Kira |
|---|---|
| Custodia de credenciales (`api_key`, `client_id`, `password`, token) | Son las credenciales maestras del integrador: en el navegador darían control total de la cuenta |
| Sesión y roles por empresa (JWT propio, RBAC) | Kira autentica al integrador, no a cada operador de cada empresa |
| Aislamiento multiempresa | Kira trata los recursos como globales del integrador |
| Traducción de ids del portal a ids de Kira | El front no debe conocer ni manipular ids de Kira |
| Maker-checker de pagos | Kira no ofrece aprobación en dos pasos a los integradores |
| Validación temprana de reglas de Kira | Muchas reglas sólo fallan en Kira al ejecutar (p. ej. riel incompatible), cuando ya se gastó la cotización |
| Espejo local de lo que la API no devuelve | Cuestionario KYB, motivos de rechazo, estados de liveness, `bank_address` completo… |
| Recepción de webhooks firmados | Kira entrega una sola vez, sin reintentos; un navegador no puede recibirlos |
| Idempotencia persistente | La clave debe sobrevivir a un reintento desde otro dispositivo |

---

## 2. Stack y dependencias

**Plataforma:** Java 21, Spring Boot 4.1.1 (`spring-boot-starter-parent`), Maven con wrapper (`./mvnw`),
Jackson 3 (`tools.jackson.*`), Hibernate 7.

| Dependencia (`pom.xml`) | Ámbito | Para qué se usa |
|---|---|---|
| `spring-boot-starter-webmvc` | compile | Controladores REST, multipart, `RestClient` |
| `spring-boot-starter-security` | compile | Cadena de filtros, `@PreAuthorize`, BCrypt |
| `spring-boot-starter-data-jpa` | compile | Entidades, repositorios Spring Data, transacciones |
| `spring-boot-starter-validation` | compile | `@NotBlank`, `@Email`, `@DecimalMin`… en los comandos |
| `spring-boot-starter-cache` | compile | Soporte de caché (el token y los catálogos usan Caffeine directamente) |
| `spring-boot-starter-actuator` | compile | `/actuator/health` e `info` |
| `com.auth0:java-jwt` 4.5.0 | compile | Emisión y verificación del JWT del BFF (HMAC256) |
| `com.github.ben-manes.caffeine:caffeine` | compile | Caché del token de Kira y del catálogo de países |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` 3.1.1 | compile | Swagger UI y `/v3/api-docs` |
| `org.projectlombok:lombok` | optional | `@Getter`, `@Setter`, `@NoArgsConstructor` en dominio y entidades (procesador configurado en `maven-compiler-plugin`) |
| `com.mysql:mysql-connector-j` | runtime | Driver MySQL |
| `com.h2database:h2` | test | Base en memoria (`MODE=MySQL`) para las pruebas |
| `spring-boot-starter-*-test` (cache, data-jpa, security, validation, webmvc) | test | JUnit 5, Mockito, MockMvc, `MockRestServiceServer` |

**Plugins:** `spring-boot-maven-plugin` (empaquetado y `spring-boot:run`) y `maven-compiler-plugin` con
Lombok como `annotationProcessorPath` en `compile` y `testCompile`.

---

## 3. Estructura del repositorio

```
AuTransactional/
├── pom.xml, mvnw, mvnw.cmd, .mvn/        Build Maven
├── src/main/java/com/example/autransactional/
│   ├── AuTransactionalApplication.java   Punto de entrada (@SpringBootApplication)
│   ├── domain/                           Lógica de negocio pura (48 ficheros)
│   │   ├── shared/      (7)   TenantId, Money, IdempotencyKey, Rail, PostalAddress, StatusNormalizer, DomainException
│   │   ├── tenant/     (14)   Tenant, TenantStatus, MissingFields, EligibleProduct, Ubo, UboRoster, LivenessStatus,
│   │   │                      OperatorUser, Role, RoleScope, UserStatus + repositorios
│   │   ├── account/     (8)   VirtualAccount, VirtualAccountMode/Status/Readiness, Deposit, DepositStatus + repositorios
│   │   ├── treasury/   (18)   Recipient, RecipientHolder, RecipientAccount, WalletToken, BankAccountKind, Quotation,
│   │   │                      QuotationRail/Status, FeeBreakdown, Payout, PayoutStatus/ApprovalState,
│   │   │                      NatureOfPayment, SupportingDocument + repositorios
│   │   └── compliance/  (5)   Rfi, RfiStatus, AuditLog + repositorios
│   ├── application/                      Casos de uso (38 ficheros)
│   │   ├── auth/        (1)   LoginUseCase
│   │   ├── tenant/      (7)   SubmitOnboardingService, SyncUbosService, KiraUserState, comandos y vistas
│   │   ├── account/     (6)   OpenVirtualAccountService, RecordDepositService, KiraDepositEvent, comandos y vistas
│   │   ├── treasury/   (14)   RegisterRecipientService, CreateQuoteService, ExecutePayoutService, KiraQuoteResponse, comandos y vistas
│   │   ├── compliance/  (5)   AnswerRfiService, RfiCommands, RfiView, RfiDocumentLink, RfiAnswerRejectedException
│   │   ├── shared/      (1)   IdempotencyKeyStore (clave de idempotencia en transacción propia)
│   │   ├── reference/   (2)   ReferenceCatalogService, CountryView
│   │   └── webhook/     (2)   ProcessWebhookUseCase, KiraWebhookEnvelope
│   ├── infrastructure/                   Adaptadores técnicos (65 ficheros)
│   │   ├── persistence/(36)   Entidades JPA, repositorios Spring Data, adaptadores de puertos, mappers
│   │   ├── kira/       (12)   KiraApiClient, KiraCredentialManager, KiraClientConfig, KiraProperties, KiraAmounts,
│   │   │                      KiraErrorParser, KiraApiException, KiraNotConfiguredException, KiraResponse,
│   │   │                      KiraAuthResponse, KiraFile, KiraWebhookVerifier
│   │   ├── security/    (6)   SecurityConfig, JwtService, JwtTenantFilter, TenantContext, AuthenticatedOperator, BffSecurityProperties
│   │   ├── bootstrap/   (3)   DevDataSeeder, DevSeedProperties, RequiredSecretsValidator
│   │   ├── config/      (2)   AsyncConfig, OpenApiConfig
│   │   ├── audit/       (1)   AuditTrail
│   │   ├── reconciliation/(6) 5 workers programados + package-info.java
│   └── interfaces/                       Entrada HTTP (12 ficheros)
│       ├── rest/       (11)   10 controladores + RestExceptionHandler
│       └── webhook/     (1)   KiraWebhookController
├── src/main/resources/
│   ├── application.yaml                  Configuración común
│   ├── application-dev.yaml              Desarrollo local
│   ├── application-cert.yaml             Certificación (sandbox de Kira)
│   └── application-prod.yaml             Producción
├── src/test/java/…                       40 clases de prueba (4.832 líneas)
├── src/test/resources/application.yaml   H2 en memoria y credenciales de prueba
└── docs/
    ├── DOCUMENTACION-CODIGO.md           Este documento
    ├── API-GUIA.md, GUIA-BRUNO.md, ARQUITECTURA.md, ESTADO.md
    ├── kira-cuerpos-peticiones.json      Cuerpos exactos enviados a Kira
    └── bruno/AuTransactional/            Colección de Bruno (84 peticiones)
```

---

## 4. Arquitectura

### 4.1 Capas y regla de dependencias

Arquitectura hexagonal con DDD ligero, organizada por **contextos acotados** (`tenant`, `account`,
`treasury`, `compliance`, más `shared`):

```
interfaces  ──►  application  ──►  domain  ◄──  infrastructure
(HTTP)           (casos de uso)    (reglas)     (JPA, Kira, seguridad)
```

| Capa | Contiene | Depende de | Nunca contiene |
|---|---|---|---|
| `domain` | Agregados, value objects, enums de estado, **interfaces de repositorio** (puertos) | Nada de Spring ni JPA (sólo Lombok `@Getter`) | Anotaciones JPA, HTTP, JSON |
| `application` | Servicios `@Service` con `@Transactional`, comandos (entrada) y vistas (salida) como `record` | `domain`; `KiraApiClient`, `AuditTrail` y `AuthenticatedOperator` de `infrastructure` (excepción consciente, §17) | Entidades JPA, controladores |
| `infrastructure` | Implementaciones de puertos (`Jpa*Repository`), entidades, cliente Kira, seguridad, configuración | `domain`, Spring, JPA, Jackson | Reglas de negocio |
| `interfaces` | `@RestController`, `@RestControllerAdvice`, ingreso de webhooks | `application`, seguridad | Lógica de negocio |

### 4.2 Recorrido de una petición

```
Cliente ──HTTP──► JwtTenantFilter ──► SecurityFilterChain (reglas públicas / authenticated)
                  (verifica JWT,        │
                   fija principal)      ▼
                                  @RestController (+ @PreAuthorize por rol, @Valid en el cuerpo)
                                        │ operator = @AuthenticationPrincipal
                                        ▼
                                  Servicio de aplicación @Transactional
                                   1. comprueba el rol (Role.canX)
                                   2. carga agregados filtrando por operator.tenantId()
                                   3. valida invariantes de dominio (lanza DomainException)
                                   4. traduce ids del portal → ids de Kira
                                   5. KiraApiClient (token, cabeceras, versión, idempotencia, errores)
                                   6. aplica la respuesta al agregado y guarda
                                   7. AuditTrail.record(...)
                                        │
                                        ▼
                                  View (record) ──► JSON ──► Cliente
                  Cualquier excepción ──► RestExceptionHandler ──► { code, message, details? }
```

### 4.3 Convenciones del código

| Convención | Detalle |
|---|---|
| **Rehidratación explícita** | Cada agregado tiene constructor de negocio (valida invariantes, estado inicial) y `static rehydrate(...)` para reconstruir desde BD. Los adaptadores `Jpa*Repository` o los `*Mapper` hacen la conversión entidad ↔ dominio |
| **Comandos y vistas** | Entrada: `XxxCommands.*` (records con Bean Validation). Salida: `XxxView` (records con `static from(...)`). Nunca se devuelven entidades ni `JsonNode` crudos de Kira, salvo campos libres documentados (`items` de RFI, `fees` de la vista previa) |
| **Errores de negocio** | `DomainException(mensaje en español)` → `422 business_rule_violation` |
| **Estados de Kira** | Enum local + `fromWire(String)` tolerante: compara sin mayúsculas (`StatusNormalizer`), mapea sinónimos y nunca lanza por un valor desconocido |
| **Nada retrocede desde un estado terminal** | `applyRemoteStatus` de Payout, Deposit, Ubo y Rfi ignoran transiciones de terminal a no terminal: los eventos llegan sin orden garantizado |
| **Tenant en la consulta** | Toda lectura va por `findByIdAndTenant(id, tenantId)`: un id de otra empresa devuelve "no encontrado", no 403 |
| **Ids** | El front ve ids del BFF (UUID). Los servicios guardan también `kira*Id` y traducen antes de llamar |
| **Importes** | `BigDecimal` siempre; `DECIMAL(18,4)` en BD; conversiones de formato de Kira sólo en `KiraAmounts` |
| **Enums en BD** | `@Enumerated(STRING)` + `@JdbcTypeCode(SqlTypes.VARCHAR)`: VARCHAR, nunca `ENUM` nativo de MySQL |
| **Comentarios** | Explican el porqué (casi siempre una trampa de la API de Kira), en español sin tildes |
| **Pruebas** | Nombres en español describiendo la regla (`elCreadorNoPuedeAprobarSuPropioPago`) |

---

## 5. Configuración y arranque

### 5.1 Perfiles

`spring.profiles.active` = `${SPRING_PROFILES_ACTIVE:dev}`.

| Aspecto | `dev` | `cert` | `prod` | pruebas (`src/test/resources`) |
|---|---|---|---|---|
| Base de datos | MySQL local (`DB_URL` o `localhost:3306/autransactional`) | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | ídem | H2 en memoria `MODE=MySQL` |
| `ddl-auto` | `${DB_DDL_AUTO:update}` | `validate` | `validate` | `create-drop` |
| `show-sql` | `true` | `false` | `false` | — |
| Kira `base-url` | `${KIRA_BASE_URL:https://api.balampay.com/sandbox}` | ídem sandbox | `${KIRA_BASE_URL:https://api.balampay.com}` | `http://localhost:0/sandbox` |
| Kira `bank` / `sandbox` | `slovak_savings_bank` / `true` (por defecto común) | ídem | `${KIRA_BANK:portage}` / `false` | — |
| JWT secret | `${BFF_JWT_SECRET:secreto-de-desarrollo-…}` | obligatorio | obligatorio | fijo de pruebas |
| Semilla de datos | `${BFF_DEV_SEED:true}` | `false` | `false` | — |
| Validación de secretos | no | **sí** (`RequiredSecretsValidator`) | **sí** | — |
| Logging | `com.example.autransactional: DEBUG` | `INFO` (+ DEBUG a un paquete inexistente, §17) | `root: WARN`, app `INFO` | — |
| Swagger | `/swagger-ui.html` | `/swagger-ui.html` | **desactivado** (`api-docs.enabled: false`) | activo |

### 5.2 Propiedades

| Propiedad | Variable de entorno | Valor por defecto | Uso |
|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | — | `30MB` | Límite por archivo (documentos de RFI) |
| `spring.servlet.multipart.max-request-size` | — | `100MB` | Límite por petición |
| `spring.jpa.open-in-view` | — | `false` | Sin sesión JPA abierta en la vista |
| `spring.jackson.default-property-inclusion` | — | `non_null` | Los campos nulos no se serializan |
| `kira.api-key` | `KIRA_API_KEY` | vacío | Cabecera `x-api-key` |
| `kira.client-id` | `KIRA_CLIENT_ID` | vacío | Cuerpo de `POST /auth` |
| `kira.password` | `KIRA_PASSWORD` | vacío | Cuerpo de `POST /auth` |
| `kira.webhook-secret` | `KIRA_WEBHOOK_SECRET` | vacío | Verificación HMAC de webhooks |
| `kira.api-version` | `KIRA_API_VERSION` | `2026-04-14` | Cabecera `X-Api-Version` (RFIs usan `2026-06-01`) |
| `kira.token-ttl-seconds` | — | `3600` | Vida del token de Kira |
| `kira.token-refresh-margin-seconds` | — | `300` | Se renueva antes: la caché expira a los 3300 s |
| `kira.connect-timeout-ms` / `read-timeout-ms` | — | `5000` / `30000` | Timeouts del `RestClient` |
| `kira.bank` | `KIRA_BANK` | `slovak_savings_bank` | Banco de las cuentas virtuales (depende del entorno) |
| `kira.sandbox` | `KIRA_SANDBOX` | `true` | Habilita simular depósitos |
| `kira.base-url` | `KIRA_BASE_URL` | por perfil | Raíz de la API de Kira |
| `bff.security.jwt-secret` | `BFF_JWT_SECRET` | vacío (dev: valor de desarrollo) | Clave HMAC256 del JWT (≥ 32 caracteres fuera de dev) |
| `bff.security.jwt-issuer` | `BFF_JWT_ISSUER` | `autransactional-bff` | Claim `iss` |
| `bff.security.token-expiration-ms` | `BFF_JWT_TTL_MS` | `28800000` (8 h) | Vida del JWT |
| `bff.reconciliation.enabled` | `BFF_RECONCILIATION_ENABLED` | `true` (`false` en pruebas) | Registra o no los 4 workers |
| `bff.reconciliation.initial-delay-ms` | — | `60000` | Espera tras el arranque |
| `bff.reconciliation.payouts-ms` / `payout-batch` | — | `600000` / `50` | Pagos en vuelo por pasada |
| `bff.reconciliation.quotations-ms` | — | `300000` | Cotizaciones vencidas |
| `bff.reconciliation.liveness-ms` | — | `3600000` | Enlaces de liveness vencidos |
| `bff.reconciliation.rfis-ms` | — | `900000` | Sincronización de RFIs |
| `bff.reconciliation.webhooks-ms` / `webhook-batch` | — | `1800000` / `50` | Eventos almacenados sin proyectar |
| `bff.dev.seed` | `BFF_DEV_SEED` | `true` en dev | Activa `DevDataSeeder` |
| `bff.dev.seed-password` | `BFF_DEV_SEED_PASSWORD` | `Dev12345!` | Contraseña de los operadores sembrados |
| `management.endpoints.web.exposure.include` | — | `health,info` | Endpoints de Actuator expuestos |
| `management.endpoint.health.show-details` | — | `never` | Sin detalle en `/actuator/health` |

> ⚠️ `application-dev.yaml` ya **no** lleva la contraseña: toma `${DB_PASSWORD:…}` del entorno, así que
> hay que exportar `DB_PASSWORD` antes de arrancar el perfil `dev`. La contraseña antigua **sigue en el
> historial de Git** y el repositorio es público: falta rotarla. Ver §17.

Las propiedades se enlazan a records `@ConfigurationProperties`: `KiraProperties` (`kira.*`),
`BffSecurityProperties` (`bff.security.*`) y `DevSeedProperties` (`bff.dev.*`).

### 5.3 Beans de configuración

| Clase | Beans / efecto |
|---|---|
| `KiraClientConfig` | `RestClient kiraRestClient`: `java.net.http.HttpClient` con timeout de conexión, **sin seguir redirecciones**, `JdkClientHttpRequestFactory` con timeout de lectura y `baseUrl`. `Cache<String,String> kiraTokenCache`: Caffeine, `expireAfterWrite = max(60, ttl − margen)` s, tamaño máximo 1 |
| `AsyncConfig` | `@EnableAsync`, `@EnableScheduling`. `ThreadPoolTaskExecutor webhookExecutor`: 4 hilos base, 16 máximo, cola de 500, prefijo `kira-webhook-`, espera a terminar tareas al apagar |
| `SecurityConfig` | `SecurityFilterChain` (§6.1), `PasswordEncoder` = `BCryptPasswordEncoder`, `@EnableMethodSecurity` |
| `OpenApiConfig` | `OpenAPI` con título, descripción de grupos y esquema `bearer-jwt` (HTTP bearer, formato JWT) aplicado globalmente |

### 5.4 Arranque

1. `AuTransactionalApplication.main` → Spring Boot.
2. **`RequiredSecretsValidator`** (sólo `cert` y `prod`, `InitializingBean`): exige `KIRA_API_KEY`,
   `KIRA_CLIENT_ID`, `KIRA_PASSWORD`, `KIRA_WEBHOOK_SECRET` y `BFF_JWT_SECRET`; lista **todas** las que faltan
   en un único `IllegalStateException` y exige además que el secreto JWT tenga al menos 32 caracteres. La
   aplicación no arranca si falla.
3. **`DevDataSeeder`** (sólo `dev` con `bff.dev.seed=true`, `ApplicationRunner`, `@Transactional`), idempotente:
   - calcula el hash BCrypt de la contraseña **una sola vez**;
   - asegura las 5 filas de `roles` (`name` = `Role.dbName()`, `description`, `scope`);
   - crea, si no existen, las empresas `juriscop` (Juriscop, NIT 900123456-1), `bankvision` (Bankvision,
     900234567-2) y `au-colombia` (AU Colombia, 900345678-3), jurisdicción Colombia, **estado `CREATED`** y sin
     `kira_user_id`;
   - crea un operador por rol y empresa: id `<empresa>:<rol>`, correo `<rol con puntos>@<empresa>.test`
     (p. ej. `treasury.maker@juriscop.test`), nombre según rol (Admin, Operador, Tesorero, Cumplimiento,
     Consulta), apellido = nombre de la empresa, estado `ACTIVE`;
   - registra en log cuántas organizaciones y operadores creó.
4. La readiness de Actuator pasa a `UP` cuando terminan los `ApplicationRunner` (durante ese instante
   `/actuator/health` puede responder `503 OUT_OF_SERVICE`).

---

## 6. Seguridad

### 6.1 Cadena de filtros

`SecurityConfig.filterChain`:

- **CSRF desactivado** (API sin estado, sin cookies) y sesión `STATELESS`.
- Rutas **públicas**: `POST /api/auth/login`, `/api/webhooks/**` (se autentican por HMAC), `/actuator/health`,
  `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`.
- **Todo lo demás exige autenticación.**
- `JwtTenantFilter` se inserta antes de `UsernamePasswordAuthenticationFilter`.

### 6.2 JWT del BFF (`JwtService`)

| Elemento | Valor |
|---|---|
| Algoritmo | HMAC256 con `bff.security.jwt-secret` |
| `iss` | `bff.security.jwt-issuer` |
| `sub` | correo del operador |
| `uid` | id del operador (`juriscop:treasury_maker`) |
| `tenant_id` | id de la empresa |
| `role` | constante del enum `Role` (`TREASURY_MAKER`) |
| `iat` / `exp` | emisión / emisión + `token-expiration-ms` (8 h) |

`verify(token)` exige firma, emisor y vigencia; si falta `tenant_id`, `role` o `uid` lanza
`IllegalArgumentException`. Devuelve `AuthenticatedOperator(userId, email, tenantId, role)`.

### 6.3 `JwtTenantFilter`

1. **No filtra** `/api/webhooks/*` (`shouldNotFilter`).
2. Si hay `Authorization: Bearer <token>`:
   - token válido → `TenantContext.set(tenantId)` y `Authentication` con el principal `AuthenticatedOperator`
     y la autoridad `ROLE_<ROL>`;
   - token inválido → limpia contextos y **responde directamente `401`**
     `{"code":"unauthorized","message":"Token invalido o expirado."}` sin continuar la cadena.
3. Sin cabecera, la petición sigue como anónima: si la ruta exige autenticación, Spring Security responde
   **`403` sin cuerpo**.
4. En `finally` limpia `TenantContext` y `SecurityContextHolder` (evita que otro request herede el tenant
   del hilo del pool).

`TenantContext` es un `ThreadLocal<TenantId>` con `set/get/require/clear`. Hoy sólo lo usa el filtro: los
servicios toman la empresa de `operator.tenantId()` (§17).

### 6.4 Roles y permisos

`Role` (enum) — el `dbName` vive en la tabla `roles`; la constante la usan el JWT y `@PreAuthorize`:

| Constante | `dbName` | Descripción | `canCreatePayout` | `canApprovePayout` | `canManageCompliance` |
|---|---|---|---|---|---|
| `ADMIN` | `admin` | Administrador General de la Empresa Cliente | ✓ | ✓ | ✓ |
| `TREASURY_MAKER` | `tesoreria_maker` | Registra borradores, destinatarios y cotiza | ✓ | | |
| `TREASURY_APPROVER` | `tesoreria_approver` | Aprueba y autoriza pagos (maker-checker) | | ✓ | |
| `COMPLIANCE_INTERNAL` | `compliance_internal` | Ficha 360, UBOs, liveness y RFIs | | | ✓ |
| `READ_ONLY` | `read_only` | Sólo lectura | | | |

Todos tienen `RoleScope.TENANT` (existe `SYSTEM` para soporte de plataforma, sin uso todavía).

**Doble barrera:** `@PreAuthorize("hasAnyRole(...)")` en el controlador **y** comprobación `role.canX()` en
el servicio (`DomainException` → 422). Matriz completa por endpoint en §10.

### 6.5 Aislamiento multiempresa

- Todas las consultas locales filtran por `tenant_id` dentro del propio query (`findByIdAndTenant`,
  `findByTenant…`). Un id ajeno se comporta igual que uno inexistente.
- Las lecturas que pasan a Kira **descartan cualquier fila de otro `user_id`** aunque se envíe el filtro:
  historial de pagos (`kiraHistory`), sincronización de RFIs (`sync`), depósitos de una cuenta
  (`syncFromKira` compara `virtual_account_id`) y la atribución de RFIs por webhook.
- Los ids enviados por el front se resuelven dentro de la empresa **antes** de traducirlos a ids de Kira.

### 6.6 Login (`LoginUseCase`)

1. Busca el operador por correo (sin distinguir mayúsculas). Si no existe → `"Credenciales invalidas."`.
2. Compara la contraseña con BCrypt. Si no coincide → **el mismo** mensaje (no revela qué cuentas existen).
3. `OperatorUser.assertCanLogin()`: sólo `UserStatus.ACTIVE` puede entrar (`SUSPENDED` y `DISABLED` no).
4. Carga la empresa y `Tenant.assertActive()` (una empresa `REJECTED` no opera).
5. Devuelve `LoginResult(accessToken, expiresIn, email, role, tenantId, tenantName)`.

La columna `users.mfa_secret` existe pero **no hay MFA implementado**.

### 6.7 Webhooks (HMAC)

`KiraWebhookVerifier`: HMAC-SHA256 en hexadecimal sobre los **bytes crudos** del cuerpo con
`kira.webhook-secret`, comparación en **tiempo constante** (`MessageDigest.isEqual`). Detalle del flujo en §11.

---

## 7. Integración con Kira

### 7.1 `KiraApiClient` — adaptador HTTP único

Responsabilidades que ningún caso de uso repite:

| Regla | Implementación |
|---|---|
| `x-api-key` en toda petición | cabecera fija en `doExchange` |
| `Authorization: Bearer <token>` | `KiraCredentialManager.getAccessToken()` |
| `X-Api-Version` | `kira.api-version` (`2026-04-14`), con dos excepciones por petición: **RFIs y sus documentos** (`RFI_API_VERSION`, esas rutas no existen antes) y la **cotización** (`QUOTATION_API_VERSION`), cuyo desglose `fees[]`/`totals` sólo existe desde `2026-06-01` |
| `Idempotency-Key` | parámetro `IdempotencyKey` en `createUser`, `createVirtualAccount`, `createRecipient`, `executePayout` |
| Cuerpo | JSON; si el cuerpo es `MultiValueMap` → `multipart/form-data` |
| Reintento ante `401` | invalida el token y repite **una** vez |
| Errores | cuerpo de error → `KiraErrorParser.parse` → `KiraApiException` |
| Respuesta vacía | `{}`; respuesta ilegible → `KiraApiException(200, "unparseable_response")` |
| Query | `withQuery` añade sólo los parámetros no nulos |

**Operaciones expuestas y quién las usa:**

| Método del cliente | HTTP de Kira | Usado por |
|---|---|---|
| `getUser` | `GET /v1/users/{id}` | `SubmitOnboardingService` (refresh) |
| `createUser` | `POST /v1/users` + idempotencia | `SubmitOnboardingService.register` |
| `updateUser` | `PUT /v1/users/{id}` | `SubmitOnboardingService.completeProfile` (y UBOs vía onboarding) |
| `requestLivenessLink` | `POST /v1/users/{id}/liveness-link` | `SyncUbosService.requestLivenessLinks` |
| `listVirtualAccounts` | `GET /v1/virtual-accounts?user_id=` | `OpenVirtualAccountService.adoptExisting` |
| `getVirtualAccount` | `GET /v1/virtual-accounts/{id}` | `OpenVirtualAccountService` |
| `createVirtualAccount` | `POST /v1/virtual-accounts` + idempotencia | `OpenVirtualAccountService.open` |
| `getVirtualAccountBalance` | `GET /v1/virtual-accounts/{id}/balance` | `OpenVirtualAccountService.refreshBalance` |
| `simulateDeposit` | `POST /v1/virtual-accounts/{id}/simulate-deposit` | `OpenVirtualAccountService.simulateDeposit` |
| `listAccountDeposits` | `GET /v1/virtual-accounts/{id}/deposits` (limit+offset) | `RecordDepositService.syncFromKira` |
| `listRecipients` | `GET /v1/recipients?user_id=` | `RegisterRecipientService.listInKira` |
| `getRecipient` | `GET /v1/recipients/{id}` | `RegisterRecipientService.getInKira` |
| `createRecipient` → `KiraResponse` | `POST /v1/recipients` + idempotencia | `RegisterRecipientService.register` |
| `createQuotation` | `POST /v1/quotations` · **`2026-06-01`** | `CreateQuoteService.create` |
| `previewPayout` | `POST /v1/virtual-accounts/{id}/payout/preview` | `ExecutePayoutService.preview` |
| `executePayout` | `POST /v1/virtual-accounts/{id}/payout` + idempotencia | `ExecutePayoutService.approveAndSubmit` |
| `getPayout` | `GET /v1/payouts/{id}` | `ExecutePayoutService.refreshFromKira`, `events` |
| `listPayouts` | `GET /v1/payouts` (page+limit) | `ExecutePayoutService.kiraHistory` |
| `listRfis` | `GET /v1/rfis` (limit+offset) · `2026-06-01` | `AnswerRfiService.sync` |
| `getRfi` | `GET /v1/rfis/{id}` · `2026-06-01` | `AnswerRfiService` |
| `answerRfiItems` | `PATCH /v1/rfis/{id}/items` · `2026-06-01` | `AnswerRfiService.answer` |
| `uploadRfiDocuments` | `POST /v1/rfis/{id}/items/{item}/documents` multipart `files` · `2026-06-01` | `AnswerRfiService.uploadDocuments` |
| `removeRfiDocument` | `DELETE …/documents/{doc}` · `2026-06-01` | `AnswerRfiService.removeDocument` |
| `getRfiDocumentLink` | `GET …/documents/{doc}` · `2026-06-01` | `AnswerRfiService.documentLink` |
| `listCountries` | `GET /v1/countries` | `ReferenceCatalogService` |
| `exchange` / `exchangeWithStatus` | genérico | uso interno |

### 7.2 `KiraCredentialManager` — token

1. `getAccessToken()` lee la caché Caffeine (`kira-access-token`); si no hay token, `authenticate()`.
2. `authenticate()` exige `apiKey`, `clientId` y `password` no vacíos; si falta alguno lanza
   **`KiraNotConfiguredException("Falta KIRA_… ")`** (→ `503 kira_not_configured`).
3. `POST /auth` con `x-api-key` y cuerpo `{client_id, password}`; un error HTTP se convierte con
   `KiraErrorParser`; sin `data.access_token` → `IllegalStateException`.
4. `invalidate()` borra el token (lo llama el cliente ante un `401`).

`KiraAuthResponse` modela `{ message, data: { access_token, expires_in, token_type } }`.

### 7.3 Errores de Kira

- **`KiraErrorParser`** tolera las formas `{error, details}`, `{message}`, `{code, error, message}` y
  `{statusCode, error, message}`: `code` si es texto; `message` = primero no vacío de `message`, `error`,
  `detail`; si no, `details` serializado; si no, el cuerpo crudo o `"Error HTTP <n>"`.
- **`KiraApiException(statusCode, code, message, rawBody)`** con `isUnauthorized()`.
- **`KiraNotConfiguredException`**: faltan credenciales.

### 7.4 `KiraAmounts` — importes

| Función | Regla |
|---|---|
| `fromMinor(amount, precision)` | `amount / 10^precision` con `BigDecimal` (5000000, 2 → 50000.00) |
| `toMinor(amount, precision)` | inverso, redondeo `HALF_UP`, `longValueExact` |
| `amountString(amount)` | 2 decimales exactos; `≤ 0` → `DomainException` |
| `markupForQuotation(fixedFee, bps)` | `{ fixed_minor: <entero en centavos>, percentage_bps: <int> }` |
| `markupForPayout(fixedFee, bps)` | `{ fixed_fee: "15.00", percentage_fee: "<bps/10000 con 4 decimales>" }` — fracción 0–1 (`"0.0050"` = 0,5 %) |
| `precisionOf(currency)` | `USDC`/`USDT` → 6; resto → 2 |
| Constantes | `FIAT_PRECISION = 2`, `STABLECOIN_PRECISION = 6`, `MAX_PERCENTAGE_BPS = 10000` |

### 7.5 Otras piezas

- **`KiraResponse(status, body)`**: `alreadyExisted()` = `status == 202` (destinatario ya existente);
  `data()` desenvuelve `data` si existe.
- **`KiraFile(fileName, contentType, content)`**: archivo para multipart.
- **Cuerpos exactos** de cada llamada: [`kira-cuerpos-peticiones.json`](kira-cuerpos-peticiones.json).

---

## 8. Modelo de dominio

### 8.1 `shared`

| Tipo | Descripción y reglas |
|---|---|
| `TenantId(value)` | Id de la organización; toda consulta filtra por él |
| `Money(amount, currency)` | Importe con moneda, escala 4 (`DECIMAL(18,4)`). `of`, `zero`, `plus`/`minus` (exigen misma moneda), `isPositive`, `isLessThan` |
| `IdempotencyKey(value)` | UUID v4. `newKey()`, `of(value)`. Una clave por intención de negocio |
| `Rail` | `ACH`, `WIRE`, `WALLET`. `from` estricto, `fromWireOrNull` tolerante |
| `PostalAddress(streetName, city, state, postalCode, country)` | `assertIso2Country()` (destinatarios usan ISO-2); `isBlank()` |
| `StatusNormalizer` | `normalize` (trim + mayúsculas), `matches` (sin distinguir mayúsculas) |
| `DomainException` | Violación de una invariante → 422 |

### 8.2 `tenant`

#### `Tenant` — la empresa cliente (contraparte del `user` de Kira)

| Campo | Significado |
|---|---|
| `id`, `name`, `taxId`, `jurisdiction` (por defecto "Colombia") | Identidad |
| `kiraUserId` | Id de `POST /v1/users`; ata KYB, cuentas y pagos |
| `status: TenantStatus` | Estado del KYB |
| `eligibleProducts: List<EligibleProduct>` | Productos de Kira y su elegibilidad |
| `missingFields: MissingFields` | Lo que Kira sigue pidiendo, por producto |
| `verificationTriggered` | Si el KYB se disparó (el GET no lo devuelve; una vez `true` no vuelve a `false`) |
| `onboardingPayload` | Objeto completo enviado a Kira (el siguiente `PUT` se construye sobre él) |
| `onboardingIdempotencyKey` | Clave del alta (reservada una sola vez) |
| `rejectionReason` | Motivo de rechazo (sólo llega por webhook; máx. 500 caracteres) |

| Método | Regla |
|---|---|
| constructor | nombre obligatorio; estado `CREATED`; sin productos ni campos pendientes |
| `reserveOnboardingKey()` | genera la clave sólo si no existe |
| `linkKiraUser(id)` | id obligatorio; no se reasigna a otro id distinto |
| `isRegisteredInKira()` / `assertRegisteredInKira()` | `kiraUserId != null` / `"La empresa todavia no esta dada de alta en Kira."` |
| `applyRemoteState(status, missing, products, triggered)` | aplica sólo lo no nulo; `triggered` sólo pasa a `true` |
| `isReadyFor(product)` | `VERIFIED` **y** campos del producto completos **y** producto `eligible` |
| `assertVerificationInProgress()` | registrada y (`verificationTriggered` o estado ≠ `CREATED`) |
| `rejectVerification(reason)` | estado `REJECTED` + motivo |
| `assertActive()` | estado distinto de `REJECTED` |
| `assertCanOperateTreasury()` | activa y `VERIFIED` (`"…todavia no supero la verificacion (estado X)."`) |

**`TenantStatus`**: `CREATED → VERIFYING → REVIEW → VERIFIED | REJECTED`. `fromWire`: `APPROVED→VERIFIED`,
`DECLINED→REJECTED`, `PENDING`/`IN_REVIEW→REVIEW`, desconocido o nulo → `CREATED`. `canOperate()` = no `REJECTED`.

**`MissingFields(byProduct)`**: mapa inmutable producto → campos. `forProduct(p)` = campos de `general` ∪
campos de `p` sin duplicados; `isCompleteFor`, `isEmpty`, `products`.

**`EligibleProduct(productCode, eligible, missingFields, unsupportedReason)`**: constantes
`USA_VIRTUAL_ACCOUNTS = "usa-virtual-accounts"` y `EDD_REQUIRED = "enhanced_due_diligence_required"`;
`requiresEnhancedDueDiligence()`.

#### `Ubo` — beneficiario final, director o firmante

Campos: nombre, apellido, documento, `ownershipPercentage` (0–100), `roleInCompany` (por defecto
"Beneficiario Final"), `hasOwnership`, `hasControl`, `signer`, `politicallyExposed`, `countryOfBirth` (ISO-3,
obligatorio, en mayúsculas), `personReferenceId` (Kira), `livenessStatus`, `livenessLink`, `livenessExpiresAt`.

| Método | Regla |
|---|---|
| `isBeneficialOwner()` | `hasOwnership` **y** porcentaje ≥ 5 (`BENEFICIAL_OWNER_THRESHOLD`). El cargo no cuenta |
| `describeRole(...)` | país de nacimiento obligatorio |
| `assignLivenessLink(link, expiresAt)` | enlace obligatorio; vuelve a `PENDING` |
| `applyLivenessStatus(s)` | no retrocede desde `COMPLETED`/`FAILED` |
| `expireLivenessLink()` / `isLivenessLinkExpired(now)` | vencimiento del enlace (7 días) |

**`LivenessStatus`**: `PENDING`, `COMPLETED`, `EXPIRED`, `FAILED`; `fromWire`: `APPROVED`/`PASSED`/`SUCCESS→COMPLETED`,
`DECLINED`/`REJECTED→FAILED`; finales: `COMPLETED`, `FAILED`.

**`UboRoster(members)`** — reglas sobre el grupo: `beneficialOwners`, `totalOwnership`, `hasBeneficialOwner`,
`pendingLiveness`, `livenessComplete` (todos los beneficiarios en `COMPLETED`) y
`assertReadyForVerification()`: al menos un miembro, al menos un beneficiario final, suma ≤ 100 %.

#### Operadores

- **`OperatorUser(id, tenantId, email, passwordHash, firstName, lastName, role, status, mfaSecret)`**:
  `assertCanLogin`, `assertBelongsTo`, `fullName`, `isActive`.
- **`UserStatus`**: `ACTIVE` (único que puede entrar), `SUSPENDED`, `DISABLED`.
- **`Role`**, **`RoleScope`**: §6.4.

### 8.3 `account`

#### `VirtualAccount`

Campos: `currency`, `mode` (inmutable), `bank`, `description`, `kiraAccountId`, `bankName`, `accountNumber`,
`routingNumber`, `status`, `balanceAvailable`, `balanceRefreshedAt`, `activatedEventSeen`,
`openingIdempotencyKey`, `createdAt`.

| Método | Regla |
|---|---|
| `reserveOpeningKey()` | clave de apertura, una sola vez |
| `linkKiraAccount(id)` | enlaza con Kira |
| `describeBank(name, number, routing)` | sólo sobrescribe lo que llega con valor (un evento sin número no borra el conocido) |
| `applyRemoteStatus`, `markActivatedEventSeen` | estado y señal de fondos-listos |
| `refreshBalance(available, at)` | proyección local del saldo |
| `markBalanceStale()` / `isBalanceStale()` | un depósito acreditado invalida el saldo cacheado (no se suma localmente) |
| `isFundsReady()` / `assertFundsReady()` | delega en `VirtualAccountReadiness` |
| `isActivationDelayed(now)` | no lista para fondos y más de 5 min (`ACTIVATION_GRACE`) desde `createdAt` |
| `isOpenInKira()` | tiene `kiraAccountId` |

- **`VirtualAccountMode`**: `FIAT`, `CRYPTO` (`wireValue` en minúsculas).
- **`VirtualAccountStatus`**: `PENDING`, `ACTIVE`, `INACTIVE`, `FAILED`; `fromWire`:
  `APPROVED`/`ACTIVATING`/`ACTIVATED→ACTIVE`, `DEACTIVATED→INACTIVE`, `DECLINED`/`REJECTED→FAILED`.
- **`VirtualAccountReadiness.isFundsReady(status, accountNumber, activatedEventSeen)`**: `true` si se vio el
  evento `virtual_account.activated`; `false` si el estado es `declined`, `deactivated` o `failed`; si no,
  `true` sólo con número de cuenta real (no vacío y distinto del centinela `PENDING-ACT-ACCOUNT`).

#### `Deposit`

Tres importes independientes (`grossAmount`, `feeAmount`, `netAmount`), `currency`, `kiraDepositId`,
`senderName`, `senderAccount`, `rail`, `status`, `microdeposit`.

| Método | Regla |
|---|---|
| `applyRemoteStatus(s)` | no retrocede desde `FAILED`/`REFUNDED` |
| `restate(gross, fee, net)` | corrige importes con un evento más completo |
| `creditsBalance()` | `COMPLETED` y no microdepósito |
| `markAsMicrodeposit`, `describeSender`, `linkKiraDeposit`, `net()`, `gross()` | — |

**`DepositStatus`**: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED` (terminales: `FAILED`, `REFUNDED`).
`fromWire`: `RETURNED`/`REVERSED→REFUNDED`; `DECLINED`/`REJECTED`/`KYT_REJECTED→FAILED`;
`PROCESSING`/`IN_TRANSIT`/`IN_REVIEW`/`KYT_PENDING→PENDING`; nulo o desconocido → `COMPLETED`.
`fromEventName`: `deposit_funds_in_transit→PENDING`, `deposit_funds_failed→FAILED`,
`deposit_returned→REFUNDED`, `deposit_funds_received`/`microdeposit_funds_received`/`deposit_funds_in_destination→COMPLETED`
(o el estado del payload si viene).

### 8.4 `treasury`

#### Destinatarios

- **`Recipient`**: `holder`, `account`, `address`, `kiraRecipientId`, `status`, `replacedByRecipientId`.
  Constructor: titular y cuenta obligatorios; si el riel no es `WALLET`, dirección obligatoria en ISO-2.
  `getRail()` (del tipo de cuenta), `getNetwork()` (sólo wallet), `getName()`, `linkKiraRecipient`,
  `replaceWith(id)` (archiva apuntando al reemplazo), `archive`, `isActive`, `isRegisteredInKira`,
  `assertUsable()` (activo **y** registrado en Kira).
- **`RecipientHolder(business, firstName, lastName, companyName, email, phone)`**: `company(...)` exige
  razón social; `person(...)` exige nombre y apellido; teléfono ≤ 16; `wireType()` = `business`/`individual`;
  `displayName()`.
- **`RecipientAccount`** (interfaz sellada, oneOf por `account_type`):
  - `Ach(routingNumber, accountNumber, kind, bankName, bankAddressText, docType, docNumber)` — routing de 9 dígitos, `bank_address` texto;
  - `Wire(routingNumber, swiftCode, accountNumber, kind, bankName, bankAddress, docType, docNumber)` — SWIFT de 8 u 11, `bank_address` objeto;
  - `Wallet(token, network, address, docType, docNumber)` — valida el par token/red.
- **`WalletToken`**: `USDC` (polygon, solana), `USDT` (polygon, solana, **tron**), `COPM` (`COPm`, polygon);
  `assertSupportedOn(network)`.
- **`BankAccountKind`**: `CHECKING`, `SAVINGS`. **`RecipientStatus`**: `ACTIVE`, `ARCHIVED` (local).

#### `Quotation` — precio en firme (TTL 900 s)

Campos: `virtualAccountId`, `recipientId`, `rail`, `originAmount` (lo que recibe el destinatario), `fees`,
`expiresAt`, `kiraQuoteId`, `destinationAmount`, `destinationCurrency`, `exchangeRate`, `totalDebitAmount`,
`balanceSufficient`, `rateSource`, `feesSnapshot`, `status`.

| Método | Regla |
|---|---|
| constructor | importe > 0 y vencimiento obligatorio; `totalDebitAmount = originAmount + totalFee` estimado |
| `applyKiraQuote(...)` | exige `quote_id` (sin él es un *preview* no redimible); aplica lo que devolvió Kira sin recalcular |
| `hasConsistentTotals()` | bruto = origen + comisiones |
| `usesFallbackRate()` | `rateSource` distinto de `kraken` |
| `assertUsable(now)` | no ejecutada y no vencida (si vence, pasa a `EXPIRED`) |
| `assertRedeemable(now)` | usable, con `kiraQuoteId` y con `balanceSufficient` |
| `markExecuted()`, `expire()`, `secondsToExpiry(now)` (nunca negativo) | — |

- **`QuotationStatus`**: `ACTIVE`, `EXPIRED`, `EXECUTED`.
- **`QuotationRail`**: `ACH_STANDARD`, `ACH_SAME_DAY` (ACH), `WIRE_DOMESTIC` (WIRE), `TRON`, `SOLANA`,
  `POLYGON` (WALLET). `defaultFor(rail, network)`, `validFor(rail)`, `fromNetwork`, `from`,
  `assertMatches(recipientRail)` (evita `RECIPIENT_ACCOUNT_TYPE_MISMATCH` al pagar).
- **`FeeBreakdown(kiraFee, platformFee, totalFee)`**: escala 4, no negativos, `totalFee = kiraFee + platformFee`.
  `standard()` = 15 + 15 (estimación), `fromTotals(kiraRevenue, clientMarkup)`, `requestedPlatformMarkup()` = 15,
  `totalDebitFor(origin)`.

#### `Payout` — pago con maker-checker

Campos: `kiraUserId`, `virtualAccountId`, `recipientId` (ids del portal), `amount: Money`, `fees`,
`idempotencyKey`, `makerUserId`, `quotationId`, `quotationExpiresAt`, `approvalState`, `status`,
`approverUserId`, `rejectionReason`, `kiraPayoutId`, `errorCode`, `referenceNumber`, `paymentMethod`.

**Máquina de aprobación (`PayoutApprovalState`, sólo BFF):**

```
PENDING_APPROVAL ──approve (aprobador ≠ creador, cotización vigente)──► APPROVED ──markAsSubmitted──► SUBMITTED
        │
        └──reject (con motivo)──► REJECTED
```

**Estado en Kira (`PayoutStatus`):** `NOT_SUBMITTED` (local) → `CREATED` → `PENDING` / `PROCESSING` /
`KYT_PENDING` / `IN_REVIEW` → `COMPLETED` | `FAILED` | `EXPIRED` (terminales). `UNKNOWN` para valores
desconocidos (no terminal). `fromWire`: `RETURNED`, `CANCELLED`, `CANCELED → FAILED`. `isInFlight()`:
`CREATED`, `PENDING`, `PROCESSING`, `KYT_PENDING`, `IN_REVIEW`.

| Método | Regla |
|---|---|
| constructor | importe > 0, creador obligatorio, `PENDING_APPROVAL` + `NOT_SUBMITTED` |
| `attachQuotation(quotation, now)` | cotización usable y de la misma cuenta y destinatario; hereda sus comisiones reales |
| `approve(approverId, now)` | sólo desde `PENDING_APPROVAL`; aprobador distinto del creador; cotización no vencida |
| `reject(approverId, reason)` | sólo desde `PENDING_APPROVAL` |
| `assertSubmittable(now)` | aprobado, no enviado antes y cotización vigente |
| `markAsSubmitted(kiraId, status)` | exige `APPROVED`; pasa a `SUBMITTED`; estado desconocido → `CREATED` |
| `applyRemoteStatus(s, errorCode)` | ignora `UNKNOWN` y retrocesos desde terminal |
| `fail(reason)` | `FAILED` con código de error |
| `grossAmountToSend(quotation)` | con cotización: `totalDebitAmount`; sin ella: importe + comisiones estimadas. **Kira descuenta las comisiones del monto enviado** |
| `isPriceLocked()` | hay cotización |
| `describeRemote(reference, method)` | IMAD / ACH trace / UETR y método de pago |

- **`NatureOfPayment`**: `VENDOR`, `POBO`, `FIRST_PARTY`, `SPOT_3P`, `SPOT_1P`, `RELATED_ENTITIES`, `OTHER`;
  `requiresSupportingDocuments()` salvo `FIRST_PARTY`.
- **`SupportingDocument(type, file)`**: `type` ∈ `invoice`, `other`; `file` sólo *data URI* (no URL), ≤ 3 MB;
  `assertValid(documents, nature, cryptoFunded)` (máx. 2; lista vacía rechazada).

### 8.5 `compliance`

#### `Rfi`

Campos: `kiraRfiId`, `status`, `itemsPayload` (JSON de items tal cual Kira), `dueDate`, `blockingType`,
`blockingResourceId`, `createdAt`, `updatedAt`.

```
         Kira genera
             │
             ▼
PENDING ◄──(item devuelto)── ANSWERED
   │  (todo respondido)──────►  │
   │                            ├──► RESOLVED      (terminal)
   └────────────────────────────┴──► NOT_RESOLVED  (terminal: vencido o rechazado)
```

| Método | Regla |
|---|---|
| constructor | `itemsPayload` obligatorio; `PENDING` |
| `assertAcceptsAnswers()` | estado abierto (`PENDING` o `ANSWERED`) |
| `applyRemoteStatus(s, items)` | un RFI terminal no se reabre; actualiza items si llegan |
| `describeDueDate(d)` | sólo si llega (el plazo no se prorroga) |
| `describeBlocking(type, resourceId)` | transferencia o depósito bloqueado |
| `isOverdue(now)` | abierto y con plazo vencido |

**`RfiStatus`**: `fromWire` exacto para los 4 valores; `NOT-RESOLVED`, `NOTRESOLVED`, `EXPIRED→NOT_RESOLVED`;
desconocido → `PENDING` (mejor visible que oculto).

#### `AuditLog`

Record inmutable: `id`, `tenantId` y `userId` opcionales (acciones de sistema), `userRole`, `action`,
`resourceType`, `resourceId`, `changes` (JSON, máx. 4000), `ipAddress`, `createdAt`. Nunca secretos ni
datos personales.

---

## 9. Casos de uso (capa de aplicación)

Todos reciben `AuthenticatedOperator` y trabajan dentro de su empresa. Los que escriben son
`@Transactional`; los de lectura, `@Transactional(readOnly = true)`.

### 9.1 `LoginUseCase`

Ver §6.6.

### 9.2 `SubmitOnboardingService` — KYB de la empresa

| Método | Pasos |
|---|---|
| `status` | Devuelve `OnboardingView` desde el estado local (no llama a Kira) |
| `register(RegisterBusiness)` | 1) rol `canManageCompliance`; 2) `tenant.assertActive`; 3) si ya está en Kira devuelve el estado sin llamar; 4) cuerpo `{type: business, business_legal_name, email, source_of_funds, external_id: tenantId}`; 5) reserva y guarda la clave de idempotencia; 6) `POST /v1/users`; 7) `linkKiraUser`, guarda el payload, aplica estado; 8) auditoría OK. Si Kira falla: auditoría ERROR y relanza |
| `completeProfile(CompleteProfile)` | 1) rol; 2) activa y registrada; 3) **fusión superficial** del payload guardado con el nuevo (una clave nueva reemplaza entera a la guardada: `associated_persons` debe ir completo); 4) `PUT /v1/users/{id}`; 5) guarda payload y estado; 6) auditoría; 7) **relee con `GET`** porque el estado real lo confirma el recurso |
| `refresh` | Registrada → `GET /v1/users/{id}` → aplica estado |

`KiraUserState.from(json)` normaliza POST, PUT y GET: `id`, `status`, `missing_fields` (objeto producto →
lista), `eligible_products[]` (`product_code`, `eligible`, `missing_fields`, `unsupported_reason`) y
`verification_triggered` (nulo si no viene).

`OnboardingView`: `tenantId`, `name`, `kiraUserId`, `status`, `verificationTriggered`,
`pendingFields` (= `missingFields.forProduct("usa-virtual-accounts")`), `eligibleProducts`,
`readyForVirtualAccounts`, `enhancedDueDiligenceRequired`.

### 9.3 `SyncUbosService` — beneficiarios finales

| Método | Pasos |
|---|---|
| `list` | `UboView.Roster` (miembros, suma, hay beneficiario, liveness completo) |
| `save(SaveUbo)` | Rol; sin `id` crea, con `id` carga dentro de la empresa; `describeDocument` + `describeRole`; auditoría `tenant.ubo_saved`. **No llama a Kira** |
| `syncToKira` | Rol; `roster.assertReadyForVerification()`; construye `associated_persons[]` (`first_name`, `last_name`, `has_ownership`, `ownership_percentage`, `has_control`, `is_signer`, `pep_status`, `country_of_birth`, `title`, y si existen `document_type`, `document_number`, `person_reference_id`); lo envía con `onboarding.completeProfile`; auditoría `tenant.ubos_synced` |
| `requestLivenessLinks(urls)` | Rol; `tenant.assertVerificationInProgress()`; `redirect` sólo si llegan **ambas** URLs; `POST …/liveness-link`; cada enlace se asigna por `person_reference_id` o, si no, por nombre completo; vencimiento `expires_at` o +7 días; auditoría |
| `applyLivenessResult(personRef, status)` | Proyección del webhook `user.liveness_completed`: busca por referencia y aplica el estado |

### 9.4 `OpenVirtualAccountService` — cuentas virtuales

| Método | Pasos |
|---|---|
| `list`, `get` | Cuentas de la empresa → `VirtualAccountView` |
| `open(OpenAccount)` | 1) rol `canCreatePayout` o `canManageCompliance`; 2) `tenant.isReadyFor(usa-virtual-accounts)`; 3) crea la cuenta local con moneda (por defecto USD), modo y **banco de configuración**; 4) reserva y guarda clave; 5) `POST /v1/virtual-accounts {user_id, type: US_BANK, bank, mode, description?}`; 6) aplica respuesta y guarda; 7) auditoría. Un **`409`** adopta la cuenta existente (`adoptExisting`: lista por `user_id`, toma el primer `id` y relee la cuenta individual) |
| `refresh` | Abierta en Kira → `GET /v1/virtual-accounts/{id}` → aplica; si está demorada, WARN |
| `refreshBalance` | `GET …/balance`; `available_balance` numérico; un **`400` no es error** (sigue activándose) y devuelve el último saldo |
| `simulateDeposit` | Sólo con `kira.sandbox=true` (si no, `422` sin llamar); `{amount: 2 decimales, payment_type: wire|ach}`; auditoría; refresca saldo |

`applyRemote`: enlaza `id`, `describeBank(bank_name, account_number, routing_number)`, estado y saldo si viene.

`VirtualAccountView`: `id`, `kiraAccountId`, `status`, `mode`, `bank`, `bankName`, `description`,
`accountNumber`, `routingNumber`, `currency`, `availableBalance`, `balanceRefreshedAt`, `balanceStale`,
**`fundsReady`**, **`activationDelayed`**, `createdAt`.

### 9.5 `RecordDepositService` — depósitos

| Método | Pasos |
|---|---|
| `list(limit)` | Depósitos de la empresa (máx. 200) |
| `listByAccount(id, limit)` | Cuenta de la empresa → sus depósitos (máx. 200) |
| `apply(KiraDepositEvent)` | Proyección idempotente por `kira_deposit_id`: ignora eventos sin ids; busca la cuenta por `kiraAccountId` (si no existe, INFO y sale); crea o actualiza el depósito; si acredita saldo, `markBalanceStale` en la cuenta |
| `syncFromKira(accountId)` | Cuenta de la empresa abierta en Kira → pagina `GET …/deposits` (100 por página, máx. 20 páginas) → `KiraDepositEvent.fromResource` → descarta otras cuentas → `apply` |
| `listForTenant` | Lectura interna para el reconciliador |

`KiraDepositEvent.from(eventName, payload)` lee cada campo en `snake_case` y `camelCase`
(`deposit_id`/`depositId`/`internalPaymentId`/`id`…); `fromResource` lee la forma REST (`sender.name`,
`fees.total_fees`, `payment_rail`).

`DepositView`: `id`, `kiraDepositId`, `virtualAccountId`, `grossAmount`, `feeAmount`, `netAmount`, `currency`,
`senderName`, `senderAccount`, `rail`, `status`, `microdeposit`, `creditsBalance`, `createdAt`, `updatedAt`.

### 9.6 `RegisterRecipientService` — destinatarios

| Método | Pasos |
|---|---|
| `list` | Sólo **activos** de la empresa, ordenados por nombre |
| `get` | Uno de la empresa |
| `register(RegisterRecipient)` | 1) rol `canCreatePayout`; 2) empresa verificada y registrada; 3) construye titular (empresa o persona) y cuenta según riel (valida routing, SWIFT, par token/red, dirección ISO-2); 4) clave nueva; 5) `POST /v1/recipients` (`user_id`, `type`, nombres, contacto, `address`, `account` con `account_type` y sus campos; `bank_address` texto en ACH y objeto en WIRE); 6) lee `recipient_id` (o `id`); 7) guarda; 8) auditoría (marca "ya existía" si fue `202`) |
| `archive(id, replacedBy?)` | Rol; con reemplazo (de la misma empresa) enlaza y archiva; sin él, archiva |
| `listInKira` | Registrada → `GET /v1/recipients?user_id=` → `KiraRecipientView` con cuenta enmascarada y `localRecipientId` si existe en la empresa |
| `getInKira(id)` | Destinatario local registrado → `GET /v1/recipients/{kiraId}` |

`RecipientView`: `id`, `kiraRecipientId`, `name`, `rail`, `network`, `bankName`, `maskedDestination`
(`****` + últimos 4), `status`, `registeredInKira`, `alreadyExisted`, `replacedByRecipientId`, `bankAddress`
(WIRE, del espejo local), `createdAt`.

### 9.7 `CreateQuoteService` — cotizaciones

`create(CreateQuote)`: 1) rol `canCreatePayout`; 2) empresa verificada; 3) cuenta de la empresa con
`assertFundsReady` y abierta en Kira; 4) destinatario de la empresa `assertUsable`; 5) riel: el del
destinatario por defecto o el pedido, validado con `assertMatches` y la red; 6) cotización local con
`FeeBreakdown.standard()` y vencimiento +900 s; 7) `POST /v1/quotations` con `virtual_account_id` **de Kira**,
`amount`, `rail`, `inverse: true`, `from_held_balance: true`, `client_markup {fixed_minor: 1500,
percentage_bps: 0}`, `target {currency (la pedida o la de la cuenta), network (si wallet)}`;
8) `KiraQuoteResponse.from` y `applyKiraQuote`; 9) WARN si no cuadran los totales o si la tasa es de
contingencia; 10) guarda y audita. `list(limit)` y `get(id)` leen dentro de la empresa.

`KiraQuoteResponse.from`: `quote_id`, `quote_expires_at`, `source` y `recipient` en unidades menores
(con `precision` o por moneda), `conversion.rate`, `conversion.rate_source`, `totals.kira_revenue_total` y
`totals.client_markup_total` como comisiones, `balance_sufficient`, y `feesSnapshot = {fees, totals}`.

`QuotationView`: incluye `totalDebitAmount`, `balanceSufficient`, `fallbackRate` y `secondsToExpiry`.

### 9.8 `ExecutePayoutService` — pagos

| Método | Pasos |
|---|---|
| `create(CreatePayout)` | 1) rol; 2) empresa verificada y registrada; 3) cuenta de la empresa abierta en Kira y destinatario usable; 4) clave nueva; 5) pago con `kiraUserId` **de la empresa**, ids del portal y comisiones estimadas; 6) con `quotationId`: debe ser de la misma cuenta y destinatario y se ata (hereda comisiones); 7) guarda y audita `payout.created` |
| `approveAndSubmit(id, ApprovePayout?)` | 1) rol `canApprovePayout`; 2) carga el pago; 3) si hay cotización, `assertRedeemable`; 4) `approve` (aprobador ≠ creador); 5) guarda y audita `payout.approved`; 6) **envío**: `assertSubmittable`, cuenta con fondos listos, destinatario usable con id de Kira; cuerpo (`recipient_id` de Kira, `amount` bruto, `quote_id` **o** `client_markup`, `nature_of_payment`, `supporting_documents`, `extra_info {memo ≤255, internal_notes ≤1000}`); `POST /v1/virtual-accounts/{kiraAccountId}/payout` con la clave del pago; lee `id` o `payout_id`; `markAsSubmitted`, `describeRemote`; marca la cotización `EXECUTED`; audita `payout.submitted OK`. Si Kira falla: `fail`, audita ERROR y relanza |
| `reject(id, reason)` | Rol aprobador; `payout.reject`; auditoría |
| `refreshFromKira(id)` | Sin `kiraPayoutId` devuelve el estado local; si no, `GET /v1/payouts/{id}` → `applyRemoteStatus(status, error_code)` y `describeRemote(reference_number, payment_method)` |
| `list(limit)` (máx. 100), `get(id)` | Con `blockedByRfiId` si un RFI abierto lo detiene |
| `preview(PreviewPayout)` | Rol maker; cuenta y destinatario de la empresa registrados; `POST …/payout/preview` con `amount`, `recipient_id` de Kira, `inverse_calculation` (por defecto `true`) y `client_markup`; devuelve `amount`, `currency`, `recipientAmount`, `recipientCurrency`, `fees` |
| `events(id)` | `events[]` de `GET /v1/payouts/{id}` (vacío si no se envió) |
| `kiraHistory(status, page, limit, from, to)` | Empresa registrada; valida `status` contra la lista de Kira (otro → 422 sin llamar); `page ≥ 1`, `limit` 1–100; `GET /v1/payouts`; descarta filas de otro `user_id`; enlaza `localPayoutId` |

### 9.9 `AnswerRfiService` — RFIs

| Método | Pasos |
|---|---|
| `list(onlyOpen)`, `get(id)` | `RfiView` con items como mapas, contadores y bloqueo resuelto a pago o depósito de la empresa |
| `sync` | Rol; empresa registrada; pagina `GET /v1/rfis?user_id=&limit=100&offset=` (máx. 20 páginas); **descarta** entradas no atribuibles a la empresa (por `user_id`, pago o depósito bloqueado); completa con `GET` si la entrada no trae items; `upsert` por `kira_rfi_id` (nunca reasigna a otra empresa); auditoría |
| `refresh(id)` | `GET /v1/rfis/{kiraId}` → aplica |
| `answer(id, AnswerItems)` | Rol; RFI abierto; validación **por item** (repetido, ajeno, de tipo documento, valor no escalar) → `RfiAnswerRejectedException` sin llamar; `PATCH …/items {items: [{item_id, answer_value}]}`; `409` → relee, guarda y `422`; `422` → errores por `item_id`; tras éxito relee (si falla la relectura, WARN) y audita. `@Transactional(noRollbackFor = DomainException.class)` |
| `uploadDocuments(id, itemId, files)` | Rol; RFI abierto; item de tipo documento; valida cantidad (`answer_spec.max_files` o 20), tamaño (30 MB), vacío y MIME (`answer_spec.mime_types` o PDF/JPEG/PNG/HEIC/WebP); multipart a Kira; `409`/`422` como arriba; relee y audita |
| `removeDocument(id, itemId, documentId)` | Rol; RFI abierto; item documento; `DELETE`; `422` (último archivo) por item; relee y audita |
| `documentLink(id, itemId, documentId)` | Item documento de la empresa → `{downloadUrl, expiresAt}`; la URL no se registra |
| `syncForTenant(tenantId)` | Lo mismo que `sync` pero sin operador (lo usa el worker de reconciliación); devuelve cuántos RFIs asentó |
| `applyWebhook(kiraRfiId, rawStatus)` | Aplica el estado del evento si el RFI existe; relee siempre de Kira; atribuye por RFI local o por `user_id`/bloqueo; `upsert` |

`RfiView`: `id`, `kiraRfiId`, `status`, `open`, `overdue`, `dueDate`, `totalItems`, `pendingItems`, `items`,
`blocking {type, kiraResourceId, payoutId, payoutStatus, depositId, depositStatus}`, `createdAt`, `updatedAt`.

### 9.10 `ReferenceCatalogService`

`countries()`: caché Caffeine de 24 h (máx. 10 entradas) sobre `GET /v1/countries` → `CountryView(name,
alpha3, postalCodeFormat, subdivisions[{name, code}])`. Un fallo no se cachea.

### 9.11 `ProcessWebhookUseCase`

Ver §11.

### 9.12 `IdempotencyKeyStore` (`application/shared`)

Dos métodos `persistNow(Tenant)` y `persistNow(VirtualAccount)`, ambos
`@Transactional(propagation = REQUIRES_NEW)`: abren su propia transacción, guardan el agregado con la
clave ya reservada y confirman antes de volver. Los llaman `SubmitOnboardingService.register` y
`OpenVirtualAccountService.open` justo antes de la llamada a Kira, para que el rollback del caso de
uso no borre la clave. Vive en una clase aparte porque una llamada interna al propio servicio no pasa
por el proxy de Spring y la propagación no se aplicaría.

---

## 10. API REST

Base `/api`. Autenticación `Authorization: Bearer <JWT>` salvo lo indicado. Respuestas JSON sin campos nulos.
Contrato campo a campo y ejemplos: [`API-GUIA.md`](API-GUIA.md).

| Método | Ruta | Roles (`@PreAuthorize`) | Controlador → servicio | Llama a Kira |
|---|---|---|---|---|
| POST | `/api/auth/login` | **público** | `AuthController.login` → `LoginUseCase.login` | no |
| GET | `/api/auth/me` | autenticado | `AuthController.me` (del JWT) | no |
| GET | `/api/onboarding` | autenticado | `OnboardingController.status` | no |
| POST | `/api/onboarding` → **201** | ADMIN, COMPLIANCE_INTERNAL | `.register` | `POST /v1/users` |
| PUT | `/api/onboarding` | ADMIN, COMPLIANCE_INTERNAL | `.completeProfile` | `PUT` + `GET /v1/users/{id}` |
| POST | `/api/onboarding/refresh` | autenticado | `.refresh` | `GET /v1/users/{id}` |
| GET | `/api/ubos` | autenticado | `UboController.list` | no |
| POST | `/api/ubos` | ADMIN, COMPLIANCE_INTERNAL | `.save` | no |
| POST | `/api/ubos/sync` | ADMIN, COMPLIANCE_INTERNAL | `.sync` | `PUT` + `GET /v1/users/{id}` |
| POST | `/api/ubos/liveness-links` | ADMIN, COMPLIANCE_INTERNAL | `.requestLivenessLinks` | `POST …/liveness-link` |
| GET | `/api/rfis?open=` | autenticado | `RfiController.list` | no |
| GET | `/api/rfis/{id}` | autenticado | `.get` | no |
| POST | `/api/rfis/sync` | ADMIN, COMPLIANCE_INTERNAL | `.sync` | `GET /v1/rfis` (+ detalle) |
| POST | `/api/rfis/{id}/refresh` | ADMIN, COMPLIANCE_INTERNAL | `.refresh` | `GET /v1/rfis/{id}` |
| PATCH | `/api/rfis/{id}/items` | ADMIN, COMPLIANCE_INTERNAL | `.answer` | `PATCH …/items` + `GET` |
| POST | `/api/rfis/{id}/items/{itemId}/documents` (multipart `files`) | ADMIN, COMPLIANCE_INTERNAL | `.uploadDocuments` | `POST …/documents` + `GET` |
| DELETE | `/api/rfis/{id}/items/{itemId}/documents/{documentId}` | ADMIN, COMPLIANCE_INTERNAL | `.removeDocument` | `DELETE …/documents/{doc}` + `GET` |
| GET | `/api/rfis/{id}/items/{itemId}/documents/{documentId}/link` | autenticado | `.documentLink` | `GET …/documents/{doc}` |
| GET | `/api/virtual-accounts` | autenticado | `VirtualAccountController.list` | no |
| GET | `/api/virtual-accounts/{id}` | autenticado | `.get` | no |
| POST | `/api/virtual-accounts` → **201** | ADMIN, TREASURY_MAKER, COMPLIANCE_INTERNAL | `.open` | `POST /v1/virtual-accounts` (+ lista/detalle si 409) |
| POST | `/api/virtual-accounts/{id}/refresh` | autenticado | `.refresh` | `GET /v1/virtual-accounts/{id}` |
| POST | `/api/virtual-accounts/{id}/balance` | autenticado | `.refreshBalance` | `GET …/balance` |
| POST | `/api/virtual-accounts/{id}/simulate-deposit` | ADMIN, TREASURY_MAKER | `.simulateDeposit` | `POST …/simulate-deposit` + balance |
| GET | `/api/deposits?limit=50` | autenticado | `DepositController.list` | no |
| GET | `/api/virtual-accounts/{id}/deposits?limit=50` | autenticado | `.listByAccount` | no |
| POST | `/api/virtual-accounts/{id}/deposits/sync` | autenticado | `.syncFromKira` | `GET …/deposits` |
| GET | `/api/recipients` | autenticado | `RecipientController.list` | no |
| GET | `/api/recipients/{id}` | autenticado | `.get` | no |
| POST | `/api/recipients` → **201** | TREASURY_MAKER, ADMIN | `.register` | `POST /v1/recipients` |
| POST | `/api/recipients/{id}/archive` | TREASURY_MAKER, ADMIN | `.archive` | no |
| GET | `/api/recipients/kira` | autenticado | `.listInKira` | `GET /v1/recipients` |
| GET | `/api/recipients/{id}/kira` | autenticado | `.getInKira` | `GET /v1/recipients/{id}` |
| GET | `/api/quotations?limit=50` | autenticado | `QuotationController.list` | no |
| GET | `/api/quotations/{id}` | autenticado | `.get` | no |
| POST | `/api/quotations` → **201** | TREASURY_MAKER, ADMIN | `.create` | `POST /v1/quotations` |
| GET | `/api/payouts?limit=50` | autenticado | `PayoutController.list` | no |
| GET | `/api/payouts/{id}` | autenticado | `.get` | no |
| POST | `/api/payouts` → **201** | TREASURY_MAKER, ADMIN | `.create` | no |
| POST | `/api/payouts/{id}/approve` | TREASURY_APPROVER, ADMIN | `.approve` | `POST …/payout` |
| POST | `/api/payouts/{id}/reject` | TREASURY_APPROVER, ADMIN | `.reject` | no |
| POST | `/api/payouts/{id}/refresh` | autenticado | `.refresh` | `GET /v1/payouts/{id}` |
| GET | `/api/payouts/{id}/events` | autenticado | `.events` | `GET /v1/payouts/{id}` |
| POST | `/api/payouts/preview` | TREASURY_MAKER, ADMIN | `.preview` | `POST …/payout/preview` |
| GET | `/api/payouts/kira?status=&page=1&limit=20&fromDate=&toDate=` | autenticado | `.kiraHistory` | `GET /v1/payouts` |
| GET | `/api/reference/countries` | autenticado | `ReferenceController.countries` | `GET /v1/countries` (cache 24 h) |
| POST | `/api/webhooks/kira` | **público (HMAC)** | `KiraWebhookController.receive` | según evento |

Además: `GET /actuator/health` (público), `GET /swagger-ui.html` y `GET /v3/api-docs` (públicos salvo en prod).

**Total: 47 operaciones sobre 40 rutas en 11 controladores.** Los grupos de Swagger (`@Tag`) son:
`0. Catalogos`, `1. Sesion`, `1.1 Onboarding KYB`, `1.2 Beneficiarios finales`,
`1.3 Solicitudes de informacion (RFI)`, `2. Pagos`, `2.1 Cotizaciones`, `2.2 Destinatarios`,
`2.3 Cuentas virtuales`, `2.4 Depositos`, `6. Webhooks de Kira`.

---

## 11. Webhooks de Kira

### 11.1 Ingreso — `KiraWebhookController.receive`

`POST /api/webhooks/kira`, cualquier `Content-Type`, cuerpo como `byte[]`:

1. Sin `kira.webhook-secret` → `503 {"error":"webhook_secret_not_configured"}` (log ERROR).
2. Firma `x-signature-sha256` inválida o ausente → `401 {"error":"invalid_signature"}` (log WARN con tamaño).
3. Válida → convierte a texto **después** de verificar y `processWebhook.enqueue(payload)` → `200 {"status":"received"}`.

Kira entrega **una sola vez**, sin reintentos, y aborta a los 30 s: por eso se responde inmediatamente y
nunca con `4xx` ante un evento desconocido.

### 11.2 Procesamiento — `ProcessWebhookUseCase`

`enqueue` (`@Async` en `webhookExecutor`) llama a `process` y **nunca propaga** excepciones (sólo log).

`process(rawPayload)`:

1. `KiraWebhookEnvelope.from(json)` normaliza las dos envolturas:
   - plana `{event, data{event_id, status, …}}`;
   - V2 de `payout.status_changed` `{data{event_id, event_type, created_at, data{status, previous_status, …}}}`.
   El id de deduplicación está siempre en `data.event_id`.
2. Sin `event_id` → WARN y se guarda con id `no-id:<uuid>`; con `event_id` ya existente → se ignora.
3. Inserta en `webhooks_log` (`saveAndFlush`); una violación de unicidad concurrente se ignora.
4. `applyProjection`; si falla, guarda `processing_error` (máx. 1000) y deja `processed = false`; si no,
   `processed = true` y `processed_at`.

> ⚠️ `process` está anotado `@Transactional`, pero `enqueue` lo invoca dentro de la misma clase, así que el
> proxy de Spring no aplica: en ejecución real **cada operación de repositorio corre en su propia
> transacción** (§17).

### 11.3 Proyecciones por familia

| Prefijo | Qué hace |
|---|---|
| `payout.*` | `resource_id` = `payout_id` o `id`; `normalized_status` = `PayoutStatus.fromWire`. Si el pago local existe: `applyRemoteStatus` (con `error_code = va-payout-bank-returned` para `payout.returned`). Se decide por el **valor** del estado, no por el nombre (payout.* y payout.status_changed se solapan) |
| `user.*` | `resource_id` = `user_id` o `id`. `user.liveness_completed` → `SyncUbosService.applyLivenessResult(person_reference_id o subject_id, LivenessStatus)`. Con empresa local: `user.verification.failed` → `rejectVerification(reason/rejection_reason/message)`; `user.verification.accepted` → `VERIFIED` y verificación disparada; otros con `status` explícito → aplica estado; **sin `status` no se toca** (evita degradar a `CREATED`) |
| `virtual_account.*` | Si el nombre contiene `deposit` → `KiraDepositEvent.from` + `RecordDepositService.apply`. Si no, con cuenta local: `virtual_account.activated` → `markActivatedEventSeen`; otros con estado → `applyRemoteStatus`; siempre `describeBank` |
| `rfi.*` | `resource_id` = `rfi_id` o `id`; `AnswerRfiService.applyWebhook` (relee el RFI en Kira) |
| otros | Se almacenan y se registra INFO |

**Eventos que son única fuente de su dato:** `user.verification.failed` (motivo del rechazo),
`user.liveness_completed` (resultado real), `virtual_account.activated` (fondos-listos) y
`payout.status_changed` (`KYT_PENDING`/`IN_REVIEW`).

---

## 12. Persistencia

### 12.1 Patrón

```
domain.XRepository (interfaz, puerto)
        ▲ implementa
infrastructure.persistence.JpaXRepository (@Repository, adaptador)
        │ usa
infrastructure.persistence.XJpaRepository (Spring Data JpaRepository<XEntity, String>)
        │ mapea
XEntity (@Entity @Table) ◄── toDomain / toEntity (en el adaptador o en XMapper)
```

`PayoutMapper` y `RecipientMapper` son clases de mapeo separadas (el destinatario aplana el oneOf de la
cuenta en columnas por riel); el resto mapea dentro del adaptador. `JpaTenantRepository` serializa
`eligible_products` y `missing_fields` a JSON con Jackson; `JpaRecipientRepository` serializa las
direcciones.

### 12.2 Tablas

12 tablas, ids `VARCHAR(36)`, importes `DECIMAL(18,4)`, enums como `VARCHAR`, JSON nativo donde el dato es libre.

| Tabla | Entidad | Contenido | Claves y restricciones |
|---|---|---|---|
| `tenants` | `TenantEntity` | Empresas y estado del bucle KYB (`missing_fields`, `eligible_products`, `onboarding_payload` JSON; `verification_triggered`, `onboarding_idempotency_key`, `rejection_reason`) | `uk_tenants_name`, `uk_tenants_kira_user` |
| `roles` | `RoleEntity` | Catálogo RBAC (`name`, `description`, `scope`) | `uk_roles_name` |
| `users` | `OperatorUserEntity` | Operadores (`email`, `password_hash`, nombres, `role_id`, `tenant_id` nulo para soporte, `status`, `mfa_secret`) | `uk_users_email`, **FK `fk_users_role` → `roles.id`** |
| `ubos` | `UboEntity` | Beneficiarios, booleanos del KYB, liveness (`liveness_link TEXT`) | `idx_ubos_tenant` |
| `virtual_accounts` | `VirtualAccountEntity` | Cuentas, banco, saldo, `activated_event_seen`, `opening_idempotency_key` | `uk_va_kira_account`, `idx_virtual_accounts_tenant` |
| `deposits` | `DepositEntity` | Bruto, comisión, neto, ordenante, riel, estado, microdepósito | `uk_deposits_kira_id`, `idx_deposits_tenant` |
| `recipients` | `RecipientEntity` | Espejo completo: titular, contacto, `holder_address`/`bank_address` JSON, columnas ACH/WIRE/WALLET, estado, reemplazo | `uk_recipients_kira_id`, `idx_recipients_tenant` |
| `quotations` | `QuotationEntity` | Riel, importes, tasa, comisiones, `fees_snapshot` JSON, `rate_source`, vencimiento | `idx_quotations_tenant` |
| `payouts` | `PayoutEntity` | Pago, comisiones, maker/approver, estados, cotización, `idempotency_key`, comprobante | `uk_payouts_idempotency`, `uk_payouts_kira_id`, `idx_payouts_tenant`, `idx_payouts_idempotency` |
| `rfis` | `RfiEntity` | Estado, `items_payload` JSON, plazo, bloqueo | `uk_rfis_kira_id`, `idx_rfis_tenant`, `idx_rfis_blocking` |
| `webhooks_log` | `WebhookEventEntity` | Evento crudo (`payload` JSON), tipo, `resource_id`, `normalized_status`, `processed`, `processing_error`, `retry_count` | `uk_webhooks_event_id`, `idx_webhooks_event` |
| `audit_logs` | `AuditLogEntity` | Bitácora (`changes` JSON) | `idx_audit_tenant (tenant_id, created_at)` |

Columnas exactas y tipos: **Anexo C**.

### 12.3 Consultas específicas

| Repositorio Spring Data | Consultas derivadas |
|---|---|
| `TenantJpaRepository` | `findByKiraUserId`, `findByNameIgnoreCase` |
| `RoleJpaRepository` | `findByName` |
| `OperatorUserJpaRepository` | `findByEmailIgnoreCase`, `findByTenantId` |
| `UboJpaRepository` | `findByIdAndTenantId`, `findByPersonReferenceId`, `findByTenantId`, `findByLivenessStatusAndLivenessExpiresAtBefore` |
| `VirtualAccountJpaRepository` | `findByIdAndTenantId`, `findByKiraAccountId`, `findByTenantId` |
| `DepositJpaRepository` | `findByKiraDepositId`, `findByTenantIdOrderByCreatedAtDesc`, `findByVirtualAccountIdOrderByCreatedAtDesc` |
| `RecipientJpaRepository` | `findByIdAndTenantId`, `findByKiraRecipientId`, `findByTenantIdAndStatusOrderByNameAsc` |
| `QuotationJpaRepository` | `findByIdAndTenantId`, `findByTenantIdOrderByCreatedAtDesc`, `findByStatusAndQuoteExpiresAtBefore` |
| `PayoutJpaRepository` | `findByIdAndTenantId`, `findByIdempotencyKey`, `findByKiraPayoutId`, `findByTenantIdOrderByCreatedAtDesc`, `findByKiraPayoutIdIsNotNullAndStatusInOrderByUpdatedAtAsc` (en vuelo) |
| `RfiJpaRepository` | `findByIdAndTenantId`, `findByKiraRfiId`, `findByTenantIdOrderByCreatedAtDesc`, `findByTenantIdAndStatusInOrderByCreatedAtDesc`, `findFirstByBlockingResourceIdAndStatusInOrderByCreatedAtDesc` |
| `WebhookEventJpaRepository` | `existsByEventId`, `findByEventId` |
| `AuditLogJpaRepository` | `findByTenantIdOrderByCreatedAtDesc` |

Los límites (`limit`) se aplican en memoria en los adaptadores (`stream().limit(...)`).

### 12.4 Desviaciones del DDL de referencia v2

Columnas propias del BFF que tapan huecos de la API: `payouts.approval_state`, `quotation_expires_at`,
`rejection_reason`, `error_code`, `reference_number`, `payment_method`, `quotation_id` nulo;
`tenants.missing_fields`, `verification_triggered`, `onboarding_payload`, `onboarding_idempotency_key`,
`rejection_reason`; `ubos.has_ownership`, `has_control`, `is_signer`, `politically_exposed`,
`country_of_birth`; `virtual_accounts.mode`, `bank`, `description`, `activated_event_seen`,
`balance_refreshed_at`, `opening_idempotency_key`; `recipients` (espejo completo); `quotations.rail`,
`destination_currency`, `balance_sufficient`, `rate_source`, `fees_snapshot`; `deposits.microdeposit`,
`updated_at`; `webhooks_log.resource_id`, `normalized_status`, `processing_error`, `retry_count`;
`rfis.blocking_type`, `blocking_resource_id`.

### 12.5 Regenerar el DDL

Crear temporalmente en `src/test/java/com/example/autransactional/`:

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
`target/schema-mysql.sql` y **borrar la clase**. Si aparece un `enum('…')`, falta
`@JdbcTypeCode(SqlTypes.VARCHAR)`.

### 12.6 Reconciliación

Cinco workers `@Component` con `@Scheduled(fixedDelayString = …)`, todos condicionados a
`bff.reconciliation.enabled` (por defecto `true`; `false` en las pruebas). Ninguno lleva
`@Transactional`: cada guardado va en su propia transacción, así que un fallo aislado no deshace lo ya
reconciliado. Un error por elemento se registra y el lote continúa; un `KiraNotConfiguredException`
corta el lote con un solo aviso.

| Worker | Intervalo por defecto | Qué hace |
|---|---|---|
| `PayoutReconciliationWorker` | 10 min (lote de 50) | `PayoutRepository.findInFlight` → `GET /v1/payouts/{id}` → `applyRemoteStatus` + `describeRemote` |
| `QuotationReconciliationWorker` | 5 min | `findActiveExpiredBefore(now)` → `expire()` (sin llamar a Kira) |
| `LivenessReconciliationWorker` | 1 h | `findPendingLivenessExpiredBefore(now)` → `expireLivenessLink()` (sin llamar a Kira) |
| `RfiReconciliationWorker` | 15 min | Por cada empresa con `kiraUserId`: `AnswerRfiService.syncForTenant` |
| `WebhookReprojectionWorker` | 30 min (lote de 50) | `findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(5)` → `ProcessWebhookUseCase.reproject` |

**Tope de reintentos.** `WebhookReprojectionWorker.MAX_RETRIES = 5`. Cada fallo incrementa
`webhooks_log.retry_count`; al quinto, la fila queda con `processing_error = 'Max retries reached'`
y el propio filtro de la consulta deja de devolverla. Resuelve dos cosas a la vez: la fila
envenenada (un evento que nunca va a proyectarse y se reintentaría para siempre) y la inanición de
los eventos nuevos, que iban detrás de las filas viejas y rotas porque el lote va de más antiguo a
más nuevo. La última causa real sólo queda en el log: a partir de ahí la fila sólo dice
`Max retries reached`.

Un `KiraNotConfiguredException` no gasta intento ni marca error: no es culpa del evento. El precio
de esa decisión, medido en dev: al ir el lote de más antiguo a más nuevo y cortar en la primera fila
que necesita Kira, un `rfi.*` pendiente sin credenciales bloquea la cola entera. En cert y prod, con
credenciales, no ocurre.

**Verificado en vivo** con `scripts/verificar-reproyeccion-dev.sh`, que inserta una fila
`processed = 0` en la base de desarrollo y espera a que el worker la reproyecte.

**Despliegue:** cert y prod usan `ddl-auto: validate` y no hay Flyway. La columna se aplica a mano:
`ALTER TABLE webhooks_log ADD COLUMN retry_count INT NOT NULL DEFAULT 0;`

---

## 13. Manejo de errores

`RestExceptionHandler` (`@RestControllerAdvice`). Cuerpo: `{ "code", "message", "details"? }`.

| Excepción | HTTP | `code` | `message` / `details` |
|---|---|---|---|
| `RfiAnswerRejectedException` | 422 | `rfi_answer_rejected` | mensaje + `details` por `item_id` o archivo |
| `DomainException` | 422 | `business_rule_violation` | mensaje del dominio |
| `AccessDeniedException` | 403 | `forbidden` | "No tienes permiso para esta accion." |
| `MethodArgumentNotValidException` | 400 | `validation_error` | "Datos invalidos." + `details` por campo |
| `MissingServletRequestPartException` | 400 | `validation_error` | "Falta la parte 'x'." |
| `MissingServletRequestParameterException` | 400 | `validation_error` | "Falta el parametro 'x'." |
| `MethodArgumentTypeMismatchException` | 400 | `validation_error` | "Valor invalido para 'x'." |
| `HttpMessageNotReadableException` | 400 | `validation_error` | "El cuerpo de la peticion no es un JSON valido." |
| `MaxUploadSizeExceededException` | 413 | `file_too_large` | "El archivo supera el tamano permitido (30 MB por archivo)." |
| `NoResourceFoundException` | 404 | `not_found` | "La ruta no existe." |
| `KiraNotConfiguredException` | 503 | `kira_not_configured` | "La integracion con Kira no esta configurada en este entorno." (log ERROR) |
| `KiraApiException` | 502 si Kira dio 5xx o 401; si no, 422 | `kira_<code>` o `kira_error` | mensaje de Kira (log WARN) |
| `Exception` | 500 | `internal_error` | "Ocurrio un error inesperado." (log ERROR con traza) |

Fuera del advice: `JwtTenantFilter` responde `401 unauthorized` con JSON; Spring Security responde `403`
sin cuerpo a peticiones anónimas; el controlador de webhooks responde `401`/`503` con `{"error": …}`.

---

## 14. Auditoría y logging

### 14.1 `AuditTrail.record(operator, action, resourceType, resourceId, idempotencyKey, result, detail)`

Guarda un `AuditLog` con empresa, usuario y rol del operador (nulos si no hay), `changes` =
`{idempotencyKey?, result?, detail?}` en JSON y la IP del cliente (primer valor de `X-Forwarded-For` o
`remoteAddr`; nula fuera de una petición HTTP).

**Acciones auditadas (18):**

| Acción | Recurso | Servicio |
|---|---|---|
| `tenant.onboarding_registered` (OK / ERROR) | tenant | `SubmitOnboardingService.register` |
| `tenant.onboarding_profile_updated` | tenant | `SubmitOnboardingService.completeProfile` |
| `tenant.ubo_saved` | ubo | `SyncUbosService.save` |
| `tenant.ubos_synced` | tenant | `SyncUbosService.syncToKira` |
| `tenant.liveness_links_requested` | tenant | `SyncUbosService.requestLivenessLinks` |
| `virtual_account.opened` (OK / ERROR / reutilizada) | virtual_account | `OpenVirtualAccountService.open` |
| `virtual_account.deposit_simulated` | virtual_account | `OpenVirtualAccountService.simulateDeposit` |
| `recipient.registered` | recipient | `RegisterRecipientService.register` |
| `recipient.archived` | recipient | `RegisterRecipientService.archive` |
| `quotation.created` | quotation | `CreateQuoteService.create` |
| `payout.created` | payout | `ExecutePayoutService.create` |
| `payout.approved` | payout | `ExecutePayoutService.approveAndSubmit` |
| `payout.submitted` (OK / ERROR) | payout | `ExecutePayoutService.submitToKira` |
| `payout.rejected` | payout | `ExecutePayoutService.reject` |
| `compliance.rfis_synced` | tenant | `AnswerRfiService.sync` |
| `compliance.rfi_answered` | rfi | `AnswerRfiService.answer` |
| `compliance.rfi_documents_uploaded` | rfi | `AnswerRfiService.uploadDocuments` |
| `compliance.rfi_document_removed` | rfi | `AnswerRfiService.removeDocument` |

No se auditan: lecturas, `refresh`, sincronización de depósitos, vista previa de pagos, enlaces de descarga
ni proyecciones de webhooks.

### 14.2 Logging

SLF4J con `Logger` por clase. Niveles relevantes: INFO en reintentos por 401, reutilización de cuentas y
eventos sin correspondencia local; WARN en cotizaciones que no cuadran o con tasa de contingencia,
activación demorada, entradas no atribuibles y webhooks con firma inválida; ERROR en fallos de envío a
Kira, eventos no proyectados y errores no controlados. Las credenciales y las URLs de descarga nunca se
registran.

---

## 15. Pruebas

**283 pruebas en 40 clases, todas en verde** (`./mvnw clean test`). Nombres completos en el Anexo B.

| Tipo | Cómo | Clases |
|---|---|---|
| Dominio | JUnit puro, sin mocks | `RfiTest`, `TenantOnboardingTest`, `UboRosterTest`, `VirtualAccountActivationTest`, `VirtualAccountReadinessTest`, `PayoutTest`, `PayoutStatusTest`, `QuotationTest`, `QuotationRailTest`, `RecipientAccountTest` |
| Aplicación | Mockito para `KiraApiClient`, repositorios y `AuditTrail`; captura de cuerpos enviados | `SubmitOnboardingServiceTest`, `SyncUbosServiceTest`, `KiraUserStateTest`, `OpenVirtualAccountServiceTest`, `RecordDepositServiceTest`, `RegisterRecipientServiceTest`, `CreateQuoteServiceTest`, `ExecutePayoutServiceTest`, `AnswerRfiServiceTest`, `ReferenceCatalogServiceTest`, `KiraWebhookEnvelopeTest`, `UserEventProjectionTest` |
| Infraestructura | `MockRestServiceServer` sobre `RestClient`; unitarias | `KiraApiClientVersionTest`, `KiraCredentialManagerTest`, `KiraAmountsTest`, `KiraWebhookVerifierTest`, `RequiredSecretsValidatorTest` |
| Reconciliación | Mockito sobre repositorios y `KiraApiClient` | `PayoutReconciliationWorkerTest`, `QuotationReconciliationWorkerTest`, `LivenessReconciliationWorkerTest`, `RfiReconciliationWorkerTest`, `WebhookReprojectionWorkerTest` |
| Integración | `@SpringBootTest` con H2 y MockMvc | `AuTransactionalApplicationTests`, `OpenApiDocsTest`, `KiraWebhookControllerTest`, `DevDataSeederTest`, `CertProfileStartupTest`, `IdempotencyKeyPersistenceTest`, `ReconciliationWorkersEnabledTest`, `ReconciliationWorkersDisabledTest` |

`src/test/resources/application.yaml`: H2 `MODE=MySQL`, `ddl-auto: create-drop`, credenciales de Kira de
prueba y `base-url` a `localhost:0` (nunca sale a Internet).

Comandos:

```bash
./mvnw clean test                          # todas (usar clean ante cambios de firma)
./mvnw test -Dtest=ExecutePayoutServiceTest
./mvnw test -Dtest='*RfiTest'
```

**Límite importante:** las pruebas de aplicación simulan Kira y no ejecutan transacciones reales; no
detectan errores de ids enviados a Kira (corregido el 11-sep) ni efectos de rollback. Por eso la
idempotencia se comprueba con `IdempotencyKeyPersistenceTest`, que es de integración con H2.

---

## 16. Herramientas, documentación y operación

| Recurso | Uso |
|---|---|
| `./mvnw spring-boot:run -Dspring-boot.run.jvmArguments="-Xmx768m"` | Arrancar en dev (con `KIRA_WEBHOOK_SECRET` y credenciales de Kira si se quiere llegar a Kira) |
| `http://localhost:8080/swagger-ui.html` | Swagger UI (no en prod) |
| `/actuator/health` | Salud (`UP`) |
| `docs/bruno/AuTransactional/` | Colección de Bruno: 84 peticiones en 11 carpetas, entorno `local` |
| `docs/GUIA-BRUNO.md` | Guía de pruebas paso a paso |
| `docs/API-GUIA.md` | Contrato HTTP y trampas de Kira |
| `docs/kira-cuerpos-peticiones.json` | Cuerpos exactos enviados a Kira |
| `docs/ARQUITECTURA.md`, `docs/ESTADO.md` | Visión resumida y estado/pendientes |
| Git | Remoto `origin` = `github.com/CaroLopez09/Au_transaction` (público), rama `main` |

---

## 17. Deuda técnica, defectos conocidos y riesgos

Hallazgos verificados en el código el 11-sep-2026 que siguen abiertos.

### 17.1 Defectos

| # | Hallazgo | Dónde | Efecto |
|---|---|---|---|
| ~~F1~~ | **Corregido el 11-sep (noche).** La clave se consolida con `IdempotencyKeyStore` en una transacción propia (`REQUIRES_NEW`) antes de llamar a Kira, así que el rollback del caso de uso ya no la borra | `SubmitOnboardingService.register`, `OpenVirtualAccountService.open` | Verificado con `IdempotencyKeyPersistenceTest` (integración con H2) |
| F2 | **Se pierde la traza de un envío fallido.** `approveAndSubmit` marca el pago como `FAILED` y audita `ERROR`, pero relanza dentro de la transacción: todo se revierte | `ExecutePayoutService.approveAndSubmit` | El pago vuelve a `PENDING_APPROVAL` (esperado) pero **no queda auditoría del intento fallido** |
| F3 | **`process()` de webhooks no es transaccional en ejecución real**: `enqueue` lo invoca dentro de la misma clase y el proxy no aplica | `ProcessWebhookUseCase` | Cada guardado va en su propia transacción: una proyección puede quedar a medias. El comentario de `AnswerRfiService.applyWebhook` supone lo contrario |
| F4 | Clave de idempotencia **por petición HTTP**, no persistida antes de llamar, en destinatarios y pagos creados | `RegisterRecipientService.register` | Un reintento del portal genera otra clave (Kira responde `202` si detecta el duplicado de destinatario) |
| F5 | Logging DEBUG de `cert` apunta a `com.example.autransactional.infrastructure.kiraclient`, que no existe | `application-cert.yaml` | No hay DEBUG del cliente de Kira en cert |
| F6 | Javadoc obsoleto: sigue mencionando endpoints de verificación biométrica sin seguridad | `OpenApiConfig` | Confusión al leer el código |
| F7 | Sin cabecera `Authorization` la API responde `403` vacío en lugar de `401` | `SecurityConfig` (sin `AuthenticationEntryPoint`) | El front debe tratar ambos |
| F8 | Un pago sin cotización envía un bruto calculado con comisiones **estimadas** (15 + 15) | `Payout.grossAmountToSend` | Si la tarifa real difiere, el destinatario recibe un importe distinto (decisión abierta: exigir cotización) |

### 17.2 Deuda técnica

| # | Deuda | Detalle |
|---|---|---|
| D1 | Casos de uso dependen de `infrastructure` | `KiraApiClient` (devuelve `JsonNode`), `AuditTrail`, `AuthenticatedOperator` en `application`. Lo limpio sería un puerto `KiraGateway` |
| D2 | `TenantContext` sin lectores | El filtro lo fija y limpia, pero ningún servicio lo usa (usan `operator.tenantId()`) |
| D3 | Falta el worker de eventos no proyectados | §12.6; los otros cuatro están implementados |
| D4 | MFA sin implementar | Existe `users.mfa_secret` |
| D5 | Sin gestión de operadores | Los usuarios sólo entran por la semilla de dev |
| D6 | `idx_payouts_idempotency` redundante | Duplica el índice de `uk_payouts_idempotency` |
| D7 | Límites aplicados en memoria | Los adaptadores leen todas las filas de la empresa y cortan con `limit` |
| D8 | `resolution_reason` de RFI no se guarda | Motivo `expired`/`rejected` de un RFI cerrado |
| D9 | Cotización en versión `2026-04-14` | La cotización detallada sólo existe en `2026-06-01` (sin verificar qué se pierde) |
| D10 | Tablas huérfanas en la base de dev | `audit_log`, `operator_user`, `payout`, `tenant`, `webhook_event` (restos del esquema anterior) |

### 17.3 Riesgos

| Riesgo | Mitigación actual |
|---|---|
| **Contraseña de MySQL en el historial de un repositorio público** | Parcial: `application-dev.yaml` ya la toma de `${DB_PASSWORD}`, pero el valor antiguo sigue en los commits ya hechos. Falta **rotarla** y valorar hacer el repo privado |
| Sin credenciales de Kira: el flujo real no se ha probado contra el sandbox | Contratos verificados en docs.kirafin.ai; cuerpos en `kira-cuerpos-peticiones.json` |
| Webhook perdido (entrega única) | Los cinco workers de reconciliación (§12.6), más `refresh`/`sync` manuales |
| Eventos `rfi.*` requieren suscripción explícita en Kira | `POST /api/rfis/sync` |
| `ddl-auto: validate` en cert/prod con esquema aplicado a mano | Regenerar el DDL (§12.5) antes de desplegar |




---

## Anexo A. Referencia clase por clase

Generado automáticamente desde el código fuente el 11-sep-2026: **todos** los tipos de `src/main/java` (clases, records, enums e interfaces, incluidos los anidados), con su javadoc, anotaciones, campos constantes o documentados y todos los métodos no privados. Los métodos privados se omiten; su lógica se explica en las secciones 8 a 12.

### A.1 Arranque

<sub>`AuTransactionalApplication.java` · 14 líneas</sub>

#### `AuTransactionalApplication` · clase · `@SpringBootApplication`

| Método | Descripción |
|---|---|
| `public static void main(String[] args)` |  |

### A.2 Dominio — shared

<sub>`domain/shared/DomainException.java` · 9 líneas</sub>

#### `DomainException` · clase

Violacion de una invariante de negocio. Se traduce a HTTP 409/422 en la capa REST.

| Método | Descripción |
|---|---|
| `public DomainException(String message)` |  |

<sub>`domain/shared/IdempotencyKey.java` · 30 líneas</sub>

#### `IdempotencyKey` · record

Clave de idempotencia. Kira la exige como UUID v4 en POST /v1/users, /v1/recipients,
/v1/virtual-accounts y /v1/virtual-accounts/{id}/payout.
Regla: una clave nueva por intencion distinta; la misma solo para reintentar la misma intencion.

| Componente |
|---|
| `String value` |

| Método | Descripción |
|---|---|
| `public static IdempotencyKey newKey()` |  |
| `public static IdempotencyKey of(String value)` |  |
| `public String toString()` |  |

<sub>`domain/shared/Money.java` · 69 líneas</sub>

#### `Money` · record

Importe con moneda. Existe para que un monto no viaje nunca separado de su divisa:
el esquema guarda DECIMAL(18,4) y una columna de moneda por fila, y sumar dos filas
de monedas distintas es el error que este tipo hace imposible.

| Componente |
|---|
| `BigDecimal amount` |
| `String currency` |

| Campo | Descripción |
|---|---|
| `public static final int SCALE = 4` | La escala del esquema: DECIMAL(18, 4) en cada columna de importe. |

| Método | Descripción |
|---|---|
| `public static Money of(BigDecimal amount, String currency)` |  |
| `public static Money of(String amount, String currency)` |  |
| `public static Money zero(String currency)` |  |
| `public Money plus(Money other)` |  |
| `public Money minus(Money other)` |  |
| `public boolean isPositive()` |  |
| `public boolean isLessThan(Money other)` |  |
| `public String toString()` |  |

<sub>`domain/shared/PostalAddress.java` · 34 líneas</sub>

#### `PostalAddress` · record

Direccion postal.

Ojo con el pais: los destinatarios usan ISO-2 ("US") y las empresas del KYB usan ISO-3
("USA"). Mezclarlos es un error de validacion en la API, asi que el codigo se guarda
tal como lo exige cada superficie y esta clase solo comprueba la longitud.

| Componente |
|---|
| `String streetName` |
| `String city` |
| `String state` |
| `String postalCode` |
| `String country` |

| Método | Descripción |
|---|---|
| `public void assertIso2Country()` | Direccion de un destinatario: pais en ISO-2. |
| `public boolean isBlank()` |  |

<sub>`domain/shared/Rail.java` · 40 líneas</sub>

#### `Rail` · enum

Riel de movimiento de fondos. Discrimina account.account_type en POST /v1/recipients
y clasifica el origen en los depositos entrantes.

Kira no expone actualizacion ni borrado de destinatarios: para corregir uno se crea
un reemplazo y el anterior se archiva localmente.

Valores: `ACH`, `WIRE`, `WALLET`.

| Método | Descripción |
|---|---|
| `public static Rail from(String raw)` |  |
| `public static Rail fromWireOrNull(String raw)` | Tolerante: un riel desconocido en un evento no debe romper la proyeccion. |

<sub>`domain/shared/StatusNormalizer.java` · 24 líneas</sub>

#### `StatusNormalizer` · clase

Kira devuelve los estados con distinto casing segun la superficie:
el 201 de payout responde "created" y el GET responde "CREATED"; los eventos planos
usan minusculas y payout.status_changed mayusculas. La documentacion es explicita:
comparar SIEMPRE sin distinguir mayusculas y tolerar valores desconocidos.

| Método | Descripción |
|---|---|
| `public static String normalize(String raw)` |  |
| `public static boolean matches(String raw, String expected)` |  |

<sub>`domain/shared/TenantId.java` · 23 líneas</sub>

#### `TenantId` · record

Identificador de la organizacion propietaria del dato. Toda consulta debe filtrar por el.

| Componente |
|---|
| `String value` |

| Método | Descripción |
|---|---|
| `public static TenantId of(String value)` |  |
| `public String toString()` |  |

### A.3 Dominio — tenant

<sub>`domain/tenant/EligibleProduct.java` · 29 líneas</sub>

#### `EligibleProduct` · record

Producto bancario de Kira y su elegibilidad para esta empresa.

Abrir una cuenta virtual exige DOS condiciones a la vez: que el KYB este VERIFIED y que
el producto concreto este 'eligible'. Guardar solo el codigo del producto perderia la
segunda mitad de esa pregunta.

| Componente |
|---|
| `String productCode` |
| `boolean eligible` |
| `List<String> missingFields` |
| `String unsupportedReason` |

| Campo | Descripción |
|---|---|
| `public static final String USA_VIRTUAL_ACCOUNTS = "usa-virtual-accounts"` | Producto objetivo de la integracion: cuentas virtuales en bancos de EE. UU. |
| `public static final String EDD_REQUIRED = "enhanced_due_diligence_required"` | Motivo que Kira devuelve cuando exige diligencia reforzada (file_proof_of_address). |

| Método | Descripción |
|---|---|
| `public boolean requiresEnhancedDueDiligence()` |  |

<sub>`domain/tenant/LivenessStatus.java` · 33 líneas</sub>

#### `LivenessStatus` · enum

Estado del enlace biometrico alojado que Kira emite para cada UBO.

Valores: `PENDING`, `COMPLETED`, `EXPIRED`, `FAILED`.

| Método | Descripción |
|---|---|
| `public static LivenessStatus fromWire(String raw)` |  |
| `public boolean isFinal()` |  |

<sub>`domain/tenant/MissingFields.java` · 55 líneas</sub>

#### `MissingFields` · record

Campos que Kira todavia exige para verificar a la empresa, agrupados por producto.

Es la fuente de verdad del formulario de onboarding: la pantalla NO debe tener campos
estaticos, sino renderizar lo que llegue aqui. La clave "general" aplica a todos los
productos; el resto son codigos de producto (p. ej. usa-virtual-accounts).

| Componente |
|---|
| `Map<String` |
| `List<String>> byProduct` |

| Campo | Descripción |
|---|---|
| `public static final String GENERAL = "general"` |  |

| Método | Descripción |
|---|---|
| `public static MissingFields empty()` |  |
| `public List<String> forProduct(String productCode)` | Lo que falta para un producto concreto: los generales mas los suyos. |
| `public boolean isCompleteFor(String productCode)` |  |
| `public boolean isEmpty()` |  |
| `public Set<String> products()` |  |

<sub>`domain/tenant/OperatorUser.java` · 34 líneas</sub>

#### `OperatorUser` · record

Usuario humano de una empresa cliente.
No confundir con el "user" de Kira, que es la empresa misma en el KYB.

| Componente |
|---|
| `String id` |
| `TenantId tenantId` |
| `String email` |
| `String passwordHash` |
| `String firstName` |
| `String lastName` |
| `Role role` |
| `UserStatus status` |
| `String mfaSecret` |

| Método | Descripción |
|---|---|
| `public void assertCanLogin()` |  |
| `public void assertBelongsTo(TenantId expected)` |  |
| `public String fullName()` |  |
| `public boolean isActive()` |  |

<sub>`domain/tenant/OperatorUserRepository.java` · 16 líneas</sub>

#### `OperatorUserRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<OperatorUser> findByEmail(String email)` |  |
| `Optional<OperatorUser> findById(String id)` |  |
| `List<OperatorUser> findByTenant(TenantId tenantId)` |  |

<sub>`domain/tenant/Role.java` · 75 líneas</sub>

#### `Role` · enum

RBAC B2B. El nombre tecnico (dbName) es el que vive en la tabla `roles` y el que
lee el negocio; la constante es la que usan @PreAuthorize y el JWT.

La segregacion de funciones exige separar quien prepara un pago (TREASURY_MAKER)
de quien lo autoriza (TREASURY_APPROVER): la API de Kira no ofrece maker-checker
para integradores, asi que el control es del BFF.

Valores: `ADMIN`, `TREASURY_MAKER`, `TREASURY_APPROVER`, `COMPLIANCE_INTERNAL`, `READ_ONLY`.

| Método | Descripción |
|---|---|
| `Role(String dbName, RoleScope scope, String description)` |  |
| `public String dbName()` |  |
| `public RoleScope scope()` |  |
| `public String description()` |  |
| `public static Role fromDbName(String raw)` |  |
| `public boolean canCreatePayout()` |  |
| `public boolean canApprovePayout()` |  |
| `public boolean canManageCompliance()` | Ficha 360, UBOs, liveness y RFIs. |

<sub>`domain/tenant/RoleScope.java` · 8 líneas</sub>

#### `RoleScope` · enum

Alcance del rol: propio de la empresa cliente o del soporte de la plataforma.

Valores: `TENANT`, `SYSTEM`.

<sub>`domain/tenant/Tenant.java` · 221 líneas</sub>

#### `Tenant` · clase · `@Getter`

Agregado Tenant: la empresa cliente que opera sobre el BFF
(p. ej. Juriscop, Bankvision, AU Colombia).

Es la contraparte local del "user" de Kira: kiraUserId guarda el id que devuelve
POST /v1/users y es lo que ata todo el KYB, las cuentas virtuales y los pagos.

El onboarding NO es una linea recta sino un bucle: se crea el user con lo minimo, y
despues se repite PUT + GET hasta que missingFields queda vacio para el producto
objetivo. Por eso el agregado guarda el estado de ese bucle y no solo el resultado.

| Método | Descripción |
|---|---|
| `public Tenant(TenantId id, String name, String taxId, String jurisdiction)` |  |
| `public static Tenant rehydrate(TenantId id, String name, String taxId, String jurisdiction, …)` |  |
| `public IdempotencyKey reserveOnboardingKey()` | Reserva la clave de idempotencia del alta en Kira. Una clave por intencion de negocio, no por intento HTTP: se persiste ANTES de la primera llamada y todos los reintentos reutilizan la misma, o un timeout seguido de reintento crearia dos empresas en Kira. |
| `public void linkKiraUser(String kiraUserId)` | Se invoca tras el 201 de POST /v1/users. No cambia el estado: el 201 devuelve CREATED y la verificacion NO se dispara sola. Solo un PUT completo (con source_of_funds) la dispara. |
| `public boolean isRegisteredInKira()` |  |
| `public void assertRegisteredInKira()` |  |
| `public void recordOnboardingPayload(String payloadJson)` | Guarda el objeto completo enviado a Kira. Es obligatorio conservarlo: el GET no devuelve los campos del cuestionario y un PUT parcial borra en silencio lo que no viaje en el, asi que el siguiente PUT solo puede construirse a partir de lo que se envio la vez anterior. |
| `public void applyRemoteState(TenantStatus incoming, MissingFields missingFields, …)` | Asienta lo que devolvieron POST/PUT/GET de /v1/users. |
| `public Optional<EligibleProduct> product(String productCode)` |  |
| `public boolean isReadyFor(String productCode)` | Abrir cuenta virtual exige KYB VERIFIED y el producto concreto elegible. |
| `public void assertVerificationInProgress()` | POST /v1/users/{id}/liveness-link devuelve 422 "No verification is in progress" si el KYB aun no se disparo. Se comprueba aqui para no gastar la llamada. |
| `public void rejectVerification(String reason)` | Guarda el motivo del rechazo del KYB. Solo llega por el webhook user.verification.failed y solo una vez: GET /v1/users/{id} nunca lo expone. Si no se captura aqui, el operador ve un REJECTED sin explicacion y no hay forma de recuperarla. |
| `public boolean isVerified()` |  |
| `public void assertActive()` |  |
| `public void assertCanOperateTreasury()` | Ninguna operacion de tesoreria sale hacia Kira si el KYB no esta aprobado. |

<sub>`domain/tenant/TenantRepository.java` · 19 líneas</sub>

#### `TenantRepository` · interfaz

Puerto de salida. La implementacion vive en infrastructure/persistence.

| Método | Descripción |
|---|---|
| `Tenant save(Tenant tenant)` |  |
| `Optional<Tenant> findById(TenantId id)` |  |
| `Optional<Tenant> findByKiraUserId(String kiraUserId)` |  |
| `List<Tenant> findAll()` |  |

<sub>`domain/tenant/TenantStatus.java` · 40 líneas</sub>

#### `TenantStatus` · enum

Estado del KYB de la empresa cliente en Kira.
Se recibe por evento user.* y por GET /v1/users/{id}; se compara siempre sin
distinguir mayusculas y un valor desconocido no rompe la maquina.

Valores: `CREATED`, `VERIFYING`, `REVIEW`, `VERIFIED`, `REJECTED`.

| Método | Descripción |
|---|---|
| `public static TenantStatus fromWire(String raw)` |  |
| `public boolean canOperate()` |  |

<sub>`domain/tenant/Ubo.java` · 179 líneas</sub>

#### `Ubo` · clase · `@Getter`

Beneficiario final, director o firmante de la empresa cliente.

Existe como tabla propia por dos motivos que la API impone: hay que reconstruir el array
'associated_persons' COMPLETO en cada PUT (un PUT parcial borra campos en silencio), y
el resultado real del liveness solo llega una vez, por webhook.

El enlace de liveness que emite Kira vive 7 dias: por eso la fecha de vencimiento se
guarda aparte del estado. Un enlace vencido no se reintenta, se vuelve a pedir.

| Campo | Descripción |
|---|---|
| `public static final String DEFAULT_ROLE = "Beneficiario Final"` | Rol por defecto cuando el formulario de onboarding no lo precisa. |
| `public static final BigDecimal BENEFICIAL_OWNER_THRESHOLD = new BigDecimal("5")` | Umbral a partir del cual Kira considera beneficiario final a una persona. |

| Método | Descripción |
|---|---|
| `public Ubo(String id, TenantId tenantId, String firstName, String lastName, …)` |  |
| `public static Ubo rehydrate(String id, TenantId tenantId, String personReferenceId, …)` |  |
| `public void describeDocument(String documentType, String documentNumber)` |  |
| `public void describeRole(boolean hasOwnership, BigDecimal ownershipPercentage, boolean hasControl, …)` | Define el papel de la persona en el KYB. hasOwnership es un booleano explicito y no se deduce del cargo: el titulo NO identifica al beneficiario, y omitirlo deja el KYB bloqueado sin decir por que. |
| `public boolean isBeneficialOwner()` | Kira exige al menos una persona asi para verificar a la empresa. |
| `public void linkKiraPerson(String personReferenceId)` |  |
| `public void assignLivenessLink(String link, Instant expiresAt)` | Se invoca con la respuesta de POST /v1/users/{id}/liveness-link. |
| `public boolean isLivenessLinkExpired(Instant now)` |  |
| `public void applyLivenessStatus(LivenessStatus incoming)` | No retrocede desde un estado final: los eventos llegan una vez y sin orden garantizado. |
| `public void expireLivenessLink()` |  |
| `public String fullName()` |  |

<sub>`domain/tenant/UboRepository.java` · 24 líneas</sub>

#### `UboRepository` · interfaz

| Método | Descripción |
|---|---|
| `Ubo save(Ubo ubo)` |  |
| `Optional<Ubo> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `Optional<Ubo> findByPersonReferenceId(String personReferenceId)` | Los webhooks de liveness identifican a la persona por esta referencia de Kira. |
| `List<Ubo> findByTenant(TenantId tenantId)` |  |
| `UboRoster rosterOf(TenantId tenantId)` |  |
| `List<Ubo> findPendingLivenessExpiredBefore(java.time.Instant cutoff)` | Enlaces de liveness que ya vencieron y siguen en PENDING: material del reconciliador. |

<sub>`domain/tenant/UboRoster.java` · 63 líneas</sub>

#### `UboRoster` · record

El conjunto de UBOs de una empresa, con las reglas que Kira verifica sobre el grupo
y no sobre cada persona.

La regla del beneficiario final causa la mayoria de los bloqueos silenciosos del KYB:
sin al menos una persona con propiedad >= 5 %, Kira devuelve
missing_fields "associated_persons:beneficial_owner" y la verificacion no avanza,
aunque el formulario parezca completo.

| Componente |
|---|
| `List<Ubo> members` |

| Método | Descripción |
|---|---|
| `public List<Ubo> beneficialOwners()` |  |
| `public BigDecimal totalOwnership()` |  |
| `public boolean hasBeneficialOwner()` |  |
| `public void assertReadyForVerification()` | Se comprueba antes de enviar el array a Kira: es mas barato que un KYB atascado. |
| `public List<Ubo> pendingLiveness()` | UBOs cuyo enlace de prueba de vida sigue pendiente de resolverse. |
| `public boolean livenessComplete()` |  |

<sub>`domain/tenant/UserStatus.java` · 13 líneas</sub>

#### `UserStatus` · enum

Estado del usuario dentro de la empresa cliente.

Valores: `ACTIVE`, `SUSPENDED`, `DISABLED`.

| Método | Descripción |
|---|---|
| `public boolean canLogin()` |  |

### A.4 Dominio — account

<sub>`domain/account/Deposit.java` · 142 líneas</sub>

#### `Deposit` · clase · `@Getter`

Deposito entrante sobre una cuenta virtual.

Se guardan los tres importes por separado porque son tres hechos distintos:
lo que envio el ordenante (bruto), lo que cobro el banco (comision) y lo que
quedo disponible (neto). Derivar uno de los otros pierde el desglose contable.

| Campo | Descripción |
|---|---|
| `private boolean microdeposit` | Deposito de verificacion de cuenta, no un ingreso real del cliente. |

| Método | Descripción |
|---|---|
| `public Deposit(String id, TenantId tenantId, String virtualAccountId, …)` |  |
| `public static Deposit rehydrate(String id, TenantId tenantId, String virtualAccountId, …)` |  |
| `public void markAsMicrodeposit()` |  |
| `public void applyRemoteStatus(DepositStatus incoming)` | Aplica el estado que trae un evento. No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden garantizado, asi que un 'in_transit' que llega tarde no puede resucitar un deposito ya devuelto. |
| `public void restate(BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount)` | Corrige los importes con lo que traiga un evento posterior mas completo. |
| `public boolean creditsBalance()` |  |
| `public void describeSender(String senderName, String senderAccount, Rail rail)` |  |
| `public void linkKiraDeposit(String kiraDepositId)` |  |
| `public Money net()` |  |
| `public Money gross()` |  |

<sub>`domain/account/DepositRepository.java` · 18 líneas</sub>

#### `DepositRepository` · interfaz

| Método | Descripción |
|---|---|
| `Deposit save(Deposit deposit)` |  |
| `Optional<Deposit> findByKiraDepositId(String kiraDepositId)` |  |
| `List<Deposit> findByTenant(TenantId tenantId, int limit)` |  |
| `List<Deposit> findByVirtualAccount(String virtualAccountId, int limit)` |  |

<sub>`domain/account/DepositStatus.java` · 77 líneas</sub>

#### `DepositStatus` · enum

Estado del deposito entrante.

REFUNDED existe porque un deposito completado puede revertirse despues: la ficha tiene
que soportar el paso COMPLETED -> REFUNDED, que es justo lo que reproduce el valor
magico de 11 en el simulador del sandbox.

Valores: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED`.

| Campo | Descripción |
|---|---|
| `private static final Set<DepositStatus> TERMINAL = Set.of(FAILED, REFUNDED)` | Una vez revertido o fallido, el deposito ya no vuelve a acreditar. |

| Método | Descripción |
|---|---|
| `public static DepositStatus fromWire(String raw)` |  |
| `public static DepositStatus fromEventName(String eventName, String rawStatus)` | Estado que implica cada evento de la familia de depositos. El nombre del evento es mas fiable que el 'status' del payload, porque hay eventos cuyo estado llega vacio y el propio nombre ya dice lo que paso. |
| `public boolean isTerminal()` |  |
| `public boolean creditsBalance()` | Solo un deposito completado suma saldo disponible. |

<sub>`domain/account/VirtualAccount.java` · 201 líneas</sub>

#### `VirtualAccount` · clase · `@Getter`

Agregado VirtualAccount: la cuenta bancaria virtual de la empresa cliente en Kira.

El saldo es una proyeccion local de GET /v1/virtual-accounts/{id}/balance y de los
depositos recibidos por webhook. La autoridad es siempre Kira: aqui solo se refleja.

| Campo | Descripción |
|---|---|
| `public static final Duration ACTIVATION_GRACE = Duration.ofMinutes(5)` | Pasado este tiempo sin activarse, deja de ser una espera normal. |

| Método | Descripción |
|---|---|
| `public VirtualAccount(String id, TenantId tenantId, String currency, VirtualAccountMode mode, …)` |  |
| `public static VirtualAccount rehydrate(String id, TenantId tenantId, String kiraAccountId, …)` |  |
| `public IdempotencyKey reserveOpeningKey()` | Reserva la clave de idempotencia de la apertura, antes de la primera llamada. Un timeout seguido de reintento no debe dejar dos cuentas abiertas. |
| `public void linkKiraAccount(String kiraAccountId)` |  |
| `public void describeBank(String bankName, String accountNumber, String routingNumber)` | Completa los datos bancarios. Solo sobrescribe lo que llega con valor: un evento que no trae el numero de cuenta no puede borrar el que ya conocemos, porque ese numero es justamente la senal de que la cuenta puede mover fondos. |
| `public void applyRemoteStatus(VirtualAccountStatus incoming)` |  |
| `public void markActivatedEventSeen()` | virtual_account.activated es la unica senal inequivoca de fondos-listos. |
| `public void refreshBalance(BigDecimal available, Instant at)` | El saldo es una proyeccion: la autoridad es Kira. En el sandbox ademas es un valor fijo del proveedor que no se mueve con la actividad. |
| `public boolean isActivationDelayed(Instant now)` | La activacion lleva demasiado tiempo. En el sandbox puede quedarse colgada indefinidamente sin que llegue nunca el evento virtual_account.activated, asi que el portal necesita poder decir "activacion demorada, contacta con Kira" en vez de girar un spinner para siempre. |
| `public boolean isOpenInKira()` |  |
| `public void markBalanceStale()` | Marca el saldo como desactualizado. Un deposito acreditado NO se suma al saldo local: la autoridad es Kira y en el sandbox el saldo es ademas un valor fijo del proveedor. Inventar aqui una suma seria mostrar un numero que el banco no reconoce; lo unico honesto es decir que hay que volver a preguntar. |
| `public boolean isBalanceStale()` |  |
| `public Money availableBalance()` |  |
| `public boolean isFundsReady()` |  |
| `public void assertFundsReady()` | Ningun pago se prepara sobre una cuenta que todavia no puede mover fondos. |

<sub>`domain/account/VirtualAccountMode.java` · 30 líneas</sub>

#### `VirtualAccountMode` · enum

Modo de la cuenta virtual. Es INMUTABLE una vez creada: cambiar de fiat a crypto
significa abrir otra cuenta, no editar esta.

Valores: `FIAT`, `CRYPTO`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static VirtualAccountMode from(String raw)` |  |

<sub>`domain/account/VirtualAccountReadiness.java` · 31 líneas</sub>

#### `VirtualAccountReadiness` · clase

En el pin 2026-04-14 la API colapsa activating/active en "approved", asi que 'approved'
NO significa que la cuenta pueda mover fondos. La documentacion indica detectar la cuenta
realmente operativa por un account_number real: no nulo y distinto del centinela
"PENDING-ACT-ACCOUNT". El evento virtual_account.activated es la unica senal fondos-listos.

| Campo | Descripción |
|---|---|
| `public static final String ACT_PENDING_SENTINEL = "PENDING-ACT-ACCOUNT"` |  |

| Método | Descripción |
|---|---|
| `public static boolean isFundsReady(String status, String accountNumber, boolean activatedEventSeen)` |  |

<sub>`domain/account/VirtualAccountRepository.java` · 18 líneas</sub>

#### `VirtualAccountRepository` · interfaz

| Método | Descripción |
|---|---|
| `VirtualAccount save(VirtualAccount account)` |  |
| `Optional<VirtualAccount> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `Optional<VirtualAccount> findByKiraAccountId(String kiraAccountId)` |  |
| `List<VirtualAccount> findByTenant(TenantId tenantId)` |  |

<sub>`domain/account/VirtualAccountStatus.java` · 36 líneas</sub>

#### `VirtualAccountStatus` · enum

Estado local de la cuenta virtual.

Ojo: en el pin 2026-04-14 la API colapsa activating/active en "approved", asi que
ACTIVE aqui NO implica que la cuenta pueda mover fondos. Esa pregunta la responde
`VirtualAccountReadiness`, no este enum.

Valores: `PENDING`, `ACTIVE`, `INACTIVE`, `FAILED`.

| Método | Descripción |
|---|---|
| `public static VirtualAccountStatus fromWire(String raw)` |  |

### A.5 Dominio — treasury

<sub>`domain/treasury/BankAccountKind.java` · 27 líneas</sub>

#### `BankAccountKind` · enum

Tipo de cuenta bancaria del destinatario.

Valores: `CHECKING`, `SAVINGS`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static BankAccountKind from(String raw)` |  |

<sub>`domain/treasury/FeeBreakdown.java` · 72 líneas</sub>

#### `FeeBreakdown` · record

Desglose comisional de una transferencia: 15 USD de KiraFin + 15 USD de margen de la
plataforma = 30 USD que se cobran al cliente.

Se guardan las tres cifras y no solo el total porque el margen de la plataforma es lo
unico que la contabilidad propia puede reconocer como ingreso; derivarlo restando
obligaria a asumir para siempre que la tarifa de Kira no cambia.

| Componente |
|---|
| `BigDecimal kiraFee` |
| `BigDecimal platformFee` |
| `BigDecimal totalFee` |

| Campo | Descripción |
|---|---|
| `public static final BigDecimal DEFAULT_KIRA_FEE = new BigDecimal("15.0000")` |  |
| `public static final BigDecimal DEFAULT_PLATFORM_FEE = new BigDecimal("15.0000")` |  |

| Método | Descripción |
|---|---|
| `public static FeeBreakdown standard()` | El desglose de referencia: 15 + 15 = 30 USD. Es una estimacion, no un hecho: la tarifa de Kira depende del riel y lleva un tramo porcentual, asi que la cifra real solo se conoce cuando la cotizacion vuelve. Se usa mientras no haya cotizacion. |
| `public static FeeBreakdown fromTotals(BigDecimal kiraRevenueTotal, BigDecimal clientMarkupTotal)` | El desglose real, leido de la cotizacion: totals.kira_revenue_total es el ingreso de Kira y totals.client_markup_total el nuestro. |
| `public static BigDecimal requestedPlatformMarkup()` | El margen fijo que la plataforma pide a Kira como client_markup. |
| `public static FeeBreakdown of(BigDecimal kiraFee, BigDecimal platformFee)` |  |
| `public BigDecimal totalDebitFor(BigDecimal originAmount)` | Lo que se debita de la cuenta virtual: el importe enviado mas el cobro total. |

<sub>`domain/treasury/NatureOfPayment.java` · 37 líneas</sub>

#### `NatureOfPayment` · enum

Naturaleza del pago que Kira reporta al banco corresponsal.

Valores: `VENDOR`, `POBO`, `FIRST_PARTY`, `SPOT_3P`, `SPOT_1P`, `RELATED_ENTITIES`, `OTHER`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static NatureOfPayment from(String raw)` |  |
| `public boolean requiresSupportingDocuments()` | Un pago a uno mismo no necesita justificar el destino con documentos. |

<sub>`domain/treasury/Payout.java` · 249 líneas</sub>

#### `Payout` · clase · `@Getter`

Agregado Payout. Entidad de dominio pura: sin anotaciones de JPA ni dependencias de framework.
Concentra el control interno (maker-checker) que la API de Kira no ofrece a los integradores.

| Campo | Descripción |
|---|---|
| `private String referenceNumber` | IMAD / ACH trace / UETR: el comprobante que el cliente final reclama. |

| Método | Descripción |
|---|---|
| `public Payout(String id, TenantId tenantId, String kiraUserId, String virtualAccountId, …)` |  |
| `public static Payout rehydrate(String id, TenantId tenantId, String kiraUserId, String virtualAccountId, …)` | Rehidratacion desde persistencia. |
| `public Money totalDebit()` | Lo que se debita de la cuenta virtual: importe enviado mas el cobro total al cliente. |
| `public BigDecimal platformMargin()` |  |
| `public void attachQuotation(String quotationId, Instant expiresAt)` |  |
| `public void attachQuotation(Quotation quotation, Instant now)` | Ata el pago a una cotizacion vigente. NO la consume: la cotizacion se marca como ejecutada al enviar el pago a Kira, no al prepararlo, porque entre preparar y aprobar puede pasar de todo (incluido que venza y haya que recotizar). |
| `public boolean isQuotationExpired(Instant now)` |  |
| `public void approve(String approverId, Instant now)` | Segregacion de funciones: quien crea la solicitud no puede autorizar su envio. |
| `public void reject(String approverId, String reason)` |  |
| `public void markAsSubmitted(String kiraPayoutId, String wireStatus)` | Se invoca tras un 201 de POST /v1/virtual-accounts/{id}/payout. |
| `public void applyRemoteStatus(PayoutStatus incoming, String errorCode)` | Aplica una transicion recibida por webhook o por reconciliacion. No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden garantizado. |
| `public void fail(String reason)` |  |
| `public boolean isReadyToSubmit()` |  |
| `public Money grossAmountToSend(Quotation quotation)` | El importe que viaja en POST /payout. Kira DESCUENTA las comisiones del monto enviado: mandar 1.000 con 30 de comision deja al destinatario con 970. Como se cotiza en modo inverse, el bruto que hay que enviar es el total a debitar, y solo asi el destinatario recibe el importe prometido. |
| `public boolean isPriceLocked()` | El precio esta cerrado solo si hay cotizacion detras. |
| `public void assertSubmittable(Instant now)` |  |
| `public void describeRemote(String referenceNumber, String paymentMethod)` | Se completa desde el 201 del envio y desde GET /v1/payouts/{id}. |

<sub>`domain/treasury/PayoutApprovalState.java` · 10 líneas</sub>

#### `PayoutApprovalState` · enum

Estado del control interno maker-checker. Vive solo en el BFF; Kira no lo conoce.

Valores: `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `SUBMITTED`.

<sub>`domain/treasury/PayoutRepository.java` · 25 líneas</sub>

#### `PayoutRepository` · interfaz

| Método | Descripción |
|---|---|
| `Payout save(Payout payout)` |  |
| `Optional<Payout> findByIdAndTenant(String id, TenantId tenantId)` | Toda lectura se filtra por tenant, aunque Kira trate el recurso como global del integrador. |
| `Optional<Payout> findByIdempotencyKey(IdempotencyKey key)` |  |
| `Optional<Payout> findByKiraPayoutId(String kiraPayoutId)` |  |
| `List<Payout> findByTenant(TenantId tenantId, int limit)` |  |
| `List<Payout> findInFlight(int limit)` | Pagos que Kira ya conoce y siguen sin estado terminal: material del reconciliador. |

<sub>`domain/treasury/PayoutStatus.java` · 52 líneas</sub>

#### `PayoutStatus` · enum

Estado del payout en Kira (vocabulario del recurso, en MAYUSCULAS segun GET /v1/payouts/{id}).
KYT_PENDING e IN_REVIEW solo afloran via el evento payout.status_changed y son NO terminales.
No existe RETURNED ni CANCELLED como estado de recurso: ambos resuelven en FAILED.

Valores: `NOT_SUBMITTED`, `CREATED`, `PENDING`, `PROCESSING`, `KYT_PENDING`, `IN_REVIEW`, `COMPLETED`, `FAILED`, `EXPIRED`, `UNKNOWN`.

| Campo | Descripción |
|---|---|
| `private static final Set<PayoutStatus> TERMINAL = Set.of(COMPLETED, FAILED, EXPIRED)` |  |

| Método | Descripción |
|---|---|
| `public static PayoutStatus fromWire(String raw)` | Tolerante: un estado desconocido no rompe la maquina, se trata como no terminal. |
| `public boolean isTerminal()` |  |
| `public boolean isInFlight()` |  |

<sub>`domain/treasury/Quotation.java` · 191 líneas</sub>

#### `Quotation` · clase · `@Getter`

Cotizacion de una transferencia.

Se cotiza con inverse=true: el importe que teclea el operador es lo que RECIBE el
destinatario, y las comisiones se suman por encima. Por eso originAmount y
totalDebitAmount son cifras distintas y ambas se guardan: la primera es lo prometido
al destinatario, la segunda lo que sale de la cuenta virtual.

La cotizacion vive 900 segundos exactos (locked_at + 15 min). Un pago aprobado con una
cotizacion vencida se ejecutaria a una tasa distinta de la que vio el tesorero, asi que
el vencimiento es parte del agregado y no un detalle de la respuesta HTTP.

| Campo | Descripción |
|---|---|
| `public static final int TTL_SECONDS = 900` | TTL documentado de una cotizacion redimible. |

| Método | Descripción |
|---|---|
| `public Quotation(String id, TenantId tenantId, String virtualAccountId, String recipientId, …)` |  |
| `public static Quotation rehydrate(String id, TenantId tenantId, String virtualAccountId, …)` |  |
| `public void applyKiraQuote(String kiraQuoteId, Instant expiresAt, BigDecimal totalDebit, …)` | Asienta lo que devolvio POST /v1/quotations. totalDebit es source.amount, el bruto que sale de la cuenta virtual; destination es recipient.amount, el neto que llega. Ninguno se recalcula aqui: el precio que se le muestra al tesorero tiene que ser exactamente el que Kira va a cobrar. |
| `public boolean hasConsistentTotals()` | Comprueba que el bruto cuadra con lo prometido mas las comisiones. Un descuadre no es un error de Kira: es que el importe mostrado al tesorero y el debitado de la cuenta no son el mismo numero, y eso hay que verlo. |
| `public boolean usesFallbackRate()` | La tasa no viene del mercado sino de una politica de contingencia. |
| `public boolean isExpired(Instant now)` |  |
| `public boolean isUsable(Instant now)` |  |
| `public void assertUsable(Instant now)` |  |
| `public void assertRedeemable(Instant now)` | Ademas de vigente, la cuenta debe tener saldo: Kira no encola ni cancela sola. |
| `public void markExecuted()` | Se consume al enviar el pago a Kira, no al prepararlo. |
| `public void expire()` |  |
| `public long secondsToExpiry(Instant now)` |  |

<sub>`domain/treasury/QuotationRail.java` · 89 líneas</sub>

#### `QuotationRail` · enum

Riel concreto con el que se cotiza. No es el mismo vocabulario que el account_type del
destinatario, y ahi esta la trampa: el riel del pago se deriva EXCLUSIVAMENTE del
account_type del destinatario, no del quote.

Si no coinciden, la API no falla al cotizar sino al ejecutar el pago, con
422 RECIPIENT_ACCOUNT_TYPE_MISMATCH. Por eso se valida aqui, antes de gastar la
cotizacion.

Valores: `ACH_STANDARD`, `ACH_SAME_DAY`, `WIRE_DOMESTIC`, `TRON`, `SOLANA`, `POLYGON`.

| Método | Descripción |
|---|---|
| `QuotationRail(Rail accountType)` |  |
| `public Rail accountType()` |  |
| `public String network()` | El valor de 'network' en el destinatario cuando el riel es de wallet. |
| `public static List<QuotationRail> validFor(Rail accountType)` |  |
| `public static QuotationRail defaultFor(Rail accountType, String network)` | Riel por defecto del destinatario. Para wallets hace falta saber la red. |
| `public static QuotationRail fromNetwork(String network)` |  |
| `public static QuotationRail from(String raw)` |  |
| `public void assertMatches(Rail recipientAccountType)` | Se comprueba antes de cotizar: Kira solo lo detecta al ejecutar el pago. |

<sub>`domain/treasury/QuotationRepository.java` · 20 líneas</sub>

#### `QuotationRepository` · interfaz

| Método | Descripción |
|---|---|
| `Quotation save(Quotation quotation)` |  |
| `Optional<Quotation> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `List<Quotation> findByTenant(TenantId tenantId, int limit)` |  |
| `List<Quotation> findActiveExpiredBefore(Instant cutoff)` | Cotizaciones ACTIVE cuyo TTL ya paso: el reconciliador las cierra. |

<sub>`domain/treasury/QuotationStatus.java` · 9 líneas</sub>

#### `QuotationStatus` · enum

Ciclo de vida local de la cotizacion. El TTL de 15 minutos lo fija Kira.

Valores: `ACTIVE`, `EXPIRED`, `EXECUTED`.

<sub>`domain/treasury/Recipient.java` · 133 líneas</sub>

#### `Recipient` · clase · `@Getter`

Destinatario del directorio de pagos de la empresa cliente.

Un destinatario = un riel, y el riel del pago se deriva EXCLUSIVAMENTE de su tipo de
cuenta: ni el quote ni el payout lo determinan. Es el punto de decision mas importante
del flujo de pagos.

Kira no expone actualizacion ni borrado: corregir un destinatario significa crear un
reemplazo y archivar el anterior. Por eso el espejo local guarda el registro completo,
incluidos los campos que la API acepta y luego no devuelve.

| Método | Descripción |
|---|---|
| `public Recipient(String id, TenantId tenantId, RecipientHolder holder, RecipientAccount account, …)` |  |
| `public static Recipient rehydrate(String id, TenantId tenantId, RecipientHolder holder, …)` |  |
| `public Rail getRail()` |  |
| `public String getNetwork()` | Red de la wallet. Null para rieles bancarios. |
| `public String getName()` |  |
| `public void linkKiraRecipient(String kiraRecipientId)` | La respuesta usa 'recipient_id', no 'id'. |
| `public void replaceWith(String replacementId)` | Reemplazo logico: Kira no borra destinatarios, asi que el corregido es otro registro y este solo queda archivado apuntando a su sustituto. |
| `public void archive()` |  |
| `public boolean isActive()` |  |
| `public boolean isRegisteredInKira()` |  |
| `public void assertUsable()` |  |

<sub>`domain/treasury/RecipientAccount.java` · 115 líneas</sub>

#### `RecipientAccount` · interfaz

Datos de cobro del destinatario. Es el oneOf que Kira discrimina por account_type.

Un destinatario = un riel. Modelarlo como jerarquia sellada y no como una bolsa de
campos opcionales es lo que impide que exista un destinatario ACH con swift_code, o una
wallet con numero de cuenta: combinaciones que la API acepta enviar y rechaza al pagar.

| Método | Descripción |
|---|---|
| `Rail rail()` |  |
| `String destination()` | Numero de cuenta o direccion de wallet, segun el riel. |
| `String docType()` |  |
| `String docNumber()` |  |

##### `RecipientAccount.Ach` · record

Cuenta ACH: bank_address viaja como texto plano.

| Componente |
|---|
| `String routingNumber` |
| `String accountNumber` |
| `BankAccountKind kind` |
| `String bankName` |
| `String bankAddressText` |
| `String docType` |
| `String docNumber` |

| Método | Descripción |
|---|---|
| `public Rail rail()` |  |
| `public String destination()` |  |

##### `RecipientAccount.Wire` · record

Cuenta WIRE: bank_address viaja como OBJETO, no como texto.

| Componente |
|---|
| `String routingNumber` |
| `String swiftCode` |
| `String accountNumber` |
| `BankAccountKind kind` |
| `String bankName` |
| `PostalAddress bankAddress` |
| `String docType` |
| `String docNumber` |

| Método | Descripción |
|---|---|
| `public Rail rail()` |  |
| `public String destination()` |  |

##### `RecipientAccount.Wallet` · record

Wallet de stablecoin. El par token/red se valida al construirla.

| Componente |
|---|
| `WalletToken token` |
| `String network` |
| `String address` |
| `String docType` |
| `String docNumber` |

| Método | Descripción |
|---|---|
| `public Rail rail()` |  |
| `public String destination()` |  |

<sub>`domain/treasury/RecipientHolder.java` · 53 líneas</sub>

#### `RecipientHolder` · record

Titular del destinatario.

No existe un campo 'holder_name' en la API: el titular se infiere de first_name +
last_name para personas y de company_name para empresas. Enviar el que no toca deja al
destinatario sin nombre.

| Componente |
|---|
| `boolean business` |
| `String firstName` |
| `String lastName` |
| `String companyName` |
| `String email` |
| `String phone` |

| Campo | Descripción |
|---|---|
| `public static final int MAX_PHONE_LENGTH = 16` |  |

| Método | Descripción |
|---|---|
| `public static RecipientHolder company(String companyName, String email, String phone)` |  |
| `public static RecipientHolder person(String firstName, String lastName, String email, String phone)` |  |
| `public String wireType()` |  |
| `public String displayName()` | Nombre legible para el directorio local. |
| `public String type()` |  |

<sub>`domain/treasury/RecipientRepository.java` · 18 líneas</sub>

#### `RecipientRepository` · interfaz

| Método | Descripción |
|---|---|
| `Recipient save(Recipient recipient)` |  |
| `Optional<Recipient> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `Optional<Recipient> findByKiraRecipientId(String kiraRecipientId)` |  |
| `List<Recipient> findActiveByTenant(TenantId tenantId)` |  |

<sub>`domain/treasury/RecipientStatus.java` · 11 líneas</sub>

#### `RecipientStatus` · enum

Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno se crea
un reemplazo y el anterior se archiva: ARCHIVED es un estado puramente local.

Valores: `ACTIVE`, `ARCHIVED`.

<sub>`domain/treasury/SupportingDocument.java` · 55 líneas</sub>

#### `SupportingDocument` · record

Documento de soporte del pago.

Aqui el archivo va SIEMPRE como data URI base64: a diferencia del KYB, este endpoint
no acepta URLs. Y el limite es de 3 MB por archivo, sobre un base64 que ya infla el
original un tercio.

| Componente |
|---|
| `String type` |
| `String file` |

| Campo | Descripción |
|---|---|
| `public static final int MAX_FILE_BYTES = 3 * 1024 * 1024` |  |
| `public static final int MAX_DOCUMENTS = 2` |  |
| `private static final List<String> TYPES = List.of("invoice", "other")` |  |

| Método | Descripción |
|---|---|
| `public static void assertValid(List<SupportingDocument> documents, NatureOfPayment nature, …)` | Un array vacio se rechaza: o no va el campo, o van uno o dos documentos. |

<sub>`domain/treasury/WalletToken.java` · 57 líneas</sub>

#### `WalletToken` · enum

Stablecoin del destinatario y las redes en las que Kira la admite.

Los pares no son intercambiables: USDC NO existe en tron. Un par invalido se rechaza
aqui porque la alternativa es descubrirlo con un pago retenido.

Valores: `USDC`, `USDT`, `COPM`.

| Método | Descripción |
|---|---|
| `WalletToken(String wireValue, List<String> networks)` |  |
| `public String wireValue()` |  |
| `public List<String> networks()` |  |
| `public static WalletToken from(String raw)` |  |
| `public void assertSupportedOn(String network)` |  |

### A.6 Dominio — compliance

<sub>`domain/compliance/AuditLog.java` · 46 líneas</sub>

#### `AuditLog` · record

Entrada inmutable de la bitacora de auditoria B2B.

Kira no ofrece historial de cambios al integrador, asi que el registro de quien hizo que
es propio. Regla que no se negocia: aqui van actor, recurso y resultado; nunca secretos,
biometria ni datos personales.

tenantId y userId son opcionales a proposito: hay acciones del sistema (reconciliacion,
proyeccion de webhooks) que no tienen persona detras.

| Componente |
|---|
| `String id` |
| `TenantId tenantId` |
| `String userId` |
| `Role userRole` |
| `String action` |
| `String resourceType` |
| `String resourceId` |
| `String changes` |
| `String ipAddress` |
| `Instant createdAt` |

| Campo | Descripción |
|---|---|
| `public static final int MAX_CHANGES_LENGTH = 4000` | Longitud maxima del JSON de cambios; el resto se recorta antes de persistir. |

<sub>`domain/compliance/AuditLogRepository.java` · 14 líneas</sub>

#### `AuditLogRepository` · interfaz

Puerto de salida de la bitacora. Solo escritura y lectura: nunca actualizacion ni borrado.

| Método | Descripción |
|---|---|
| `AuditLog append(AuditLog entry)` |  |
| `List<AuditLog> findByTenant(TenantId tenantId, int limit)` |  |

<sub>`domain/compliance/Rfi.java` · 107 líneas</sub>

#### `Rfi` · clase · `@Getter`

Agregado Rfi: requerimiento de informacion de KiraFin sobre una empresa cliente.

Nunca se crea desde aqui: Kira lo genera y nosotros lo leemos y respondemos. Los items
llegan como un array libre que cambia por tipo de requerimiento; se guardan tal cual
(JSON) porque normalizarlos obligaria a migrar el esquema cada vez que Kira pide algo
nuevo. Lo que si es del dominio es el estado, el plazo y lo que el RFI tiene bloqueado.

| Método | Descripción |
|---|---|
| `public Rfi(String id, TenantId tenantId, String kiraRfiId, String itemsPayload, Instant dueDate)` |  |
| `public static Rfi rehydrate(String id, TenantId tenantId, String kiraRfiId, RfiStatus status, …)` |  |
| `public void assertAcceptsAnswers()` | Antes de llamar a Kira: responder un RFI cerrado solo gasta la llamada y devuelve 409. |
| `public void applyRemoteStatus(RfiStatus incoming, String itemsPayload)` | Asienta lo que dice Kira. Kira es la fuente de verdad, con una excepcion: un RFI cerrado no se reabre. Los webhooks pueden llegar desordenados, y un 'answered' tardio no debe devolver a la bandeja algo ya resuelto. |
| `public void describeDueDate(Instant dueDate)` | El plazo no se prorroga aunque Kira devuelva items para otra ronda. |
| `public void describeBlocking(String type, String resourceId)` | blocking: { type: "transfer", transfer_uuid }. Lo bloqueado sigue bloqueado si el RFI vence. |
| `public boolean isOverdue(Instant now)` |  |

<sub>`domain/compliance/RfiRepository.java` · 23 líneas</sub>

#### `RfiRepository` · interfaz

| Método | Descripción |
|---|---|
| `Rfi save(Rfi rfi)` |  |
| `Optional<Rfi> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `Optional<Rfi> findByKiraRfiId(String kiraRfiId)` |  |
| `List<Rfi> findByTenant(TenantId tenantId)` |  |
| `List<Rfi> findOpenByTenant(TenantId tenantId)` |  |
| `Optional<Rfi> findOpenBlocking(String kiraResourceId)` | RFI abierto que tiene detenido el recurso de Kira indicado (pago o deposito). |

<sub>`domain/compliance/RfiStatus.java` · 46 líneas</sub>

#### `RfiStatus` · enum

Ciclo de vida de una solicitud de informacion (RFI) planteada por KiraFin.

Son los cuatro estados de Kira y ninguno mas. Un item devuelto no crea un estado propio:
el RFI vuelve a PENDING, que significa siempre "te toca responder".

Valores: `PENDING`, `ANSWERED`, `RESOLVED`, `NOT_RESOLVED`.

| Método | Descripción |
|---|---|
| `public static RfiStatus fromWire(String raw)` | Un valor desconocido cae a PENDING: mostrar de mas un RFI en la bandeja es un susto, esconder uno abierto deja bloqueado un pago hasta que vence. |
| `public boolean isTerminal()` |  |
| `public boolean isOpen()` | Mientras no este cerrado admite respuestas: tambien en ANSWERED, si Kira devuelve un item. |

### A.7 Aplicación — auth

<sub>`application/auth/LoginUseCase.java` · 51 líneas</sub>

#### `LoginUseCase` · clase · `@Service`

Sesion propia del BFF. Las credenciales de Kira nunca salen del servidor.

| Método | Descripción |
|---|---|
| `public LoginUseCase(OperatorUserRepository users, TenantRepository tenants, …)` |  |
| `public LoginResult login(String email, String rawPassword)` |  |

##### `LoginUseCase.LoginResult` · record

| Componente |
|---|
| `String accessToken` |
| `long expiresIn` |
| `String email` |
| `String role` |
| `String tenantId` |
| `String tenantName` |

### A.8 Aplicación — tenant

<sub>`application/tenant/KiraUserState.java` · 69 líneas</sub>

#### `KiraUserState` · record

Lo que POST, PUT y GET de /v1/users devuelven sobre el KYB, normalizado.

Las tres superficies comparten forma pero no la rellenan igual: el POST no trae
verification_triggered y el GET nunca devuelve el cuestionario. Aqui se leen todas
igual y se deja que el agregado decida que conserva.

| Componente |
|---|
| `String kiraUserId` |
| `TenantStatus status` |
| `MissingFields missingFields` |
| `List<EligibleProduct> eligibleProducts` |
| `Boolean verificationTriggered` |

| Método | Descripción |
|---|---|
| `public static KiraUserState from(JsonNode response)` |  |

<sub>`application/tenant/OnboardingCommands.java` · 36 líneas</sub>

#### `OnboardingCommands` · clase

##### `OnboardingCommands.RegisterBusiness` · record

Alta minima viable en Kira. Crea el registro pero NO dispara la verificacion.

source_of_funds es obligatorio aqui aunque la API lo acepte vacio: sin el, el KYB
no arranca nunca por mucho que el resto del formulario este completo, y ese fallo
es silencioso.

##### `OnboardingCommands.CompleteProfile` · record · `@NotBlank String businessLegalName,` `@NotBlank @Email String email,` `@NotBlank String sourceOfFunds) {`

Campos del perfil KYB. Se envian tal cual los nombra Kira (business_type,
formation_date, associated_persons...), porque el formulario se renderiza desde
missing_fields y traducir nombres aqui obligaria a mantener un diccionario que
cambia cada vez que Kira pide un campo nuevo.

| Componente |
|---|
| `@NotEmpty Map<String, Object> profile` |

<sub>`application/tenant/OnboardingView.java` · 41 líneas</sub>

#### `OnboardingView` · record

Proyeccion del estado del KYB para el portal.

pendingFields es lo que la pantalla debe renderizar: no hay formulario estatico, se
dibuja desde lo que Kira sigue pidiendo para el producto objetivo.

| Componente |
|---|
| `String tenantId` |
| `String name` |
| `String kiraUserId` |
| `String status` |
| `boolean verificationTriggered` |
| `List<String> pendingFields` |
| `List<EligibleProduct> eligibleProducts` |
| `boolean readyForVirtualAccounts` |
| `boolean enhancedDueDiligenceRequired` |

| Método | Descripción |
|---|---|
| `public static OnboardingView from(Tenant tenant)` |  |

<sub>`application/tenant/SubmitOnboardingService.java` · 188 líneas</sub>

#### `SubmitOnboardingService` · clase · `@Service`

Onboarding KYB de la empresa cliente contra /v1/users.

El flujo no es una linea recta sino un bucle: alta minima, y despues PUT completo + GET
hasta que no falte nada para el producto objetivo. Este servicio implementa los tres
pasos por separado para que el portal pueda repetir el del medio tantas veces como haga
falta sin volver a crear nada.

| Campo | Descripción |
|---|---|
| `private static final TypeReference<Map<String, Object>> PAYLOAD` |  |

| Método | Descripción |
|---|---|
| `public SubmitOnboardingService(TenantRepository tenants, KiraApiClient kira, AuditTrail audit, …)` |  |
| `public OnboardingView status(AuthenticatedOperator operator)` `@Transactional(readOnly = true)` |  |
| `public OnboardingView register(AuthenticatedOperator operator, …)` `@Transactional` | Paso 1: alta minima en Kira. Idempotente por dos vias: si la empresa ya tiene kiraUserId no se vuelve a llamar, y si la llamada se corta a medias el reintento reutiliza la misma clave de idempotencia ya persistida. |
| `public OnboardingView completeProfile(AuthenticatedOperator operator, …)` `@Transactional` | Paso 2: completa el perfil. Se repite tantas veces como haga falta. Kira exige el objeto COMPLETO en cada PUT: lo que no viaje se borra en silencio. Por eso se envia la fusion de lo ya enviado con lo nuevo, y no solo los campos del formulario que el usuario acaba de tocar. |
| `public OnboardingView refresh(AuthenticatedOperator operator)` `@Transactional` | Paso 3: el recurso es la autoridad. Tambien cubre el hueco de un webhook perdido. |

<sub>`application/tenant/SyncUbosService.java` · 256 líneas</sub>

#### `SyncUbosService` · clase · `@Service`

Beneficiarios finales: registro local, sincronizacion con Kira y enlaces de prueba de vida.

El registro local no es una copia por comodidad. Kira exige que 'associated_persons'
viaje COMPLETO en cada PUT —lo que no va, se borra en silencio— asi que la unica forma
de reconstruir el array es tenerlo entero de este lado.

| Método | Descripción |
|---|---|
| `public SyncUbosService(UboRepository ubos, TenantRepository tenants, …)` |  |
| `public UboView.Roster list(AuthenticatedOperator operator)` `@Transactional(readOnly = true)` |  |
| `public UboView save(AuthenticatedOperator operator, UboCommands.SaveUbo command)` `@Transactional` | Alta o edicion local. No toca Kira: eso lo hace `#syncToKira`. |
| `public OnboardingView syncToKira(AuthenticatedOperator operator)` `@Transactional` | Envia el array completo de beneficiarios a Kira. Se valida el grupo antes de llamar: sin una persona con propiedad >= 5 %, Kira acepta el PUT y deja el KYB atascado pidiendo "associated_persons:beneficial_owner". Fallar aqui es mas barato que descubrirlo tres pantallas mas adelante. |
| `public UboView.Roster requestLivenessLinks(AuthenticatedOperator operator, …)` `@Transactional` | Pide un enlace de prueba de vida por beneficiario final. Llamadas repetidas devuelven el mismo enlace salvo que cambien las URLs de redireccion, asi que reintentar es seguro. El enlace vive 7 dias y el resultado real NO llega por la landing de redireccion, sino por el webhook user.liveness_completed. |
| `public void applyLivenessResult(String personReferenceId, …)` `@Transactional` | Asienta el resultado del webhook user.liveness_completed. Es la unica fuente de verdad del resultado y llega una sola vez, sin reintentos: si no se proyecta aqui, el dato no se recupera por GET. |

<sub>`application/tenant/UboCommands.java` · 45 líneas</sub>

#### `UboCommands` · clase

##### `UboCommands.SaveUbo` · record

Alta o edicion de un beneficiario final.

hasOwnership es obligatorio y explicito: el cargo no identifica al beneficiario, y
si se omite, Kira bloquea el KYB pidiendo "associated_persons:has_ownership" sin mas
pistas. pepStatus y countryOfBirth son igual de obligatorios para Kira.

| Componente |
|---|
| `String id` |
| `String documentType` |
| `String documentNumber` |
| `@NotNull @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal ownershipPercentage` |
| `@NotBlank @Size(min = 3, max = 3) String countryOfBirth` |
| `String roleInCompany` |

##### `UboCommands.RequestLivenessLinks` · record

URLs a las que Kira devuelve a la persona tras la prueba de vida. Deben estar
preautorizadas por Kira; la landing NO es fuente de verdad del resultado.

| Componente |
|---|
| `String successUrl` |
| `String rejectUrl` |

<sub>`application/tenant/UboView.java` · 61 líneas</sub>

#### `UboView` · record

| Componente |
|---|
| `String id` |
| `String personReferenceId` |
| `String fullName` |
| `String documentType` |
| `String documentNumber` |
| `boolean hasOwnership` |
| `BigDecimal ownershipPercentage` |
| `boolean beneficialOwner` |
| `boolean hasControl` |
| `boolean signer` |
| `boolean politicallyExposed` |
| `String countryOfBirth` |
| `String roleInCompany` |
| `String livenessStatus` |
| `String livenessLink` |
| `Instant livenessExpiresAt` |

| Método | Descripción |
|---|---|
| `public static UboView from(Ubo u)` |  |

##### `UboView.Roster` · record

Vista del grupo: lo que Kira valida sobre el conjunto, no sobre cada persona.

| Componente |
|---|
| `List<UboView> members` |
| `BigDecimal totalOwnership` |
| `boolean hasBeneficialOwner` |
| `boolean livenessComplete` |

| Método | Descripción |
|---|---|
| `public static Roster from(UboRoster roster)` |  |

### A.9 Aplicación — account

<sub>`application/account/DepositView.java` · 50 líneas</sub>

#### `DepositView` · record

Deposito para el portal.

Los tres importes van por separado porque son tres hechos distintos: lo que envio el
ordenante, lo que cobro el banco y lo que quedo disponible.

| Componente |
|---|
| `String id` |
| `String kiraDepositId` |
| `String virtualAccountId` |
| `BigDecimal grossAmount` |
| `BigDecimal feeAmount` |
| `BigDecimal netAmount` |
| `String currency` |
| `String senderName` |
| `String senderAccount` |
| `String rail` |
| `String status` |
| `boolean microdeposit` |
| `boolean creditsBalance` |
| `Instant createdAt` |
| `Instant updatedAt` |

| Método | Descripción |
|---|---|
| `public static DepositView from(Deposit d)` |  |

<sub>`application/account/KiraDepositEvent.java` · 103 líneas</sub>

#### `KiraDepositEvent` · record

Evento de deposito, normalizado.

Las respuestas de deposito mezclan snake_case (deposit_id) y camelCase
(internalPaymentId) en el mismo payload, asi que cada campo se busca en las dos formas.
Los importes llegan en decimal, no en unidades menores: esa convencion es exclusiva de
las cotizaciones.

| Componente |
|---|
| `String kiraDepositId` |
| `String kiraAccountId` |
| `DepositStatus status` |
| `boolean microdeposit` |
| `BigDecimal grossAmount` |
| `BigDecimal feeAmount` |
| `BigDecimal netAmount` |
| `String currency` |
| `String senderName` |
| `String senderAccount` |
| `Rail rail` |

| Método | Descripción |
|---|---|
| `public static KiraDepositEvent from(String eventName, JsonNode payload)` |  |
| `public static KiraDepositEvent fromResource(JsonNode resource, String fallbackKiraAccountId)` | Deposito tal como lo devuelve GET /v1/virtual-accounts/{id}/deposits. No es la forma del webhook: el ordenante va anidado en 'sender', la comision en 'fees.total_fees' y el riel en 'payment_rail'. |
| `public boolean isIdentifiable()` |  |

<sub>`application/account/OpenVirtualAccountService.java` · 254 líneas</sub>

#### `OpenVirtualAccountService` · clase · `@Service`

Apertura y seguimiento de cuentas virtuales.

Abrir una cuenta exige que el KYB este VERIFIED Y que el producto este elegible: son dos
condiciones, no una. Y una vez abierta, 'aprobada' no significa que pueda mover fondos:
eso lo dice un numero de cuenta real o el evento virtual_account.activated.

| Campo | Descripción |
|---|---|
| `private static final String ACCOUNT_TYPE = "US_BANK"` | Unico valor admitido por el campo 'type'. |

| Método | Descripción |
|---|---|
| `public OpenVirtualAccountService(VirtualAccountRepository accounts, TenantRepository tenants, …)` |  |
| `public List<VirtualAccountView> list(AuthenticatedOperator operator)` `@Transactional(readOnly = true)` |  |
| `public VirtualAccountView get(AuthenticatedOperator operator, String accountId)` `@Transactional(readOnly = true)` |  |
| `public VirtualAccountView open(AuthenticatedOperator operator, …)` `@Transactional` |  |
| `public VirtualAccountView refresh(AuthenticatedOperator operator, String accountId)` `@Transactional` | El recurso es la autoridad. Tambien cubre el hueco de un evento de activacion perdido. |
| `public VirtualAccountView refreshBalance(AuthenticatedOperator operator, String accountId)` `@Transactional` | Refresca el saldo. Durante la activacion, GET /balance puede responder 400: eso no es un fallo sino "todavia calculando", y se devuelve el ultimo saldo conocido en lugar de un error. |
| `public VirtualAccountView simulateDeposit(AuthenticatedOperator operator, String accountId, …)` `@Transactional` | Simulacion de deposito. Solo existe en el sandbox; en produccion Kira responde 403. |

<sub>`application/account/RecordDepositService.java` · 179 líneas</sub>

#### `RecordDepositService` · clase · `@Service`

Depositos entrantes.

El espejo local no es una comodidad: en el sandbox un deposito entrante NO aparece en
GET /deposits, asi que el webhook es la unica constancia que va a existir de que ese
dinero llego.

| Campo | Descripción |
|---|---|
| `private static final int PAGE_SIZE = 100` | Tope de Kira por pagina en el listado de depositos de una cuenta. |
| `private static final int MAX_PAGES = 20` |  |

| Método | Descripción |
|---|---|
| `public RecordDepositService(DepositRepository deposits, VirtualAccountRepository accounts, KiraApiClient kira)` |  |
| `public List<DepositView> syncFromKira(AuthenticatedOperator operator, String accountId)` `@Transactional` | Trae de Kira los depositos de una cuenta y los asienta con la misma proyeccion que los webhooks, asi que converge en las mismas filas. Es la red de seguridad de un evento perdido (entrega unica, sin reintentos). En el sandbox Kira no devuelve nada aqui. |
| `public List<DepositView> list(AuthenticatedOperator operator, int limit)` `@Transactional(readOnly = true)` |  |
| `public List<DepositView> listByAccount(AuthenticatedOperator operator, String accountId, int limit)` `@Transactional(readOnly = true)` |  |
| `public void apply(KiraDepositEvent event)` `@Transactional` | Proyecta un evento de deposito. Es idempotente por kira_deposit_id: la familia tiene seis eventos que describen el mismo deposito en distintos momentos, y todos deben converger en una sola fila. |
| `public List<DepositView> listForTenant(TenantId tenantId, int limit)` `@Transactional(readOnly = true)` | Solo para lecturas internas del reconciliador. |

<sub>`application/account/VirtualAccountCommands.java` · 35 líneas</sub>

#### `VirtualAccountCommands` · clase

##### `VirtualAccountCommands.OpenAccount` · record

Apertura de cuenta virtual.

El banco no se pide: lo fija la configuracion del entorno, porque el valor valido
depende de si se apunta al sandbox o a produccion y equivocarlo devuelve
400 "Invalid bank".

| Componente |
|---|
| `@Size(max = 255) String description` |
| `String mode` |
| `@Size(max = 10) String currency` |

##### `VirtualAccountCommands.SimulateDeposit` · record

Solo sandbox. En produccion, Kira responde 403.

| Componente |
|---|
| `@NotNull @DecimalMin("0.01") BigDecimal amount` |
| `@Pattern(regexp = "wire\|ach") String paymentType` |

<sub>`application/account/VirtualAccountView.java` · 55 líneas</sub>

#### `VirtualAccountView` · record

Cuenta virtual para el portal.

fundsReady es la pregunta que de verdad importa y NO es lo mismo que el estado: la API
colapsa activating y active en 'approved', asi que una cuenta 'activa' puede seguir sin
poder mover fondos. Manda el numero de cuenta real o el evento de activacion.

| Componente |
|---|
| `String id` |
| `String kiraAccountId` |
| `String status` |
| `String mode` |
| `String bank` |
| `String bankName` |
| `String description` |
| `String accountNumber` |
| `String routingNumber` |
| `String currency` |
| `BigDecimal availableBalance` |
| `Instant balanceRefreshedAt` |
| `boolean balanceStale` |
| `boolean fundsReady` |
| `boolean activationDelayed` |
| `Instant createdAt` |

| Método | Descripción |
|---|---|
| `public static VirtualAccountView from(VirtualAccount a)` |  |

### A.10 Aplicación — treasury

<sub>`application/treasury/CreateQuoteService.java` · 194 líneas</sub>

#### `CreateQuoteService` · clase · `@Service`

Cotizacion de una transferencia contra POST /v1/quotations.

Se cotiza en modo redimible (con virtual_account_id) y con inverse=true: el importe que
teclea el operador es lo que recibe el destinatario, y las comisiones se suman por
encima. El modo preview (quote_for) devuelve quote_id nulo y no sirve para pagar.

| Campo | Descripción |
|---|---|
| `private static final int PLATFORM_MARKUP_BPS = 0` | Markup porcentual de la plataforma. Hoy el margen es solo fijo. |

| Método | Descripción |
|---|---|
| `public CreateQuoteService(QuotationRepository quotations, RecipientRepository recipients, …)` |  |
| `public List<QuotationView> list(AuthenticatedOperator operator, int limit)` `@Transactional(readOnly = true)` |  |
| `public QuotationView get(AuthenticatedOperator operator, String quotationId)` `@Transactional(readOnly = true)` |  |
| `public QuotationView create(AuthenticatedOperator operator, QuotationCommands.CreateQuote command)` `@Transactional` |  |

<sub>`application/treasury/ExecutePayoutService.java` · 456 líneas</sub>

#### `ExecutePayoutService` · clase · `@Service`

Preparacion, aprobacion y ejecucion de pagos.

El maker-checker vive aqui porque la API de Kira no lo ofrece a los integradores:
el pago solo sale hacia Kira despues de que un segundo operador lo autoriza.

| Campo | Descripción |
|---|---|
| `private static final Set<String> KIRA_PAYOUT_STATUSES = Set.of( ...` | Filtros que acepta GET /v1/payouts: cualquier otro parametro lo rechaza con 400. |

| Método | Descripción |
|---|---|
| `public ExecutePayoutService(PayoutRepository payouts, QuotationRepository quotations, …)` |  |
| `public PayoutPreviewView preview(AuthenticatedOperator operator, PayoutCommands.PreviewPayout command)` `@Transactional(readOnly = true)` | Vista previa de comisiones contra POST /v1/virtual-accounts/{id}/payout/preview. No reserva precio ni crea nada: sirve para mostrar el coste mientras el operador teclea. El margen de la plataforma viaja igual que en el pago sin cotizacion, para que lo que se muestra aqui sea lo que se cobraria. |
| `public List<PayoutEventView> events(AuthenticatedOperator operator, String payoutId)` `@Transactional(readOnly = true)` | Linea de tiempo del pago (events[] de GET /v1/payouts/{id}). Vacia si aun no se envio. |
| `public KiraPayoutPage kiraHistory(AuthenticatedOperator operator, String status, int page, int limit, …)` `@Transactional(readOnly = true)` | Historial de pagos de la empresa en Kira (GET /v1/payouts?user_id=...). Kira trata los pagos como globales del integrador: ademas del filtro user_id, se descarta cualquier fila de otro user. Solo se envian los filtros que Kira documenta, porque un parametro desconocido es un 400. |
| `public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command)` `@Transactional` |  |
| `public PayoutView approveAndSubmit(AuthenticatedOperator operator, String payoutId, …)` `@Transactional` |  |
| `public PayoutView reject(AuthenticatedOperator operator, String payoutId, String reason)` `@Transactional` |  |
| `public PayoutView refreshFromKira(AuthenticatedOperator operator, String payoutId)` `@Transactional` | Reconciliacion puntual: los eventos llegan una sola vez, el recurso es la autoridad final. |
| `public List<PayoutView> list(AuthenticatedOperator operator, int limit)` `@Transactional(readOnly = true)` |  |
| `public PayoutView get(AuthenticatedOperator operator, String payoutId)` `@Transactional(readOnly = true)` |  |

<sub>`application/treasury/KiraPayoutPage.java` · 32 líneas</sub>

#### `KiraPayoutPage` · record

Historial de pagos tal como lo ve Kira para la empresa, paginado por pagina (no offset).

Incluye movimientos que no nacieron en este portal (origin "deposit" o "api"). Cuando un
pago si nacio aqui, localPayoutId enlaza con /api/payouts/{id}.

| Componente |
|---|
| `List<Item> items` |
| `int page` |
| `int limit` |
| `int total` |
| `int totalPages` |

##### `KiraPayoutPage.Item` · record

| Componente |
|---|
| `String kiraPayoutId` |
| `String shortId` |
| `String localPayoutId` |
| `String virtualAccountId` |
| `String status` |
| `String origin` |
| `String fromAmount` |
| `String fromCurrency` |
| `String toAmount` |
| `String toCurrency` |
| `String paymentMethod` |
| `String senderName` |
| `String recipientName` |
| `String reference` |
| `String memo` |
| `String createdAt` |

<sub>`application/treasury/KiraQuoteResponse.java` · 100 líneas</sub>

#### `KiraQuoteResponse` · record

Respuesta de POST /v1/quotations, con los importes ya convertidos a BigDecimal.

Kira envia unidades menores mas una precision: 5000000 con precision 2 son 50.000,00 USD.
La conversion ocurre aqui y una sola vez; del agregado hacia adentro solo circulan
importes decimales.

| Componente |
|---|
| `String quoteId` |
| `Instant expiresAt` |
| `BigDecimal sourceAmount` |
| `String sourceCurrency` |
| `BigDecimal recipientAmount` |
| `String recipientCurrency` |
| `BigDecimal exchangeRate` |
| `FeeBreakdown fees` |
| `boolean balanceSufficient` |
| `String rateSource` |
| `String feesSnapshot` |

| Método | Descripción |
|---|---|
| `public static KiraQuoteResponse from(JsonNode response)` |  |

<sub>`application/treasury/KiraRecipientView.java` · 20 líneas</sub>

#### `KiraRecipientView` · record

Destinatario tal como lo tiene registrado Kira.

Sirve para conciliar el directorio local con Kira: localRecipientId es nulo si el destinatario
existe en Kira pero no se dio de alta desde este portal. La cuenta va enmascarada, igual que
en el directorio local.

| Componente |
|---|
| `String kiraRecipientId` |
| `String localRecipientId` |
| `String type` |
| `String name` |
| `String accountType` |
| `String maskedDestination` |
| `String email` |
| `String createdAt` |

<sub>`application/treasury/PayoutCommands.java` · 57 líneas</sub>

#### `PayoutCommands` · clase

##### `PayoutCommands.CreatePayout` · record

Ids del portal (los de /api/virtual-accounts y /api/recipients), no los de Kira.

| Componente |
|---|
| `@NotNull @DecimalMin(value = "0.00000001") BigDecimal amount` |
| `String quotationId` |

##### `PayoutCommands.PreviewPayout` · record

Vista previa de comisiones. Por defecto 'amount' es lo que RECIBE el destinatario, igual
que al cotizar; con recipientReceivesAmount=false es lo que sale de la cuenta.

| Componente |
|---|
| `@NotNull @DecimalMin(value = "0.01") BigDecimal amount` |
| `Boolean recipientReceivesAmount` |

##### `PayoutCommands.ApprovePayout` · record

Datos que solo se conocen al autorizar.

memo es obligatorio para WIRE en algunos bancos corresponsales y viaja en extra_info;
los documentos de soporte van como data URI base64, maximo dos y 3 MB cada uno.

| Componente |
|---|
| `String comment` |
| `String natureOfPayment` |
| `@Size(max = 255) String memo` |
| `@Size(max = SupportingDocument.MAX_DOCUMENTS) List<SupportingDocument> documents` |

##### `PayoutCommands.RejectPayout` · record

| Componente |
|---|
| `@NotBlank String reason` |

<sub>`application/treasury/PayoutEventView.java` · 6 líneas</sub>

#### `PayoutEventView` · record

Un paso de la linea de tiempo de un pago (events[] de GET /v1/payouts/{id}).

| Componente |
|---|
| `String eventId` |
| `String status` |
| `String message` |
| `String createdAt` |

<sub>`application/treasury/PayoutPreviewView.java` · 18 líneas</sub>

#### `PayoutPreviewView` · record

Vista previa de un pago: lo que sale de la cuenta, lo que llega y las comisiones, sin
reservar precio. Para cerrar el precio se cotiza (POST /api/quotations).

fees va tal cual lo desglosa Kira: su forma depende del riel.

| Componente |
|---|
| `String amount` |
| `String currency` |
| `String recipientAmount` |
| `String recipientCurrency` |
| `Map<String, Object> fees` |

<sub>`application/treasury/PayoutView.java` · 72 líneas</sub>

#### `PayoutView` · record

Proyeccion estable para el frontend: no expone la forma cambiante de la respuesta de Kira.

amount es lo que recibe el destinatario y totalDebitAmount el bruto que sale de la cuenta.
referenceNumber (IMAD / ACH trace / UETR) es el comprobante que reclama el cliente final.
blockedByRfiId no es nulo cuando un RFI abierto de Kira tiene el pago detenido: el portal
lo muestra como "detenido" y enlaza al RFI.

| Componente |
|---|
| `String id` |
| `String virtualAccountId` |
| `String recipientId` |
| `String quotationId` |
| `BigDecimal amount` |
| `String currency` |
| `BigDecimal kiraFee` |
| `BigDecimal platformFee` |
| `BigDecimal totalFee` |
| `BigDecimal totalDebitAmount` |
| `String approvalState` |
| `String status` |
| `boolean terminal` |
| `String makerUserId` |
| `String approverUserId` |
| `boolean priceLocked` |
| `String kiraPayoutId` |
| `String referenceNumber` |
| `String paymentMethod` |
| `String errorCode` |
| `String blockedByRfiId` |
| `Instant createdAt` |
| `Instant updatedAt` |

| Método | Descripción |
|---|---|
| `public static PayoutView from(Payout p)` |  |
| `public static PayoutView from(Payout p, String blockedByRfiId)` |  |

<sub>`application/treasury/QuotationCommands.java` · 31 líneas</sub>

#### `QuotationCommands` · clase

##### `QuotationCommands.CreateQuote` · record

Peticion de cotizacion.

'amount' es lo que RECIBE el destinatario: las comisiones se suman por encima y el
debito de la cuenta virtual es mayor. El desglose vuelve en la respuesta.

'rail' es opcional: por defecto se deriva del destinatario, que es la unica fuente
valida. Enviarlo sirve para elegir entre ACH_STANDARD y ACH_SAME_DAY.

| Componente |
|---|
| `@NotNull @DecimalMin(value = "0.01") BigDecimal amount` |
| `String rail` |
| `String targetCurrency` |

<sub>`application/treasury/QuotationView.java` · 56 líneas</sub>

#### `QuotationView` · record

La cotizacion tal como debe verla el tesorero.

secondsToExpiry alimenta el contador: al llegar a cero el boton de pago se deshabilita,
porque una cotizacion vencida ejecuta a otra tasa.

| Componente |
|---|
| `String id` |
| `String kiraQuoteId` |
| `String virtualAccountId` |
| `String recipientId` |
| `String rail` |
| `BigDecimal originAmount` |
| `BigDecimal destinationAmount` |
| `String destinationCurrency` |
| `BigDecimal exchangeRate` |
| `BigDecimal kiraFee` |
| `BigDecimal platformFee` |
| `BigDecimal totalFee` |
| `BigDecimal totalDebitAmount` |
| `boolean balanceSufficient` |
| `boolean fallbackRate` |
| `String status` |
| `Instant expiresAt` |
| `long secondsToExpiry` |

| Método | Descripción |
|---|---|
| `public static QuotationView from(Quotation q)` |  |

<sub>`application/treasury/RecipientCommands.java` · 63 líneas</sub>

#### `RecipientCommands` · clase

##### `RecipientCommands.Address` · record

Direccion postal. El pais del destinatario va en ISO-2 ("US"), no en ISO-3.

| Componente |
|---|
| `String streetName` |
| `String city` |
| `String state` |
| `String postalCode` |
| `@Size(min = 2, max = 2) String country` |

##### `RecipientCommands.RegisterRecipient` · record

Alta de un destinatario. El bloque que se rellena depende del riel:
ACH y WIRE llevan datos bancarios, WALLET lleva token, red y direccion.

Un destinatario = un riel. Enviar campos de dos rieles a la vez se rechaza.

| Componente |
|---|
| `boolean business` |
| `String firstName` |
| `String lastName` |
| `String companyName` |
| `@Size(max = 16) String phone` |
| `Address address` |
| `String routingNumber` |
| `String swiftCode` |
| `String accountNumber` |
| `String accountKind` |
| `String bankName` |
| `String bankAddressText` |
| `Address bankAddress` |
| `String token` |
| `String network` |
| `String walletAddress` |
| `String docType` |
| `String docNumber` |

##### `RecipientCommands.ArchiveRecipient` · record

Motivo del archivado. Kira no borra: se archiva local y se crea un reemplazo.

| Componente |
|---|
| `String replacedByRecipientId` |

<sub>`application/treasury/RecipientView.java` · 60 líneas</sub>

#### `RecipientView` · record

Destinatario para el portal.

La cuenta va enmascarada: el directorio no necesita mostrar el numero completo, y cada
pantalla que lo muestre es una copia mas de un dato bancario.

| Componente |
|---|
| `String id` |
| `String kiraRecipientId` |
| `String name` |
| `String rail` |
| `String network` |
| `String bankName` |
| `String maskedDestination` |
| `String status` |
| `boolean registeredInKira` |
| `boolean alreadyExisted` |
| `String replacedByRecipientId` |
| `PostalAddress bankAddress` |
| `Instant createdAt` |

| Método | Descripción |
|---|---|
| `public static RecipientView from(Recipient r)` |  |
| `public static RecipientView from(Recipient r, boolean alreadyExisted)` |  |

<sub>`application/treasury/RegisterRecipientService.java` · 308 líneas</sub>

#### `RegisterRecipientService` · clase · `@Service`

Alta de destinatarios contra POST /v1/recipients.

Un destinatario = un riel, y ese riel es el que va a usar cada pago suyo. Kira no ofrece
actualizacion ni borrado: corregir uno significa dar de alta un reemplazo y archivar el
anterior, asi que aqui no hay un metodo 'update'.

| Método | Descripción |
|---|---|
| `public RegisterRecipientService(RecipientRepository recipients, TenantRepository tenants, …)` |  |
| `public List<RecipientView> list(AuthenticatedOperator operator)` `@Transactional(readOnly = true)` |  |
| `public RecipientView get(AuthenticatedOperator operator, String recipientId)` `@Transactional(readOnly = true)` |  |
| `public List<KiraRecipientView> listInKira(AuthenticatedOperator operator)` `@Transactional(readOnly = true)` | Destinatarios de la empresa en Kira (GET /v1/recipients?user_id=...). Kira no pagina esta lista. Se descarta cualquier destinatario que no pueda confirmarse como de esta empresa. |
| `public KiraRecipientView getInKira(AuthenticatedOperator operator, String recipientId)` `@Transactional(readOnly = true)` | Un destinatario del directorio, leido de Kira. |
| `public RecipientView register(AuthenticatedOperator operator, …)` `@Transactional` |  |
| `public RecipientView archive(AuthenticatedOperator operator, String recipientId, …)` `@Transactional` | Archiva un destinatario. Es un reemplazo logico: Kira no borra, asi que el registro remoto sigue existiendo y lo que cambia es que aqui deja de ofrecerse para pagos. |

### A.11 Aplicación — compliance

<sub>`application/compliance/AnswerRfiService.java` · 586 líneas</sub>

#### `AnswerRfiService` · clase · `@Service`

Solicitudes de informacion (RFI) de Kira: bandeja, sincronizacion y respuesta.

Nunca se crea un RFI desde aqui. Kira lo genera, casi siempre para detener una
transferencia o un KYB en revision, y su reloj (due_at) no se prorroga: al vencer cierra
en not_resolved y lo bloqueado sigue bloqueado. Por eso la bandeja importa.

Kira trata los RFIs como globales del integrador; el aislamiento por organizacion lo
imponemos aqui atribuyendo cada RFI a su empresa por user_id.

| Campo | Descripción |
|---|---|
| `private static final int PAGE_SIZE = 100` | Tope de Kira por pagina. |
| `private static final int MAX_PAGES = 20` | Corte de seguridad: 2.000 RFIs abiertos de una empresa no es un caso, es un bucle. |
| `static final int MAX_FILES = 20` | Limites de Kira para los archivos de un item documento. |
| `static final long MAX_FILE_BYTES = 30L * 1024 * 1024` |  |
| `static final List<String> DEFAULT_MIME_TYPES = ...` | MIME aceptados por Kira; el answer_spec de cada item puede estrecharlos. |

| Método | Descripción |
|---|---|
| `public AnswerRfiService(RfiRepository rfis, TenantRepository tenants, PayoutRepository payouts, …)` |  |
| `public List<RfiView> list(AuthenticatedOperator operator, boolean onlyOpen)` `@Transactional(readOnly = true)` |  |
| `public RfiView get(AuthenticatedOperator operator, String rfiId)` `@Transactional(readOnly = true)` |  |
| `public List<RfiView> sync(AuthenticatedOperator operator)` `@Transactional` | Trae de Kira los RFIs de la empresa y los asienta. Es la red de seguridad del webhook: rfi.* exige suscripcion explicita en Kira y, como todo webhook, se entrega una sola vez. |
| `public int syncForTenant(TenantId tenantId)` `@Transactional` | Lo mismo, sin operador: lo usa el worker de reconciliacion, que no actua en nombre de nadie. Devuelve cuantos RFIs quedaron asentados. |
| `public RfiView refresh(AuthenticatedOperator operator, String rfiId)` `@Transactional` |  |
| `public RfiView answer(AuthenticatedOperator operator, String rfiId, RfiCommands.AnswerItems command)` `@Transactional(noRollbackFor = DomainException.class)` | Responde items de texto con PATCH /v1/rfis/{id}/items. Se valida todo antes de llamar, y lo que Kira rechace se devuelve por item_id. Tras un PATCH aceptado se relee el RFI: el estado del RFI (answered o sigue pending, si la respuesta fue parcial) lo decide Kira, no la respuesta del PATCH. noRollbackFor: ante un 409 se asienta el cierre antes de avisar al operador. |
| `public RfiView uploadDocuments(AuthenticatedOperator operator, String rfiId, String itemId, …)` `@Transactional(noRollbackFor = DomainException.class)` | Sube archivos a un item de tipo documento (POST /v1/rfis/{id}/items/{item}/documents). Se valida contra los limites de Kira y el answer_spec del item antes de enviar nada: un archivo rechazado por Kira significa haber subido hasta 20 x 30 MB para nada. |
| `public RfiView removeDocument(AuthenticatedOperator operator, String rfiId, String itemId, String documentId)` `@Transactional(noRollbackFor = DomainException.class)` | Elimina un archivo. Kira no deja borrar el ultimo de un item ya respondido. |
| `public RfiDocumentLink documentLink(AuthenticatedOperator operator, String rfiId, String itemId, …)` `@Transactional(readOnly = true)` | Enlace temporal de descarga. La URL no se registra: es una credencial al portador. |
| `public void applyWebhook(String kiraRfiId, String rawStatus)` | Proyeccion de la familia rfi.* de webhooks. Sin @Transactional a proposito: corre dentro de la transaccion del procesador de webhooks, y un fallo al consultar Kira debe quedar como processing_error del evento, no marcar la transaccion entera para rollback y perder la fila del evento. |

<sub>`application/compliance/RfiAnswerRejectedException.java` · 25 líneas</sub>

#### `RfiAnswerRejectedException` · clase

Una o varias respuestas de un RFI no son validas. El error va POR item_id: el PATCH de
Kira es all-or-nothing, asi que ningun item se guardo y el portal no debe marcar ninguno
como respondido.

| Método | Descripción |
|---|---|
| `public RfiAnswerRejectedException(String message, Map<String, String> itemErrors)` |  |
| `public Map<String, String> itemErrors()` |  |

<sub>`application/compliance/RfiCommands.java` · 35 líneas</sub>

#### `RfiCommands` · clase

##### `RfiCommands.AnswerItems` · record

Respuesta a uno o varios items. Responder un subconjunto es valido.

Un item 'document' se responde subiendo archivos y nunca lleva answer_value, asi que
aqui se rechaza antes de llamar a Kira.

| Componente |
|---|
| `@NotEmpty @Valid List<ItemAnswer> items` |

##### `RfiCommands.ItemAnswer` · record

answerValue es texto, numero o booleano segun el answer_type del item (text_short,
number, boolean, choice, date, identifier...). Kira lo valida contra el answer_spec.

| Componente |
|---|
| `@NotBlank String itemId` |
| `@NotNull Object answerValue` |

##### `RfiCommands.UploadedFile` · record

Archivo recibido del portal para un item de tipo documento.

| Componente |
|---|
| `String fileName` |
| `String contentType` |
| `byte[] content` |

<sub>`application/compliance/RfiDocumentLink.java` · 13 líneas</sub>

#### `RfiDocumentLink` · record

Enlace temporal de descarga de un archivo de un RFI.

La URL es una credencial al portador que caduca en minutos: el portal la abre al momento y
pide otra si caduca. No se guarda ni se registra en ningun log.

| Componente |
|---|
| `String downloadUrl` |
| `Instant expiresAt` |

<sub>`application/compliance/RfiView.java` · 71 líneas</sub>

#### `RfiView` · record

RFI para la bandeja y el formulario de respuesta.

Los items van tal como los entrega Kira: el formulario se genera desde answer_spec y
cada tipo nuevo de requerimiento debe poder mostrarse sin desplegar el BFF.

| Componente |
|---|
| `String id` |
| `String kiraRfiId` |
| `String status` |
| `boolean open` |
| `boolean overdue` |
| `Instant dueDate` |
| `int totalItems` |
| `int pendingItems` |
| `List<Map<String, Object>> items` |
| `Blocking blocking` |
| `Instant createdAt` |
| `Instant updatedAt` |

| Método | Descripción |
|---|---|
| `public static RfiView from(Rfi rfi, List<Map<String, Object>> items, Payout blockedPayout, …)` |  |

##### `RfiView.Blocking` · record

Lo que el RFI tiene detenido: una transferencia (type "transfer") o un deposito
(type "virtual_account_deposit"). payoutId / depositId son los ids del portal; van nulos
si Kira bloquea algo que este portal no conoce.

| Componente |
|---|
| `String type` |
| `String kiraResourceId` |
| `String payoutId` |
| `String payoutStatus` |
| `String depositId` |
| `String depositStatus` |

### A.12 Aplicación — reference

<sub>`application/reference/CountryView.java` · 15 líneas</sub>

#### `CountryView` · record

Pais soportado por Kira. alpha3 es el codigo ISO-3 que piden la empresa y sus UBOs;
subdivisions alimenta el selector de estado o departamento, y postalCodeFormat es la
expresion regular (sintaxis Ruby, \A...\Z) con la que Kira valida el codigo postal.

| Componente |
|---|
| `String name` |
| `String alpha3` |
| `String postalCodeFormat` |
| `List<Subdivision> subdivisions` |

##### `CountryView.Subdivision` · record

| Componente |
|---|
| `String name` |
| `String code` |

<sub>`application/reference/ReferenceCatalogService.java` · 57 líneas</sub>

#### `ReferenceCatalogService` · clase · `@Service`

Catalogos de referencia de Kira.

El de paises es estable (sin cambios desde 2025-01-01) y lo usan todos los formularios de
direccion: se cachea 24 h para no gastar una llamada autenticada cada vez que se pinta uno.
Un fallo no se cachea: la siguiente peticion vuelve a intentarlo.

| Campo | Descripción |
|---|---|
| `private static final String COUNTRIES = "countries"` |  |

| Método | Descripción |
|---|---|
| `public ReferenceCatalogService(KiraApiClient kira)` |  |
| `public List<CountryView> countries()` |  |

### A.13 Aplicación — webhook

<sub>`application/webhook/KiraWebhookEnvelope.java` · 40 líneas</sub>

#### `KiraWebhookEnvelope` · record

Normaliza las dos envolturas activas de Kira.

- Plana:  { event, data { event_id, status, ... } }
- V2 de payout.status_changed: { event, data { event_id, event_type, created_at,
data { status EN MAYUSCULAS, previous_status, ... } } }

En ambas el identificador para deduplicar esta en data.event_id; no existe a nivel raiz.

| Componente |
|---|
| `String eventName` |
| `String eventId` |
| `JsonNode payload` |
| `boolean nested` |

| Método | Descripción |
|---|---|
| `public static KiraWebhookEnvelope from(JsonNode root)` |  |
| `public String rawStatus()` | Estado tal como lo trae el evento. Siempre debe compararse sin distinguir mayusculas. |
| `public String text(String field)` |  |

<sub>`application/webhook/ProcessWebhookUseCase.java` · 304 líneas</sub>

#### `ProcessWebhookUseCase` · clase · `@Service`

Procesamiento asincrono de los eventos de Kira.

El ingress ya respondio 2xx: aqui no se puede pedir un reintento al emisor porque no lo hay.
La idempotencia se apoya en la unicidad de data.event_id en base de datos.

| Método | Descripción |
|---|---|
| `public ProcessWebhookUseCase(WebhookEventJpaRepository events, PayoutRepository payouts, …)` |  |
| `public void enqueue(String rawPayload)` `@Async(AsyncConfig.WEBHOOK_EXECUTOR)` |  |
| `public void process(String rawPayload) throws Exception` `@Transactional` |  |
| `public void reproject(WebhookEventEntity stored) throws Exception` `@Transactional` | Reintenta la proyeccion de un evento ya almacenado que nunca se proyecto. No se puede reutilizar `#process(String)`: ese metodo empieza deduplicando por event_id y, como la fila ya existe, saldria sin proyectar nada, que es justo lo contrario de lo que se busca aqui. Deja que la excepcion suba: quien llama decide si la fila queda marcada con el error o si el lote entero se corta (por ejemplo, cuando faltan las credenciales de Kira). |

### A.14 Infraestructura — security

<sub>`infrastructure/security/AuthenticatedOperator.java` · 9 líneas</sub>

#### `AuthenticatedOperator` · record

Principal autenticado. Se expone como principal de Spring Security.

| Componente |
|---|
| `String userId` |
| `String email` |
| `TenantId tenantId` |
| `Role role` |

<sub>`infrastructure/security/BffSecurityProperties.java` · 12 líneas</sub>

#### `BffSecurityProperties` · record · `@ConfigurationProperties`

| Componente |
|---|
| `String jwtSecret` |
| `@DefaultValue("autransactional-bff"` |

<sub>`infrastructure/security/JwtService.java` · 61 líneas</sub>

#### `JwtService` · clase · `@Service`

Emite y verifica el JWT propio del BFF. Nada de esto viaja a Kira.

| Campo | Descripción |
|---|---|
| `private static final String CLAIM_TENANT = "tenant_id"` |  |
| `private static final String CLAIM_ROLE = "role"` |  |
| `private static final String CLAIM_USER_ID = "uid"` |  |

| Método | Descripción |
|---|---|
| `public JwtService(BffSecurityProperties properties)` |  |
| `public String issue(OperatorUser user)` |  |
| `public long expiresInSeconds()` |  |
| `public AuthenticatedOperator verify(String token)` | Lanza JWTVerificationException si la firma, el emisor o la vigencia no cuadran. |

<sub>`infrastructure/security/JwtTenantFilter.java` · 72 líneas</sub>

#### `JwtTenantFilter` · clase · `@Component`

Autentica la peticion con el JWT propio del BFF y fija la organizacion en el contexto del hilo.
Un token invalido no autentica y no fija tenant: la peticion sigue como anonima y es
la cadena de autorizacion la que decide si el recurso exige sesion.

| Campo | Descripción |
|---|---|
| `private static final String BEARER = "Bearer "` |  |

| Método | Descripción |
|---|---|
| `public JwtTenantFilter(JwtService jwtService)` |  |
| `protected boolean shouldNotFilter(HttpServletRequest request)` |  |
| `protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, …)` |  |

<sub>`infrastructure/security/SecurityConfig.java` · 43 líneas</sub>

#### `SecurityConfig` · clase · `@Configuration` `@EnableMethodSecurity` `@EnableConfigurationProperties`

| Método | Descripción |
|---|---|
| `public SecurityFilterChain filterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter) throws Exception` `@Bean` |  |
| `public PasswordEncoder passwordEncoder()` `@Bean` |  |

<sub>`infrastructure/security/TenantContext.java` · 38 líneas</sub>

#### `TenantContext` · clase

Contexto de la organizacion activa para el hilo que atiende la peticion.
Regla de oro: siempre limpiar en un finally, o el siguiente request reutiliza el hilo
del pool y hereda el tenant equivocado.

| Campo | Descripción |
|---|---|
| `private static final ThreadLocal<TenantId> CURRENT = new ThreadLocal<>()` |  |

| Método | Descripción |
|---|---|
| `public static void set(TenantId tenantId)` |  |
| `public static TenantId get()` |  |
| `public static TenantId require()` |  |
| `public static void clear()` |  |

### A.15 Infraestructura — kira

<sub>`infrastructure/kira/KiraAmounts.java` · 107 líneas</sub>

#### `KiraAmounts` · clase

Conversion entre los importes del dominio (BigDecimal) y las dos formas de onda que
conviven en la API de Kira. Vive en un solo sitio a proposito.

Kira expresa los importes en unidades menores mas una precision: 5000000 con
precision 2 son 50.000,00 USD, y 49920000000 con precision 6 son 49.920,00 USDC.
La formula es amount / 10^precision y NUNCA se calcula con float: un centavo perdido
por redondeo binario es un descuadre contable.

Y hay una inconsistencia real de la API que este archivo encapsula: el markup del
cliente viaja como entero en unidades menores + puntos basicos en POST /v1/quotations,
pero como cadena decimal en POST /payout. Convertirlo en dos sitios distintos es
garantizar que un dia dejen de coincidir.

| Campo | Descripción |
|---|---|
| `public static final int FIAT_PRECISION = 2` | Precision de las monedas fiat en la API. |
| `public static final int STABLECOIN_PRECISION = 6` | Precision de las stablecoins (USDC, USDT). |
| `public static final int MAX_PERCENTAGE_BPS = 10000` | Tope de los puntos basicos admitidos por Kira. |

| Método | Descripción |
|---|---|
| `public static BigDecimal fromMinor(long amount, int precision)` | amount / 10^precision, con BigDecimal. Es la formula G5 del documento de integracion. |
| `public static long toMinor(BigDecimal amount, int precision)` |  |
| `public static String amountString(BigDecimal amount)` | Formato del campo 'amount': patron ^\d+\.\d{2}$ exactamente. Ni mas ni menos decimales, y "0.00" se rechaza del lado de Kira. |
| `public static Map<String, Object> markupForQuotation(BigDecimal fixedFee, int percentageBps)` | client_markup para POST /v1/quotations: entero en unidades menores + puntos basicos. Es el ingreso de la plataforma, y vuelve en totals.client_markup_total. |
| `public static Map<String, Object> markupForPayout(BigDecimal fixedFee, int percentageBps)` | El MISMO markup para POST /payout y /payout/preview, donde la API lo espera como cadenas decimales. Misma cifra, otra forma de onda: por eso las dos funciones estan juntas. percentage_fee es una FRACCION entre 0 y 1 ("0.01" = 1 %), no un porcentaje ni puntos basicos. Por eso se divide entre 10.000 y no entre 100: con 100, 50 bps viajaban como "0.50" y Kira cobraria un 50 %. Cuatro decimales representan exacto cualquier bps. |
| `public static int precisionOf(String currency)` | Precision por defecto de una moneda cuando la respuesta no la trae. |

<sub>`infrastructure/kira/KiraApiClient.java` · 310 líneas</sub>

#### `KiraApiClient` · clase · `@Component`

Adaptador HTTP unico hacia KiraFin.

Responsabilidades que la documentacion exige y que ningun caso de uso debe repetir:
- x-api-key en TODA peticion, incluida POST /auth.
- Authorization: Bearer en toda peticion salvo POST /auth.
- X-Api-Version en cada peticion mientras la cuenta no este fijada. Los RFIs la
sobrescriben: solo existen en 2026-06-01, y la cabecera por peticion gana al pin.
- Idempotency-Key (UUID v4) en POST /v1/users, /v1/recipients, /v1/virtual-accounts
y /v1/virtual-accounts/{id}/payout.
- Reautenticar y reintentar una vez ante un 401.
- Normalizar las varias formas de error que conviven hoy en la API.

| Campo | Descripción |
|---|---|
| `static final String RFI_API_VERSION = "2026-06-01"` | Las seis rutas de RFI solo existen en esta version; con 2026-04-14 no se encuentran. |
| `static final String QUOTATION_API_VERSION = "2026-06-01"` | La cotizacion desglosada (fees[], totals, pricing_context) solo existe desde esta version; con 2026-04-14 la respuesta es una forma simple sin totals, y de ahi salen las comisiones reales que hereda el pago. |

| Método | Descripción |
|---|---|
| `public KiraApiClient(RestClient kiraRestClient, …)` |  |
| `public JsonNode getUser(String userId)` |  |
| `public JsonNode createUser(Object body, IdempotencyKey key)` |  |
| `public JsonNode updateUser(String userId, Object body)` | PUT, no PATCH: PATCH no esta soportado en esta ruta. Solo se escriben los campos enviados. |
| `public JsonNode requestLivenessLink(String userId, Object body)` | Un enlace por cada beneficiario final, con vigencia de 7 dias. Llamadas repetidas devuelven el mismo enlace salvo que cambie 'redirect', cuyas URLs deben estar preautorizadas por Kira. Da 422 si la verificacion aun no se ha disparado. |
| `public JsonNode listVirtualAccounts(Map<String, ?> query)` |  |
| `public JsonNode getVirtualAccount(String virtualAccountId)` |  |
| `public JsonNode createVirtualAccount(Object body, IdempotencyKey key)` |  |
| `public JsonNode getVirtualAccountBalance(String virtualAccountId)` | Devuelve 400 mientras la cuenta sigue activandose; 200 con available_balance si esta activa. |
| `public JsonNode simulateDeposit(String virtualAccountId, Object body)` | Solo sandbox: acredita saldo de inmediato. En produccion responde 403. El monto 11 es un valor magico que devuelve estado 'refunded', para probar esa rama. |
| `public JsonNode listAccountDeposits(String virtualAccountId, Map<String, ?> query)` | Array desnudo, paginado con limit (1-100) + offset. |
| `public JsonNode listRecipients(String userId, Map<String, ?> extraQuery)` | user_id es obligatorio: omitirlo devuelve 400. |
| `public JsonNode getRecipient(String recipientId)` |  |
| `public KiraResponse createRecipient(Object body, IdempotencyKey key)` | Devuelve el codigo de estado porque un 202 significa "ya existia": es un exito y hay que poder distinguirlo del 201 de alta nueva. |
| `public JsonNode createQuotation(Object body)` |  |
| `public JsonNode previewPayout(String virtualAccountId, Object body)` |  |
| `public JsonNode executePayout(String virtualAccountId, Object body, IdempotencyKey key)` | El 201 responde status "created" en minusculas y el identificador en el campo id. |
| `public JsonNode getPayout(String payoutId)` | El GET responde status en MAYUSCULAS y el identificador en payout_id. |
| `public JsonNode listPayouts(Map<String, ?> query)` |  |
| `public JsonNode listRfis(Map<String, ?> query)` | Pagina con limit + offset (tope 100), no con page como pagos y depositos. |
| `public JsonNode getRfi(String rfiId)` |  |
| `public JsonNode answerRfiItems(String rfiId, Object body)` | All-or-nothing: si un item no cumple su answer_spec, 422 y no se guarda ninguno. 409 si esta cerrado. |
| `public JsonNode uploadRfiDocuments(String rfiId, String itemId, List<KiraFile> files)` | Sube archivos a un item de tipo documento. Multipart con la parte 'files' repetida; maximo 20 archivos y 30 MB cada uno. 409 si el RFI esta cerrado, 422 si un archivo no cumple el answer_spec del item. |
| `public JsonNode removeRfiDocument(String rfiId, String itemId, String documentId)` | 422 "The last file cannot be removed": un item respondido necesita al menos un archivo. |
| `public JsonNode getRfiDocumentLink(String rfiId, String itemId, String documentId)` | La URL es una credencial al portador que caduca en minutos: no se guarda ni se registra. |
| `public JsonNode listCountries()` | Ruta verificada: /v1/countries. /countries o /api/countries responden 403. |
| `public JsonNode exchange(HttpMethod method, String path, Object body, IdempotencyKey idempotencyKey)` |  |
| `public KiraResponse exchangeWithStatus(HttpMethod method, String path, Object body, …)` |  |

<sub>`infrastructure/kira/KiraApiException.java` · 24 líneas</sub>

#### `KiraApiException` · clase · `@Getter`

Error devuelto por Kira, ya normalizado.

| Método | Descripción |
|---|---|
| `public KiraApiException(int statusCode, String code, String message, String rawBody)` |  |
| `public boolean isUnauthorized()` |  |

<sub>`infrastructure/kira/KiraAuthResponse.java` · 17 líneas</sub>

#### `KiraAuthResponse` · record · `@JsonIgnoreProperties`

Envoltura estandar { message, data } de POST /auth.

| Componente |
|---|
| `String message` |
| `Data data` |

##### `KiraAuthResponse.Data` · record · `@JsonIgnoreProperties`

| Componente |
|---|
| `@JsonProperty("access_token") String accessToken` |
| `@JsonProperty("expires_in") Long expiresIn` |
| `@JsonProperty("token_type") String tokenType` |

<sub>`infrastructure/kira/KiraClientConfig.java` · 47 líneas</sub>

#### `KiraClientConfig` · clase · `@Configuration` `@EnableConfigurationProperties`

| Método | Descripción |
|---|---|
| `public RestClient kiraRestClient(KiraProperties properties)` `@Bean` |  |
| `public Cache<String, String> kiraTokenCache(KiraProperties properties)` `@Bean` | Cache del bearer token de Kira. Vive 3600s; expiramos antes por el margen configurado para no usar nunca un token a punto de vencer. |

<sub>`infrastructure/kira/KiraCredentialManager.java` · 97 líneas</sub>

#### `KiraCredentialManager` · clase · `@Service`

Obtiene y cachea el bearer token de Kira.

POST /auth es el unico endpoint que se autentica solo con x-api-key; el cuerpo lleva
client_id y password. El token vive 3600s y no hay refresh token: se vuelve a autenticar.
Cacheamos con margen para no llamar en cada peticion, e invalidamos ante cualquier 401.

| Campo | Descripción |
|---|---|
| `public static final String CACHE_KEY = "kira-access-token"` |  |

| Método | Descripción |
|---|---|
| `public KiraCredentialManager(RestClient kiraRestClient, …)` |  |
| `public String getAccessToken()` |  |
| `public void invalidate()` | Se invoca cuando una llamada responde 401: el siguiente getAccessToken reautentica. |

<sub>`infrastructure/kira/KiraErrorParser.java` · 58 líneas</sub>

#### `KiraErrorParser` · clase · `@Component`

Kira convive hoy con varias formas de error: {error, details}, {message},
{code, error, message} y {statusCode, error, message}. La documentacion advierte
explicitamente de no escribir un parser que asuma una sola forma.

| Método | Descripción |
|---|---|
| `public KiraErrorParser(ObjectMapper objectMapper)` |  |
| `public KiraApiException parse(int statusCode, String body)` |  |

<sub>`infrastructure/kira/KiraFile.java` · 6 líneas</sub>

#### `KiraFile` · record

Archivo que se reenvia a Kira en una peticion multipart.

| Componente |
|---|
| `String fileName` |
| `String contentType` |
| `byte[] content` |

<sub>`infrastructure/kira/KiraNotConfiguredException.java` · 13 líneas</sub>

#### `KiraNotConfiguredException` · clase

Faltan las credenciales de Kira en este entorno. No es un fallo de la peticion ni de Kira:
la integracion no esta configurada, y el portal debe decirlo asi en vez de "error inesperado".

| Método | Descripción |
|---|---|
| `public KiraNotConfiguredException(String message)` |  |

<sub>`infrastructure/kira/KiraProperties.java` · 27 líneas</sub>

#### `KiraProperties` · record · `@ConfigurationProperties`

| Componente |
|---|
| `@DefaultValue("https://api.balampay.com/sandbox") String baseUrl` |
| `String apiKey` |
| `String clientId` |
| `String password` |
| `@DefaultValue("2026-04-14") String apiVersion` |
| `String webhookSecret` |
| `@DefaultValue("slovak_savings_bank"` |

<sub>`infrastructure/kira/KiraResponse.java` · 22 líneas</sub>

#### `KiraResponse` · record

Respuesta de Kira con su codigo de estado.

Hace falta para POST /v1/recipients: un 202 significa "ya existia, te devuelvo el
registro existente" y es un exito, no un error. Sin el codigo no hay forma de
distinguirlo de un alta nueva, y el portal debe decir "destino ya registrado".

| Componente |
|---|
| `int status` |
| `JsonNode body` |

| Método | Descripción |
|---|---|
| `public boolean alreadyExisted()` |  |
| `public JsonNode data()` |  |

<sub>`infrastructure/kira/KiraWebhookVerifier.java` · 56 líneas</sub>

#### `KiraWebhookVerifier` · clase · `@Component`

Verifica la cabecera x-signature-sha256: HMAC-SHA256 en hexadecimal sobre los BYTES CRUDOS
del cuerpo, con el secreto de firma de la URL a la que llego la entrega.

Dos reglas que la documentacion subraya:
- no re-serializar el JSON antes de firmar (cambian espacios y orden de claves);
- comparar en tiempo constante.

| Campo | Descripción |
|---|---|
| `public static final String SIGNATURE_HEADER = "x-signature-sha256"` |  |
| `private static final String ALGORITHM = "HmacSHA256"` |  |

| Método | Descripción |
|---|---|
| `public KiraWebhookVerifier(KiraProperties properties)` |  |
| `public boolean isConfigured()` |  |
| `public boolean verify(byte[] rawBody, String signatureHeader)` |  |

### A.16 Infraestructura — persistence

<sub>`infrastructure/persistence/AuditLogEntity.java` · 58 líneas</sub>

#### `AuditLogEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Bitacora de auditoria B2B. Tabla `audit_logs`.
Kira no ofrece historial de cambios al integrador, asi que el registro es propio.

| Campo | Descripción |
|---|---|
| `private String userRole` | tesoreria_maker, tesoreria_approver, compliance_internal, admin, read_only. |
| `private String changes` | Detalle del cambio en JSON. Nunca secretos, biometria ni datos personales. |

<sub>`infrastructure/persistence/AuditLogJpaRepository.java` · 11 líneas</sub>

#### `AuditLogJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `List<AuditLogEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId)` |  |

<sub>`infrastructure/persistence/DepositEntity.java` · 76 líneas</sub>

#### `DepositEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Historial de depositos entrantes. Tabla `deposits`.

| Campo | Descripción |
|---|---|
| `private boolean microdeposit = false` | Deposito de verificacion de cuenta: no es un ingreso real y no suma saldo. |

<sub>`infrastructure/persistence/DepositJpaRepository.java` · 16 líneas</sub>

#### `DepositJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<DepositEntity> findByKiraDepositId(String kiraDepositId)` |  |
| `List<DepositEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId)` |  |
| `List<DepositEntity> findByVirtualAccountIdOrderByCreatedAtDesc(String virtualAccountId)` |  |

<sub>`infrastructure/persistence/JpaAuditLogRepository.java` · 54 líneas</sub>

#### `JpaAuditLogRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaAuditLogRepository(AuditLogJpaRepository jpa)` |  |
| `public AuditLog append(AuditLog entry)` |  |
| `public List<AuditLog> findByTenant(TenantId tenantId, int limit)` |  |

<sub>`infrastructure/persistence/JpaDepositRepository.java` · 70 líneas</sub>

#### `JpaDepositRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaDepositRepository(DepositJpaRepository jpa)` |  |
| `public Deposit save(Deposit deposit)` |  |
| `public Optional<Deposit> findByKiraDepositId(String kiraDepositId)` |  |
| `public List<Deposit> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Deposit> findByVirtualAccount(String virtualAccountId, int limit)` |  |

<sub>`infrastructure/persistence/JpaOperatorUserRepository.java` · 44 líneas</sub>

#### `JpaOperatorUserRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaOperatorUserRepository(OperatorUserJpaRepository jpa)` |  |
| `public Optional<OperatorUser> findByEmail(String email)` |  |
| `public Optional<OperatorUser> findById(String id)` |  |
| `public List<OperatorUser> findByTenant(TenantId tenantId)` |  |

<sub>`infrastructure/persistence/JpaPayoutRepository.java` · 68 líneas</sub>

#### `JpaPayoutRepository` · clase · `@Repository`

| Campo | Descripción |
|---|---|
| `private static final Set<PayoutStatus> IN_FLIGHT = Set.of( ...` | Estados no terminales que Kira ya conoce: son los que el reconciliador vuelve a preguntar. |

| Método | Descripción |
|---|---|
| `public JpaPayoutRepository(PayoutJpaRepository jpa)` |  |
| `public Payout save(Payout payout)` |  |
| `public Optional<Payout> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Payout> findByIdempotencyKey(IdempotencyKey key)` |  |
| `public Optional<Payout> findByKiraPayoutId(String kiraPayoutId)` |  |
| `public List<Payout> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Payout> findInFlight(int limit)` |  |

<sub>`infrastructure/persistence/JpaQuotationRepository.java` · 79 líneas</sub>

#### `JpaQuotationRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaQuotationRepository(QuotationJpaRepository jpa)` |  |
| `public Quotation save(Quotation quotation)` |  |
| `public Optional<Quotation> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public List<Quotation> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Quotation> findActiveExpiredBefore(Instant cutoff)` |  |

<sub>`infrastructure/persistence/JpaRecipientRepository.java` · 49 líneas</sub>

#### `JpaRecipientRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaRecipientRepository(RecipientJpaRepository jpa, ObjectMapper objectMapper)` |  |
| `public Recipient save(Recipient recipient)` |  |
| `public Optional<Recipient> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Recipient> findByKiraRecipientId(String kiraRecipientId)` |  |
| `public List<Recipient> findActiveByTenant(TenantId tenantId)` |  |

<sub>`infrastructure/persistence/JpaRfiRepository.java` · 80 líneas</sub>

#### `JpaRfiRepository` · clase · `@Repository`

| Campo | Descripción |
|---|---|
| `private static final Set<RfiStatus> OPEN = Set.of(RfiStatus.PENDING, RfiStatus.ANSWERED)` |  |

| Método | Descripción |
|---|---|
| `public JpaRfiRepository(RfiJpaRepository jpa)` |  |
| `public Rfi save(Rfi rfi)` |  |
| `public Optional<Rfi> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Rfi> findByKiraRfiId(String kiraRfiId)` |  |
| `public List<Rfi> findByTenant(TenantId tenantId)` |  |
| `public List<Rfi> findOpenByTenant(TenantId tenantId)` |  |
| `public Optional<Rfi> findOpenBlocking(String kiraResourceId)` |  |

<sub>`infrastructure/persistence/JpaTenantRepository.java` · 95 líneas</sub>

#### `JpaTenantRepository` · clase · `@Repository`

| Campo | Descripción |
|---|---|
| `private static final TypeReference<List<EligibleProduct>> PRODUCTS` |  |
| `private static final TypeReference<Map<String, List<String>>> FIELDS` |  |

| Método | Descripción |
|---|---|
| `public JpaTenantRepository(TenantJpaRepository jpa, ObjectMapper objectMapper)` |  |
| `public Tenant save(Tenant tenant)` |  |
| `public Optional<Tenant> findById(TenantId id)` |  |
| `public Optional<Tenant> findByKiraUserId(String kiraUserId)` |  |
| `public List<Tenant> findAll()` |  |

<sub>`infrastructure/persistence/JpaUboRepository.java` · 84 líneas</sub>

#### `JpaUboRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaUboRepository(UboJpaRepository jpa)` |  |
| `public Ubo save(Ubo ubo)` |  |
| `public Optional<Ubo> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Ubo> findByPersonReferenceId(String personReferenceId)` |  |
| `public List<Ubo> findByTenant(TenantId tenantId)` |  |
| `public UboRoster rosterOf(TenantId tenantId)` |  |
| `public List<Ubo> findPendingLivenessExpiredBefore(Instant cutoff)` |  |

<sub>`infrastructure/persistence/JpaVirtualAccountRepository.java` · 69 líneas</sub>

#### `JpaVirtualAccountRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaVirtualAccountRepository(VirtualAccountJpaRepository jpa)` |  |
| `public VirtualAccount save(VirtualAccount account)` |  |
| `public Optional<VirtualAccount> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<VirtualAccount> findByKiraAccountId(String kiraAccountId)` |  |
| `public List<VirtualAccount> findByTenant(TenantId tenantId)` |  |

<sub>`infrastructure/persistence/OperatorUserEntity.java` · 63 líneas</sub>

#### `OperatorUserEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Usuarios de cada empresa cliente. Tabla `users` del esquema v2.

| Campo | Descripción |
|---|---|
| `private String tenantId` | NULL cuando el usuario es de soporte de la plataforma (rol de scope 'system'). |

<sub>`infrastructure/persistence/OperatorUserJpaRepository.java` · 14 líneas</sub>

#### `OperatorUserJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<OperatorUserEntity> findByEmailIgnoreCase(String email)` |  |
| `List<OperatorUserEntity> findByTenantId(String tenantId)` |  |

<sub>`infrastructure/persistence/PayoutEntity.java` · 125 líneas</sub>

#### `PayoutEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Pagos con segregacion maker-checker. Tabla `payouts`.

Las columnas approval_state, quotation_expires_at, rejection_reason y error_code no
existen en la API de Kira: son el control interno del BFF, que es justamente lo que
Kira no ofrece a los integradores.

| Campo | Descripción |
|---|---|
| `private String quotationId` | Nulo mientras el pago es un borrador. El DDL de referencia lo declara NOT NULL; aqui se permite nulo porque el operador prepara la orden antes de cotizar, y el envio a Kira si exige cotizacion vigente. |
| `private String idempotencyKey` | UUID v4 obligatorio para Kira. |
| `private String makerUserId` | Operador (tesoreria_maker) que creo la solicitud. |
| `private String approverUserId` | Tesorero (tesoreria_approver) que autorizo la ejecucion. |
| `private String referenceNumber` | IMAD / ACH trace / UETR. Es el comprobante que el cliente final reclama. |

<sub>`infrastructure/persistence/PayoutJpaRepository.java` · 23 líneas</sub>

#### `PayoutJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<PayoutEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `Optional<PayoutEntity> findByIdempotencyKey(String idempotencyKey)` |  |
| `Optional<PayoutEntity> findByKiraPayoutId(String kiraPayoutId)` |  |
| `List<PayoutEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId)` |  |
| `List<PayoutEntity> findByKiraPayoutIdIsNotNullAndStatusInOrderByUpdatedAtAsc(` |  |

<sub>`infrastructure/persistence/PayoutMapper.java` · 68 líneas</sub>

#### `PayoutMapper` · clase

| Método | Descripción |
|---|---|
| `static Payout toDomain(PayoutEntity e)` |  |
| `static PayoutEntity toEntity(Payout p, PayoutEntity target)` |  |

<sub>`infrastructure/persistence/QuotationEntity.java` · 102 líneas</sub>

#### `QuotationEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Cotizaciones con desglose comisional 15 USD (Kira) + 15 USD (plataforma) = 30 USD.
Tabla `quotations`.

| Campo | Descripción |
|---|---|
| `private QuotationRail rail` | Riel cotizado. Debe corresponder al account_type del destinatario. |
| `private BigDecimal totalDebitAmount` | origin_amount + total_fee. |
| `private boolean balanceSufficient = false` | Se valida antes de habilitar el pago: Kira no encola ni cancela por saldo. |
| `private String rateSource` | kraken, fallback_at_peg, stale_at_peg... Si no es kraken, es tasa de contingencia. |
| `private String feesSnapshot` | Copia de fees[] y totals tal como los devolvio Kira. Es la unica prueba de que el precio mostrado al tesorero es el que se cobro. |
| `private Instant quoteExpiresAt` | TTL de 15 minutos devuelto por Kira. |

<sub>`infrastructure/persistence/QuotationJpaRepository.java` · 18 líneas</sub>

#### `QuotationJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<QuotationEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `List<QuotationEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId)` |  |
| `List<QuotationEntity> findByStatusAndQuoteExpiresAtBefore(QuotationStatus status, Instant cutoff)` |  |

<sub>`infrastructure/persistence/RecipientEntity.java` · 142 líneas</sub>

#### `RecipientEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Directorio de destinatarios de pagos. Tabla `recipients`.

El espejo es completo a proposito. Kira no expone actualizacion ni borrado, asi que
corregir un destinatario obliga a reconstruir el alta entera; y ademas devuelve
bank_address.state y postal_code VACIOS aunque se hayan enviado. Sin esta copia, esos
datos se pierden en cuanto se guardan.

| Campo | Descripción |
|---|---|
| `private String kiraRecipientId` | La respuesta de Kira lo llama recipient_id, no id. |
| `private String name` | Alias legible: razon social o nombre completo, segun el tipo de titular. |
| `private String replacedByRecipientId` | Reemplazo logico: apunta al destinatario que corrige a este. |
| `private String holderAddress` | Direccion del titular. Pais en ISO-2, a diferencia del KYB de la empresa. |
| `private String bankAddressText` | ACH: la direccion del banco viaja como texto plano. |
| `private String bankAddress` | WIRE: la direccion del banco viaja como objeto. Se guarda entera porque Kira devuelve state y postal_code vacios aunque se hayan enviado. |
| `private String network` | solana, polygon o tron. Determina el riel de la cotizacion. |

<sub>`infrastructure/persistence/RecipientJpaRepository.java` · 17 líneas</sub>

#### `RecipientJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<RecipientEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `Optional<RecipientEntity> findByKiraRecipientId(String kiraRecipientId)` |  |
| `List<RecipientEntity> findByTenantIdAndStatusOrderByNameAsc(String tenantId, RecipientStatus status)` |  |

<sub>`infrastructure/persistence/RecipientMapper.java` · 110 líneas</sub>

#### `RecipientMapper` · clase

Aplana el oneOf del destinatario sobre la tabla y lo reconstruye.
El riel decide que bloque de columnas se usa; el resto quedan nulas.

| Método | Descripción |
|---|---|
| `RecipientMapper(ObjectMapper objectMapper)` |  |
| `RecipientEntity toEntity(Recipient r, RecipientEntity target)` |  |
| `Recipient toDomain(RecipientEntity e)` |  |

<sub>`infrastructure/persistence/RfiEntity.java` · 62 líneas</sub>

#### `RfiEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Solicitudes de informacion de compliance. Tabla `rfis`.

| Campo | Descripción |
|---|---|
| `private String itemsPayload` | Array de items requeridos, tal como lo entrega Kira. |
| `private String blockingType` | blocking.type de Kira ("transfer", ...). Desviacion del DDL v2: enlaza el RFI con lo que detiene. |
| `private String blockingResourceId` | blocking.transfer_uuid: identificador de Kira del recurso bloqueado. |

<sub>`infrastructure/persistence/RfiJpaRepository.java` · 24 líneas</sub>

#### `RfiJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<RfiEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `Optional<RfiEntity> findByKiraRfiId(String kiraRfiId)` |  |
| `List<RfiEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId)` |  |
| `List<RfiEntity> findByTenantIdAndStatusInOrderByCreatedAtDesc(String tenantId, …)` |  |
| `Optional<RfiEntity> findFirstByBlockingResourceIdAndStatusInOrderByCreatedAtDesc(String blockingResourceId, …)` |  |

<sub>`infrastructure/persistence/RoleEntity.java` · 45 líneas</sub>

#### `RoleEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Catalogo RBAC de la plataforma. La fila es la autoridad para la FK de `users`;
el enum `com.example.autransactional.domain.tenant.Role` es la autoridad
para las reglas de negocio y para @PreAuthorize.

| Campo | Descripción |
|---|---|
| `private String name` | tesoreria_maker, tesoreria_approver, compliance_internal, admin, read_only. |

<sub>`infrastructure/persistence/RoleJpaRepository.java` · 11 líneas</sub>

#### `RoleJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<RoleEntity> findByName(String name)` |  |

<sub>`infrastructure/persistence/TenantEntity.java` · 88 líneas</sub>

#### `TenantEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Empresas clientes: Juriscop, Bankvision, AU Colombia. Tabla `tenants` del esquema v2.

| Campo | Descripción |
|---|---|
| `private String taxId` | NIT / identificador fiscal. |
| `private String kiraUserId` | Id devuelto por POST /v1/users de Kira. |
| `private String eligibleProducts` | Array de productos bancarios habilitados por Kira, con su elegibilidad. |
| `private String missingFields` | Fuente de verdad del formulario de KYB: lo que Kira sigue exigiendo, por producto. |
| `private boolean verificationTriggered = false` | El GET no lo devuelve; sin este dato, pedir los enlaces de liveness da 422. |
| `private String onboardingPayload` | Objeto completo enviado a Kira. El GET no devuelve el cuestionario y un PUT parcial borra en silencio lo que no viaje en el: sin esta copia, el siguiente PUT es una perdida de datos garantizada. |
| `private String onboardingIdempotencyKey` | Se persiste ANTES del primer POST /v1/users: un reintento no debe crear dos empresas. |
| `private String rejectionReason` | Unica fuente del motivo: llega solo por el webhook user.verification.failed. |

<sub>`infrastructure/persistence/TenantJpaRepository.java` · 13 líneas</sub>

#### `TenantJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<TenantEntity> findByKiraUserId(String kiraUserId)` |  |
| `Optional<TenantEntity> findByNameIgnoreCase(String name)` |  |

<sub>`infrastructure/persistence/UboEntity.java` · 94 líneas</sub>

#### `UboEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Beneficiarios finales, directores y liveness por empresa. Tabla `ubos`.

| Campo | Descripción |
|---|---|
| `private String personReferenceId` | Referencia del sujeto en Kira. |
| `private String roleInCompany` | Director, Accionista, Firmante, Beneficiario Final. Etiqueta legible, no regla. |
| `private boolean politicallyExposed = false` | pep_status: obligatorio para Kira, sin excepciones. |
| `private String countryOfBirth` | ISO-3. Kira no admite vacio. |
| `private String livenessLink` | Enlace de prueba biometrica alojada. Vigencia de 7 dias. TEXT y no VARCHAR(255): la URL viene firmada y desborda el tamano por defecto. |

<sub>`infrastructure/persistence/UboJpaRepository.java` · 20 líneas</sub>

#### `UboJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<UboEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `Optional<UboEntity> findByPersonReferenceId(String personReferenceId)` |  |
| `List<UboEntity> findByTenantId(String tenantId)` |  |
| `List<UboEntity> findByLivenessStatusAndLivenessExpiresAtBefore(LivenessStatus status, Instant cutoff)` |  |

<sub>`infrastructure/persistence/VirtualAccountEntity.java` · 89 líneas</sub>

#### `VirtualAccountEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Cuentas virtuales B2B. Tabla `virtual_accounts`.

| Campo | Descripción |
|---|---|
| `private VirtualAccountMode mode = VirtualAccountMode.FIAT` | fiat o crypto. INMUTABLE tras crearse: cambiarlo obliga a abrir otra cuenta. |
| `private String bank` | slovak_savings_bank (sandbox), portage (prod), austin_capital_trust. Depende del entorno. |
| `private boolean activatedEventSeen = false` | Columna propia del BFF: 'approved' colapsa activating/active en el pin 2026-04-14, asi que el evento virtual_account.activated es la unica senal fondos-listos fiable y hay que recordar haberlo visto. |
| `private String openingIdempotencyKey` | Se persiste ANTES del primer POST: un reintento no debe abrir dos cuentas. |

<sub>`infrastructure/persistence/VirtualAccountJpaRepository.java` · 16 líneas</sub>

#### `VirtualAccountJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `Optional<VirtualAccountEntity> findByIdAndTenantId(String id, String tenantId)` |  |
| `Optional<VirtualAccountEntity> findByKiraAccountId(String kiraAccountId)` |  |
| `List<VirtualAccountEntity> findByTenantId(String tenantId)` |  |

<sub>`infrastructure/persistence/WebhookEventEntity.java` · 71 líneas</sub>

#### `WebhookEventEntity` · clase · `@Entity` `@Table` `@Getter` `@Setter` `@NoArgsConstructor`

Bitacora inmutable de eventos de Kira. Tabla `webhooks_log`.

La unicidad de event_id es lo que hace idempotente el procesamiento: Kira entrega una
sola vez y sin reintento, y el mismo evento puede llegar por dos familias distintas
(payout.* y payout.status_changed).

| Campo | Descripción |
|---|---|
| `private String eventId` | data.event_id. Nunca existe a nivel raiz en ninguna de las dos envolturas. |
| `private int retryCount = 0` | Intentos de proyeccion fallidos. Corta dos problemas del reconciliador: la fila envenenada (un evento que nunca va a proyectarse y se reintenta para siempre) y la inanicion de los eventos nuevos, que quedaban detras de las filas viejas y rotas. |

<sub>`infrastructure/persistence/WebhookEventJpaRepository.java` · 24 líneas</sub>

#### `WebhookEventJpaRepository` · interfaz

| Método | Descripción |
|---|---|
| `boolean existsByEventId(String eventId)` |  |
| `Optional<WebhookEventEntity> findByEventId(String eventId)` |  |
| `List<WebhookEventEntity> findByProcessedFalseAndRetryCountLessThanOrderByCreatedAtAsc(int maxRetries)` | Eventos almacenados que nunca llegaron a proyectarse y que aun tienen reintentos. Kira no reintenta, asi que esta fila es el unico rastro que queda de ese cambio de estado. Mas antiguo primero: el evento perdido mas viejo es el que lleva mas tiempo desalineando el modelo de lectura. El filtro por retry_count es lo que evita que las filas agotadas ocupen el lote para siempre y dejen sin sitio a los eventos nuevos. |

### A.17 Infraestructura — audit

<sub>`infrastructure/audit/AuditTrail.java` · 79 líneas</sub>

#### `AuditTrail` · clase · `@Component`

Bitacora de acciones sensibles. Registra actor, organizacion, recurso, clave de idempotencia
y resultado; nunca secretos ni datos personales.

El detalle va como JSON en `changes` porque el esquema v2 tiene una sola columna para el
contexto de la accion: meter ahi campos sueltos obligaria a migrar la tabla cada vez que
una accion nueva quiera anotar algo distinto.

| Método | Descripción |
|---|---|
| `public AuditTrail(AuditLogRepository repository, ObjectMapper objectMapper)` |  |
| `public void record(AuthenticatedOperator operator, String action, String resourceType, …)` |  |

### A.18 Infraestructura — bootstrap

<sub>`infrastructure/bootstrap/DevDataSeeder.java` · 143 líneas</sub>

#### `DevDataSeeder` · clase · `@Component` `@Profile` `@ConditionalOnProperty` `@EnableConfigurationProperties`

Datos de arranque para desarrollo local.

Solo se activa con el perfil dev y bff.dev.seed=true, nunca en cert ni en prod.
Es idempotente: si la organizacion, el rol o el correo ya existen, no los toca.

Crea un operador por rol y por organizacion para poder probar de verdad el maker-checker:
hacen falta dos personas distintas para que un pago salga hacia Kira.

| Campo | Descripción |
|---|---|
| `private static final List<SeedTenant> TENANTS = List.of( ...` |  |

| Método | Descripción |
|---|---|
| `new SeedTenant("juriscop", "Juriscop", "900123456-1"), …)` |  |
| `new SeedTenant("bankvision", "Bankvision", "900234567-2"), …)` |  |
| `new SeedTenant("au-colombia", "AU Colombia", "900345678-3"))` |  |
| `public DevDataSeeder(TenantJpaRepository tenants, RoleJpaRepository roles, …)` |  |
| `public void run(org.springframework.boot.ApplicationArguments args)` `@Transactional` |  |

##### `DevDataSeeder.SeedTenant` · record

| Componente |
|---|
| `String id` |
| `String name` |
| `String taxId` |

<sub>`infrastructure/bootstrap/DevSeedProperties.java` · 11 líneas</sub>

#### `DevSeedProperties` · record · `@ConfigurationProperties`

| Componente |
|---|
| `@DefaultValue("Dev12345!") String seedPassword` |

<sub>`infrastructure/bootstrap/RequiredSecretsValidator.java` · 56 líneas</sub>

#### `RequiredSecretsValidator` · clase · `@Component` `@Profile`

En cert y prod el arranque falla si falta un secreto, en vez de descubrirlo con la primera
llamada a Kira o con un token firmado con una clave de ejemplo.
En dev no se aplica: alli hay valores por defecto deliberados.

| Método | Descripción |
|---|---|
| `public RequiredSecretsValidator(KiraProperties kira, BffSecurityProperties security)` |  |
| `public void afterPropertiesSet()` |  |

### A.19 Infraestructura — config

<sub>`infrastructure/config/AsyncConfig.java` · 32 líneas</sub>

#### `AsyncConfig` · clase · `@Configuration` `@EnableAsync` `@EnableScheduling`

Kira entrega cada webhook UNA sola vez, sin reintentos, y aborta a los 30 segundos.
Por eso el ingress responde 2xx de inmediato y el procesamiento ocurre en este pool.

| Campo | Descripción |
|---|---|
| `public static final String WEBHOOK_EXECUTOR = "webhookExecutor"` |  |

| Método | Descripción |
|---|---|
| `public ThreadPoolTaskExecutor webhookExecutor()` `@Bean(name = WEBHOOK_EXECUTOR)` |  |

<sub>`infrastructure/config/OpenApiConfig.java` · 55 líneas</sub>

#### `OpenApiConfig` · clase · `@Configuration`

Documentacion viva de la API del BFF.

El esquema 'bearer-jwt' es el JWT propio del BFF, no el de Kira: el token de Kira nunca
sale del servidor. Los endpoints de verificacion biometrica no llevan seguridad porque
los invoca la persona que se esta vinculando, que todavia no tiene sesion.

| Método | Descripción |
|---|---|
| `public OpenAPI bffOpenApi()` `@Bean` |  |

### A.20 Infraestructura — reconciliation

<sub>`infrastructure/reconciliation/LivenessReconciliationWorker.java` · 52 líneas</sub>

#### `LivenessReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty`

Cierra los enlaces de prueba de vida vencidos.

El enlace que emite Kira vive 7 dias y su resultado real solo llega por el webhook
user.liveness_completed. Un enlace vencido que sigue en PENDING deja al portal esperando algo
que ya no va a pasar: se marca EXPIRED para que la pantalla ofrezca pedir uno nuevo, que es lo
unico que funciona (Kira no prorroga el enlace).

| Método | Descripción |
|---|---|
| `public LivenessReconciliationWorker(UboRepository ubos)` |  |
| `public void expireStaleLivenessLinks()` |  |

<sub>`infrastructure/reconciliation/PayoutReconciliationWorker.java` · 96 líneas</sub>

#### `PayoutReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty`

Pagos en vuelo: pregunta por el recurso, que es la autoridad final.

Kira entrega cada webhook una sola vez y sin reintentos: si el BFF estaba caido o la proyeccion
fallo, ese cambio de estado no vuelve. Aqui se recuperan los pagos que Kira ya conoce y siguen
sin estado terminal (CREATED, PENDING, PROCESSING, KYT_PENDING, IN_REVIEW y UNKNOWN).

Cada pago se guarda por separado: un fallo de red en uno no debe tumbar el lote ni dejar a
medias los anteriores.

| Método | Descripción |
|---|---|
| `public PayoutReconciliationWorker(PayoutRepository payouts, KiraApiClient kira, …)` |  |
| `public void reconcile()` |  |

<sub>`infrastructure/reconciliation/QuotationReconciliationWorker.java` · 52 líneas</sub>

#### `QuotationReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty`

Cierra las cotizaciones cuyo TTL de 15 minutos ya paso.

No llama a Kira: el vencimiento es local y deterministico. Sirve para que la bandeja no muestre
como ACTIVE un precio que ya no se puede redimir; el pago, por su parte, vuelve a comprobar el
vencimiento antes de enviar nada.

| Método | Descripción |
|---|---|
| `public QuotationReconciliationWorker(QuotationRepository quotations)` |  |
| `public void expireStaleQuotations()` |  |

<sub>`infrastructure/reconciliation/RfiReconciliationWorker.java` · 61 líneas</sub>

#### `RfiReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty`

Trae de Kira los RFIs de cada empresa registrada.

La familia rfi.* exige suscripcion explicita en Kira y, como todo webhook, se entrega una sola
vez. Un RFI que no llega es un pago o un deposito detenido que nadie ve hasta que vence, y el
plazo (due_at, unas dos semanas) no se prorroga: por eso esta es la red de seguridad que mas
importa de las cuatro.

| Método | Descripción |
|---|---|
| `public RfiReconciliationWorker(TenantRepository tenants, AnswerRfiService rfis)` |  |
| `public void syncOpenRfis()` |  |

<sub>`infrastructure/reconciliation/WebhookReprojectionWorker.java` · 101 líneas</sub>

#### `WebhookReprojectionWorker` · clase · `@Component` `@ConditionalOnProperty`

Eventos almacenados y nunca proyectados: las filas de webhooks_log con processed = false.

El ingress responde 2xx en cuanto guarda el evento, asi que un fallo posterior de la proyeccion
no se le puede devolver a Kira, que ademas entrega una sola vez y sin reintentos. La fila con
processing_error es lo unico que queda de ese cambio de estado, y aqui se vuelve a intentar.

Es el complemento de los otros cuatro workers: aquellos preguntan por el recurso, este recupera
eventos cuyo dato NO esta en ningun GET (el motivo del rechazo del KYB y el resultado real de la
prueba de vida sólo viajan en el webhook).

| Campo | Descripción |
|---|---|
| `static final int MAX_RETRIES = 5` | Intentos antes de dar un evento por perdido. Sin este tope, un evento que nunca va a proyectarse se reintenta indefinidamente y, al ir el lote de mas antiguo a mas nuevo, acaba desplazando a los eventos recientes. |
| `private static final String AGOTADO = "Max retries reached"` |  |

| Método | Descripción |
|---|---|
| `public WebhookReprojectionWorker(WebhookEventJpaRepository events, ProcessWebhookUseCase webhooks, …)` |  |
| `public void reprojectPendingEvents()` |  |

#### `infrastructure/reconciliation/package-info.java` (25 líneas)

Sólo documentación de paquete (sin tipos). Ver §12.6.

### A.21 Interfaces — rest

<sub>`interfaces/rest/AuthController.java` · 43 líneas</sub>

#### `AuthController` · clase · `@RestController` `@RequestMapping`

Grupo en Swagger: **1. Sesion**.

| Método | Descripción |
|---|---|
| `public AuthController(LoginUseCase loginUseCase)` |  |
| `public ResponseEntity<LoginUseCase.LoginResult> login(@Valid @RequestBody LoginRequest request)` `@PostMapping("/login")` |  |
| `public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping("/me")` |  |

##### `AuthController.LoginRequest` · record

| Componente |
|---|
| `@NotBlank @Email String email` |
| `@NotBlank String password` |

<sub>`interfaces/rest/DepositController.java` · 50 líneas</sub>

#### `DepositController` · clase · `@RestController` `@RequestMapping`

Depositos entrantes.

Los depositos no se crean desde aqui: llegan por webhook y, como red de seguridad, se
sincronizan desde Kira. En el sandbox el webhook es la unica constancia que existe de ellos.

Grupo en Swagger: **2.4 Depositos**.

| Método | Descripción |
|---|---|
| `public DepositController(RecordDepositService deposits)` |  |
| `public List<DepositView> list(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/deposits")` |  |
| `public List<DepositView> syncFromKira(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/virtual-accounts/{id}/deposits/sync")` | Trae de Kira los depositos de la cuenta y los asienta. Idempotente por id de deposito. |
| `public List<DepositView> listByAccount(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/virtual-accounts/{id}/deposits")` |  |

<sub>`interfaces/rest/OnboardingController.java` · 62 líneas</sub>

#### `OnboardingController` · clase · `@RestController` `@RequestMapping`

Onboarding KYB de la empresa cliente.

El portal no debe tener un formulario estatico: GET devuelve 'pendingFields' y esa es
la lista de campos que hay que pintar. PUT se repite hasta que quede vacia.

Grupo en Swagger: **1.1 Onboarding KYB**.

| Método | Descripción |
|---|---|
| `public OnboardingController(SubmitOnboardingService onboarding)` |  |
| `public OnboardingView status(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping` | Estado local, sin llamar a Kira. |
| `public ResponseEntity<OnboardingView> register(` `@PostMapping` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` |  |
| `public OnboardingView completeProfile(` `@PutMapping` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Envia el perfil completo. Se puede repetir; cada llamada reenvia el objeto entero. |
| `public OnboardingView refresh(@AuthenticationPrincipal AuthenticatedOperator operator)` `@PostMapping("/refresh")` | Relee el recurso en Kira. Cubre el hueco de un webhook que nunca llego. |

<sub>`interfaces/rest/PayoutController.java` · 108 líneas</sub>

#### `PayoutController` · clase · `@RestController` `@RequestMapping`

Grupo en Swagger: **2. Pagos**.

| Método | Descripción |
|---|---|
| `public PayoutController(ExecutePayoutService payoutService)` |  |
| `public List<PayoutView> list(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping` |  |
| `public KiraPayoutPage kiraHistory(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/kira")` | Historial de la empresa en Kira, incluidos movimientos que no nacieron en el portal. Pagina por `page` (desde 1) y `limit` (1-100). `status`: CREATED, PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED, IN_REVIEW, KYT_PENDING. Fechas ISO 8601 o AAAA-MM-DD. |
| `public PayoutPreviewView preview(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/preview")` `@PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")` | Coste del pago sin reservar precio. Para cerrarlo, cotiza en /api/quotations. |
| `public PayoutView get(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id)` `@GetMapping("/{id}")` |  |
| `public ResponseEntity<PayoutView> create(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping` `@PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")` |  |
| `public PayoutView approve(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/approve")` `@PreAuthorize("hasAnyRole('TREASURY_APPROVER','ADMIN')")` | Aprueba y envia a Kira. La entidad rechaza que el aprobador sea el mismo que lo creo, y la cotizacion debe seguir vigente y con saldo suficiente. El cuerpo es opcional: solo hace falta para la naturaleza del pago, el memo del WIRE y los documentos de soporte. |
| `public PayoutView reject(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/reject")` `@PreAuthorize("hasAnyRole('TREASURY_APPROVER','ADMIN')")` |  |
| `public List<PayoutEventView> events(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}/events")` | Linea de tiempo del pago en Kira. Vacia mientras no se haya enviado. |
| `public PayoutView refresh(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/refresh")` |  |

<sub>`interfaces/rest/QuotationController.java` · 55 líneas</sub>

#### `QuotationController` · clase · `@RestController` `@RequestMapping`

Cotizaciones de transferencia.

Viven 15 minutos exactos. La respuesta trae 'secondsToExpiry' para el contador: al
llegar a cero hay que recotizar, no reutilizar.

Grupo en Swagger: **2.1 Cotizaciones**.

| Método | Descripción |
|---|---|
| `public QuotationController(CreateQuoteService quotes)` |  |
| `public List<QuotationView> list(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping` |  |
| `public QuotationView get(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}")` |  |
| `public ResponseEntity<QuotationView> create(` `@PostMapping` `@PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")` |  |

<sub>`interfaces/rest/RecipientController.java` · 77 líneas</sub>

#### `RecipientController` · clase · `@RestController` `@RequestMapping`

Directorio de destinatarios.

No hay PUT: Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno
se da de alta el reemplazo y se archiva el anterior apuntando al nuevo.

Grupo en Swagger: **2.2 Destinatarios**.

| Método | Descripción |
|---|---|
| `public RecipientController(RegisterRecipientService recipients)` |  |
| `public List<RecipientView> list(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping` |  |
| `public List<KiraRecipientView> listInKira(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping("/kira")` | Destinatarios de la empresa tal como los tiene Kira, para conciliar con el directorio. |
| `public KiraRecipientView getInKira(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}/kira")` |  |
| `public RecipientView get(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}")` |  |
| `public ResponseEntity<RecipientView> register(` `@PostMapping` `@PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")` | `alreadyExisted: true` significa que Kira devolvio un 202: el destino ya estaba. |
| `public RecipientView archive(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/archive")` `@PreAuthorize("hasAnyRole('TREASURY_MAKER','ADMIN')")` | Archiva el destinatario. Con `replacedByRecipientId` queda enlazado a su sustituto. |

<sub>`interfaces/rest/ReferenceController.java` · 29 líneas</sub>

#### `ReferenceController` · clase · `@RestController` `@RequestMapping`

Grupo en Swagger: **0. Catalogos**.

| Método | Descripción |
|---|---|
| `public ReferenceController(ReferenceCatalogService catalog)` |  |
| `public List<CountryView> countries()` `@GetMapping("/countries")` | Paises soportados con sus subdivisiones. Cacheado 24 h. |

<sub>`interfaces/rest/RestExceptionHandler.java` · 117 líneas</sub>

#### `RestExceptionHandler` · clase · `@RestControllerAdvice`

Traduce los errores a una forma unica para el frontend. La API de Kira convive con varias
formas de error; el BFF no las propaga crudas: entrega codigo estable y mensaje accionable.

| Método | Descripción |
|---|---|
| `public ResponseEntity<Map<String, Object>> handleRfiAnswer(RfiAnswerRejectedException e)` `@ExceptionHandler(RfiAnswerRejectedException.class)` | Errores por item_id: el portal los pinta junto a cada campo y no marca ninguno como guardado. |
| `public ResponseEntity<Map<String, Object>> handleDomain(DomainException e)` `@ExceptionHandler(DomainException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException e)` `@ExceptionHandler(AccessDeniedException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e)` `@ExceptionHandler(MethodArgumentNotValidException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e)` `@ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,` | Peticion mal formada: parte o parametro ausente, JSON ilegible o tipo equivocado. |
| `public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e)` `@ExceptionHandler(MaxUploadSizeExceededException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e)` `@ExceptionHandler(NoResourceFoundException.class)` | Ruta inexistente: 404, no "error inesperado". |
| `public ResponseEntity<Map<String, Object>> handleKiraNotConfigured(KiraNotConfiguredException e)` `@ExceptionHandler(KiraNotConfiguredException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleKira(KiraApiException e)` `@ExceptionHandler(KiraApiException.class)` |  |
| `public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e)` `@ExceptionHandler(Exception.class)` |  |

<sub>`interfaces/rest/RfiController.java` · 112 líneas</sub>

#### `RfiController` · clase · `@RestController` `@RequestMapping`

Bandeja de solicitudes de informacion (RFI) de Kira.

No hay POST de alta: Kira genera los RFIs. El portal los sincroniza, los muestra y
responde sus items. Un RFI sin atender detiene lo que bloquea hasta que vence.

Grupo en Swagger: **1.3 Solicitudes de informacion (RFI)**.

| Método | Descripción |
|---|---|
| `public RfiController(AnswerRfiService rfis)` |  |
| `public List<RfiView> list(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping` | Con `open=true` solo los que admiten respuesta (pending y answered). |
| `public RfiView get(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id)` `@GetMapping("/{id}")` |  |
| `public List<RfiView> sync(@AuthenticationPrincipal AuthenticatedOperator operator)` `@PostMapping("/sync")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Trae de Kira los RFIs de la empresa. Red de seguridad del webhook rfi.*. |
| `public RfiView refresh(@AuthenticationPrincipal AuthenticatedOperator operator, @PathVariable String id)` `@PostMapping("/{id}/refresh")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` |  |
| `public RfiView answer(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PatchMapping("/{id}/items")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Responde items de texto. Es all-or-nothing: un `422` trae `details` por item_id y significa que no se guardo ninguno. |
| `public RfiView uploadDocuments(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping(value = "/{id}/items/{itemId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Sube archivos a un item de tipo documento: multipart con la parte `files` repetida (maximo 20, 30 MB cada uno; PDF, JPEG, PNG, HEIC o WebP salvo que el item diga otra cosa). |
| `public RfiView removeDocument(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@DeleteMapping("/{id}/items/{itemId}/documents/{documentId}")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Kira no permite borrar el ultimo archivo de un item ya respondido (422). |
| `public RfiDocumentLink documentLink(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}/items/{itemId}/documents/{documentId}/link")` | Enlace temporal (minutos). Abrirlo al momento; si caduca, pedir otro. |

<sub>`interfaces/rest/UboController.java` · 65 líneas</sub>

#### `UboController` · clase · `@RestController` `@RequestMapping`

Beneficiarios finales (UBOs) y sus enlaces de prueba de vida.

El registro es local primero y se sincroniza en bloque: Kira exige el array completo en
cada envio, asi que no hay un "alta de un UBO" contra su API.

Grupo en Swagger: **1.2 Beneficiarios finales**.

| Método | Descripción |
|---|---|
| `public UboController(SyncUbosService ubos)` |  |
| `public UboView.Roster list(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping` | Incluye la validacion del grupo: suma de participacion y si hay beneficiario final. |
| `public UboView save(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Alta o edicion local. Sin `id` crea; con `id` actualiza. |
| `public OnboardingView sync(@AuthenticationPrincipal AuthenticatedOperator operator)` `@PostMapping("/sync")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Envia el array completo a Kira. Falla antes de llamar si no hay beneficiario final. |
| `public UboView.Roster requestLivenessLinks(` `@PostMapping("/liveness-links")` `@PreAuthorize("hasAnyRole('ADMIN','COMPLIANCE_INTERNAL')")` | Un enlace por beneficiario final. Repetir la llamada devuelve los mismos enlaces mientras no cambien las URLs de redireccion. |

<sub>`interfaces/rest/VirtualAccountController.java` · 79 líneas</sub>

#### `VirtualAccountController` · clase · `@RestController` `@RequestMapping`

Cuentas virtuales.

'fundsReady' es la unica senal fiable de que la cuenta puede mover fondos; el estado por
si solo no basta. Si 'activationDelayed' es true, la activacion lleva demasiado tiempo y
el portal debe ofrecer contactar con Kira en vez de seguir esperando.

Grupo en Swagger: **2.3 Cuentas virtuales**.

| Método | Descripción |
|---|---|
| `public VirtualAccountController(OpenVirtualAccountService accounts)` |  |
| `public List<VirtualAccountView> list(@AuthenticationPrincipal AuthenticatedOperator operator)` `@GetMapping` |  |
| `public VirtualAccountView get(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@GetMapping("/{id}")` |  |
| `public ResponseEntity<VirtualAccountView> open(` `@PostMapping` `@PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER','COMPLIANCE_INTERNAL')")` | Exige KYB VERIFIED y producto elegible. Un 409 de Kira reutiliza la cuenta existente. |
| `public VirtualAccountView refresh(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/refresh")` | Relee la cuenta. Cubre el hueco de un virtual_account.activated que nunca llego. |
| `public VirtualAccountView refreshBalance(@AuthenticationPrincipal AuthenticatedOperator operator, …)` `@PostMapping("/{id}/balance")` |  |
| `public VirtualAccountView simulateDeposit(` `@PostMapping("/{id}/simulate-deposit")` `@PreAuthorize("hasAnyRole('ADMIN','TREASURY_MAKER')")` | Solo sandbox: en produccion responde 422 sin llamar a Kira. |

### A.22 Interfaces — webhook

<sub>`interfaces/webhook/KiraWebhookController.java` · 69 líneas</sub>

#### `KiraWebhookController` · clase · `@RestController` `@RequestMapping`

Ingress de eventos de Kira.

Kira entrega una sola vez, sin reintentos, y aborta a los 30 segundos. Por eso este
controlador solo hace dos cosas: verificar la firma sobre los bytes crudos y encolar.
Todo lo demas ocurre despues de haber respondido.

Se responde 2xx incluso ante un evento desconocido: un 4xx no provoca reintento, solo
pierde el evento.

Grupo en Swagger: **6. Webhooks de Kira**.

| Método | Descripción |
|---|---|
| `public KiraWebhookController(ProcessWebhookUseCase processWebhook, KiraWebhookVerifier verifier)` |  |
| `public ResponseEntity<Map<String, String>> receive(` `@PostMapping(value = "/kira", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)` |  |

---

## Anexo B. Catálogo de pruebas

**40 clases de prueba, 283 métodos `@Test`.** Los nombres describen la regla de negocio que protegen.

### `AuTransactionalApplicationTests.java` — 1

- `contextLoads` — context loads

### `application/account/OpenVirtualAccountServiceTest.java` — 13

- `elBancoLoFijaElEntornoNoElFormulario` — el banco lo fija el entorno no el formulario
- `verifiedNoBastaSiElProductoNoEsElegible` — verified no basta si el producto no es elegible
- `laClaveDeIdempotenciaSePersisteAntesDeLlamar` — la clave de idempotencia se persiste antes de llamar
- `unaCuentaPendienteNoEstaListaParaFondos` — una cuenta pendiente no esta lista para fondos
- `approvedSinNumeroRealSigueSinEstarLista` — approved sin numero real sigue sin estar lista
- `unNumeroDeCuentaRealSiLaHabilita` — un numero de cuenta real si la habilita
- `unConflictoReutilizaLaCuentaExistente` — un conflicto reutiliza la cuenta existente
- `unCuatrocientosEnElSaldoEsCalculandoNoUnError` — un cuatrocientos en el saldo es calculando no un error
- `otrosErroresDeSaldoSiSePropagan` — otros errores de saldo si se propagan
- `elSaldoLlegaEnDecimalNoEnUnidadesMenores` — el saldo llega en decimal no en unidades menores
- `simularDepositoNoExisteFueraDelSandbox` — simular deposito no existe fuera del sandbox
- `enSandboxSimularDepositoRefrescaElSaldo` — en sandbox simular deposito refresca el saldo
- `unaCuentaSinAbrirEnKiraNoSeRefresca` — una cuenta sin abrir en kira no se refresca

### `application/account/RecordDepositServiceTest.java` — 13

- `unDepositoRecibidoSeProyectaConSusTresImportes` — un deposito recibido se proyecta con sus tres importes
- `seLeeElCasingMezcladoDelPayload` — se lee el casing mezclado del payload
- `losSeisEventosConvergenEnUnaSolaFila` — los seis eventos convergen en una sola fila
- `unDepositoDevueltoNoVuelveAAcreditar` — un deposito devuelto no vuelve a acreditar
- `unEventoTardioNoResucitaUnDepositoDevuelto` — un evento tardio no resucita un deposito devuelto
- `elFalloSeProyectaAunqueElPayloadNoTraigaEstado` — el fallo se proyecta aunque el payload no traiga estado
- `unMicrodepositoNoCuentaComoIngreso` — un microdeposito no cuenta como ingreso
- `unDepositoAcreditadoInvalidaElSaldoCacheadoPeroNoLoInventa` — un deposito acreditado invalida el saldo cacheado pero no lo inventa
- `unMicrodepositoNoInvalidaElSaldo` — un microdeposito no invalida el saldo
- `unDepositoDeUnaCuentaDesconocidaNoRompeNada` — un deposito de una cuenta desconocida no rompe nada
- `unEventoSinIdentificadorNoSeProyecta` — un evento sin identificador no se proyecta
- `laSincronizacionDesdeKiraConvergeEnLaMismaFilaQueElWebhook` — la sincronizacion desde kira converge en la misma fila que el webhook
- `kytRechazadoEsUnDepositoFallido` — kyt rechazado es un deposito fallido

### `application/compliance/AnswerRfiServiceTest.java` — 22

- `laSincronizacionAsientaEstadoPlazoYBloqueo` — la sincronizacion asienta estado plazo y bloqueo
- `laSincronizacionNoImportaRfisDeOtraEmpresaAunqueKiraLosDevuelva` — la sincronizacion no importa rfis de otra empresa aunque kira los devuelva
- `laSincronizacionFiltraPorEmpresaYPaginaConOffset` — la sincronizacion filtra por empresa y pagina con offset
- `unaEntradaResumidaSeCompletaConElDetalle` — una entrada resumida se completa con el detalle
- `unItemDeDocumentoNuncaEnviaAnswerValue` — un item de documento nunca envia answer value
- `unItemQueNoEsDelRfiSeRechazaSinLlamarAKira` — un item que no es del rfi se rechaza sin llamar a kira
- `un422DeKiraSeDevuelvePorItemYNoSeMarcaNadaComoRespondido` — un422 de kira se devuelve por item y no se marca nada como respondido
- `un409AsientaElCierreAntesDeAvisar` — un409 asienta el cierre antes de avisar
- `trasUnaRespuestaParcialElEstadoLoDecideKiraYNoElPortal` — tras una respuesta parcial el estado lo decide kira y no el portal
- `elCuerpoDelPatchLlevaItemIdYAnswerValue` — el cuerpo del patch lleva item id y answer value
- `tesoreriaNoPuedeResponderRfis` — tesoreria no puede responder rfis
- `elRfiEnlazaElPagoQueTieneDetenido` — el rfi enlaza el pago que tiene detenido
- `unRfiNuevoPorWebhookSeLeeDeKiraYSeAtribuyePorUserId` — un rfi nuevo por webhook se lee de kira y se atribuye por user id
- `unWebhookDeResolucionCierraElRfiLocal` — un webhook de resolucion cierra el rfi local
- `unaRespuestaNumericaOBooleanaViajaConSuTipo` — una respuesta numerica o booleana viaja con su tipo
- `unRfiQueDetieneUnDepositoEnlazaElDepositoDelPortal` — un rfi que detiene un deposito enlaza el deposito del portal
- `subirArchivosAUnItemDeTextoSeRechazaSinLlamarAKira` — subir archivos a un item de texto se rechaza sin llamar a kira
- `unTipoDeArchivoNoAdmitidoSeRechazaPorArchivo` — un tipo de archivo no admitido se rechaza por archivo
- `elAnswerSpecDelItemPuedeLimitarElNumeroDeArchivos` — el answer spec del item puede limitar el numero de archivos
- `subirUnPdfLoEnviaAKiraYReleeElRfi` — subir un pdf lo envia a kira y relee el rfi
- `noSePuedeBorrarElUltimoArchivoYElErrorVaPorItem` — no se puede borrar el ultimo archivo y el error va por item
- `elEnlaceDeDescargaSeDevuelveConSuCaducidad` — el enlace de descarga se devuelve con su caducidad

### `application/reference/ReferenceCatalogServiceTest.java` — 2

- `elCatalogoDePaisesSeLeeUnaVezYSeSirveDeCache` — el catalogo de paises se lee una vez y se sirve de cache
- `unFalloNoSeCachea` — un fallo no se cachea

### `application/shared/IdempotencyKeyPersistenceTest.java` — 2

> La clave de idempotencia tiene que estar en MySQL ANTES de llamar a Kira y seguir ahi si la llamada falla: si Kira llego a crear el recurso y la respuesta se perdio, el reintento debe viajar con la MISMA clave o se crea una segunda empresa o una segunda cuenta. Estas pruebas son de integracion a proposito: el caso de uso es @Transactional y relanza la excepcion, asi que el rollback borraba el guardado. Con mocks y sin transaccion real ese defecto no se ve.

- `laClaveDelAltaSobreviveAlFalloDeKira` — la clave del alta sobrevive al fallo de kira
- `laClaveDeAperturaDeCuentaSobreviveAlFalloDeKira` — la clave de apertura de cuenta sobrevive al fallo de kira

### `application/tenant/KiraUserStateTest.java` — 5

- `leeLaRespuestaDelAlta` — lee la respuesta del alta
- `desenvuelveLaRespuestaConSobreData` — desenvuelve la respuesta con sobre data
- `unGetSinVerificationTriggeredDejaElDatoIndefinido` — un get sin verification triggered deja el dato indefinido
- `reconoceLaDiligenciaReforzada` — reconoce la diligencia reforzada
- `unEstadoDesconocidoNoRompeLaLectura` — un estado desconocido no rompe la lectura

### `application/tenant/SubmitOnboardingServiceTest.java` — 8

- `elAltaEnviaSoloEmpresasYAmarraElIdInterno` — el alta envia solo empresas y amarra el id interno
- `laClaveDeIdempotenciaSePersisteAntesDeLlamarAKira` — la clave de idempotencia se persiste antes de llamar a kira
- `siLaLlamadaFallaLaClaveQuedaGuardadaParaElReintento` — si la llamada falla la clave queda guardada para el reintento
- `reenviarElAltaNoVuelveALlamarAKira` — reenviar el alta no vuelve a llamar a kira
- `elPutReenviaElObjetoCompletoNoSoloLoNuevo` — el put reenvia el objeto completo no solo lo nuevo
- `unArrayNuevoReemplazaEnteroAlGuardado` — un array nuevo reemplaza entero al guardado
- `completarElPerfilExigeAltaPrevia` — completar el perfil exige alta previa
- `unRolDeTesoreriaNoGestionaElOnboarding` — un rol de tesoreria no gestiona el onboarding

### `application/tenant/SyncUbosServiceTest.java` — 10

- `elArrayEnviadoLlevaLosBooleanosQueKiraExige` — el array enviado lleva los booleanos que kira exige
- `seEnviaSiempreElArrayCompletoNoSoloElUltimoAlta` — se envia siempre el array completo no solo el ultimo alta
- `sinBeneficiarioNoSeGastaLaLlamadaAKira` — sin beneficiario no se gasta la llamada a kira
- `sinVerificacionEnCursoNoSePidenEnlaces` — sin verificacion en curso no se piden enlaces
- `losEnlacesSeRepartenPorReferenciaDePersona` — los enlaces se reparten por referencia de persona
- `sinReferenciaPreviaElEnlaceSeEmparejaPorNombreYLaGuarda` — sin referencia previa el enlace se empareja por nombre y la guarda
- `lasUrlsDeRedireccionViajanSoloSiEstanCompletas` — las urls de redireccion viajan solo si estan completas
- `elWebhookDeLivenessAsientaElResultadoEnSuPersona` — el webhook de liveness asienta el resultado en su persona
- `unWebhookSinPersonaNoTocaANadie` — un webhook sin persona no toca a nadie
- `unRolDeTesoreriaNoGestionaBeneficiarios` — un rol de tesoreria no gestiona beneficiarios

### `application/treasury/CreateQuoteServiceTest.java` — 13

- `seCotizaEnModoRedimibleYCompensandoHaciaArriba` — se cotiza en modo redimible y compensando hacia arriba
- `elMarkupDeLaPlataformaViajaEnUnidadesMenores` — el markup de la plataforma viaja en unidades menores
- `lasComisionesRealesSonLasQueLiquidaKiraNoLaEstimacion` — las comisiones reales son las que liquida kira no la estimacion
- `elDestinatarioRecibeElImporteExactoYLaCuentaPagaMas` — el destinatario recibe el importe exacto y la cuenta paga mas
- `elRielSeDerivaDelDestinatarioNoDelFormulario` — el riel se deriva del destinatario no del formulario
- `unRielQueNoCorrespondeSeCortaAntesDeCotizar` — un riel que no corresponde se corta antes de cotizar
- `unaCuentaSinNumeroRealNoPuedeCotizar` — una cuenta sin numero real no puede cotizar
- `elVencimientoEsElQueDiceKiraNoElCalculado` — el vencimiento es el que dice kira no el calculado
- `unPreviewSinQuoteIdNoSeAcepta` — un preview sin quote id no se acepta
- `elSaldoInsuficienteQuedaRegistradoEnLaCotizacion` — el saldo insuficiente queda registrado en la cotizacion
- `unaTasaDeContingenciaQuedaSenalizada` — una tasa de contingencia queda senalizada
- `seGuardaLaCopiaDelPrecioMostrado` — se guarda la copia del precio mostrado
- `unRolAprobadorNoCotiza` — un rol aprobador no cotiza

### `application/treasury/ExecutePayoutServiceTest.java` — 24

- `seEnviaElBrutoParaQueElDestinatarioRecibaLoPrometido` — se envia el bruto para que el destinatario reciba lo prometido
- `conCotizacionViajaElQuoteIdYNoElMarkup` — con cotizacion viaja el quote id y no el markup
- `sinCotizacionElMargenViajaComoCadenaDecimal` — sin cotizacion el margen viaja como cadena decimal
- `laCotizacionSeConsumeAlEnviarNoAlPreparar` — la cotizacion se consume al enviar no al preparar
- `siElEnvioFallaLaCotizacionSigueSiendoRedimible` — si el envio falla la cotizacion sigue siendo redimible
- `unaCotizacionVencidaNoSeEnvia` — una cotizacion vencida no se envia
- `sinSaldoSuficienteNoSeIntentaElPago` — sin saldo suficiente no se intenta el pago
- `elCreadorSigueSinPoderAprobarSuPropioPago` — el creador sigue sin poder aprobar su propio pago
- `seGuardaElComprobanteQueReclamaElClienteFinal` — se guarda el comprobante que reclama el cliente final
- `laNaturalezaYElMemoViajanEnSuSitio` — la naturaleza y el memo viajan en su sitio
- `losDocumentosDeSoporteViajanComoDataUri` — los documentos de soporte viajan como data uri
- `unaUrlNoValeComoDocumentoDeSoporte` — una url no vale como documento de soporte
- `unPagoYaEnviadoNoSeReenvia` — un pago ya enviado no se reenvia
- `aKiraViajanSusIdsYNoLosDelPortal` — a kira viajan sus ids y no los del portal
- `crearUnPagoConUnaCuentaDeOtraEmpresaSeRechazaAlPreparar` — crear un pago con una cuenta de otra empresa se rechaza al preparar
- `crearUnPagoConUnDestinatarioInexistenteSeRechazaAlPreparar` — crear un pago con un destinatario inexistente se rechaza al preparar
- `elUserDeKiraDelPagoEsElDeLaEmpresa` — el user de kira del pago es el de la empresa
- `unaCotizacionDeOtroDestinatarioNoSeAtaAlPago` — una cotizacion de otro destinatario no se ata al pago
- `laVistaPreviaUsaLosIdsDeKiraYElMargenDeLaPlataforma` — la vista previa usa los ids de kira y el margen de la plataforma
- `laLineaDeTiempoSaleDeLosEventosDelPagoEnKira` — la linea de tiempo sale de los eventos del pago en kira
- `unPagoSinEnviarNoTieneLineaDeTiempoEnKira` — un pago sin enviar no tiene linea de tiempo en kira
- `elHistorialDeKiraDescartaPagosDeOtrasEmpresasYEnlazaLosDelPortal` — el historial de kira descarta pagos de otras empresas y enlaza los del portal
- `unEstadoQueKiraNoConoceSeRechazaAntesDeLlamar` — un estado que kira no conoce se rechaza antes de llamar
- `unPagoDetenidoPorUnRfiLoIndicaEnSuDetalle` — un pago detenido por un rfi lo indica en su detalle

### `application/treasury/RegisterRecipientServiceTest.java` — 13

- `elTitularSeInfiereDeLosNombresPorqueNoExisteHolderName` — el titular se infiere de los nombres porque no existe holder name
- `enWireLaDireccionDelBancoEsUnObjeto` — en wire la direccion del banco es un objeto
- `enAchLaDireccionDelBancoEsTextoPlano` — en ach la direccion del banco es texto plano
- `unaWalletViajaConTokenYRed` — una wallet viaja con token y red
- `unParTokenRedInvalidoSeCortaAntesDeLlamar` — un par token red invalido se corta antes de llamar
- `seLeeRecipientIdNoId` — se lee recipient id no id
- `unDoscientosDosEsExitoNoError` — un doscientos dos es exito no error
- `elEstadoYElCodigoPostalSalenDelEspejoLocal` — el estado y el codigo postal salen del espejo local
- `laCuentaSeMuestraEnmascarada` — la cuenta se muestra enmascarada
- `sinKybAprobadoNoHayDestinatarios` — sin kyb aprobado no hay destinatarios
- `archivarEnlazaConElReemplazo` — archivar enlaza con el reemplazo
- `unRolAprobadorNoRegistraDestinatarios` — un rol aprobador no registra destinatarios
- `losDestinatariosDeKiraSeEnmascaranYSeEnlazanConElDirectorio` — los destinatarios de kira se enmascaran y se enlazan con el directorio

### `application/webhook/KiraWebhookEnvelopeTest.java` — 3

- `leeLaEnvolturaPlana` — lee la envoltura plana
- `desanidaLaEnvolturaV2DePayoutStatusChanged` — desanida la envoltura v2 de payout status changed
- `noHayEventIdEnLaRaiz` — no hay event id en la raiz

### `application/webhook/UserEventProjectionTest.java` — 13

> La familia user.* trae dos datos que no existen en ningun otro sitio: el motivo del rechazo del KYB y el resultado real de la prueba de vida. Si no se proyectan aqui, se pierden: la entrega es unica y el GET no los expone.

- `elMotivoDelRechazoSeCapturaPorqueElGetNoLoExpone` — el motivo del rechazo se captura porque el get no lo expone
- `unRechazoSinMotivoDejaConstanciaIgual` — un rechazo sin motivo deja constancia igual
- `laAceptacionMarcaVerificadoYVerificacionDisparada` — la aceptacion marca verificado y verificacion disparada
- `unEventoSinStatusNoDegradaAUnaEmpresaVerificada` — un evento sin status no degrada a una empresa verificada
- `elResultadoDeLivenessSeDelegaEnSuPersona` — el resultado de liveness se delega en su persona
- `unLivenessRechazadoSeTraduceAFallido` — un liveness rechazado se traduce a fallido
- `unUsuarioDesconocidoSeRegistraSinRomperNada` — un usuario desconocido se registra sin romper nada
- `elMismoEventoDosVecesSoloSeProyectaUnaVez` — el mismo evento dos veces solo se proyecta una vez
- `elEventoQuedaMarcadoComoProcesado` — el evento queda marcado como procesado
- `elEventoActivatedEsLaSenalDeFondosListos` — el evento activated es la senal de fondos listos
- `unEventoDeCuentaSinNumeroNoBorraElQueYaTeniamos` — un evento de cuenta sin numero no borra el que ya teniamos
- `unEventoDeOtraFamiliaSigueFuncionando` — un evento de otra familia sigue funcionando
- `unEventoDeRfiSeProyectaEnLaBandejaYNoSoloSeAlmacena` — un evento de rfi se proyecta en la bandeja y no solo se almacena

### `domain/account/VirtualAccountActivationTest.java` — 2

> La fecha de alta es la base del aviso de activacion demorada. Si al leer la cuenta de la base se tomara la hora actual, el aviso no saltaria nunca: la cuenta siempre parece nueva.

- `alLeerLaCuentaSeConservaSuFechaDeAlta` — al leer la cuenta se conserva su fecha de alta
- `unaCuentaSinActivarTrasCincoMinutosEstaDemorada` — una cuenta sin activar tras cinco minutos esta demorada

### `domain/account/VirtualAccountReadinessTest.java` — 5

- `approvedSinNumeroDeCuentaNoEstaListaParaFondos` — approved sin numero de cuenta no esta lista para fondos
- `elCentinelaDeActNoCuentaComoCuentaReal` — el centinela de act no cuenta como cuenta real
- `unNumeroDeCuentaRealSiLaHabilita` — un numero de cuenta real si la habilita
- `elEventoActivatedEsSenalSuficiente` — el evento activated es senal suficiente
- `unaCuentaRechazadaNuncaEstaLista` — una cuenta rechazada nunca esta lista

### `domain/compliance/RfiTest.java` — 8

- `notResolvedEsUnCierreYNoUnRfiPendiente` — not resolved es un cierre y no un rfi pendiente
- `losEstadosSeLeenSinDistinguirMayusculas` — los estados se leen sin distinguir mayusculas
- `unEstadoDesconocidoQuedaVisibleEnLaBandeja` — un estado desconocido queda visible en la bandeja
- `unRfiRespondidoSigueAdmitiendoRespuestasPorqueKiraPuedeDevolverUnItem` — un rfi respondido sigue admitiendo respuestas porque kira puede devolver un item
- `unRfiCerradoNoAdmiteRespuestas` — un rfi cerrado no admite respuestas
- `unEventoTardioNoReabreUnRfiResuelto` — un evento tardio no reabre un rfi resuelto
- `unRfiVencidoYAbiertoEstaAtrasado` — un rfi vencido y abierto esta atrasado
- `alRehidratarSeConservaLaFechaDeCreacion` — al rehidratar se conserva la fecha de creacion

### `domain/tenant/TenantOnboardingTest.java` — 9

- `laClaveDeIdempotenciaSeReservaUnaSolaVez` — la clave de idempotencia se reserva una sola vez
- `elAltaNoDisparaLaVerificacion` — el alta no dispara la verificacion
- `noSeReasignaLaEmpresaAOtroUsuarioDeKira` — no se reasigna la empresa a otro usuario de kira
- `losCamposPendientesSonLosGeneralesMasLosDelProducto` — los campos pendientes son los generales mas los del producto
- `verifiedNoBastaSiElProductoNoEsElegible` — verified no basta si el producto no es elegible
- `conKybAprobadoYProductoElegibleSiEstaLista` — con kyb aprobado y producto elegible si esta lista
- `laVerificacionDisparadaNoSeRevierteSiElGetNoLaReporta` — la verificacion disparada no se revierte si el get no la reporta
- `sinVerificacionDisparadaNoSePidenEnlacesDeLiveness` — sin verificacion disparada no se piden enlaces de liveness
- `unaEmpresaSinKybNoOperaTesoreria` — una empresa sin kyb no opera tesoreria

### `domain/tenant/UboRosterTest.java` — 9

- `elCargoNoConvierteANadieEnBeneficiario` — el cargo no convierte a nadie en beneficiario
- `pordebajoDelCincoPorCientoNoEsBeneficiario` — pordebajo del cinco por ciento no es beneficiario
- `sinBeneficiarioElEnvioSeCortaAntesDeLlamarAKira` — sin beneficiario el envio se corta antes de llamar a kira
- `laSumaDeParticipacionesNoPuedeSuperarCien` — la suma de participaciones no puede superar cien
- `unGrupoValidoPasa` — un grupo valido pasa
- `unaEmpresaSinBeneficiariosNoSeEnvia` — una empresa sin beneficiarios no se envia
- `elLivenessSoloEstaCompletoCuandoTodosLosBeneficiariosPasan` — el liveness solo esta completo cuando todos los beneficiarios pasan
- `elPaisDeNacimientoEsObligatorio` — el pais de nacimiento es obligatorio
- `unResultadoFinalDeLivenessNoRetrocede` — un resultado final de liveness no retrocede

### `domain/treasury/PayoutStatusTest.java` — 4

- `comparaSinDistinguirMayusculas` — compara sin distinguir mayusculas
- `returnedYCancelledResuelvenEnFailed` — returned y cancelled resuelven en failed
- `unEstadoDesconocidoNoRompeYNoEsTerminal` — un estado desconocido no rompe y no es terminal
- `kytPendingEInReviewSonNoTerminales` — kyt pending e in review son no terminales

### `domain/treasury/PayoutTest.java` — 9

- `elCreadorNoPuedeAprobarSuPropioPago` — el creador no puede aprobar su propio pago
- `unSegundoOperadorSiPuedeAprobar` — un segundo operador si puede aprobar
- `noSePuedeAprobarDosVeces` — no se puede aprobar dos veces
- `unaCotizacionVencidaBloqueaLaAprobacion` — una cotizacion vencida bloquea la aprobacion
- `noSeEnviaAKiraSinAprobacionInterna` — no se envia a kira sin aprobacion interna
- `elEstadoEnMinusculasDelCreateSeNormaliza` — el estado en minusculas del create se normaliza
- `unEventoTardioNoRevierteUnEstadoTerminal` — un evento tardio no revierte un estado terminal
- `elDesgloseComisionalPorDefectoSonQuinceMasQuince` — el desglose comisional por defecto son quince mas quince
- `elMontoDebeSerPositivo` — el monto debe ser positivo

### `domain/treasury/QuotationRailTest.java` — 5

> El riel del pago se deriva del account_type del destinatario. Si no coinciden, la API falla al EJECUTAR el pago, no al cotizar: por eso se valida antes.

- `cadaTipoDeCuentaTieneSusRieles` — cada tipo de cuenta tiene sus rieles
- `unRielDeOtroTipoDeCuentaSeRechaza` — un riel de otro tipo de cuenta se rechaza
- `achAdmiteLosDosRitmos` — ach admite los dos ritmos
- `unaWalletSinRedNoSePuedeCotizar` — una wallet sin red no se puede cotizar
- `soloLosRielesDeWalletLlevanRed` — solo los rieles de wallet llevan red

### `domain/treasury/QuotationTest.java` — 11

- `elDebitoEsLoPrometidoMasLasComisiones` — el debito es lo prometido mas las comisiones
- `unBrutoQueNoCuadraSeDetecta` — un bruto que no cuadra se detecta
- `unPreviewNoEsRedimible` — un preview no es redimible
- `unaCotizacionVencidaNoSeReutiliza` — una cotizacion vencida no se reutiliza
- `sinSaldoNoSeRedime` — sin saldo no se redime
- `unaCotizacionYaEjecutadaNoSeVuelveAUsar` — una cotizacion ya ejecutada no se vuelve a usar
- `elContadorNuncaEsNegativo` — el contador nunca es negativo
- `detectaLaTasaDeContingencia` — detecta la tasa de contingencia
- `elPagoHeredaLasComisionesRealesDeLaCotizacion` — el pago hereda las comisiones reales de la cotizacion
- `noSeAtaUnaCotizacionDeOtroDestinatario` — no se ata una cotizacion de otro destinatario
- `unaCotizacionVencidaBloqueaLaAprobacionDelPago` — una cotizacion vencida bloquea la aprobacion del pago

### `domain/treasury/RecipientAccountTest.java` — 12

> Un destinatario = un riel, y de ese riel sale el riel de todos sus pagos. Las combinaciones imposibles se cortan al construirlo, no al pagar.

- `elRoutingNumberTieneNueveDigitos` — el routing number tiene nueve digitos
- `elSwiftTieneOchoUOnceCaracteres` — el swift tiene ocho u once caracteres
- `usdcNoExisteEnTron` — usdc no existe en tron
- `usdtSiExisteEnTron` — usdt si existe en tron
- `copmSoloVivEnPolygon` — copm solo viv en polygon
- `elRielSaleDelTipoDeCuenta` — el riel sale del tipo de cuenta
- `unaEmpresaNecesitaRazonSocialYUnaPersonaNombreCompleto` — una empresa necesita razon social y una persona nombre completo
- `elTelefonoTieneTope` — el telefono tiene tope
- `unDestinatarioBancarioNecesitaDireccionEnIso2` — un destinatario bancario necesita direccion en iso2
- `unaWalletNoNecesitaDireccionPostal` — una wallet no necesita direccion postal
- `archivarEnlazaConElReemplazo` — archivar enlaza con el reemplazo
- `sinAltaEnKiraNoSePuedeUsar` — sin alta en kira no se puede usar

### `infrastructure/bootstrap/CertProfileStartupTest.java` — 2

> Fuera de desarrollo el arranque debe caerse si falta un secreto, en lugar de quedar en pie firmando tokens con una clave de ejemplo o fallando en la primera llamada a Kira.

- `certNoArrancaSinLosSecretosDeKira` — cert no arranca sin los secretos de kira
- `certArrancaConTodosLosSecretosPresentes` — cert arranca con todos los secretos presentes

### `infrastructure/bootstrap/DevDataSeederTest.java` — 4

- `creaLasTresOrganizacionesConUnOperadorPorRol` — crea las tres organizaciones con un operador por rol
- `laContrasenaQuedaCifradaNoEnClaro` — la contrasena queda cifrada no en claro
- `makerYApproverSonOperadoresDistintosDelMismoTenant` — maker y approver son operadores distintos del mismo tenant
- `volverARegarNoDuplicaNada` — volver a regar no duplica nada

### `infrastructure/bootstrap/RequiredSecretsValidatorTest.java` — 3

- `arrancaCuandoTodosLosSecretosEstanPresentes` — arranca cuando todos los secretos estan presentes
- `falloAlArrancarNombraTodoLoQueFalta` — fallo al arrancar nombra todo lo que falta
- `rechazaUnaClaveDeFirmaDemasiadoCorta` — rechaza una clave de firma demasiado corta

### `infrastructure/kira/KiraAmountsTest.java` — 9

> La conversion de importes vive en un solo sitio porque la API expresa el MISMO markup de dos formas distintas segun el endpoint. Si algun dia dejan de coincidir, falla aqui.

- `convierteUnidadesMenoresConSuPrecision` — convierte unidades menores con su precision
- `laConversionEsExactaYNoPierdeCentavos` — la conversion es exacta y no pierde centavos
- `idaYVueltaConservaElImporte` — ida y vuelta conserva el importe
- `elMontoViajaSiempreConDosDecimales` — el monto viaja siempre con dos decimales
- `unMontoCeroNoSeCotiza` — un monto cero no se cotiza
- `elMismoMarkupEnLasDosFormasDeOndaDeLaApi` — el mismo markup en las dos formas de onda de la api
- `elPorcentajeDelPagoEsUnaFraccionEntreCeroYUno` — el porcentaje del pago es una fraccion entre cero y uno
- `elMarkupPorcentualTieneTope` — el markup porcentual tiene tope
- `lasStablecoinsUsanSeisDecimales` — las stablecoins usan seis decimales

### `infrastructure/kira/KiraApiClientVersionTest.java` — 5

> Los RFIs solo existen en 2026-06-01 y la cuenta integra con 2026-04-14. Si la cabecera por peticion no se sobrescribe, las rutas de RFI no se encuentran y la bandeja queda vacia sin ningun error visible.

- `lasRutasDeRfiViajanConLaVersionQueLasContiene` — las rutas de rfi viajan con la version que las contiene
- `laCotizacionViajaConLaVersionQueTraeElDesglose` — la cotizacion viaja con la version que trae el desglose
- `elRestoDeRutasSigueConLaVersionConfigurada` — el resto de rutas sigue con la version configurada
- `losDocumentosDeUnRfiViajanComoMultipartConLaParteFilesYLaVersionDeRfis` — los documentos de un rfi viajan como multipart con la parte files y la version de rfis
- `elCatalogoDePaisesVivePorDebajoDeV1` — el catalogo de paises vive por debajo de v1

### `infrastructure/kira/KiraCredentialManagerTest.java` — 2

> Sin credenciales, el BFF debe decir "integracion no configurada" (503) y no un error inesperado. Faltar client_id o password tambien cuenta: antes acababa en un NullPointerException.

- `sinApiKeyLaIntegracionNoEstaConfigurada` — sin api key la integracion no esta configurada
- `sinClientIdOPasswordTampocoYNoEsUnNullPointer` — sin client id o password tampoco y no es un null pointer

### `infrastructure/kira/KiraWebhookVerifierTest.java` — 6

- `aceptaUnaFirmaValidaSobreLosBytesCrudos` — acepta una firma valida sobre los bytes crudos
- `rechazaSiElCuerpoCambiaUnSoloByte` — rechaza si el cuerpo cambia un solo byte
- `reserializarElJsonInvalidaLaFirma` — reserializar el json invalida la firma
- `rechazaConOtroSecreto` — rechaza con otro secreto
- `rechazaSinCabeceraDeFirma` — rechaza sin cabecera de firma
- `sinSecretoConfiguradoNoValidaNada` — sin secreto configurado no valida nada

### `infrastructure/reconciliation/LivenessReconciliationWorkerTest.java` — 3

> El enlace de prueba de vida vive 7 dias y Kira no lo prorroga. Uno vencido que sigue en PENDING deja al portal esperando un resultado que ya no va a llegar.

- `unEnlaceVencidoQuedaMarcadoComoExpirado` — un enlace vencido queda marcado como expirado
- `sinEnlacesVencidosNoSeGuardaNada` — sin enlaces vencidos no se guarda nada
- `unResultadoFinalYaRecibidoNoSePisa` — un resultado final ya recibido no se pisa

### `infrastructure/reconciliation/PayoutReconciliationWorkerTest.java` — 5

> Kira entrega cada webhook una sola vez: si se pierde, el pago se queda para siempre en el estado que tenia. Este worker vuelve a preguntar por el recurso, que es la autoridad final.

- `unPagoEnVueloSeActualizaConElEstadoDelRecurso` — un pago en vuelo se actualiza con el estado del recurso
- `unFalloEnUnPagoNoDetieneElLote` — un fallo en un pago no detiene el lote
- `sinCredencialesDeKiraSeCortaElLoteSinTocarNada` — sin credenciales de kira se corta el lote sin tocar nada
- `sinPagosEnVueloNoSeLlamaAKira` — sin pagos en vuelo no se llama a kira
- `unEstadoTardioNoRevierteUnPagoYaTerminal` — un estado tardio no revierte un pago ya terminal

### `infrastructure/reconciliation/QuotationReconciliationWorkerTest.java` — 3

> Una cotizacion vencida que sigue apareciendo como ACTIVE es un precio que el portal ofrece y que ya no se puede redimir. El vencimiento es local: no hace falta preguntar a Kira.

- `unaCotizacionActivaYVencidaQuedaMarcadaComoExpirada` — una cotizacion activa y vencida queda marcada como expirada
- `sinCotizacionesVencidasNoSeGuardaNada` — sin cotizaciones vencidas no se guarda nada
- `unFalloAlGuardarUnaNoDetieneALasDemas` — un fallo al guardar una no detiene a las demas

### `infrastructure/reconciliation/ReconciliationWorkersDisabledTest.java` — 1

> El interruptor tiene que apagarlos de verdad: las pruebas corren con bff.reconciliation.enabled=false para que ningun worker toque la base ni llame a Kira.

- `conElInterruptorApagadoNoSeRegistraNinguno` — con el interruptor apagado no se registra ninguno

### `infrastructure/reconciliation/ReconciliationWorkersEnabledTest.java` — 1

> Los workers son la red de seguridad de los webhooks perdidos: si no se registran como beans, nada avisa y el hueco vuelve sin que nadie lo note.

- `losCincoWorkersSeRegistranCuandoLaReconciliacionEstaActiva` — los cinco workers se registran cuando la reconciliacion esta activa

### `infrastructure/reconciliation/RfiReconciliationWorkerTest.java` — 4

> Los eventos rfi.* exigen suscripcion explicita en Kira y se entregan una sola vez. Un RFI que no llega es un pago detenido que nadie ve hasta que vence, y el plazo no se prorroga.

- `soloSeSincronizanLasEmpresasDadasDeAltaEnKira` — solo se sincronizan las empresas dadas de alta en kira
- `unFalloEnUnaEmpresaNoDetieneALasDemas` — un fallo en una empresa no detiene a las demas
- `sinCredencialesDeKiraSeCortaSinRecorrerElResto` — sin credenciales de kira se corta sin recorrer el resto
- `sinEmpresasRegistradasNoSeLlamaAlServicio` — sin empresas registradas no se llama al servicio

### `infrastructure/reconciliation/WebhookReprojectionWorkerTest.java` — 9

> El ingress responde 2xx en cuanto guarda el evento: si la proyeccion falla despues, Kira no reintenta y la fila con processed = false es el unico rastro del cambio de estado. Dos de esos eventos (el motivo del rechazo del KYB y el resultado de la prueba de vida) no estan en ningun GET, asi que perderlos es perderlos para siempre.

- `unEventoPendienteSeVuelveAProyectar` — un evento pendiente se vuelve a proyectar
- `unEventoQueSigueFallandoConservaElMotivoYNoSeMarcaComoProcesado` — un evento que sigue fallando conserva el motivo y no se marca como procesado
- `cadaFalloCuentaUnIntentoMas` — cada fallo cuenta un intento mas
- `alQuintoIntentoElEventoQuedaComoFallidoDefinitivo` — al quinto intento el evento queda como fallido definitivo
- `losEventosAgotadosNoSeVuelvenAPedir` — los eventos agotados no se vuelven a pedir
- `unFalloEnUnEventoNoDetieneALosDemas` — un fallo en un evento no detiene a los demas
- `sinCredencialesDeKiraSeCortaElLoteYLaFilaSiguePendiente` — sin credenciales de kira se corta el lote y la fila sigue pendiente
- `sinEventosPendientesNoSeLlamaAlCasoDeUso` — sin eventos pendientes no se llama al caso de uso
- `elLoteRespetaElTamanoConfigurado` — el lote respeta el tamano configurado

### `interfaces/rest/OpenApiDocsTest.java` — 6

> La documentacion es parte del entregable: si un endpoint no aparece aqui, el equipo de frontend no puede probarlo. Estas pruebas fallan si alguien saca un endpoint del contrato sin darse cuenta.

- `laInterfazDeSwaggerSeSirveSinAutenticacion` — la interfaz de swagger se sirve sin autenticacion
- `losEndpointsDeNegocioEstanDocumentados` — los endpoints de negocio estan documentados
- `elEsquemaDeSeguridadEsElJwtPropioDelBff` — el esquema de seguridad es el jwt propio del bff
- `laDocumentacionNoFiltraSecretosNiLaUrlDeKira` — la documentacion no filtra secretos ni la url de kira
- `laVerificacionBiometricaPropiaYaNoExiste` — la verificacion biometrica propia ya no existe
- `elHealthCheckRespondeSinAutenticacion` — el health check responde sin autenticacion

### `interfaces/webhook/KiraWebhookControllerTest.java` — 4

- `elWebhookNoExigeJwtPeroSiFirmaValida` — el webhook no exige jwt pero si firma valida
- `rechazaUnaFirmaInvalidaSinTocarLaBase` — rechaza una firma invalida sin tocar la base
- `rechazaSiFaltaLaCabeceraDeFirma` — rechaza si falta la cabecera de firma
- `elMismoEventIdSoloSeAlmacenaUnaVez` — el mismo event id solo se almacena una vez

---

## Anexo C. DDL que espera Hibernate (MySQL)

Generado el 11-sep-2026 desde los metadatos JPA con el dialecto `MySQLDialect` (procedimiento en §12.5). Es exactamente el esquema que valida `ddl-auto: validate` en cert y prod.

```sql
create table audit_logs (
    created_at datetime(6) not null,
    id varchar(36) not null,
    tenant_id varchar(36),
    user_id varchar(36),
    ip_address varchar(45),
    action varchar(100) not null,
    resource_id varchar(100),
    resource_type varchar(100) not null,
    user_role varchar(100),
    changes json,
    primary key (id)
) engine=InnoDB;

create table deposits (
    fee_amount decimal(18,4) not null,
    gross_amount decimal(18,4) not null,
    microdeposit bit not null,
    net_amount decimal(18,4) not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    currency varchar(10) not null,
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    virtual_account_id varchar(36) not null,
    rail varchar(50),
    status varchar(50) not null,
    kira_deposit_id varchar(100),
    sender_account varchar(100),
    sender_name varchar(255),
    primary key (id)
) engine=InnoDB;

create table payouts (
    amount decimal(18,4) not null,
    kira_fee decimal(18,4) not null,
    platform_fee decimal(18,4) not null,
    total_fee decimal(18,4) not null,
    created_at datetime(6) not null,
    quotation_expires_at datetime(6),
    updated_at datetime(6) not null,
    currency varchar(10) not null,
    payment_method varchar(20),
    approver_user_id varchar(36),
    id varchar(36) not null,
    maker_user_id varchar(36) not null,
    quotation_id varchar(36),
    recipient_id varchar(36) not null,
    tenant_id varchar(36) not null,
    virtual_account_id varchar(36) not null,
    approval_state varchar(50) not null,
    status varchar(50) not null,
    kira_payout_id varchar(100),
    kira_user_id varchar(100),
    error_code varchar(120),
    reference_number varchar(120),
    rejection_reason varchar(500),
    idempotency_key varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table quotations (
    balance_sufficient bit not null,
    destination_amount decimal(18,4) not null,
    exchange_rate decimal(18,6),
    kira_fee decimal(18,4) not null,
    origin_amount decimal(18,4) not null,
    platform_fee decimal(18,4) not null,
    total_debit_amount decimal(18,4) not null,
    total_fee decimal(18,4) not null,
    created_at datetime(6) not null,
    quote_expires_at datetime(6) not null,
    destination_currency varchar(10),
    id varchar(36) not null,
    recipient_id varchar(36) not null,
    tenant_id varchar(36) not null,
    virtual_account_id varchar(36) not null,
    rail varchar(50) not null,
    rate_source varchar(50),
    status varchar(50) not null,
    kira_quote_id varchar(100),
    fees_snapshot json,
    primary key (id)
) engine=InnoDB;

create table recipients (
    is_business bit not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    phone varchar(16),
    account_kind varchar(20),
    network varchar(20),
    routing_number varchar(20),
    swift_code varchar(20),
    wallet_token varchar(20),
    id varchar(36) not null,
    replaced_by_recipient_id varchar(36),
    tenant_id varchar(36) not null,
    doc_type varchar(50),
    rail varchar(50) not null,
    status varchar(50) not null,
    account_number varchar(100),
    doc_number varchar(100),
    first_name varchar(100),
    kira_recipient_id varchar(100),
    last_name varchar(100),
    bank_name varchar(150),
    bank_address_text varchar(500),
    company_name varchar(255),
    email varchar(255),
    name varchar(255) not null,
    wallet_address varchar(255),
    bank_address json,
    holder_address json,
    primary key (id)
) engine=InnoDB;

create table rfis (
    created_at datetime(6) not null,
    due_date datetime(6),
    updated_at datetime(6) not null,
    blocking_type varchar(30),
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    status varchar(50) not null,
    blocking_resource_id varchar(100),
    kira_rfi_id varchar(100),
    items_payload json not null,
    primary key (id)
) engine=InnoDB;

create table roles (
    created_at datetime(6) not null,
    id varchar(36) not null,
    scope varchar(50) not null,
    name varchar(100) not null,
    description varchar(255),
    primary key (id)
) engine=InnoDB;

create table tenants (
    verification_triggered bit not null,
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    id varchar(36) not null,
    status varchar(50) not null,
    tax_id varchar(50) not null,
    jurisdiction varchar(100) not null,
    kira_user_id varchar(100),
    rejection_reason varchar(500),
    name varchar(255) not null,
    onboarding_idempotency_key varchar(255),
    eligible_products json,
    missing_fields json,
    onboarding_payload json,
    primary key (id)
) engine=InnoDB;

create table ubos (
    country_of_birth varchar(3),
    has_control bit not null,
    has_ownership bit not null,
    is_signer bit not null,
    ownership_percentage decimal(5,2) not null,
    politically_exposed bit not null,
    created_at datetime(6) not null,
    liveness_expires_at datetime(6),
    updated_at datetime(6) not null,
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    document_type varchar(50),
    liveness_status varchar(50) not null,
    document_number varchar(100),
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    person_reference_id varchar(100),
    role_in_company varchar(100),
    liveness_link text,
    primary key (id)
) engine=InnoDB;

create table users (
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    id varchar(36) not null,
    role_id varchar(36) not null,
    tenant_id varchar(36),
    status varchar(50) not null,
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    mfa_secret varchar(100),
    email varchar(255) not null,
    password_hash varchar(255) not null,
    primary key (id)
) engine=InnoDB;

create table virtual_accounts (
    activated_event_seen bit not null,
    balance_available decimal(18,4) not null,
    balance_refreshed_at datetime(6),
    created_at datetime(6) not null,
    updated_at datetime(6) not null,
    currency varchar(10) not null,
    mode varchar(20) not null,
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    status varchar(50) not null,
    bank varchar(60),
    account_number varchar(100),
    kira_account_id varchar(100),
    routing_number varchar(100),
    bank_name varchar(150),
    description varchar(255),
    opening_idempotency_key varchar(255),
    primary key (id)
) engine=InnoDB;

create table webhooks_log (
    processed bit not null,
    retry_count integer not null,
    created_at datetime(6) not null,
    processed_at datetime(6),
    id varchar(36) not null,
    normalized_status varchar(50),
    event_id varchar(100) not null,
    event_type varchar(100) not null,
    resource_id varchar(100),
    processing_error varchar(1000),
    payload json not null,
    primary key (id)
) engine=InnoDB;

create index idx_audit_tenant on audit_logs (tenant_id, created_at);
create index idx_deposits_tenant on deposits (tenant_id);
alter table deposits add constraint uk_deposits_kira_id unique (kira_deposit_id);
create index idx_payouts_tenant on payouts (tenant_id, created_at);
create index idx_payouts_idempotency on payouts (idempotency_key);
alter table payouts add constraint uk_payouts_idempotency unique (idempotency_key);
alter table payouts add constraint uk_payouts_kira_id unique (kira_payout_id);
create index idx_quotations_tenant on quotations (tenant_id);
create index idx_recipients_tenant on recipients (tenant_id);
alter table recipients add constraint uk_recipients_kira_id unique (kira_recipient_id);
create index idx_rfis_tenant on rfis (tenant_id);
create index idx_rfis_blocking on rfis (blocking_resource_id);
alter table rfis add constraint uk_rfis_kira_id unique (kira_rfi_id);
alter table roles add constraint uk_roles_name unique (name);
alter table tenants add constraint uk_tenants_name unique (name);
alter table tenants add constraint uk_tenants_kira_user unique (kira_user_id);
create index idx_ubos_tenant on ubos (tenant_id);
alter table users add constraint uk_users_email unique (email);
create index idx_virtual_accounts_tenant on virtual_accounts (tenant_id);
alter table virtual_accounts add constraint uk_va_kira_account unique (kira_account_id);
create index idx_webhooks_event on webhooks_log (event_id);
alter table webhooks_log add constraint uk_webhooks_event_id unique (event_id);
alter table users add constraint fk_users_role foreign key (role_id) references roles (id);
```
