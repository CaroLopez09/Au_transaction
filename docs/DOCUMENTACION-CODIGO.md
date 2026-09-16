# AuTransactional BFF — Documentación técnica del código

**Fecha:** 16 de septiembre de 2026 (base del 11-sep, actualizada con los cambios de Kira, seguridad y
control interno) · **Rama:** `develop`
**Stack:** Java 21 · Spring Boot 4.1.1 · MySQL 8 · Maven · **199 clases de producción (15.508 líneas)** · **53 ficheros de prueba, 424 pruebas**
**Portal que lo consume:** `au-transactional-web` — Angular 22.1 · **127 ficheros de producción (13.027 líneas)** · **155 pruebas unitarias, 34 E2E** (§18)

> Secciones 1–17 revisadas y anexos A–C **regenerados desde el código el 16-sep-2026**; cifras del BFF
> reverificadas ese mismo día con `./mvnw clean test` (**424 pruebas, 0 fallos, 0 omitidas**) y con el
> recuento de clases, endpoints y peticiones de Bruno sobre el código. La **§18 (el portal que consume
> el BFF)** se añadió el 16-sep-2026 desde el repositorio `au-transactional-web` (`ba4d4da`), con
> `npm test` en verde (155/155). El detalle cronológico de cada cambio está en `ESTADO.md` §3; el
> contrato HTTP campo a campo, en `API-GUIA.md`.

Este documento describe **todo** el código del repositorio: arquitectura, configuración, seguridad,
integración con Kira, dominio, casos de uso, API, webhooks, persistencia, errores, auditoría y pruebas.
Las secciones 1–17 explican cómo funciona y por qué; la §18 documenta el portal Angular que lo consume
y los huecos de contrato que siguen abiertos; los anexos A–C son la referencia exhaustiva generada desde
el propio código (cada clase y método no privado, cada prueba y el DDL completo).

> Documentos relacionados: [`API-GUIA.md`](API-GUIA.md) (contrato HTTP campo a campo) ·
> [`GUIA-BRUNO.md`](GUIA-BRUNO.md) (pruebas manuales) · [`ARQUITECTURA.md`](ARQUITECTURA.md) (visión resumida) ·
> [`ESTADO.md`](ESTADO.md) (estado y pendientes) · [`kira-cuerpos-peticiones.json`](kira-cuerpos-peticiones.json) (cuerpos exactos enviados a Kira).
>
> Documentos del portal (repositorio `au-transactional-web`): `docs/frontend-architecture.md` (arquitectura) ·
> `docs/frontend-backend-contract.md` (trazabilidad endpoint a endpoint, RBAC y huecos) ·
> `docs/frontend-qa.md` (QA) · `DESIGN.md` (sistema visual).

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
18. [El portal que consume el BFF (`au-transactional-web`)](#18-el-portal-que-consume-el-bff-au-transactional-web)
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
| Recepción de webhooks firmados | Un navegador no puede recibirlos; Kira reintenta 4 veces y después da el evento por perdido |
| Idempotencia persistente | La clave debe sobrevivir a un reintento desde otro dispositivo |
| Segundo factor propio (TOTP) y consentimientos | Kira verifica a la empresa, no protege la sesión de cada operador ni guarda la aceptación de términos |
| Límites de importe y doble firma | La API no ofrece motor de aprobaciones a los integradores |
| Observabilidad de la integración | Correlación, latencia de Kira y webhooks fallidos sólo se ven desde este lado |

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
| `spring-boot-starter-actuator` | compile | `/actuator/health`, `info` y `metrics` (Micrometer, sin exportador) |
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
│   ├── domain/                           Lógica de negocio pura (53 ficheros)
│   │   ├── shared/      (8)   TenantId, Money, IdempotencyKey, Rail, PostalAddress, StatusNormalizer,
│   │   │                      FileSignature (tipo real de un archivo), DomainException
│   │   ├── tenant/     (14)   Tenant, TenantStatus, MissingFields, EligibleProduct, Ubo, UboRoster, LivenessStatus,
│   │   │                      OperatorUser, Role, RoleScope, UserStatus + repositorios
│   │   ├── account/     (8)   VirtualAccount, VirtualAccountMode/Status/Readiness, Deposit, DepositStatus + repositorios
│   │   ├── treasury/   (18)   Recipient, RecipientHolder, RecipientAccount, WalletToken, BankAccountKind, Quotation,
│   │   │                      QuotationRail/Status, FeeBreakdown, Payout, PayoutStatus/ApprovalState,
│   │   │                      NatureOfPayment, SupportingDocument + repositorios
│   │   └── compliance/  (5)   Rfi, RfiStatus, AuditLog + repositorios
│   ├── application/                      Casos de uso (50 ficheros)
│   │   ├── auth/        (2)   LoginUseCase, MfaService (TOTP)
│   │   ├── tenant/     (11)   SubmitOnboardingService (alta, perfil, documentos, términos), SyncUbosService,
│   │   │                      OnboardingDraftService, KybDocuments, KiraUserState, comandos y vistas
│   │   ├── account/     (6)   OpenVirtualAccountService, RecordDepositService, KiraDepositEvent, comandos y vistas
│   │   ├── treasury/   (15)   RegisterRecipientService, CreateQuoteService, ExecutePayoutService,
│   │   │                      PayoutApprovalPolicy (límites y doble firma), KiraQuoteResponse, comandos y vistas
│   │   ├── compliance/  (6)   AnswerRfiService, RfiCommands, RfiView, RfiUboLink, RfiDocumentLink, RfiAnswerRejectedException
│   │   ├── platform/    (1)   PlatformConsoleService (consola multiempresa de solo lectura)
│   │   ├── notification/(3)   Notification, NotificationRepository, NotificationService (avisos en la app)
│   │   ├── audit/       (1)   AuditQueryService (bitácora y centro de eventos)
│   │   ├── shared/      (1)   IdempotencyKeyStore (clave de idempotencia en transacción propia)
│   │   ├── reference/   (2)   ReferenceCatalogService, CountryView
│   │   └── webhook/     (2)   ProcessWebhookUseCase, KiraWebhookEnvelope
│   ├── infrastructure/                   Adaptadores técnicos (74 ficheros)
│   │   ├── persistence/(39)   Entidades JPA, repositorios Spring Data, adaptadores de puertos, mappers
│   │   ├── kira/       (12)   KiraApiClient, KiraCredentialManager, KiraClientConfig, KiraProperties, KiraAmounts,
│   │   │                      KiraErrorParser, KiraApiException, KiraNotConfiguredException, KiraResponse,
│   │   │                      KiraAuthResponse, KiraFile, KiraWebhookVerifier
│   │   ├── security/    (8)   SecurityConfig, JwtService, JwtTenantFilter, TenantContext, AuthenticatedOperator,
│   │   │                      BffSecurityProperties, Totp, MfaSecretCipher
│   │   ├── observability/(2)  RequestIdFilter (X-Request-Id), IntegrationMetrics (métricas de Kira y webhooks)
│   │   ├── bootstrap/   (3)   DevDataSeeder, DevSeedProperties, RequiredSecretsValidator
│   │   ├── config/      (2)   AsyncConfig, OpenApiConfig
│   │   ├── audit/       (1)   AuditTrail
│   │   └── reconciliation/(7) 7 workers programados + package-info.java
│   └── interfaces/                       Entrada HTTP (15 ficheros)
│       ├── rest/       (14)   13 controladores + RestExceptionHandler
│       └── webhook/     (1)   KiraWebhookController
├── src/main/resources/
│   ├── application.yaml                  Configuración común
│   ├── application-dev.yaml              Desarrollo local
│   ├── application-cert.yaml             Certificación (sandbox de Kira)
│   └── application-prod.yaml             Producción
├── src/test/java/…                       51 clases de prueba (6.843 líneas)
├── src/test/resources/application.yaml   H2 en memoria y credenciales de prueba
└── docs/
    ├── DOCUMENTACION-CODIGO.md           Este documento
    ├── API-GUIA.md, GUIA-BRUNO.md, ARQUITECTURA.md, ESTADO.md
    ├── REVISION-DOCS-KIRA.md, REVISION-INTEGRAL-FRONT-BFF.md, REVISION-REQUISITOS-VS-CODIGO.md
    ├── kira-cuerpos-peticiones.json      Cuerpos exactos enviados a Kira
    ├── cronograma/                       Cronograma y backlog de ClickUp
    └── bruno/AuTransactional/            Colección de Bruno (104 peticiones en 13 carpetas)
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
| Kira `bank` / `sandbox` | `jp_morgan` / `true` (por defecto común) | ídem | `jp_morgan` / `false` | — |
| MFA obligatoria | `${BFF_MFA_ENFORCED:false}` | `${BFF_MFA_ENFORCED:true}` | `${BFF_MFA_ENFORCED:true}` | `false` |
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
| `kira.api-version` | `KIRA_API_VERSION` | `2026-06-01` | Cabecera `X-Api-Version` en **todas** las peticiones; `KiraProperties` rechaza otra versión al arrancar |
| `kira.token-ttl-seconds` | — | `3600` | Vida del token de Kira |
| `kira.token-refresh-margin-seconds` | — | `300` | Se renueva antes: la caché expira a los 3300 s |
| `kira.connect-timeout-ms` / `read-timeout-ms` | — | `5000` / `30000` | Timeouts del `RestClient` |
| `kira.bank` | `KIRA_BANK` | `jp_morgan` | Banco de las cuentas virtuales y `capabilities.requested_banks` del alta. Kira documenta `jp_morgan` y `austin_capital_trust`; el BFF solo admite el primero (producto `usa-virtual-accounts`) y **no arranca** con otro |
| `kira.sandbox` | `KIRA_SANDBOX` | `true` | Habilita simular depósitos |
| `kira.base-url` | `KIRA_BASE_URL` | por perfil | Raíz de la API de Kira |
| `bff.security.jwt-secret` | `BFF_JWT_SECRET` | vacío (dev: valor de desarrollo) | Clave HMAC256 del JWT (≥ 32 caracteres fuera de dev) |
| `bff.security.jwt-issuer` | `BFF_JWT_ISSUER` | `autransactional-bff` | Claim `iss` |
| `bff.security.token-expiration-ms` | `BFF_JWT_TTL_MS` | `28800000` (8 h) | Vida del JWT |
| `bff.security.mfa-encryption-key` | `BFF_MFA_ENCRYPTION_KEY` | vacío (dev: deriva del secreto JWT) | Clave AES-256-GCM del secreto TOTP. Obligatoria en cert y prod |
| `bff.security.mfa-enforced` | `BFF_MFA_ENFORCED` | `false` (dev) / `true` (cert y prod) | Exige segundo factor a todos los operadores |
| `bff.security.mfa-challenge-ttl-ms` | — | `300000` (5 min) | Vida del reto que devuelve el login |
| `bff.security.mfa-issuer` | — | `AU Transactional` | Nombre que muestra la app autenticadora |
| `bff.terms.version` | `BFF_TERMS_VERSION` | vacío | Versión vigente de los términos; vacía, el portal no pide aceptación |
| `bff.terms.url` | `BFF_TERMS_URL` | vacío | Enlace a los términos que se muestra al aceptar |
| `bff.payouts.approval.dual-approval-threshold` | `BFF_DUAL_APPROVAL_THRESHOLD` | `10000` | Desde ese importe, un pago necesita **dos** aprobadores distintos |
| `bff.payouts.approval.tenant-thresholds` | — | vacío | Umbral propio por empresa (id del BFF → importe) |
| `bff.reconciliation.enabled` | `BFF_RECONCILIATION_ENABLED` | `true` (`false` en pruebas) | Registra o no los 7 workers |
| `bff.reconciliation.initial-delay-ms` | — | `60000` | Espera tras el arranque |
| `bff.reconciliation.payouts-ms` / `payout-batch` | — | `600000` / `50` | Pagos en vuelo por pasada |
| `bff.reconciliation.quotations-ms` | — | `300000` | Cotizaciones vencidas |
| `bff.reconciliation.liveness-ms` | — | `3600000` | Enlaces de liveness vencidos |
| `bff.reconciliation.rfis-ms` | — | `900000` | Sincronización de RFIs |
| `bff.reconciliation.webhooks-ms` / `webhook-batch` | — | `1800000` / `50` | Eventos almacenados sin proyectar |
| `bff.reconciliation.tenants-ms` | — | `1800000` | Empresas registradas que aún no pueden operar |
| `bff.reconciliation.accounts-ms` | — | `3600000` | Cuentas abiertas (`failed`, `deactivated` y `frozen` no tienen webhook) |
| `kira.webhook-secret-previous` | `KIRA_WEBHOOK_SECRET_PREVIOUS` | vacío | Segundo secreto válido durante la rotación (~1 min de entregas con la firma anterior) |
| `bff.dev.seed` | `BFF_DEV_SEED` | `true` en dev | Activa `DevDataSeeder` |
| `bff.dev.seed-password` | `BFF_DEV_SEED_PASSWORD` | `Dev12345!` | Contraseña de los operadores sembrados |
| `management.endpoints.web.exposure.include` | — | `health,info,metrics` | Endpoints de Actuator expuestos (`metrics` sólo para `PLATFORM_OPERATOR`) |
| `logging.pattern.level` | — | `%5p [%X{requestId:-}]` | El `X-Request-Id` de la petición en cada línea de log |
| `management.endpoint.health.show-details` | — | `never` | Sin detalle en `/actuator/health` |

> ⚠️ `application-dev.yaml` ya **no** lleva la contraseña: toma `${DB_PASSWORD:…}` del entorno, así que
> hay que exportar `DB_PASSWORD` antes de arrancar el perfil `dev`. La contraseña antigua **sigue en el
> historial de Git** y el repositorio es público: falta rotarla. Ver §17.

Las propiedades se enlazan a records `@ConfigurationProperties`: `KiraProperties` (`kira.*`, que además
**valida la versión de API y el banco en su constructor**), `BffSecurityProperties` (`bff.security.*`),
`PayoutApprovalPolicy` (`bff.payouts.approval.*`, habilitado en la clase de arranque) y `DevSeedProperties`
(`bff.dev.*`). Los términos se leen con `@Value` en `SubmitOnboardingService`.

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
   `KIRA_CLIENT_ID`, `KIRA_PASSWORD`, `KIRA_WEBHOOK_SECRET`, `BFF_JWT_SECRET` y `BFF_MFA_ENCRYPTION_KEY`; lista **todas** las que faltan
   en un único `IllegalStateException` y exige además que el secreto JWT tenga al menos 32 caracteres. La
   aplicación no arranca si falla.
3. **`DevDataSeeder`** (sólo `dev` con `bff.dev.seed=true`, `ApplicationRunner`, `@Transactional`), idempotente:
   - calcula el hash BCrypt de la contraseña **una sola vez**;
   - asegura las 6 filas de `roles` (`name` = `Role.dbName()`, `description`, `scope`), incluida la del
     operador de plataforma;
   - crea, si no existen, las empresas `juriscop` (Juriscop, NIT 900123456-1), `bankvision` (Bankvision,
     900234567-2) y `au-colombia` (AU Colombia, 900345678-3), jurisdicción Colombia, **estado `CREATED`** y sin
     `kira_user_id`;
   - crea un operador por rol y empresa: id `<empresa>:<rol>`, correo `<rol con puntos>@<empresa>.test`
     (p. ej. `treasury.maker@juriscop.test`), nombre según rol (Admin, Operador, Tesorero, Cumplimiento,
     Consulta), apellido = nombre de la empresa, estado `ACTIVE`;
   - crea un operador de plataforma sin empresa (`platform:operator`, `operaciones@au.test`, rol
     `PLATFORM_OPERATOR`), que no se replica por empresa;
   - registra en log cuántas organizaciones y operadores creó.
4. La readiness de Actuator pasa a `UP` cuando terminan los `ApplicationRunner` (durante ese instante
   `/actuator/health` puede responder `503 OUT_OF_SERVICE`).

---

## 6. Seguridad

### 6.1 Cadena de filtros

`SecurityConfig.filterChain`:

- **CSRF desactivado** (API sin estado, sin cookies) y sesión `STATELESS`.
- Rutas **públicas**: `POST /api/auth/login`, los pasos del segundo factor que forman parte del ingreso
  (`/api/auth/mfa/verify`, `/setup`, `/enable`, que validan el reto dentro del servicio),
  `/api/webhooks/**` (se autentican por HMAC), `/actuator/health`, `/swagger-ui.html`, `/swagger-ui/**`,
  `/v3/api-docs/**`.
- `/actuator/**` (todo lo que no sea `health`) exige el rol **`PLATFORM_OPERATOR`**: las métricas no
  llevan datos de ninguna empresa, pero tampoco son de ellas.
- **Todo lo demás exige autenticación.**
- `JwtTenantFilter` se inserta antes de `UsernamePasswordAuthenticationFilter`; `RequestIdFilter`
  (`@Order(HIGHEST_PRECEDENCE)`) corre antes que todo y fija el `X-Request-Id` de la petición.

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
`IllegalArgumentException`. Devuelve `AuthenticatedOperator(userId, email, tenantId, role)`. **Rechaza
cualquier token con el claim `purpose`**: el reto del segundo factor se firma igual pero no es una sesión.

### 6.2.1 Verificación en dos pasos (TOTP)

| Pieza | Qué hace |
|---|---|
| `Totp` | RFC 6238 sin dependencias: `newSecret()` (160 bits en base32), `otpauthUri(...)` para el QR, `verify(secret, code, now)` con ventana de ±1 paso de 30 s (devuelve el paso usado) |
| `MfaSecretCipher` | AES-256-GCM con `bff.security.mfa-encryption-key`; en dev, si falta, deriva la clave del secreto JWT |
| `JwtService.issueMfaChallenge` / `verifyMfaChallenge` | Reto de 5 min con `purpose=mfa` y un id de reto |
| `MfaService` | `setup` (secreto nuevo + URI para el QR), `enable`, `disable` (exige código), `verify` (canjea el reto). Tope de **5 códigos erróneos** por reto y **un código no vale dos veces** (cachés Caffeine de intentos y del último paso usado). Cada paso queda auditado |
| `LoginUseCase` | Si el operador tiene MFA o `mfa-enforced` está activo, devuelve `mfaChallenge` + `mfaRequired`/`mfaSetupRequired` en lugar de la sesión |

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
| `PLATFORM_OPERATOR` | `platform_operator` | Operaciones y cumplimiento de AU: consola multiempresa de solo lectura | | | |

Los cinco primeros son `RoleScope.TENANT`; `PLATFORM_OPERATOR` es `RoleScope.SYSTEM` y **no pertenece a
ninguna empresa**: su `tenantId` es el centinela `TenantId.PLATFORM` (`__platform__`), de forma que las
rutas de empresa le devuelven vacío y las de plataforma rechazan a los roles de empresa con `403`.

**Consultas que gastan cuota de Kira** (refrescos, saldo, sincronizar depósitos) exigen
`ADMIN`, `TREASURY_MAKER`, `TREASURY_APPROVER` o `COMPLIANCE_INTERNAL`: `READ_ONLY` no las usa. El enlace de
descarga de un documento de RFI es sólo de `ADMIN` y `COMPLIANCE_INTERNAL`, y queda auditado.

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
5. Devuelve `LoginResult(accessToken, expiresIn, email, role, tenantId, tenantName, mfaChallenge,
   mfaRequired, mfaSetupRequired)`: con segundo factor, los tres últimos sustituyen a la sesión
   (§6.2.1). El operador de plataforma no tiene empresa y su `tenantName` es «AU Transactional».

### 6.7 Webhooks (HMAC)

`KiraWebhookVerifier`: HMAC-SHA256 en hexadecimal sobre los **bytes crudos** del cuerpo con
`kira.webhook-secret`, comparación en **tiempo constante** (`MessageDigest.isEqual`). Acepta además
`kira.webhook-secret-previous` mientras dura una rotación: Kira sigue firmando con el anterior cerca de un
minuto. Detalle del flujo en §11.

### 6.8 Consentimientos y archivos

- **Términos**: `SubmitOnboardingService.acceptTerms` exige la versión vigente (`bff.terms.version`), la
  manda a Kira como `tos_accepted_version` y la deja en el payload guardado y en la auditoría
  (`tenant.terms_accepted`). El perfil que envía el portal **no** puede fijar ese campo.
- **Consentimiento biométrico**: pedir enlaces de prueba de vida o subir una selfie exige
  `biometricConsent`; sin él, `422` sin llamar a Kira. Queda como `tenant.biometric_consent_recorded`.
- **Tipo real de los archivos**: `FileSignature` compara los primeros bytes con el MIME declarado (PDF,
  PNG, JPEG, WebP, HEIC) en documentos KYB, archivos de RFI y soportes de pago.

---

## 7. Integración con Kira

### 7.1 `KiraApiClient` — adaptador HTTP único

Responsabilidades que ningún caso de uso repite:

| Regla | Implementación |
|---|---|
| `x-api-key` en toda petición | cabecera fija en `doExchange` |
| `Authorization: Bearer <token>` | `KiraCredentialManager.getAccessToken()` |
| `X-Api-Version` | `kira.api-version` = **`2026-06-01` en todas las peticiones** (el go-live checklist exige una sola versión). Los RFIs y el desglose `fees[]`/`totals` de la cotización sólo existen en ella, y las cuentas virtuales devuelven `pending/activating/active/failed/deactivated` en vez de `approved` |
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
| `createQuotation` | `POST /v1/quotations` | `CreateQuoteService.create` |
| `previewPayout` | `POST /v1/virtual-accounts/{id}/payout/preview` | `ExecutePayoutService.preview` |
| `executePayout` | `POST /v1/virtual-accounts/{id}/payout` + idempotencia | `ExecutePayoutService.approveAndSubmit` |
| `getPayout` | `GET /v1/payouts/{id}` | `ExecutePayoutService.refreshFromKira`, `events` |
| `listPayouts` | `GET /v1/payouts` (page+limit) | `ExecutePayoutService.kiraHistory` |
| `listRfis` | `GET /v1/rfis` (limit+offset) | `AnswerRfiService.sync` |
| `getRfi` | `GET /v1/rfis/{id}` | `AnswerRfiService` |
| `answerRfiItems` | `PATCH /v1/rfis/{id}/items` | `AnswerRfiService.answer` |
| `uploadRfiDocuments` | `POST /v1/rfis/{id}/items/{item}/documents` multipart `files` | `AnswerRfiService.uploadDocuments` |
| `removeRfiDocument` | `DELETE …/documents/{doc}` | `AnswerRfiService.removeDocument` |
| `getRfiDocumentLink` | `GET …/documents/{doc}` | `AnswerRfiService.documentLink` |
| `mintRfiUboLink` | `POST /v1/rfis/{id}/items/{item}/ubo-link` | `AnswerRfiService.mintUboLink` (ítems `ubo_link` sin `url`) |
| `listCountries` | `GET /v1/countries` | `ReferenceCatalogService` |
| `exchange` / `exchangeWithStatus` | genérico | uso interno; mide cada llamada en `kira.api.requests` (método, ruta sin ids y resultado) |

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
| `IdempotencyKey(value)` | UUID v4. `newKey()`, `of(value)`, `fromClient(value)` (valida el UUID que manda el portal). Una clave por intención de negocio |
| `Rail` | `ACH`, `WIRE`, `WALLET`. `from` estricto, `fromWireOrNull` tolerante |
| `PostalAddress(streetName, city, state, postalCode, country)` | `assertIso2Country()` (destinatarios usan ISO-2); `isBlank()` |
| `StatusNormalizer` | `normalize` (trim + mayúsculas), `matches` (sin distinguir mayúsculas) |
| `FileSignature` | Tipo real de un archivo por sus primeros bytes: `detect(bytes)` y `matches(mime, bytes)` para PDF, PNG, JPEG, WebP y HEIC |
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
| `onboardingDraft` / `onboardingDraftUpdatedAt` | Borrador del asistente del portal; nunca viaja a Kira |

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

**`MissingFields(byProduct)`**: mapa inmutable producto → campos. `forProduct(p)` = **la lista de ese
producto**, y sólo si no existe se usa `general`: `general` es la unión de todos los productos, así que
sumarla pedía requisitos de otros bancos y la empresa nunca llegaba a estar lista. `isCompleteFor`,
`isEmpty`, `products`.

**`EligibleProduct(productCode, eligible, missingFields, unsupportedReason)`**: constantes
`USA_VIRTUAL_ACCOUNTS = "usa-virtual-accounts"` y `EDD_REQUIRED = "enhanced_due_diligence_required"`;
`requiresEnhancedDueDiligence()`.

#### `Ubo` — beneficiario final, director o firmante

Campos: nombre, apellido, documento, `ownershipPercentage` (0–100), `roleInCompany` (por defecto
"Beneficiario Final"), `hasOwnership`, `hasControl`, `signer`, `politicallyExposed`, `countryOfBirth` (ISO-3,
obligatorio, en mayúsculas), `personReferenceId` (Kira), `livenessStatus`, `livenessLink`, `livenessExpiresAt`.

Desde el 15-sep guarda también lo que Kira pide por persona en `missing_fields`: `email` (**Kira empareja
`associated_persons[]` por correo**), `birthDate`, `nationality`, `occupation`, `gender`, `phoneNumber`,
`documentCountry` y la dirección plana `address_*`. `syncedToKira` marca a quien ya viajó: **a esa persona
ya no se la puede borrar** (`rename`, `delete` y `markSyncedToKira` en `SyncUbosService`).

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

- **`OperatorUser(id, tenantId, email, passwordHash, firstName, lastName, role, status, mfaSecret,
  mfaEnabled, notificationsSeenAt)`**: `assertCanLogin`, `assertBelongsTo`, `fullName`, `isActive`. El
  operador de plataforma no tiene empresa (`tenant_id` nulo → `TenantId.PLATFORM`).
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
- **`VirtualAccountStatus`**: `PENDING`, `ACTIVE`, `INACTIVE`, `FAILED`, `FROZEN`. Sobre los valores de
  `2026-06-01`: `active→ACTIVE`, `activating` y `pending→PENDING` (el banco aún la está abriendo),
  `DEACTIVATED→INACTIVE`, `DECLINED`/`REJECTED`/`failed→FAILED`, `frozen→FROZEN`.
- **`VirtualAccountReadiness.isFundsReady(status, accountNumber, activatedEventSeen)`**: `false` si está
  **congelada**; `true` si se vio el evento `virtual_account.activated` o el estado es `active`; `false` si
  es `declined`, `deactivated` o `failed`; si no, `true` sólo con número de cuenta real (no vacío y distinto
  del centinela `PENDING-ACT-ACCOUNT`), que cubre las filas proyectadas con la versión anterior.

#### `Deposit`

Tres importes independientes (`grossAmount`, `feeAmount`, `netAmount`), `currency`, `kiraDepositId`,
`senderName`, `senderAccount`, `rail`, `status`, `microdeposit`.

| Método | Regla |
|---|---|
| `applyRemoteStatus(s)` | no retrocede desde `FAILED`/`REFUNDED` |
| `restate(gross, fee, net)` | corrige importes con un evento más completo |
| `creditsBalance()` | `COMPLETED` y no microdepósito |
| `markAsMicrodeposit`, `describeSender`, `linkKiraDeposit`, `net()`, `gross()` | — |

**`DepositStatus`**: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED`, `KYT_PENDING`, `KYT_REJECTED`
(terminales: `FAILED`, `REFUNDED`; `KYT_REJECTED` **no** lo es: una devolución aprobada por cumplimiento lo
pasa a `REFUNDED`). `fromWire`: `RETURNED`/`REVERSED→REFUNDED`; `DECLINED`/`REJECTED→FAILED`;
`KYT_PENDING` y `KYT_REJECTED` son propios; `PROCESSING`/`IN_TRANSIT`/`IN_REVIEW→PENDING`; **nulo o
desconocido → `PENDING`** (antes acreditaba, que es el fallo caro).
`fromEventName`: `deposit_funds_in_transit`/`deposit_scheduled`/`deposit_in_review→PENDING`,
`deposit_funds_failed→FAILED`, `deposit_funds_refunded→REFUNDED`,
`deposit_funds_received`/`microdeposit_funds_received`/`deposit_funds_in_destination→COMPLETED`
(o el estado del payload si viene). `isHeld()` marca el dinero retenido que la cuenta no puede pagar.

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
PENDING_APPROVAL ──approve (1ª firma si el importe supera el umbral: sigue PENDING_APPROVAL)──┐
        │                                                                                      │
        ├──approve (firma que completa: ≠ creador, ≠ quien registró el destinatario,  ◄────────┘
        │           ≠ la primera firma, cotización vigente)──► APPROVED ──markAsSubmitted──► SUBMITTED
        │
        ├──replaceQuotation (recotizar: anula la primera firma)──► PENDING_APPROVAL
        │
        └──reject (con motivo)──► REJECTED
```

**Estado en Kira (`PayoutStatus`):** `NOT_SUBMITTED` (local) → `CREATED` → `PENDING` / `PROCESSING` /
`KYT_PENDING` / `IN_REVIEW` → `COMPLETED` | `FAILED` | `CANCELLED` | `EXPIRED` (terminales). `UNKNOWN` para
valores desconocidos (no terminal). `CANCELLED` es un estado final propio (antes se plegaba en `FAILED`);
una devolución bancaria pasa el pago a `FAILED` incluso desde `COMPLETED`. `isInFlight()`: `CREATED`,
`PENDING`, `PROCESSING`, `KYT_PENDING`, `IN_REVIEW`.

| Método | Regla |
|---|---|
| constructor | importe > 0, creador obligatorio, `PENDING_APPROVAL` + `NOT_SUBMITTED` |
| `attachQuotation(quotation, now)` | cotización usable y de la misma cuenta y destinatario; hereda sus comisiones reales |
| `approve(approverId, now, requiredApprovals, recipientCreatorId)` | sólo desde `PENDING_APPROVAL`; el aprobador no puede ser el creador del pago, ni quien registró el destinatario, ni repetir su propia firma; cotización no vencida. Con dos firmas requeridas, la primera guarda `firstApproverUserId` y devuelve `false` (el pago no se envía) |
| `replaceQuotation(quotation, now)` | recotiza un pago pendiente: ata la cotización nueva y **anula la primera firma**, porque el precio pudo cambiar |
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
| `register(RegisterBusiness)` | 1) rol `canManageCompliance`; 2) `tenant.assertActive`; 3) si ya está en Kira devuelve el estado sin llamar; 4) cuerpo `{type: business, business_legal_name, email, source_of_funds, capabilities: {requested_banks: [kira.bank]}, external_id: tenantId}`; 5) reserva y guarda la clave de idempotencia; 6) `POST /v1/users`; 7) `linkKiraUser`, guarda el payload, aplica estado; 8) auditoría OK. Si Kira falla: auditoría ERROR y relanza |
| `completeProfile(CompleteProfile)` | 1) rol; 2) activa y registrada; 3) descarta `tos_accepted_version` del perfil recibido; 4) **fusión superficial** del payload guardado con el nuevo (una clave nueva reemplaza entera a la guardada: `associated_persons` debe ir completo); 5) `forUpdate` traduce el cuerpo al vocabulario del `PUT`; 6) `PUT /v1/users/{id}`; 7) guarda payload y estado; 8) auditoría; 9) **relee con `GET`** porque el estado real lo confirma el recurso |
| `forUpdate(profile)` (estático) | El alta y la actualización **no comparten nombres** y el `PUT` rechaza entero lo que no reconoce (`400 Unrecognized key(s)`, verificado en sandbox): quita `type`, `external_id` y `has_material_intermediary_ownership`; renombra `representative_date_of_birth→representative_birth_date` y `business_trade_name→doing_business_as`; aplana `registered_address` en `address_*`; y rechaza `ein` si el país de constitución no es `USA` (Kira: *do NOT send for non-US businesses*) |
| `attachDocuments(AttachDocuments)` | Kira no tiene endpoint de subida: el archivo viaja en el `PUT` dentro de `identifying_information[].documents[]` como *data URI* base64. Máximo 10 archivos y 7 MB por petición (el base64 infla ⅓ y el cuerpo entero no puede pasar de 10 MB), tipo real comprobado con `FileSignature`. **El base64 no se guarda**: `KybDocuments.withoutFiles` lo limpia antes de persistir el payload |
| `terms` / `acceptTerms(AcceptTerms)` | Versión vigente (`bff.terms.version`), su enlace y la aceptada. Aceptar exige el expediente creado y la versión vigente; manda `tos_accepted_version` a Kira y audita `tenant.terms_accepted` |
| `refresh` | Registrada → `GET /v1/users/{id}` → aplica estado |
| `reconcile(tenantId)` | Igual que `refresh` pero sin operador: lo usa `TenantReconciliationWorker`; devuelve `true` si cambió el estado o la elegibilidad |

`KiraUserState.from(json)` normaliza POST, PUT y GET: `id`, `status`, `missing_fields` (objeto producto →
lista), `eligible_products[]` (`product_code`, `eligible`, `missing_fields`, `unsupported_reason`) y
`verification_triggered` (nulo si no viene).

`OnboardingView`: `tenantId`, `name`, `kiraUserId`, `status`, **`rejectionReason`** (sólo existe aquí:
ningún `GET` de Kira lo devuelve), `verificationTriggered`, `pendingFields`
(= `missingFields.forProduct("usa-virtual-accounts")`), `eligibleProducts`, `readyForVirtualAccounts`,
`enhancedDueDiligenceRequired`.

**`OnboardingDraftService`** guarda el borrador del asistente (`GET`/`PUT /api/onboarding/draft`):
reemplaza el objeto completo, `{}` lo borra y rechaza archivos (`422` ante un *data URI*). Nunca viaja a Kira.

### 9.3 `SyncUbosService` — beneficiarios finales

| Método | Pasos |
|---|---|
| `list` | `UboView.Roster` (miembros, suma, hay beneficiario, liveness completo) |
| `save(SaveUbo)` | Rol; sin `id` crea, con `id` carga dentro de la empresa; `rename` (nombre, apellido y cargo), `describeDocument`, `describeRole` y `describeIdentity` (fecha de nacimiento, nacionalidad, ocupación, género, teléfono, país del documento y dirección); **rechaza un correo repetido en la empresa** (Kira empareja por correo y duplicaba personas); auditoría `tenant.ubo_saved`. **No llama a Kira** |
| `delete(id)` | Rol; sólo si Kira **aún no conoce** a esa persona (`syncedToKira`); devuelve el grupo actualizado |
| `syncToKira` | Rol; `roster.assertReadyForVerification()`; construye `associated_persons[]` (`first_name`, `last_name`, `email`, `has_ownership`, `ownership_percentage`, `has_control`, `is_signer`, `pep_status`, `country_of_birth`, `title`, `birth_date`, `nationality`, `occupation`, `gender`, `phone_number`, `document_country` y la dirección plana `address_*`; **sin `person_reference_id`**, que no es campo de entrada documentado); lo envía con `onboarding.completeProfile`; marca a cada persona como enviada; auditoría `tenant.ubos_synced` |
| `attachDocuments(id, docs, biometricConsent)` | Documento de identidad de **una** persona, anidado en su entrada de `associated_persons[]` (de ahí que necesite correo). Con una selfie exige el consentimiento y lo audita |
| `requestLivenessLinks(cmd)` | Rol; **exige `biometricConsent`** y lo audita; `tenant.assertVerificationInProgress()`; `redirect` sólo si llegan **ambas** URLs; `POST …/liveness-link`; cada enlace se asigna por `person_reference_id` o, si no, por nombre completo; vencimiento `expires_at` o +7 días; auditoría |
| `applyLivenessResult(personRef, status)` | Proyección del webhook `user.liveness_completed`: busca por referencia y aplica el estado |

### 9.4 `OpenVirtualAccountService` — cuentas virtuales

| Método | Pasos |
|---|---|
| `list`, `get` | Cuentas de la empresa → `VirtualAccountView` |
| `open(OpenAccount)` | 1) rol `canCreatePayout` o `canManageCompliance`; 2) `tenant.isReadyFor(usa-virtual-accounts)`; 3) crea la cuenta local con moneda (por defecto USD), modo y **banco de configuración**; 4) reserva y guarda clave; 5) `POST /v1/virtual-accounts {user_id, type: US_BANK, bank, mode, description?}`; 6) aplica respuesta y guarda; 7) auditoría. Un **`409`** adopta la cuenta existente (`adoptExisting`: lista por `user_id`, toma el primer `id` y relee la cuenta individual) |
| `refresh` | Abierta en Kira → `GET /v1/virtual-accounts/{id}` → aplica; si está demorada, WARN |
| `refreshBalance` | `GET …/balance`; `available_balance` numérico; un **`400` no es error** (sigue activándose) y devuelve el último saldo |
| `simulateDeposit` | Sólo con `kira.sandbox=true` (si no, `422` sin llamar); `{amount: 2 decimales, payment_type: wire|ach}`; auditoría; refresca saldo |
| `reconcile(account)` | Sin operador, para `VirtualAccountReconciliationWorker`: `failed`, `deactivated` y `frozen` no tienen webhook propio |

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
`senderName`, **`senderAccount` enmascarada** (`****1234`), `rail`, `status`, `microdeposit`,
`creditsBalance`, **`held`** (retenido por KYT: la cuenta no puede pagar con ese dinero), `createdAt`,
`updatedAt`.

### 9.6 `RegisterRecipientService` — destinatarios

| Método | Pasos |
|---|---|
| `list` | Sólo **activos** de la empresa, ordenados por nombre |
| `get` | Uno de la empresa |
| `register(RegisterRecipient, clientKey?)` | 0) con `Idempotency-Key` del portal, repetir la petición devuelve el destinatario ya creado; 1) rol `canCreatePayout`; 2) empresa verificada y registrada; 2b) guarda **quién lo registró** (después no podrá aprobar pagos hacia él); 3) construye titular (empresa o persona) y cuenta según riel (valida routing, SWIFT, par token/red, dirección ISO-2); 4) clave nueva; 5) `POST /v1/recipients` (`user_id`, `type`, nombres, contacto, `address`, `account` con `account_type` y sus campos; `bank_address` texto en ACH y objeto en WIRE); 6) lee `recipient_id` (o `id`); 7) guarda; 8) auditoría (marca "ya existía" si fue `202`) |
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
| `create(CreatePayout, clientKey?)` | 0) con `Idempotency-Key` del portal, repetir devuelve el pago ya creado; 1) rol; 2) empresa verificada y registrada; 3) cuenta de la empresa abierta en Kira y destinatario usable; 4) clave nueva; 5) pago con `kiraUserId` **de la empresa**, ids del portal y comisiones estimadas; 6) con `quotationId`: debe ser de la misma cuenta y destinatario y se ata (hereda comisiones); 7) guarda y audita `payout.created` |
| `approveAndSubmit(id, ApprovePayout?)` | 1) rol `canApprovePayout`; 2) carga el pago; 3) si hay cotización, `assertRedeemable`; 4) `approve` con las firmas que pide `PayoutApprovalPolicy` y el autor del destinatario: **si es la primera de dos, guarda la firma, audita `payout.first_approval` y termina sin llamar a Kira**; 5) guarda y audita `payout.approved`; 6) **envío**: `assertSubmittable`, cuenta con fondos listos, destinatario usable con id de Kira; cuerpo (`recipient_id` de Kira, `amount` bruto, `quote_id` **o** `client_markup`, `nature_of_payment`, `supporting_documents`, `extra_info {memo ≤255, internal_notes ≤1000}`); `POST /v1/virtual-accounts/{kiraAccountId}/payout` con la clave del pago; lee `id` o `payout_id`; `markAsSubmitted`, `describeRemote`; marca la cotización `EXECUTED`; audita `payout.submitted OK`. Si Kira falla: `fail`, audita ERROR y relanza |
| `requote(id)` | Rol maker o aprobador; pago pendiente con precio fijado; pide a Kira una cotización nueva con la misma cuenta, destinatario, riel e importe (`CreateQuoteService.requote`), la ata al pago, vence la anterior y **anula la primera firma**; audita `payout.requoted` |
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
| `documentLink(id, itemId, documentId)` | Item documento de la empresa → `{downloadUrl, expiresAt}`; la URL es una credencial al portador y **no se registra**, pero sí queda auditado quién la pidió (`compliance.rfi_document_link_issued`) |
| `mintUboLink(id, itemId)` | Ítems `ubo_link` que llegan con `applicant_id` y `person_id` pero **sin `url`**: acuña el enlace con `POST …/ubo-link` (caduca en ~1 h) |
| `syncForTenant(tenantId)` | Lo mismo que `sync` pero sin operador (lo usa el worker de reconciliación); devuelve cuántos RFIs asentó |
| `applyWebhook(kiraRfiId, rawStatus)` | Aplica el estado del evento si el RFI existe; relee siempre de Kira; atribuye por RFI local o por `user_id`/bloqueo; `upsert` |

`RfiView`: `id`, `kiraRfiId`, `status`, `open`, `overdue`, `dueDate`, **`resolutionReason`** (por qué cerró
sin resolverse: `expired`, `rejected` o `withdrawn`), `totalItems`, `pendingItems`, `items`,
`blocking {type, kiraResourceId, payoutId, payoutStatus, depositId, depositStatus}`, `createdAt`, `updatedAt`.

Un RFI **retirado** responde `404` en todas sus rutas: al refrescar, sincronizar o recibir su webhook pasa a
`WITHDRAWN` en local en vez de fallar en cada pasada.

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

### 9.13 `MfaService` — segundo factor

Ver §6.2.1. Auditoría: `auth.mfa_setup_started`, `auth.mfa_enabled`, `auth.mfa_disabled`,
`auth.mfa_verified` y `auth.mfa_failed`.

### 9.14 `NotificationService` y `AuditQueryService` — avisos, eventos y bitácora

- **`NotificationService`**: al proyectar un webhook crea el aviso de negocio de esa empresa
  (vinculación aprobada, rechazada o en revisión, prueba de vida, cuenta operativa o congelada, depósito
  recibido, devuelto o retenido, pago completado, fallido, retenido o cancelado, RFI abierto o cerrado).
  `feed(operator, limit)`, `unreadCount(operator)` (contra `users.notifications_seen_at`) y `markAllRead`.
- **`AuditQueryService`**: `auditTrail(operator, limit)` resuelve el actor a nombre y correo, y
  `events(operator, limit)` devuelve los webhooks recibidos de esa empresa **sin el payload** (puede traer
  datos personales y aquí sólo interesa qué pasó y si se proyectó). Tope de 200 filas.

### 9.15 `PlatformConsoleService` — consola de operaciones

Sólo `PLATFORM_OPERATOR` (§6.4). `tenants()` resume cada empresa (estado KYB, faltantes, beneficiarios,
cuentas, RFIs abiertos y vencidos, pagos retenidos), `tenant(id)` arma la ficha 360 (resumen,
`OnboardingView`, beneficiarios, cuentas, 20 pagos y 20 depósitos recientes, RFIs) y **queda auditada**
(`platform.tenant_viewed`), `refresh(id)` relee empresa y cuentas en Kira, y `reviewQueue()` ordena lo que
pide atención en todas las organizaciones, lo crítico primero. Es **de solo lectura**: no remedia expedientes.

### 9.16 `PayoutApprovalPolicy` — límites de aprobación

Record de configuración (`bff.payouts.approval`): umbral general y por empresa. `requiredApprovals(tenant,
amount)` devuelve 1 o 2 y `thresholdFor(tenant)` el límite aplicado. Es lo único que decide si un pago
necesita doble firma; el resto de la regla vive en `Payout.approve`.

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
| POST | `/api/onboarding/refresh` | ADMIN, TREASURY_MAKER, TREASURY_APPROVER, COMPLIANCE_INTERNAL | `.refresh` | `GET /v1/users/{id}` |
| POST | `/api/onboarding/documents` (multipart `files`) | ADMIN, COMPLIANCE_INTERNAL | `.attachDocuments` | `PUT` + `GET /v1/users/{id}` |
| GET | `/api/onboarding/draft` | autenticado | `OnboardingDraftController.get` | no |
| PUT | `/api/onboarding/draft` | ADMIN, COMPLIANCE_INTERNAL | `.save` | no |
| GET | `/api/onboarding/terms` | autenticado | `OnboardingController.terms` | no |
| POST | `/api/onboarding/terms` | ADMIN, COMPLIANCE_INTERNAL | `.acceptTerms` | `PUT /v1/users/{id}` |
| GET | `/api/ubos` | autenticado | `UboController.list` | no |
| POST | `/api/ubos` | ADMIN, COMPLIANCE_INTERNAL | `.save` | no |
| DELETE | `/api/ubos/{id}` | ADMIN, COMPLIANCE_INTERNAL | `.delete` (sólo si Kira no lo conoce) | no |
| POST | `/api/ubos/{id}/documents` (multipart `files`, `biometricConsent`) | ADMIN, COMPLIANCE_INTERNAL | `.attachDocuments` | `PUT /v1/users/{id}` |
| POST | `/api/ubos/sync` | ADMIN, COMPLIANCE_INTERNAL | `.sync` | `PUT` + `GET /v1/users/{id}` |
| POST | `/api/ubos/liveness-links` | ADMIN, COMPLIANCE_INTERNAL | `.requestLivenessLinks` | `POST …/liveness-link` |
| GET | `/api/rfis?open=` | autenticado | `RfiController.list` | no |
| GET | `/api/rfis/{id}` | autenticado | `.get` | no |
| POST | `/api/rfis/sync` | ADMIN, COMPLIANCE_INTERNAL | `.sync` | `GET /v1/rfis` (+ detalle) |
| POST | `/api/rfis/{id}/refresh` | ADMIN, COMPLIANCE_INTERNAL | `.refresh` | `GET /v1/rfis/{id}` |
| PATCH | `/api/rfis/{id}/items` | ADMIN, COMPLIANCE_INTERNAL | `.answer` | `PATCH …/items` + `GET` |
| POST | `/api/rfis/{id}/items/{itemId}/documents` (multipart `files`) | ADMIN, COMPLIANCE_INTERNAL | `.uploadDocuments` | `POST …/documents` + `GET` |
| DELETE | `/api/rfis/{id}/items/{itemId}/documents/{documentId}` | ADMIN, COMPLIANCE_INTERNAL | `.removeDocument` | `DELETE …/documents/{doc}` + `GET` |
| GET | `/api/rfis/{id}/items/{itemId}/documents/{documentId}/link` | ADMIN, COMPLIANCE_INTERNAL | `.documentLink` (auditado) | `GET …/documents/{doc}` |
| POST | `/api/rfis/{id}/items/{itemId}/ubo-link` | ADMIN, COMPLIANCE_INTERNAL | `.mintUboLink` | `POST …/ubo-link` |
| GET | `/api/virtual-accounts` | autenticado | `VirtualAccountController.list` | no |
| GET | `/api/virtual-accounts/{id}` | autenticado | `.get` | no |
| POST | `/api/virtual-accounts` → **201** | ADMIN, TREASURY_MAKER, COMPLIANCE_INTERNAL | `.open` | `POST /v1/virtual-accounts` (+ lista/detalle si 409) |
| POST | `/api/virtual-accounts/{id}/refresh` | ADMIN, TREASURY_MAKER, TREASURY_APPROVER, COMPLIANCE_INTERNAL | `.refresh` | `GET /v1/virtual-accounts/{id}` |
| POST | `/api/virtual-accounts/{id}/balance` | ADMIN, TREASURY_MAKER, TREASURY_APPROVER, COMPLIANCE_INTERNAL | `.refreshBalance` | `GET …/balance` |
| POST | `/api/virtual-accounts/{id}/simulate-deposit` | ADMIN, TREASURY_MAKER | `.simulateDeposit` | `POST …/simulate-deposit` + balance |
| GET | `/api/deposits?limit=50` | autenticado | `DepositController.list` | no |
| GET | `/api/virtual-accounts/{id}/deposits?limit=50` | autenticado | `.listByAccount` | no |
| POST | `/api/virtual-accounts/{id}/deposits/sync` | ADMIN, TREASURY_MAKER, TREASURY_APPROVER, COMPLIANCE_INTERNAL | `.syncFromKira` | `GET …/deposits` |
| GET | `/api/recipients` | autenticado | `RecipientController.list` | no |
| GET | `/api/recipients/{id}` | autenticado | `.get` | no |
| POST | `/api/recipients` → **201** (`Idempotency-Key` opcional) | TREASURY_MAKER, ADMIN | `.register` | `POST /v1/recipients` |
| POST | `/api/recipients/{id}/archive` | TREASURY_MAKER, ADMIN | `.archive` | no |
| GET | `/api/recipients/kira` | autenticado | `.listInKira` | `GET /v1/recipients` |
| GET | `/api/recipients/{id}/kira` | autenticado | `.getInKira` | `GET /v1/recipients/{id}` |
| GET | `/api/quotations?limit=50` | autenticado | `QuotationController.list` | no |
| GET | `/api/quotations/{id}` | autenticado | `.get` | no |
| POST | `/api/quotations` → **201** | TREASURY_MAKER, ADMIN | `.create` | `POST /v1/quotations` |
| GET | `/api/payouts?limit=50` | autenticado | `PayoutController.list` | no |
| GET | `/api/payouts/{id}` | autenticado | `.get` | no |
| POST | `/api/payouts` → **201** (`Idempotency-Key` opcional) | TREASURY_MAKER, ADMIN | `.create` | no |
| POST | `/api/payouts/{id}/approve` | TREASURY_APPROVER, ADMIN | `.approve` | `POST …/payout` (no en la 1ª de dos firmas) |
| POST | `/api/payouts/{id}/requote` | TREASURY_MAKER, TREASURY_APPROVER, ADMIN | `.requote` | `POST /v1/quotations` |
| POST | `/api/payouts/{id}/reject` | TREASURY_APPROVER, ADMIN | `.reject` | no |
| POST | `/api/payouts/{id}/refresh` | ADMIN, TREASURY_MAKER, TREASURY_APPROVER, COMPLIANCE_INTERNAL | `.refresh` | `GET /v1/payouts/{id}` |
| GET | `/api/payouts/{id}/events` | autenticado | `.events` | `GET /v1/payouts/{id}` |
| POST | `/api/payouts/preview` | TREASURY_MAKER, ADMIN | `.preview` | `POST …/payout/preview` |
| GET | `/api/payouts/kira?status=&page=1&limit=20&fromDate=&toDate=` | autenticado | `.kiraHistory` | `GET /v1/payouts` |
| GET | `/api/reference/countries` | autenticado | `ReferenceController.countries` | `GET /v1/countries` (cache 24 h) |
| POST | `/api/auth/mfa/verify` | **público** (canjea el reto) | `AuthController.verifyMfa` → `MfaService.verify` | no |
| POST | `/api/auth/mfa/setup` | **público** (con reto) o autenticado | `.setupMfa` | no |
| POST | `/api/auth/mfa/enable` | **público** (con reto) o autenticado | `.enableMfa` | no |
| POST | `/api/auth/mfa/disable` | autenticado | `.disableMfa` | no |
| GET | `/api/notifications?limit=50` | autenticado | `ActivityController.notifications` | no |
| GET | `/api/notifications/unread-count` | autenticado | `.unreadCount` | no |
| POST | `/api/notifications/read` → **204** | autenticado | `.markAllRead` | no |
| GET | `/api/events?limit=100` | ADMIN, COMPLIANCE_INTERNAL | `.events` (sin payload) | no |
| GET | `/api/audit?limit=100` | ADMIN, COMPLIANCE_INTERNAL | `.auditTrail` | no |
| GET | `/api/platform/tenants` | PLATFORM_OPERATOR | `PlatformController.tenants` | no |
| GET | `/api/platform/tenants/{id}` | PLATFORM_OPERATOR | `.tenant` (auditado) | no |
| POST | `/api/platform/tenants/{id}/refresh` | PLATFORM_OPERATOR | `.refresh` | `GET /v1/users/{id}` + cuentas |
| GET | `/api/platform/review-queue` | PLATFORM_OPERATOR | `.reviewQueue` | no |
| POST | `/api/webhooks/kira` | **público (HMAC)** | `KiraWebhookController.receive` | según evento |

Además: `GET /actuator/health` (público), `GET /actuator/metrics` (**sólo `PLATFORM_OPERATOR`**),
`GET /swagger-ui.html` y `GET /v3/api-docs` (públicos salvo en prod). Cada respuesta lleva su
`X-Request-Id` (§14).

**Total: 69 operaciones en 14 controladores.** Los grupos de Swagger (`@Tag`) son:
`0. Catalogos`, `1. Sesion`, `1.1 Onboarding KYB`, `1.2 Beneficiarios finales`,
`1.3 Solicitudes de informacion (RFI)`, `2. Pagos`, `2.1 Cotizaciones`, `2.2 Destinatarios`,
`2.3 Cuentas virtuales`, `2.4 Depositos`, `6. Webhooks de Kira`, `7. Actividad`,
`8. Consola de operaciones`.

---

## 11. Webhooks de Kira

### 11.1 Ingreso — `KiraWebhookController.receive`

`POST /api/webhooks/kira`, cualquier `Content-Type`, cuerpo como `byte[]`:

1. Sin `kira.webhook-secret` → `503 {"error":"webhook_secret_not_configured"}` (log ERROR).
2. Firma `x-signature-sha256` inválida o ausente → `401 {"error":"invalid_signature"}` (log WARN con
   tamaño). Durante una rotación vale también `kira.webhook-secret-previous`.
3. Válida → convierte a texto **después** de verificar y **guarda el evento antes de responder**
   (`processWebhook.record`): si la base falla, sale un `5xx` y Kira reintenta. JSON ilegible con firma
   válida → `400 {"error":"invalid_json"}` (reintentarlo daría lo mismo).
4. `200 {"status":"received"}` o `{"status":"duplicate"}`, y la proyección sigue después en otro hilo
   (`projectLater`).

Kira aborta a los 30 s y **reintenta 4 veces** (1, 5, 15 y 60 min) ante `408`, `429`, `5xx` o falta de
respuesta; un `4xx` no se reintenta. Por eso se responde enseguida y nunca con `4xx` ante un evento
desconocido. Cada resultado incrementa `kira.webhooks.received` (§14).

### 11.2 Procesamiento — `ProcessWebhookUseCase`

`record(rawPayload)` (`@Transactional`, dentro de la petición de Kira) guarda el evento y devuelve su id
interno, o vacío si era duplicado. `projectLater(storedId)` (`@Async` en `webhookExecutor`) proyecta
después llamando a `self.reproject(...)` **a través del proxy de Spring** (una llamada directa a `this` se
saltaría la transacción) y **nunca propaga** excepciones: la fila queda con `processing_error`, suma una a
`kira.webhooks.projection.failures` y la recoge `WebhookReprojectionWorker`. `process(rawPayload)` hace las
dos cosas en el mismo hilo y lo usan las pruebas.

Pasos comunes:

1. `KiraWebhookEnvelope.from(json)` normaliza las dos envolturas:
   - plana `{event, data{event_id, status, …}}`;
   - V2 de `payout.status_changed` `{data{event_id, event_type, created_at, data{status, previous_status, …}}}`.
   El id de deduplicación está siempre en `data.event_id`.
2. Sin `event_id` → WARN y se guarda con id `no-id:<uuid>`; con `event_id` ya existente → se ignora.
3. Inserta en `webhooks_log` (`saveAndFlush`); una violación de unicidad concurrente se ignora.
4. `applyProjection`; si falla, guarda `processing_error` (máx. 1000) y deja `processed = false`; si no,
   `processed = true` y `processed_at`. La proyección anota además `tenant_id` en la fila y crea el aviso
   de negocio de esa empresa (§9.14).

### 11.3 Proyecciones por familia

| Prefijo | Qué hace |
|---|---|
| `payout.*` | `resource_id` = `payout_id` o `id`; `normalized_status` = `PayoutStatus.fromWire`. Si el pago local existe: `applyRemoteStatus` (con `error_code = va-payout-bank-returned` para `payout.returned`). Se decide por el **valor** del estado, no por el nombre (payout.* y payout.status_changed se solapan) |
| `user.*` | `resource_id` = `user_id` o `id`. `user.liveness_completed` → `SyncUbosService.applyLivenessResult(person_reference_id o subject_id, LivenessStatus)` leyendo **`result`** (es donde Kira manda `approved`), y una referencia nula no se proyecta. Con empresa local: `user.verification.failed` → `rejectVerification` con **`reasons[]` unidos por `; `**; `user.status_changed` → **`new_status`** (es el evento que Kira pide suscribir); `user.verification.accepted` → `VERIFIED` y verificación disparada; otros con `status` explícito → aplica estado; **sin estado no se toca** (evita degradar a `CREATED`) |
| `virtual_account.*` | Si el nombre contiene `deposit` → `KiraDepositEvent.from` (ordenante y riel salen de `source.sender_name` y `source.payment_rail`) + `RecordDepositService.apply`; el estado sale del **nombre del evento** cuando el payload no lo trae, así que un `deposit_funds_refunded` ya no se acredita. Si no, con cuenta local: `virtual_account.activated` → `markActivatedEventSeen`; otros con estado → `applyRemoteStatus`; siempre `describeBank` |
| `rfi.*` | `resource_id` = `rfi_id` o `id`; `AnswerRfiService.applyWebhook` (relee el RFI en Kira; guarda `to_status` y `resolution_reason`, y un `404` lo cierra como `WITHDRAWN`) |
| otros | Se almacenan y se registra INFO |

**Eventos que son única fuente de su dato:** `user.verification.failed` (motivo del rechazo),
`user.liveness_completed` (resultado real), `virtual_account.activated` (fondos-listos) y
`payout.status_changed` (`KYT_PENDING`/`IN_REVIEW`). Si se pierden tras los cuatro reintentos, los workers
de reconciliación releen empresas, cuentas, pagos y RFIs (§12.6).

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

13 tablas, ids `VARCHAR(36)`, importes `DECIMAL(18,4)`, enums como `VARCHAR`, JSON nativo donde el dato es libre.

| Tabla | Entidad | Contenido | Claves y restricciones |
|---|---|---|---|
| `tenants` | `TenantEntity` | Empresas y estado del bucle KYB (`missing_fields`, `eligible_products`, `onboarding_payload` JSON; `verification_triggered`, `onboarding_idempotency_key`, `rejection_reason`) | `uk_tenants_name`, `uk_tenants_kira_user` |
| `roles` | `RoleEntity` | Catálogo RBAC (`name`, `description`, `scope`) | `uk_roles_name` |
| `users` | `OperatorUserEntity` | Operadores (`email`, `password_hash`, nombres, `role_id`, `tenant_id` **nulo para el operador de plataforma**, `status`, `mfa_secret` cifrado, `mfa_enabled`, `notifications_seen_at`) | `uk_users_email`, **FK `fk_users_role` → `roles.id`** |
| `ubos` | `UboEntity` | Beneficiarios, booleanos del KYB, liveness (`liveness_link TEXT`) | `idx_ubos_tenant` |
| `virtual_accounts` | `VirtualAccountEntity` | Cuentas, banco, saldo, `activated_event_seen`, `opening_idempotency_key` | `uk_va_kira_account`, `idx_virtual_accounts_tenant` |
| `deposits` | `DepositEntity` | Bruto, comisión, neto, ordenante, riel, estado, microdepósito | `uk_deposits_kira_id`, `idx_deposits_tenant` |
| `recipients` | `RecipientEntity` | Espejo completo: titular, contacto, `holder_address`/`bank_address` JSON, columnas ACH/WIRE/WALLET, estado, reemplazo, `created_by_user_id` (segregación de funciones) | `uk_recipients_kira_id`, `idx_recipients_tenant` |
| `quotations` | `QuotationEntity` | Riel, importes, tasa, comisiones, `fees_snapshot` JSON, `rate_source`, vencimiento | `idx_quotations_tenant` |
| `payouts` | `PayoutEntity` | Pago, comisiones, maker/approver, `first_approver_user_id` (doble firma), estados, cotización, `idempotency_key`, comprobante | `uk_payouts_idempotency`, `uk_payouts_kira_id`, `idx_payouts_tenant`, `idx_payouts_idempotency` |
| `rfis` | `RfiEntity` | Estado, `resolution_reason`, `items_payload` JSON, plazo, bloqueo | `uk_rfis_kira_id`, `idx_rfis_tenant`, `idx_rfis_blocking` |
| `webhooks_log` | `WebhookEventEntity` | Evento crudo (`payload` JSON), tipo, `resource_id`, `tenant_id` (para el centro de eventos), `normalized_status`, `processed`, `processing_error`, `retry_count` | `uk_webhooks_event_id`, `idx_webhooks_event` |
| `audit_logs` | `AuditLogEntity` | Bitácora (`changes` JSON, con `requestId` e `idempotencyKey`) | `idx_audit_tenant (tenant_id, created_at)` |
| `notifications` | `NotificationEntity` | Avisos de negocio por empresa (tipo, severidad, título, mensaje, recurso) | `idx_notifications_tenant (tenant_id, created_at)` |
| `tenants` (borrador) | `TenantEntity` | `onboarding_draft` JSON y `onboarding_draft_updated_at`: el asistente del portal | — |

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
| `WebhookEventJpaRepository` | `existsByEventId`, `findByEventId`, `findByTenantIdOrderByCreatedAtDesc` (centro de eventos) |
| `AuditLogJpaRepository` | `findByTenantIdOrderByCreatedAtDesc` |
| `NotificationJpaRepository` | `findByTenantIdOrderByCreatedAtDesc`, conteo de no leídos desde `notifications_seen_at` |

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
`rfis.blocking_type`, `blocking_resource_id`, `resolution_reason`.

Añadidas después (15-sep): `ubos.email` y sus datos de identidad (`birth_date`, `nationality`,
`occupation`, `gender`, `phone_number`, `document_country`, `address_*`) más `synced_to_kira`;
`users.mfa_enabled`, `notifications_seen_at` y `tenant_id` nulo; `webhooks_log.tenant_id`;
`payouts.first_approver_user_id`; `recipients.created_by_user_id`; la tabla `notifications`; y
`tenants.onboarding_draft`. El SQL exacto para cert y prod está en `ESTADO.md` §7.

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
`@JdbcTypeCode(SqlTypes.VARCHAR)`. El anexo C es ese volcado con una columna por línea.

Los anexos A y B se regeneran con `python3 scripts/generar-anexos-documentacion.py a` (referencia clase
por clase) y `… b` (catálogo de pruebas), desde la raíz del repositorio.

### 12.6 Reconciliación

Siete workers `@Component` con `@Scheduled(fixedDelayString = …)`, todos condicionados a
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
| `TenantReconciliationWorker` | 30 min | Empresas registradas que aún no pueden operar → `SubmitOnboardingService.reconcile` (recupera un `user.status_changed` perdido) |
| `VirtualAccountReconciliationWorker` | 1 h | Cuentas abiertas → `OpenVirtualAccountService.reconcile`: `failed`, `deactivated` y `frozen` **no tienen webhook propio** |

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
`{idempotencyKey?, result?, detail?, requestId?}` en JSON y la IP del cliente (primer valor de
`X-Forwarded-For` o `remoteAddr`; nula fuera de una petición HTTP). El `requestId` sale del MDC
(§14.3) y es lo que enlaza una fila de la bitácora con las líneas de log de esa misma petición.

**Acciones auditadas (30):**

| Acción | Recurso | Servicio |
|---|---|---|
| `tenant.onboarding_registered` (OK / ERROR) | tenant | `SubmitOnboardingService.register` |
| `tenant.onboarding_profile_updated` | tenant | `SubmitOnboardingService.completeProfile` |
| `tenant.kyb_documents_attached` (OK / ERROR) | tenant | `SubmitOnboardingService.attachDocuments` |
| `tenant.terms_accepted` | tenant | `SubmitOnboardingService.acceptTerms` |
| `tenant.ubo_saved` | ubo | `SyncUbosService.save` |
| `tenant.ubo_deleted` | ubo | `SyncUbosService.delete` |
| `tenant.ubo_documents_attached` (OK / ERROR) | ubo | `SyncUbosService.attachDocuments` |
| `tenant.biometric_consent_recorded` (`selfie` / `liveness`) | ubo o tenant | `SyncUbosService` |
| `tenant.ubos_synced` | tenant | `SyncUbosService.syncToKira` |
| `tenant.liveness_links_requested` | tenant | `SyncUbosService.requestLivenessLinks` |
| `virtual_account.opened` (OK / ERROR / reutilizada) | virtual_account | `OpenVirtualAccountService.open` |
| `virtual_account.deposit_simulated` | virtual_account | `OpenVirtualAccountService.simulateDeposit` |
| `recipient.registered` | recipient | `RegisterRecipientService.register` |
| `recipient.archived` | recipient | `RegisterRecipientService.archive` |
| `quotation.created` | quotation | `CreateQuoteService.create` |
| `payout.created` | payout | `ExecutePayoutService.create` |
| `payout.first_approval` | payout | `ExecutePayoutService.approveAndSubmit` (1.ª de dos firmas) |
| `payout.approved` | payout | `ExecutePayoutService.approveAndSubmit` |
| `payout.requoted` | payout | `ExecutePayoutService.requote` |
| `payout.submitted` (OK / ERROR) | payout | `ExecutePayoutService.submitToKira` |
| `payout.rejected` | payout | `ExecutePayoutService.reject` |
| `compliance.rfis_synced` | tenant | `AnswerRfiService.sync` |
| `compliance.rfi_answered` | rfi | `AnswerRfiService.answer` |
| `compliance.rfi_documents_uploaded` | rfi | `AnswerRfiService.uploadDocuments` |
| `compliance.rfi_document_removed` | rfi | `AnswerRfiService.removeDocument` |
| `compliance.rfi_document_link_issued` | rfi | `AnswerRfiService.documentLink` (quién pidió la descarga; la URL no) |
| `compliance.rfi_ubo_link_minted` | rfi | `AnswerRfiService.mintUboLink` |
| `auth.mfa_setup_started`, `auth.mfa_enabled`, `auth.mfa_disabled`, `auth.mfa_verified`, `auth.mfa_failed` | user | `MfaService` |
| `platform.tenant_viewed` | tenant | `PlatformConsoleService.tenant` (consulta de una ficha 360) |

No se auditan: lecturas ordinarias, `refresh`, sincronización de depósitos, vista previa de pagos ni
proyecciones de webhooks.

### 14.2 Logging

SLF4J con `Logger` por clase; cada línea lleva entre corchetes el `requestId` de su petición
(`logging.pattern.level`). Niveles relevantes: INFO en reintentos por 401, reutilización de cuentas y
eventos sin correspondencia local; WARN en cotizaciones que no cuadran o con tasa de contingencia,
activación demorada, entradas no atribuibles y webhooks con firma inválida; ERROR en fallos de envío a
Kira, eventos no proyectados y errores no controlados. Las credenciales y las URLs de descarga nunca se
registran.

### 14.3 Correlación y métricas

- **`RequestIdFilter`** (el primero de la cadena): toma `X-Request-Id` si tiene forma de id
  (`[A-Za-z0-9-]{8,64}`) o genera un UUID, lo pone en el MDC, lo devuelve en la respuesta y lo limpia al
  terminar. Un valor con saltos de línea se sustituye: no se escribe texto ajeno en los logs.
- **`IntegrationMetrics`** (Micrometer, vía Actuator; **sin exportador**, la elección de Prometheus u OTLP
  es una decisión de despliegue pendiente):

| Métrica | Etiquetas | Para qué |
|---|---|---|
| `kira.api.requests` (timer) | `method`, `route` (los segmentos variables van como `{id}`), `outcome` (código HTTP o `io_error`) | Latencia y errores del proveedor |
| `kira.webhooks.received` (contador) | `result`: `received`, `duplicate`, `invalid_signature`, `invalid_json`, `not_configured` | Firmas rotas, secreto sin configurar, reintentos |
| `kira.webhooks.projection.failures` (contador) | `event` | Eventos guardados que no se proyectaron |

Las etiquetas nunca llevan ids ni datos de una empresa. `GET /actuator/metrics` es sólo para
`PLATFORM_OPERATOR`.

---

## 15. Pruebas

**424 pruebas en 53 clases, todas en verde** (`./mvnw clean test`, 16-sep-2026: 0 fallos, 0 errores,
0 omitidas). Las 24 últimas son de la gestión de operadores (G-13), del `401` sin cabecera (F7) y de
la reutilización de aperturas sin confirmar (G-23). Nombres completos en el Anexo B.

| Tipo | Cómo | Clases |
|---|---|---|
| Dominio | JUnit puro, sin mocks | `RfiTest`, `TenantOnboardingTest`, `UboRosterTest`, `VirtualAccountActivationTest`, `VirtualAccountReadinessTest`, `PayoutTest`, `PayoutStatusTest`, `QuotationTest`, `QuotationRailTest`, `RecipientAccountTest`, `FileSignatureTest` |
| Aplicación | Mockito para `KiraApiClient`, repositorios y `AuditTrail`; captura de cuerpos enviados | `SubmitOnboardingServiceTest`, `SyncUbosServiceTest`, `KiraUserStateTest`, `OpenVirtualAccountServiceTest`, `RecordDepositServiceTest`, `RegisterRecipientServiceTest`, `CreateQuoteServiceTest`, `ExecutePayoutServiceTest`, `AnswerRfiServiceTest`, `ReferenceCatalogServiceTest`, `KiraWebhookEnvelopeTest`, `UserEventProjectionTest` |
| Infraestructura | `MockRestServiceServer` sobre `RestClient`; unitarias | `KiraApiClientVersionTest`, `KiraCredentialManagerTest`, `KiraAmountsTest`, `KiraWebhookVerifierTest`, `KiraPropertiesTest`, `RequiredSecretsValidatorTest`, `TotpTest` (vectores de la RFC 6238) |
| Seguridad y consola | Mockito y MockMvc | `MfaServiceTest`, `PlatformConsoleServiceTest`, `ProviderQueryAuthorizationTest` (G-09), `ObservabilityTest` |
| Reconciliación | Mockito sobre repositorios y `KiraApiClient` | `PayoutReconciliationWorkerTest`, `QuotationReconciliationWorkerTest`, `LivenessReconciliationWorkerTest`, `RfiReconciliationWorkerTest`, `WebhookReprojectionWorkerTest`, `TenantAndAccountReconciliationWorkerTest` |
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
| `docs/bruno/AuTransactional/` | Colección de Bruno: 104 peticiones en 13 carpetas, entorno `local` |
| `docs/GUIA-BRUNO.md` | Guía de pruebas paso a paso |
| `docs/API-GUIA.md` | Contrato HTTP y trampas de Kira |
| `docs/kira-cuerpos-peticiones.json` | Cuerpos exactos enviados a Kira |
| `docs/ARQUITECTURA.md`, `docs/ESTADO.md` | Visión resumida y estado/pendientes |
| `docs/REVISION-REQUISITOS-VS-CODIGO.md` | Requisitos de la arquitectura y de Kira frente a lo construido, con el plan hasta el 15-oct |
| `docs/cronograma/` | Cronograma y backlog de ClickUp |
| `/actuator/metrics` | Métricas de la integración (sólo `PLATFORM_OPERATOR`) |
| Git | `github.com/CaroLopez09/Au_transaction`; ramas `develop` (trabajo), `main`, `certificacion` y `produccion`. Se sube por SSH |
| `~/Documentos/au-transactional-web` | Portal Angular que consume este BFF (§18). `npm start` sirve en `:4200` y envía `/api` a `:8080` por `proxy.conf.json`; `npm test` (unitarias) y `npm run e2e` (Playwright, necesita el BFF arriba) |

---

## 17. Deuda técnica, defectos conocidos y riesgos

Hallazgos verificados en el código el 11-sep-2026, revisados el 16-sep.

### 17.1 Defectos

| # | Hallazgo | Dónde | Efecto |
|---|---|---|---|
| ~~F1~~ | **Corregido el 11-sep (noche).** La clave se consolida con `IdempotencyKeyStore` en una transacción propia (`REQUIRES_NEW`) antes de llamar a Kira, así que el rollback del caso de uso ya no la borra | `SubmitOnboardingService.register`, `OpenVirtualAccountService.open` | Verificado con `IdempotencyKeyPersistenceTest` (integración con H2) |
| F2 | **Se pierde la traza de un envío fallido.** `approveAndSubmit` marca el pago como `FAILED` y audita `ERROR`, pero relanza dentro de la transacción: todo se revierte | `ExecutePayoutService.approveAndSubmit` | El pago vuelve a `PENDING_APPROVAL` (esperado) pero **no queda auditoría del intento fallido** |
| ~~F3~~ | **Corregido el 15-sep.** El evento se guarda en la petición (`record`, `@Transactional`) y la proyección corre aparte llamando a `self.reproject(...)` por el proxy de Spring | `ProcessWebhookUseCase` | La proyección ya abre su transacción; un fallo deja `processing_error` y lo retoma el worker |
| ~~F4~~ | **Corregido el 15-sep.** `POST /api/payouts` y `POST /api/recipients` aceptan `Idempotency-Key` del portal (UUID validado) y repetirla devuelve lo ya creado | `ExecutePayoutService`, `RegisterRecipientService` | Un doble clic o un reintento de red no crea dos operaciones |
| F5 | Logging DEBUG de `cert` apunta a `com.example.autransactional.infrastructure.kiraclient`, que no existe | `application-cert.yaml` | No hay DEBUG del cliente de Kira en cert (**sigue abierto**) |
| F6 | Javadoc obsoleto: sigue mencionando endpoints de verificación biométrica sin seguridad (se eliminaron el 11-sep) | `OpenApiConfig` | Confusión al leer el código (**sigue abierto**) |
| ~~F7~~ | **Corregido el 16-sep.** `UnauthorizedEntryPoint` responde `401` con `{"code":"unauthorized"}` cuando falta la cabecera `Authorization`; el `403` queda solo para el rol sin permiso | `SecurityConfig.exceptionHandling`, `UnauthorizedEntryPoint` | Verificado en `OperatorControllerTest` (401 con cuerpo, y `403 forbidden` con código para un rol sin permiso) |
| F8 | Un pago sin cotización envía un bruto calculado con comisiones **estimadas** (15 + 15) | `Payout.grossAmountToSend` | Si la tarifa real difiere, el destinatario recibe un importe distinto (decisión abierta: exigir cotización) |

### 17.2 Deuda técnica

| # | Deuda | Detalle |
|---|---|---|
| D1 | Casos de uso dependen de `infrastructure` | `KiraApiClient` (devuelve `JsonNode`), `AuditTrail`, `AuthenticatedOperator` en `application`. Lo limpio sería un puerto `KiraGateway` |
| D2 | `TenantContext` sin lectores | El filtro lo fija y limpia, pero ningún servicio lo usa (usan `operator.tenantId()`) |
| D3 | ~~Falta el worker de eventos no proyectados~~ | Resuelto: `WebhookReprojectionWorker` (11-sep) |
| D4 | ~~MFA sin implementar~~ | Resuelto: TOTP con secreto cifrado (15-sep) |
| ~~D5~~ | ~~Sin gestión de operadores~~ | Resuelto el 16-sep: `ManageOperatorsService` y `GET/POST/DELETE /api/operators` (G-13). Un `ADMIN` no puede crear `ADMIN` ni `PLATFORM_OPERATOR` ni desactivarse a sí mismo |
| D6 | `idx_payouts_idempotency` redundante | Duplica el índice de `uk_payouts_idempotency` |
| D7 | Límites aplicados en memoria | Los adaptadores leen todas las filas de la empresa y cortan con `limit` |
| D8 | ~~`resolution_reason` de RFI no se guarda~~ | Resuelto el 15-sep (`rfis.resolution_reason`, incluido `withdrawn`) |
| D9 | ~~Versiones de API mezcladas~~ | Resuelto el 15-sep: **`2026-06-01` en todas las peticiones** y `KiraProperties` rechaza otra |
| D10 | Tablas huérfanas en la base de dev | `audit_log`, `operator_user`, `payout`, `tenant`, `webhook_event` (restos del esquema anterior) |
| D11 | Métricas sin exportador | Hay `kira.*` en Micrometer, pero falta decidir Prometheus u OTLP y añadir la dependencia |
| D12 | Umbral de doble firma sin confirmar | `bff.payouts.approval.dual-approval-threshold` vale 10.000 por defecto, un valor provisional |
| D13 | Sin antimalware en los archivos | Se comprueba el tipo real (`FileSignature`), no el contenido; decisión pendiente (infraestructura o deuda declarada) |
| D14 | Anexos de este documento | Se regeneran con un script; no hay comprobación automática de que sigan al día |

> Los huecos de contrato que el **portal** tiene abiertos contra este BFF están en **§18.4**. Tras los
> cierres del 16-sep (G-13 y G-23), el que sigue solapándose con esta sección es **G-14 con D7**
> (límites en memoria, sin paginación de servidor), además de **G-29** con el riesgo de `ddl-auto: validate`.

### 17.3 Riesgos

| Riesgo | Mitigación actual |
|---|---|
| **Contraseña de MySQL en el historial de un repositorio público** | Parcial: `application-dev.yaml` ya la toma de `${DB_PASSWORD}`, pero el valor antiguo sigue en los commits ya hechos. Falta **rotarla** y valorar hacer el repo privado |
| **Credenciales del sandbox de Kira compartidas por chat** | Pedirlas nuevas antes de producción (`ESTADO.md` §4.8) |
| Cuentas y pagos sin recorrer contra Kira | Vinculación, beneficiarios y RFIs sí se probaron; abrir cuenta y pagar necesitan una empresa `VERIFIED` en el sandbox |
| Webhook perdido | Kira reintenta 4 veces; el evento se guarda antes del `2xx`, se puede reenviar desde su panel y hay siete workers de reconciliación (§12.6) |
| Eventos `rfi.*` requieren suscripción explícita en Kira | `POST /api/rfis/sync` mientras tanto; hay que pedirla |
| `ddl-auto: validate` en cert/prod con esquema aplicado a mano | Regenerar el DDL (§12.5) y aplicar el SQL de `ESTADO.md` §7 antes de desplegar |
| Banco y producto acoplados | `jp_morgan` ↔ `usa-virtual-accounts` es una inferencia por el nombre del producto `-act`; Kira no lo documenta |




---

## 18. El portal que consume el BFF (`au-transactional-web`)

Esta sección documenta el **cliente** del BFF, verificada contra el repositorio
`~/Documentos/au-transactional-web` (`ba4d4da`, 15-sep-2026). El detalle vive en los documentos del
propio front: [`docs/frontend-architecture.md`](../../au-transactional-web/docs/frontend-architecture.md)
(arquitectura), [`docs/frontend-backend-contract.md`](../../au-transactional-web/docs/frontend-backend-contract.md)
(trazabilidad endpoint a endpoint, RBAC y huecos) y
[`docs/frontend-qa.md`](../../au-transactional-web/docs/frontend-qa.md) (QA). Aquí se recoge lo que un
desarrollador del BFF necesita saber para no romperlo.

### 18.1 Stack y tamaño

| Dato | Valor |
|---|---|
| Framework | Angular 22.1 (standalone, `loadComponent`, signals), TypeScript 6.0 |
| Build / pruebas | `@angular/build` 22.1, Vitest 4.1 (unitarias), Playwright 1.63 (E2E), ESLint 10 + Prettier |
| Tamaño | **127 ficheros `.ts` de producción (13.027 líneas)** en 9 dominios |
| Pruebas | **155 unitarias en 9 ficheros, todas en verde** (`npm test`, ejecutado el 16-sep-2026) · **34 especificaciones E2E** en 7 ficheros (2 con salto condicional según el estado real del entorno) |
| Desarrollo | `ng serve` con `proxy.conf.json`: todo `/api` va a `http://localhost:8080` (este BFF) |

### 18.2 Cómo está organizado

`src/app` se divide en `core` (sesión, HTTP, configuración, permisos, layout), `shared` (UI, catálogos,
utilidades) y `domains`. Cada dominio repite la misma separación que el BFF —`domain/`, `application/`
(facade), `infrastructure/` (repositorio HTTP), `presentation/` (páginas y componentes)— así que **un
endpoint nuevo del BFF se consume siempre desde un `*HttpRepository`**, nunca desde un componente.

| Dominio del front | Ficheros | Áreas del BFF que consume |
|---|---|---|
| `onboarding` | 25 | `/api/onboarding/**`, `/api/ubos/**` (KYB, documentos, beneficiarios finales) |
| `payouts` | 12 | `/api/payouts/**`, `/api/quotes/**` (cotización, maker-checker, doble firma, recotización) |
| `accounts` | 9 | `/api/accounts/**` (cuentas virtuales) |
| `deposits` | 8 | `/api/deposits/**` |
| `rfis` | 8 | `/api/rfis/**` (respuestas, documentos, `ubo-link`) |
| `recipients` | 7 | `/api/recipients/**` |
| `activity` | 6 | `/api/notifications`, `/api/events`, `/api/audit` |
| `platform` | 5 | `/api/platform/**` (consola de `PLATFORM_OPERATOR`) |
| `home` | 2 | Agregación de las vistas anteriores |

Rutas de navegación (`app.routes.ts`, todas en español): `ingresar`, `vinculacion`, `cuentas`,
`cuentas/:id`, `depositos`, `destinatarios`, `destinatarios/nuevo`, `pagos`, `pagos/nuevo`,
`pagos/historial`, `pagos/:id`, `solicitudes`, `solicitudes/:id`, `avisos`, `eventos`, `auditoria`,
`seguridad` y `operaciones`, `operaciones/:id`.

### 18.3 Contrato de sesión y errores (lo que el BFF no puede cambiar sin avisar)

- **JWT en cada petición.** `authInterceptor` añade `Authorization: Bearer …` a todo lo que va a la URL
  base de la API salvo `/auth/login`.
- **Qué cierra la sesión.** Solo `401 unauthorized` y un **`403` con cuerpo vacío** (petición sin
  cabecera, que es el defecto **F7** de §17.1). Un `403 forbidden` **no** cierra sesión: se trata como
  rol sin permiso y lo resuelve la pantalla. Si el BFF cambiara ese par de casos, el portal expulsaría
  al operador o lo dejaría en una pantalla muerta.
- **Códigos de error consumidos** (`ErrorMappingService`, uno a uno los de §13): `unauthorized`,
  `forbidden`, `validation_error`, `rfi_answer_rejected`, `not_found`, `file_too_large`,
  `business_rule_violation`, `kira_not_configured` e `internal_error`. **Son contrato**: el front
  enseña un mensaje distinto por código, no por texto.
- **`X-Request-Id`.** El front lee la cabecera de la respuesta de error y la muestra como «código para
  soporte», que es el mismo identificador que el BFF pone en sus logs y en la auditoría (§14.3).
- **Vistas, no formas de Kira.** El portal consume las `*View` del BFF; las dos excepciones crudas a
  propósito son `RfiView.items` y `PayoutPreviewView.fees`.
- **Guards por rol y capacidad.** `authenticatedGuard`, `guestGuard` y `audienceGuard` separan la
  consola de `PLATFORM_OPERATOR` (`operaciones`) del resto de áreas de empresa, reflejando el RBAC de §6.4.
- **Despliegue.** No hay CORS en el BFF: en desarrollo funciona por proxy y en producción **el portal y
  el BFF deben servirse en el mismo origen** o detrás de un reverse proxy (hueco G-06 de §18.4).

### 18.4 Huecos abiertos del contrato front ↔ BFF

Los cierres del 14 y 15-sep (consola, `tenantName` en `/me`, `Idempotency-Key`, motivo de rechazo KYB,
avisos, auditoría, `DELETE /api/ubos/{id}`, edición de UBO, `ubo-link`, enmascarado de `senderAccount`,
catálogos de industrias y de tipos de documento, permisos de refresco G-09) ya están en §9, §10 y §17.
Quedan **abiertos**, y son trabajo del BFF:

| ID | Hueco | Cambio esperado en el BFF | Prioridad |
|---|---|---|---|
| G-16 | `pendingFields` llega con nombres técnicos de Kira, sin tipo ni opciones; el front mantiene un diccionario de etiquetas | Exponer el esquema (tipo, opciones, obligatoriedad) | Alta |
| ~~G-23~~ | **Cerrado el 16-sep:** `OpenVirtualAccountService.open` retoma la apertura sin confirmar de la misma empresa, moneda y modalidad, y reintenta **con la misma clave de idempotencia** | — | — |
| G-29 | Las columnas del borrador KYB solo se crean solas en `dev` (`ddl-auto: update`); en `cert`/`prod` (`validate`) hay que aplicar SQL a mano | Migraciones versionadas (Flyway) — mismo riesgo que §17.3 | Alta |
| G-03 | ~~Las vistas solo traen `makerUserId`/`approverUserId`~~ Cerrado (16-sep): `PayoutView` lleva `makerName`, `approverName`, `firstApproverName` y `recipientName` (G-26) | — | — |
| G-04 | JWT de 8 h sin revocación: el logout solo descarta el token en el navegador | Lista de revocación, o tokens cortos + refresh en cookie `HttpOnly` | Media |
| G-06 | Sin CORS: obliga a mismo origen o reverse proxy en despliegue | Documentar el reverse proxy o CORS explícito por entorno | Media |
| ~~G-13~~ | **Cerrado el 16-sep:** `GET /api/operators` (ADMIN y COMPLIANCE_INTERNAL), `POST` y `DELETE` (solo ADMIN, sin autodesactivación ni escalada a ADMIN/PLATFORM_OPERATOR) | — | — |
| G-14 | Las listas locales solo aceptan `limit` y filtran en cliente (coincide con **D7**) | `page`, `status`, `from/to` en las listas locales | Media |
| G-26 | `GET /api/recipients` solo devuelve activos: un pago antiguo se queda sin nombre de destinatario | Incluir `recipientName` en `PayoutView` | Baja |
| G-28 | `transaction_countries` sin formato documentado (ISO-2 vs ISO-3, el catálogo del BFF es ISO-3) | Documentarlo y validarlo en el BFF | Baja |
| G-05 | Sin refresh token: al expirar se vuelve a login | Refresh token en cookie `HttpOnly` | Baja |
| G-10 | Mensajes del BFF sin tildes, se muestran tal cual | Mensajes UTF-8 o códigos estables por regla | Baja |
| G-18 | `kira.sandbox` no se expone: «Simular depósito» se decide por configuración del front | `GET /api/capabilities` con las capacidades del entorno | Baja |
| G-22 | `GET /api/reference/countries` responde `503` sin credenciales de Kira | Catálogo ISO local de respaldo en el BFF | Baja |

G-19 (instrucciones cripto) quedó **fuera de alcance** del piloto y G-30 (documentos en el borrador) es
una decisión de diseño asumida: el BFF no almacena documentos de identidad.

---

## Anexo A. Referencia clase por clase

Generado automáticamente desde el código fuente el 16-sep-2026: **todos** los tipos de `src/main/java` (clases, records, enums e interfaces, incluidos los anidados), con su javadoc, anotaciones, campos constantes o documentados y todos los métodos no privados. Los métodos privados se omiten; su lógica se explica en las secciones 8 a 12.

### A.1 Arranque

<sub>`AuTransactionalApplication.java` · 16 líneas</sub>

#### `AuTransactionalApplication` · clase · `@SpringBootApplication` `@EnableConfigurationProperties(PayoutApprovalPolicy.class)`

| Método | Descripción |
|---|---|
| `public static void main(String[] args)` |  |

### A.2 Dominio — shared

<sub>`domain/shared/DomainException.java` · 8 líneas</sub>

#### `DomainException` · clase

Violacion de una invariante de negocio. Se traduce a HTTP 409/422 en la capa REST.

| Método | Descripción |
|---|---|
| `public DomainException(String message)` |  |

<sub>`domain/shared/FileSignature.java` · 60 líneas</sub>

#### `FileSignature` · clase

Tipo real de un archivo por sus primeros bytes (arquitectura §7: validar el MIME real, no el
que declara el navegador). Solo reconoce los formatos que Kira acepta.

| Método | Descripción |
|---|---|
| `public static String detect(byte[] content)` | MIME detectado, o null si no es ninguno de los formatos admitidos. |
| `public static boolean matches(String declaredMime, byte[] content)` | El contenido es de verdad del tipo declarado. |

<sub>`domain/shared/IdempotencyKey.java` · 41 líneas</sub>

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
| `public static IdempotencyKey fromClient(String value)` | Clave que manda el portal para que un doble clic o un reintento de red no cree dos operaciones. Kira solo acepta UUID: se valida aqui para no descubrirlo en su 400. |
| `public String toString()` |  |

<sub>`domain/shared/Money.java` · 68 líneas</sub>

#### `Money` · record

Importe con moneda. Existe para que un monto no viaje nunca separado de su divisa:
el esquema guarda DECIMAL(18,4) y una columna de moneda por fila, y sumar dos filas
de monedas distintas es el error que este tipo hace imposible.

| Componente |
|---|
| `BigDecimal amount` |
| `String currency` |

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

<sub>`domain/shared/PostalAddress.java` · 33 líneas</sub>

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

<sub>`domain/shared/Rail.java` · 39 líneas</sub>

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

<sub>`domain/shared/StatusNormalizer.java` · 23 líneas</sub>

#### `StatusNormalizer` · clase

Kira devuelve los estados con distinto casing segun la superficie:
el 201 de payout responde "created" y el GET responde "CREATED"; los eventos planos
usan minusculas y payout.status_changed mayusculas. La documentacion es explicita:
comparar SIEMPRE sin distinguir mayusculas y tolerar valores desconocidos.

| Método | Descripción |
|---|---|
| `public static String normalize(String raw)` |  |
| `public static boolean matches(String raw, String expected)` |  |

<sub>`domain/shared/TenantId.java` · 34 líneas</sub>

#### `TenantId` · record

Identificador de la organizacion propietaria del dato. Toda consulta debe filtrar por el.

| Componente |
|---|
| `String value` |

| Método | Descripción |
|---|---|
| `public boolean isPlatform()` |  |
| `public static TenantId of(String value)` |  |
| `public String toString()` |  |

### A.3 Dominio — tenant

<sub>`domain/tenant/EligibleProduct.java` · 28 líneas</sub>

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

| Método | Descripción |
|---|---|
| `public boolean requiresEnhancedDueDiligence()` |  |

<sub>`domain/tenant/LivenessStatus.java` · 32 líneas</sub>

#### `LivenessStatus` · enum

Estado del enlace biometrico alojado que Kira emite para cada UBO.

Valores: `PENDING`, `COMPLETED`, `EXPIRED`, `FAILED`.

| Método | Descripción |
|---|---|
| `public static LivenessStatus fromWire(String raw)` |  |
| `public boolean isFinal()` |  |

<sub>`domain/tenant/MissingFields.java` · 56 líneas</sub>

#### `MissingFields` · record

Campos que Kira todavia exige para verificar a la empresa, agrupados por producto.

Es la fuente de verdad del formulario de onboarding: la pantalla NO debe tener campos
estaticos, sino renderizar lo que llegue aqui. Las claves son codigos de producto
(p. ej. usa-virtual-accounts) mas "general", que es la union de todos ellos.

| Componente |
|---|
| `Map<String, List<String>> byProduct` |

| Método | Descripción |
|---|---|
| `public static MissingFields empty()` |  |
| `public List<String> forProduct(String productCode)` | Lo que falta para un producto concreto. "general" NO es una base comun: es la union de los faltantes de todos los productos (docs.kirafin.ai: "a general key holding every token once"; confirmado en sandbox el 15-sep, donde traia requisitos de otros bancos). Sumarlo pedia datos de productos que no se usan y dejaba el producto sin completar para siempre. Solo se usa si Kira no lista el producto por separado. |
| `public boolean isCompleteFor(String productCode)` |  |
| `public boolean isEmpty()` |  |
| `public Set<String> products()` |  |

<sub>`domain/tenant/OperatorUser.java` · 33 líneas</sub>

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
| `boolean mfaEnabled` |

| Método | Descripción |
|---|---|
| `public void assertCanLogin()` |  |
| `public void assertBelongsTo(TenantId expected)` |  |
| `public String fullName()` |  |
| `public boolean isActive()` |  |

<sub>`domain/tenant/OperatorUserRepository.java` · 27 líneas</sub>

#### `OperatorUserRepository` · interfaz

<sub>`domain/tenant/Role.java` · 80 líneas</sub>

#### `Role` · enum

RBAC B2B. El nombre tecnico (dbName) es el que vive en la tabla `roles` y el que
lee el negocio; la constante es la que usan @PreAuthorize y el JWT.

La segregacion de funciones exige separar quien prepara un pago (TREASURY_MAKER)
de quien lo autoriza (TREASURY_APPROVER): la API de Kira no ofrece maker-checker
para integradores, asi que el control es del BFF.

Valores: `ADMIN`, `TREASURY_MAKER`, `TREASURY_APPROVER`, `COMPLIANCE_INTERNAL`, `READ_ONLY`, `PLATFORM_OPERATOR`.

| Método | Descripción |
|---|---|
| `public String dbName()` |  |
| `public RoleScope scope()` |  |
| `public String description()` |  |
| `public static Role fromDbName(String raw)` |  |
| `public boolean canCreatePayout()` |  |
| `public boolean canApprovePayout()` |  |
| `public boolean isPlatform()` |  |
| `public boolean canManageCompliance()` | Ficha 360, UBOs, liveness y RFIs. |

<sub>`domain/tenant/RoleScope.java` · 7 líneas</sub>

#### `RoleScope` · enum

Alcance del rol: propio de la empresa cliente o del soporte de la plataforma.

Valores: `TENANT`, `SYSTEM`.

<sub>`domain/tenant/Tenant.java` · 248 líneas</sub>

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
| `public static Tenant rehydrate(TenantId id, String name, String taxId, String jurisdiction, String kiraUserId, TenantStatus status, List<EligibleProduct> eligibleProducts, MissingFields missingFields, boolean verificationTriggered, String onboardingPayload, String onboardingIdempotencyKey, String rejectionReason, Instant createdAt, Instant updatedAt)` |  |
| `public void saveOnboardingDraft(String draftJson, Instant now)` | Reemplaza el borrador completo. El portal es dueno del objeto entero. |
| `public void restoreOnboardingDraft(String draftJson, Instant updatedAt)` | Solo para rehidratar desde la base: no modifica la fecha de actualizacion del agregado. |
| `public IdempotencyKey reserveOnboardingKey()` | Reserva la clave de idempotencia del alta en Kira. Una clave por intencion de negocio, no por intento HTTP: se persiste ANTES de la primera llamada y todos los reintentos reutilizan la misma, o un timeout seguido de reintento crearia dos empresas en Kira. |
| `public void linkKiraUser(String kiraUserId)` | Se invoca tras el 201 de POST /v1/users. No cambia el estado: el 201 devuelve CREATED y la verificacion NO se dispara sola. Solo un PUT completo (con source_of_funds) la dispara. |
| `public boolean isRegisteredInKira()` |  |
| `public void assertRegisteredInKira()` |  |
| `public void recordOnboardingPayload(String payloadJson)` | Guarda el objeto completo enviado a Kira. Es obligatorio conservarlo: el GET no devuelve los campos del cuestionario y un PUT parcial borra en silencio lo que no viaje en el, asi que el siguiente PUT solo puede construirse a partir de lo que se envio la vez anterior. |
| `public void applyRemoteState(TenantStatus incoming, MissingFields missingFields, List<EligibleProduct> eligibleProducts, Boolean verificationTriggered)` | Asienta lo que devolvieron POST/PUT/GET de /v1/users. |
| `public Optional<EligibleProduct> product(String productCode)` |  |
| `public boolean isReadyFor(String productCode)` | Abrir cuenta virtual exige KYB VERIFIED y el producto concreto elegible. |
| `public void assertVerificationInProgress()` | POST /v1/users/{id}/liveness-link devuelve 422 "No verification is in progress" si el KYB aun no se disparo. Se comprueba aqui para no gastar la llamada. |
| `public void rejectVerification(String reason)` | Guarda el motivo del rechazo del KYB. Solo llega por el webhook user.verification.failed y solo una vez: GET /v1/users/{id} nunca lo expone. Si no se captura aqui, el operador ve un REJECTED sin explicacion y no hay forma de recuperarla. |
| `public boolean isVerified()` |  |
| `public void assertActive()` |  |
| `public void assertCanOperateTreasury()` | Ninguna operacion de tesoreria sale hacia Kira si el KYB no esta aprobado. |

<sub>`domain/tenant/TenantRepository.java` · 18 líneas</sub>

#### `TenantRepository` · interfaz

Puerto de salida. La implementacion vive en infrastructure/persistence.

<sub>`domain/tenant/TenantStatus.java` · 39 líneas</sub>

#### `TenantStatus` · enum

Estado del KYB de la empresa cliente en Kira.
Se recibe por evento user.* y por GET /v1/users/{id}; se compara siempre sin
distinguir mayusculas y un valor desconocido no rompe la maquina.

Valores: `CREATED`, `VERIFYING`, `REVIEW`, `VERIFIED`, `REJECTED`.

| Método | Descripción |
|---|---|
| `public static TenantStatus fromWire(String raw)` |  |
| `public boolean canOperate()` |  |

<sub>`domain/tenant/Ubo.java` · 295 líneas</sub>

#### `Ubo` · clase · `@Getter`

Beneficiario final, director o firmante de la empresa cliente.

Existe como tabla propia por dos motivos que la API impone: hay que reconstruir el array
'associated_persons' COMPLETO en cada PUT (un PUT parcial borra campos en silencio), y
el resultado real del liveness solo llega una vez, por webhook.

El enlace de liveness que emite Kira vive 7 dias: por eso la fecha de vencimiento se
guarda aparte del estado. Un enlace vencido no se reintenta, se vuelve a pedir.

| Método | Descripción |
|---|---|
| `public Ubo(String id, TenantId tenantId, String firstName, String lastName, BigDecimal ownershipPercentage, String roleInCompany)` |  |
| `public static Ubo rehydrate(String id, TenantId tenantId, String personReferenceId, String firstName, String lastName, String email, String documentType, String documentNumber, BigDecimal ownershipPercentage, String roleInCompany, boolean hasOwnership, boolean hasControl, boolean signer, boolean politicallyExposed, String countryOfBirth, LocalDate birthDate, String nationality, String occupation, String gender, String phoneNumber, String documentCountry, PostalAddress residentialAddress, boolean syncedToKira, LivenessStatus livenessStatus, String livenessLink, Instant livenessExpiresAt, Instant createdAt, Instant updatedAt)` |  |
| `public void describeEmail(String email)` | Kira empareja las personas de associated_persons[] por `email`: sin el, cada sincronizacion le crea una persona nueva en vez de actualizar la que ya tiene. Es opcional en el alta para no romper los beneficiarios ya registrados, pero hace falta para colgarle documentos a la persona. |
| `public void assertIdentifiableInKira()` | Sin email no hay forma de decirle a Kira a que persona pertenece el documento. |
| `public void describeDocument(String documentType, String documentNumber)` |  |
| `public void rename(String firstName, String lastName, String roleInCompany)` | Corrige nombre, apellido y cargo. Antes la edicion los exigia pero no los aplicaba (G-21). |
| `public void describeIdentity(LocalDate birthDate, String nationality, String occupation, String gender, String phoneNumber, String documentCountry, PostalAddress residentialAddress)` | Datos de identidad de la persona. Todos opcionales aqui: es Kira quien decide, por banco, cuales faltan (missing_fields). Un valor vacio borra el guardado. |
| `public boolean isKnownToKira()` | Solo lo que aun no conoce Kira se puede borrar: alli la persona no desaparece al quitarla aqui. |
| `public void markSyncedToKira()` |  |
| `public void describeRole(boolean hasOwnership, BigDecimal ownershipPercentage, boolean hasControl, boolean signer, boolean politicallyExposed, String countryOfBirth)` | Define el papel de la persona en el KYB. hasOwnership es un booleano explicito y no se deduce del cargo: el titulo NO identifica al beneficiario, y omitirlo deja el KYB bloqueado sin decir por que. |
| `public boolean isBeneficialOwner()` | Kira exige al menos una persona asi para verificar a la empresa. |
| `public void linkKiraPerson(String personReferenceId)` |  |
| `public void assignLivenessLink(String link, Instant expiresAt)` | Se invoca con la respuesta de POST /v1/users/{id}/liveness-link. |
| `public boolean isLivenessLinkExpired(Instant now)` |  |
| `public void applyLivenessStatus(LivenessStatus incoming)` | No retrocede desde un estado final: los eventos llegan una vez y sin orden garantizado. |
| `public void expireLivenessLink()` |  |
| `public String fullName()` |  |

<sub>`domain/tenant/UboRepository.java` · 25 líneas</sub>

#### `UboRepository` · interfaz

<sub>`domain/tenant/UboRoster.java` · 62 líneas</sub>

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

<sub>`domain/tenant/UserStatus.java` · 12 líneas</sub>

#### `UserStatus` · enum

Estado del usuario dentro de la empresa cliente.

Valores: `ACTIVE`, `SUSPENDED`, `DISABLED`.

| Método | Descripción |
|---|---|
| `public boolean canLogin()` |  |

### A.4 Dominio — account

<sub>`domain/account/Deposit.java` · 141 líneas</sub>

#### `Deposit` · clase · `@Getter`

Deposito entrante sobre una cuenta virtual.

Se guardan los tres importes por separado porque son tres hechos distintos:
lo que envio el ordenante (bruto), lo que cobro el banco (comision) y lo que
quedo disponible (neto). Derivar uno de los otros pierde el desglose contable.

| Método | Descripción |
|---|---|
| `public Deposit(String id, TenantId tenantId, String virtualAccountId, BigDecimal grossAmount, BigDecimal feeAmount, String currency)` |  |
| `public static Deposit rehydrate(String id, TenantId tenantId, String virtualAccountId, String kiraDepositId, BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount, String currency, String senderName, String senderAccount, Rail rail, DepositStatus status, boolean microdeposit, Instant createdAt, Instant updatedAt)` |  |
| `public void markAsMicrodeposit()` |  |
| `public void applyRemoteStatus(DepositStatus incoming)` | Aplica el estado que trae un evento. No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden garantizado, asi que un 'in_transit' que llega tarde no puede resucitar un deposito ya devuelto. |
| `public void restate(BigDecimal grossAmount, BigDecimal feeAmount, BigDecimal netAmount)` | Corrige los importes con lo que traiga un evento posterior mas completo. |
| `public boolean creditsBalance()` |  |
| `public void describeSender(String senderName, String senderAccount, Rail rail)` |  |
| `public void linkKiraDeposit(String kiraDepositId)` |  |
| `public Money net()` |  |
| `public Money gross()` |  |

<sub>`domain/account/DepositRepository.java` · 17 líneas</sub>

#### `DepositRepository` · interfaz

<sub>`domain/account/DepositStatus.java` · 97 líneas</sub>

#### `DepositStatus` · enum

Estado del deposito entrante.

Los seis valores de docs.kirafin.ai/reference/virtual-accounts/values. Solo FAILED y
REFUNDED son finales: un COMPLETED todavia puede retenerse o devolverse, que es justo lo que
reproduce el valor magico de 11 en el simulador del sandbox.

Valores: `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED`, `KYT_PENDING`, `KYT_REJECTED`.

| Método | Descripción |
|---|---|
| `public static DepositStatus fromWire(String raw)` |  |
| `public static DepositStatus fromEventName(String eventName, String rawStatus)` | Estado que implica cada evento de la familia de depositos. El nombre del evento es mas fiable que el 'status' del payload, porque hay eventos cuyo estado llega vacio y el propio nombre ya dice lo que paso. |
| `public boolean isTerminal()` |  |
| `public boolean isHeld()` | Retenido por cumplimiento: ni acreditado ni fallido, y detiene los pagos de la cuenta. |
| `public boolean creditsBalance()` | Solo un deposito completado suma saldo disponible. |

<sub>`domain/account/VirtualAccount.java` · 217 líneas</sub>

#### `VirtualAccount` · clase · `@Getter`

Agregado VirtualAccount: la cuenta bancaria virtual de la empresa cliente en Kira.

El saldo es una proyeccion local de GET /v1/virtual-accounts/{id}/balance y de los
depositos recibidos por webhook. La autoridad es siempre Kira: aqui solo se refleja.

| Método | Descripción |
|---|---|
| `public VirtualAccount(String id, TenantId tenantId, String currency, VirtualAccountMode mode, String bank, String description)` |  |
| `public static VirtualAccount rehydrate(String id, TenantId tenantId, String kiraAccountId, String bankName, String accountNumber, String routingNumber, String currency, VirtualAccountMode mode, String bank, String description, VirtualAccountStatus status, BigDecimal balanceAvailable, boolean activatedEventSeen, Instant balanceRefreshedAt, String openingIdempotencyKey, Instant createdAt, Instant updatedAt)` |  |
| `public IdempotencyKey reserveOpeningKey()` | Reserva la clave de idempotencia de la apertura, antes de la primera llamada. Un timeout seguido de reintento no debe dejar dos cuentas abiertas. |
| `public void linkKiraAccount(String kiraAccountId)` |  |
| `public void describeBank(String bankName, String accountNumber, String routingNumber)` | Completa los datos bancarios. Solo sobrescribe lo que llega con valor: un evento que no trae el numero de cuenta no puede borrar el que ya conocemos, porque ese numero es justamente la senal de que la cuenta puede mover fondos. |
| `public void applyRemoteStatus(VirtualAccountStatus incoming)` |  |
| `public void markActivatedEventSeen()` | virtual_account.activated es la unica senal inequivoca de fondos-listos. |
| `public void refreshBalance(BigDecimal available, Instant at)` | El saldo es una proyeccion: la autoridad es Kira. En el sandbox ademas es un valor fijo del proveedor que no se mueve con la actividad. |
| `public boolean isActivationDelayed(Instant now)` | La activacion lleva demasiado tiempo. En el sandbox puede quedarse colgada indefinidamente sin que llegue nunca el evento virtual_account.activated, asi que el portal necesita poder decir "activacion demorada, contacta con Kira" en vez de girar un spinner para siempre. |
| `public boolean isOpenInKira()` |  |
| `public boolean isOpeningUnconfirmed()` | La apertura se reservo pero Kira nunca confirmo la cuenta (G-23). Es el registro que queda cuando la llamada a Kira falla despues de haber guardado la clave de idempotencia: hay fila local, no hay cuenta remota. Un reintento debe volver sobre esta misma fila y con esta misma clave, porque si Kira si llego a crear la cuenta y lo que se perdio fue la respuesta, una clave nueva abriria una segunda cuenta. |
| `public boolean matches(String currency, VirtualAccountMode mode)` | Misma moneda y misma modalidad: una cuenta pendiente de otra combinacion no sirve. |
| `public void markBalanceStale()` | Marca el saldo como desactualizado. Un deposito acreditado NO se suma al saldo local: la autoridad es Kira y en el sandbox el saldo es ademas un valor fijo del proveedor. Inventar aqui una suma seria mostrar un numero que el banco no reconoce; lo unico honesto es decir que hay que volver a preguntar. |
| `public boolean isBalanceStale()` |  |
| `public Money availableBalance()` |  |
| `public boolean isFundsReady()` |  |
| `public void assertFundsReady()` | Ningun pago se prepara sobre una cuenta que todavia no puede mover fondos. |

<sub>`domain/account/VirtualAccountMode.java` · 29 líneas</sub>

#### `VirtualAccountMode` · enum

Modo de la cuenta virtual. Es INMUTABLE una vez creada: cambiar de fiat a crypto
significa abrir otra cuenta, no editar esta.

Valores: `FIAT`, `CRYPTO`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static VirtualAccountMode from(String raw)` |  |

<sub>`domain/account/VirtualAccountReadiness.java` · 34 líneas</sub>

#### `VirtualAccountReadiness` · clase

Cuando una cuenta puede mover fondos. En 2026-06-01 lo dice el estado 'active' (y el evento
virtual_account.activated, que trae ese mismo estado). Se conserva la deteccion por un
account_number real, no nulo y distinto del centinela "PENDING-ACT-ACCOUNT", para las filas
que se proyectaron con la version anterior.

| Método | Descripción |
|---|---|
| `public static boolean isFundsReady(String status, String accountNumber, boolean activatedEventSeen)` |  |

<sub>`domain/account/VirtualAccountRepository.java` · 17 líneas</sub>

#### `VirtualAccountRepository` · interfaz

<sub>`domain/account/VirtualAccountStatus.java` · 38 líneas</sub>

#### `VirtualAccountStatus` · enum

Estado local de la cuenta virtual, sobre los valores de 2026-06-01:
pending, activating, active, failed, deactivated (y frozen).

ACTIVE solo sale de 'active', que Kira define como "la cuenta puede recibir depositos".
'activating' es PENDING: el banco aun la esta abriendo. Si llegara un 'approved' de la
version anterior tampoco se da por activa; VirtualAccountReadiness decide.

Valores: `PENDING`, `ACTIVE`, `INACTIVE`, `FAILED`, `FROZEN`.

| Método | Descripción |
|---|---|
| `public static VirtualAccountStatus fromWire(String raw)` |  |

### A.5 Dominio — treasury

<sub>`domain/treasury/BankAccountKind.java` · 26 líneas</sub>

#### `BankAccountKind` · enum

Tipo de cuenta bancaria del destinatario.

Valores: `CHECKING`, `SAVINGS`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static BankAccountKind from(String raw)` |  |

<sub>`domain/treasury/FeeBreakdown.java` · 71 líneas</sub>

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

| Método | Descripción |
|---|---|
| `public static FeeBreakdown standard()` | El desglose de referencia: 15 + 15 = 30 USD. Es una estimacion, no un hecho: la tarifa de Kira depende del riel y lleva un tramo porcentual, asi que la cifra real solo se conoce cuando la cotizacion vuelve. Se usa mientras no haya cotizacion. |
| `public static FeeBreakdown fromTotals(BigDecimal kiraRevenueTotal, BigDecimal clientMarkupTotal)` | El desglose real, leido de la cotizacion: totals.kira_revenue_total es el ingreso de Kira y totals.client_markup_total el nuestro. |
| `public static BigDecimal requestedPlatformMarkup()` | El margen fijo que la plataforma pide a Kira como client_markup. |
| `public static FeeBreakdown of(BigDecimal kiraFee, BigDecimal platformFee)` |  |
| `public BigDecimal totalDebitFor(BigDecimal originAmount)` | Lo que se debita de la cuenta virtual: el importe enviado mas el cobro total. |

<sub>`domain/treasury/NatureOfPayment.java` · 36 líneas</sub>

#### `NatureOfPayment` · enum

Naturaleza del pago que Kira reporta al banco corresponsal.

Valores: `VENDOR`, `POBO`, `FIRST_PARTY`, `SPOT_3P`, `SPOT_1P`, `RELATED_ENTITIES`, `OTHER`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public static NatureOfPayment from(String raw)` |  |
| `public boolean requiresSupportingDocuments()` | Un pago a uno mismo no necesita justificar el destino con documentos. |

<sub>`domain/treasury/Payout.java` · 293 líneas</sub>

#### `Payout` · clase · `@Getter`

Agregado Payout. Entidad de dominio pura: sin anotaciones de JPA ni dependencias de framework.
Concentra el control interno (maker-checker) que la API de Kira no ofrece a los integradores.

| Método | Descripción |
|---|---|
| `public Payout(String id, TenantId tenantId, String kiraUserId, String virtualAccountId, String recipientId, Money amount, FeeBreakdown fees, IdempotencyKey idempotencyKey, String makerUserId)` |  |
| `public static Payout rehydrate(String id, TenantId tenantId, String kiraUserId, String virtualAccountId, String recipientId, Money amount, FeeBreakdown fees, IdempotencyKey idempotencyKey, String makerUserId, Instant createdAt, String quotationId, Instant quotationExpiresAt, PayoutApprovalState approvalState, PayoutStatus status, String approverUserId, String firstApproverUserId, String rejectionReason, String kiraPayoutId, String errorCode, String referenceNumber, String paymentMethod, Instant updatedAt)` | Rehidratacion desde persistencia. |
| `public Money totalDebit()` | Lo que se debita de la cuenta virtual: importe enviado mas el cobro total al cliente. |
| `public BigDecimal platformMargin()` |  |
| `public void attachQuotation(String quotationId, Instant expiresAt)` |  |
| `public void attachQuotation(Quotation quotation, Instant now)` | Ata el pago a una cotizacion vigente. NO la consume: la cotizacion se marca como ejecutada al enviar el pago a Kira, no al prepararlo, porque entre preparar y aprobar puede pasar de todo (incluido que venza y haya que recotizar). |
| `public void replaceQuotation(Quotation quotation, Instant now)` | Cambia la cotizacion de un pago pendiente por una nueva (la anterior vencio mientras esperaba aprobacion). El precio puede cambiar, asi que una primera firma ya dada no vale. |
| `public boolean isQuotationExpired(Instant now)` |  |
| `public void approve(String approverId, Instant now)` | Aprobacion de una sola firma y sin autor de destinatario conocido. |
| `public boolean approve(String approverId, Instant now, int requiredApprovals, String recipientCreatorId)` | Segregacion de funciones (arquitectura §7): - quien crea la solicitud no puede autorizar su envio; - quien registro el destinatario no puede aprobar pagos hacia el; - con dos firmas requeridas, la segunda es de otra persona. primera de dos firmas. |
| `public void reject(String approverId, String reason)` |  |
| `public void markAsSubmitted(String kiraPayoutId, String wireStatus)` | Se invoca tras un 201 de POST /v1/virtual-accounts/{id}/payout. |
| `public void applyRemoteStatus(PayoutStatus incoming, String errorCode)` | Aplica una transicion recibida por webhook o por reconciliacion. No retrocede desde un estado terminal: los eventos llegan una sola vez y sin orden garantizado. |
| `public void fail(String reason)` |  |
| `public boolean isReadyToSubmit()` |  |
| `public Money grossAmountToSend(Quotation quotation)` | El importe que viaja en POST /payout. Kira DESCUENTA las comisiones del monto enviado: mandar 1.000 con 30 de comision deja al destinatario con 970. Como se cotiza en modo inverse, el bruto que hay que enviar es el total a debitar, y solo asi el destinatario recibe el importe prometido. |
| `public boolean isPriceLocked()` | El precio esta cerrado solo si hay cotizacion detras. |
| `public void assertSubmittable(Instant now)` |  |
| `public void describeRemote(String referenceNumber, String paymentMethod)` | Se completa desde el 201 del envio y desde GET /v1/payouts/{id}. |

<sub>`domain/treasury/PayoutApprovalState.java` · 9 líneas</sub>

#### `PayoutApprovalState` · enum

Estado del control interno maker-checker. Vive solo en el BFF; Kira no lo conoce.

Valores: `PENDING_APPROVAL`, `APPROVED`, `REJECTED`, `SUBMITTED`.

<sub>`domain/treasury/PayoutRepository.java` · 24 líneas</sub>

#### `PayoutRepository` · interfaz

<sub>`domain/treasury/PayoutStatus.java` · 60 líneas</sub>

#### `PayoutStatus` · enum

Estado del payout en Kira (vocabulario del recurso, en MAYUSCULAS segun GET /v1/payouts/{id}).
KYT_PENDING e IN_REVIEW solo afloran via el evento payout.status_changed y son NO terminales.
CANCELLED (detenido antes de enviarse) es un estado final propio. RETURNED no existe como
estado: una devolucion bancaria pasa el pago a FAILED, incluso desde COMPLETED.

Valores: `NOT_SUBMITTED`, `CREATED`, `PENDING`, `PROCESSING`, `KYT_PENDING`, `IN_REVIEW`, `COMPLETED`, `FAILED`, `CANCELLED`, `EXPIRED`, `UNKNOWN`.

| Método | Descripción |
|---|---|
| `public static PayoutStatus fromWire(String raw)` | Tolerante: un estado desconocido no rompe la maquina, se trata como no terminal. |
| `public boolean isTerminal()` |  |
| `public boolean isInFlight()` |  |

<sub>`domain/treasury/Quotation.java` · 190 líneas</sub>

#### `Quotation` · clase · `@Getter`

Cotizacion de una transferencia.

Se cotiza con inverse=true: el importe que teclea el operador es lo que RECIBE el
destinatario, y las comisiones se suman por encima. Por eso originAmount y
totalDebitAmount son cifras distintas y ambas se guardan: la primera es lo prometido
al destinatario, la segunda lo que sale de la cuenta virtual.

La cotizacion vive 900 segundos exactos (locked_at + 15 min). Un pago aprobado con una
cotizacion vencida se ejecutaria a una tasa distinta de la que vio el tesorero, asi que
el vencimiento es parte del agregado y no un detalle de la respuesta HTTP.

| Método | Descripción |
|---|---|
| `public Quotation(String id, TenantId tenantId, String virtualAccountId, String recipientId, QuotationRail rail, BigDecimal originAmount, FeeBreakdown fees, Instant expiresAt)` |  |
| `public static Quotation rehydrate(String id, TenantId tenantId, String virtualAccountId, String recipientId, QuotationRail rail, String kiraQuoteId, BigDecimal originAmount, BigDecimal destinationAmount, String destinationCurrency, BigDecimal exchangeRate, FeeBreakdown fees, BigDecimal totalDebitAmount, boolean balanceSufficient, String rateSource, String feesSnapshot, Instant expiresAt, QuotationStatus status, Instant createdAt)` |  |
| `public void applyKiraQuote(String kiraQuoteId, Instant expiresAt, BigDecimal totalDebit, BigDecimal destinationAmount, String destinationCurrency, BigDecimal exchangeRate, FeeBreakdown fees, boolean balanceSufficient, String rateSource, String feesSnapshot)` | Asienta lo que devolvio POST /v1/quotations. totalDebit es source.amount, el bruto que sale de la cuenta virtual; destination es recipient.amount, el neto que llega. Ninguno se recalcula aqui: el precio que se le muestra al tesorero tiene que ser exactamente el que Kira va a cobrar. |
| `public boolean hasConsistentTotals()` | Comprueba que el bruto cuadra con lo prometido mas las comisiones. Un descuadre no es un error de Kira: es que el importe mostrado al tesorero y el debitado de la cuenta no son el mismo numero, y eso hay que verlo. |
| `public boolean usesFallbackRate()` | La tasa no viene del mercado sino de una politica de contingencia. |
| `public boolean isExpired(Instant now)` |  |
| `public boolean isUsable(Instant now)` |  |
| `public void assertUsable(Instant now)` |  |
| `public void assertRedeemable(Instant now)` | Ademas de vigente, la cuenta debe tener saldo: Kira no encola ni cancela sola. |
| `public void markExecuted()` | Se consume al enviar el pago a Kira, no al prepararlo. |
| `public void expire()` |  |
| `public long secondsToExpiry(Instant now)` |  |

<sub>`domain/treasury/QuotationRail.java` · 88 líneas</sub>

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
| `public Rail accountType()` |  |
| `public String network()` | El valor de 'network' en el destinatario cuando el riel es de wallet. |
| `public static List<QuotationRail> validFor(Rail accountType)` |  |
| `public static QuotationRail defaultFor(Rail accountType, String network)` | Riel por defecto del destinatario. Para wallets hace falta saber la red. |
| `public static QuotationRail fromNetwork(String network)` |  |
| `public static QuotationRail from(String raw)` |  |
| `public void assertMatches(Rail recipientAccountType)` | Se comprueba antes de cotizar: Kira solo lo detecta al ejecutar el pago. |

<sub>`domain/treasury/QuotationRepository.java` · 19 líneas</sub>

#### `QuotationRepository` · interfaz

<sub>`domain/treasury/QuotationStatus.java` · 8 líneas</sub>

#### `QuotationStatus` · enum

Ciclo de vida local de la cotizacion. El TTL de 15 minutos lo fija Kira.

Valores: `ACTIVE`, `EXPIRED`, `EXECUTED`.

<sub>`domain/treasury/Recipient.java` · 138 líneas</sub>

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
| `public Recipient(String id, TenantId tenantId, RecipientHolder holder, RecipientAccount account, PostalAddress address)` |  |
| `public static Recipient rehydrate(String id, TenantId tenantId, RecipientHolder holder, RecipientAccount account, PostalAddress address, String kiraRecipientId, RecipientStatus status, String replacedByRecipientId, Instant createdAt, Instant updatedAt)` |  |
| `public void recordAuthor(String userId)` |  |
| `public Rail getRail()` |  |
| `public String getNetwork()` | Red de la wallet. Null para rieles bancarios. |
| `public String getName()` |  |
| `public void linkKiraRecipient(String kiraRecipientId)` | La respuesta usa 'recipient_id', no 'id'. |
| `public void replaceWith(String replacementId)` | Reemplazo logico: Kira no borra destinatarios, asi que el corregido es otro registro y este solo queda archivado apuntando a su sustituto. |
| `public void archive()` |  |
| `public boolean isActive()` |  |
| `public boolean isRegisteredInKira()` |  |
| `public void assertUsable()` |  |

<sub>`domain/treasury/RecipientAccount.java` · 114 líneas</sub>

#### `RecipientAccount` · interfaz

Datos de cobro del destinatario. Es el oneOf que Kira discrimina por account_type.

Un destinatario = un riel. Modelarlo como jerarquia sellada y no como una bolsa de
campos opcionales es lo que impide que exista un destinatario ACH con swift_code, o una
wallet con numero de cuenta: combinaciones que la API acepta enviar y rechaza al pagar.

| Método | Descripción |
|---|---|
| `public Rail rail()` |  |
| `public String destination()` |  |
#### `Ach` · record

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

#### `Wire` · record

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

#### `Wallet` · record

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

<sub>`domain/treasury/RecipientHolder.java` · 52 líneas</sub>

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

| Método | Descripción |
|---|---|
| `public static RecipientHolder company(String companyName, String email, String phone)` |  |
| `public static RecipientHolder person(String firstName, String lastName, String email, String phone)` |  |
| `public String wireType()` |  |
| `public String displayName()` | Nombre legible para el directorio local. |
| `public String type()` |  |

<sub>`domain/treasury/RecipientRepository.java` · 17 líneas</sub>

#### `RecipientRepository` · interfaz

<sub>`domain/treasury/RecipientStatus.java` · 10 líneas</sub>

#### `RecipientStatus` · enum

Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno se crea
un reemplazo y el anterior se archiva: ARCHIVED es un estado puramente local.

Valores: `ACTIVE`, `ARCHIVED`.

<sub>`domain/treasury/SupportingDocument.java` · 78 líneas</sub>

#### `SupportingDocument` · record

Documento de soporte del pago.

Aqui el archivo va SIEMPRE como data URI base64: a diferencia del KYB, este endpoint
no acepta URLs. Y el limite es de 3 MB por archivo, sobre un base64 que ya infla el
original un tercio.

| Componente |
|---|
| `String type` |
| `String file` |

| Método | Descripción |
|---|---|
| `public static void assertValid(List<SupportingDocument> documents, NatureOfPayment nature, boolean cryptoFunded)` | Un array vacio se rechaza: o no va el campo, o van uno o dos documentos. |

<sub>`domain/treasury/WalletToken.java` · 56 líneas</sub>

#### `WalletToken` · enum

Stablecoin del destinatario y las redes en las que Kira la admite.

Los pares no son intercambiables: USDC NO existe en tron. Un par invalido se rechaza
aqui porque la alternativa es descubrirlo con un pago retenido.

Valores: `USDC`, `USDT`, `COPM`.

| Método | Descripción |
|---|---|
| `public String wireValue()` |  |
| `public List<String> networks()` |  |
| `public static WalletToken from(String raw)` |  |
| `public void assertSupportedOn(String network)` |  |

### A.6 Dominio — compliance

<sub>`domain/compliance/AuditLog.java` · 45 líneas</sub>

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

<sub>`domain/compliance/AuditLogRepository.java` · 13 líneas</sub>

#### `AuditLogRepository` · interfaz

Puerto de salida de la bitacora. Solo escritura y lectura: nunca actualizacion ni borrado.

<sub>`domain/compliance/Rfi.java` · 125 líneas</sub>

#### `Rfi` · clase · `@Getter`

Agregado Rfi: requerimiento de informacion de KiraFin sobre una empresa cliente.

Nunca se crea desde aqui: Kira lo genera y nosotros lo leemos y respondemos. Los items
llegan como un array libre que cambia por tipo de requerimiento; se guardan tal cual
(JSON) porque normalizarlos obligaria a migrar el esquema cada vez que Kira pide algo
nuevo. Lo que si es del dominio es el estado, el plazo y lo que el RFI tiene bloqueado.

| Método | Descripción |
|---|---|
| `public Rfi(String id, TenantId tenantId, String kiraRfiId, String itemsPayload, Instant dueDate)` |  |
| `public static Rfi rehydrate(String id, TenantId tenantId, String kiraRfiId, RfiStatus status, String itemsPayload, Instant dueDate, String blockingType, String blockingResourceId, String resolutionReason, Instant createdAt, Instant updatedAt)` |  |
| `public void assertAcceptsAnswers()` | Antes de llamar a Kira: responder un RFI cerrado solo gasta la llamada y devuelve 409. |
| `public void applyRemoteStatus(RfiStatus incoming, String itemsPayload)` | Asienta lo que dice Kira. Kira es la fuente de verdad, con una excepcion: un RFI cerrado no se reabre. Los webhooks pueden llegar desordenados, y un 'answered' tardio no debe devolver a la bandeja algo ya resuelto. |
| `public void describeDueDate(Instant dueDate)` | El plazo no se prorroga aunque Kira devuelva items para otra ronda. |
| `public void describeBlocking(String type, String resourceId)` | blocking: { type: "transfer", transfer_uuid }. Lo bloqueado sigue bloqueado si el RFI vence. |
| `public void describeResolutionReason(String reason)` |  |
| `public void withdraw()` | Kira lo retiro: responde 404 y ya no hay nada que contestar. |
| `public boolean isOverdue(Instant now)` |  |

<sub>`domain/compliance/RfiRepository.java` · 22 líneas</sub>

#### `RfiRepository` · interfaz

<sub>`domain/compliance/RfiStatus.java` · 47 líneas</sub>

#### `RfiStatus` · enum

Ciclo de vida de una solicitud de informacion (RFI) planteada por KiraFin.

Los cuatro estados que Kira devuelve mas WITHDRAWN: Kira lo cuenta como cierre, pero un RFI
retirado responde 404 en todas sus rutas y nunca aparece como estado, asi que lo asienta el BFF
al recibir ese 404. Un item devuelto no crea un estado propio: el RFI vuelve a PENDING.

Valores: `PENDING`, `ANSWERED`, `RESOLVED`, `NOT_RESOLVED`, `WITHDRAWN`.

| Método | Descripción |
|---|---|
| `public static RfiStatus fromWire(String raw)` | Un valor desconocido cae a PENDING: mostrar de mas un RFI en la bandeja es un susto, esconder uno abierto deja bloqueado un pago hasta que vence. |
| `public boolean isTerminal()` |  |
| `public boolean isOpen()` | Mientras no este cerrado admite respuestas: tambien en ANSWERED, si Kira devuelve un item. |

### A.7 Aplicación — auth

<sub>`application/auth/LoginUseCase.java` · 93 líneas</sub>

#### `LoginUseCase` · clase · `@Service`

Sesion propia del BFF. Las credenciales de Kira nunca salen del servidor.

| Método | Descripción |
|---|---|
| `public LoginUseCase(OperatorUserRepository users, TenantRepository tenants, PasswordEncoder passwordEncoder, JwtService jwtService, BffSecurityProperties security)` |  |
| `public LoginResult login(String email, String rawPassword)` |  |
| `public LoginResult sessionFor(OperatorUser user)` | Sesion completa para un usuario ya autenticado por todos sus factores. |
#### `LoginResult` · record

Con MFA, la primera respuesta no trae accessToken sino mfaChallenge: mfaRequired pide el codigo
y mfaSetupRequired pide configurar el segundo factor antes (entorno que lo exige).

| Componente |
|---|
| `String accessToken` |
| `long expiresIn` |
| `String email` |
| `String role` |
| `String tenantId` |
| `String tenantName` |
| `String mfaChallenge` |
| `Boolean mfaRequired` |
| `Boolean mfaSetupRequired` |

<sub>`application/auth/MfaService.java` · 167 líneas</sub>

#### `MfaService` · clase · `@Service`

Segundo factor TOTP (arquitectura §7, "autenticacion fuerte propia").

Tres garantias que no dependen del cliente:
 - un reto admite como maximo 5 codigos erroneos y despues hay que volver a poner la contrasena;
 - un codigo ya usado no vale otra vez dentro de su ventana de 30 s;
 - el secreto se guarda cifrado, nunca en claro.
Los contadores viven en memoria: con varias instancias del BFF el tope es por instancia.

| Método | Descripción |
|---|---|
| `public MfaService(OperatorUserRepository users, JwtService jwt, MfaSecretCipher cipher, BffSecurityProperties properties, LoginUseCase login, AuditTrail audit)` |  |
| `public LoginUseCase.LoginResult verify(String challengeToken, String code)` | Paso 2 del inicio de sesion: el reto de la contrasena mas un codigo valido dan la sesion. |
| `public MfaSetup setup(AuthenticatedOperator operator, String challengeToken)` | Genera un secreto pendiente. Sirve con sesion (activacion voluntaria) o con el reto del login cuando el entorno exige MFA y la cuenta aun no lo tiene. |
| `public LoginUseCase.LoginResult enable(AuthenticatedOperator operator, String challengeToken, String code)` | Confirma el secreto pendiente con un primer codigo y devuelve una sesion nueva. |
| `public void disable(AuthenticatedOperator operator, String code)` | Solo si el entorno no lo exige, y con un codigo valido: robar la sesion no basta para quitarlo. |
| `public boolean isEnforced()` |  |
#### `MfaSetup` · record

El secreto se muestra una sola vez, para escanearlo o escribirlo en la app autenticadora.

| Componente |
|---|
| `String secret` |
| `String otpauthUri` |

### A.8 Aplicación — tenant

<sub>`application/tenant/KiraUserState.java` · 68 líneas</sub>

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

<sub>`application/tenant/KybDocumentCommands.java` · 35 líneas</sub>

#### `KybDocumentCommands` · clase
#### `UploadedFile` · record

Archivo recibido del portal. Mismo contrato que el de los RFI.

| Componente |
|---|
| `String fileName` |
| `String contentType` |
| `byte[] content` |

#### `DocumentFile` · record

Un archivo con el papel que cumple dentro del registro: front, back, selfie o file_*.

| Componente |
|---|
| `String documentType` |
| `UploadedFile file` |

#### `AttachDocuments` · record

Una entrada de identifying_information[] con sus archivos.

Kira no tiene endpoint de subida: los documentos viajan dentro del PUT /v1/users,
anidados en el registro al que pertenecen. Por eso una peticion cubre un registro
(el pasaporte, o el acta de constitucion) y todos sus archivos a la vez.

| Componente |
|---|
| `String informationType` |
| `String issuingCountry` |
| `String number` |
| `String expiration` |
| `List<DocumentFile> documents` |

<sub>`application/tenant/KybDocuments.java` · 160 líneas</sub>

#### `KybDocuments` · clase

Construye la entrada de identifying_information[] que Kira espera dentro del PUT /v1/users.

Kira admite dos formas de mandar un archivo: un data URI en base64, o una URL https que
descarga despues. Aqui se usa base64 porque la URL exige un dominio preautorizado por Kira
y un host publico. El precio es el tope de 10 MB del cuerpo entero, y base64 anade un tercio
sobre el tamano real del archivo: por eso el limite se controla sobre los bytes crudos.

Los documentos NUNCA se guardan en el BFF: Kira los custodia. Por eso
#withoutFiles(List) limpia los archivos antes de persistir el payload de onboarding.
Es seguro reenviar la entrada sin ellos, porque en un PUT "a missing file works differently:
sending other fields will not clear it".

<sub>`application/tenant/ManageOperatorsService.java` · 139 líneas</sub>

#### `ManageOperatorsService` · clase · `@Service`

Administracion de los operadores de una empresa cliente (G-13 / D5).

Hasta ahora los usuarios solo entraban por la semilla de dev o por SQL. Este servicio es el
unico camino para darlos de alta desde el portal, y por eso concentra las tres reglas que no
pueden quedar en manos del controlador:

1. La empresa sale SIEMPRE de la sesion del administrador, nunca del cuerpo de la peticion.
2. Un ADMIN no puede fabricar otro ADMIN ni un PLATFORM_OPERATOR: escalar privilegios desde
   el portal convertiria el RBAC en decorativo. Esos dos siguen siendo alta controlada.
3. Nadie se da de baja a si mismo: dejaria a la empresa sin administrador.

| Método | Descripción |
|---|---|
| `public ManageOperatorsService(OperatorUserRepository users, PasswordEncoder passwordEncoder, AuditTrail audit)` |  |
| `public List<OperatorView> list(AuthenticatedOperator operator)` | Operadores de la empresa del solicitante. La consola de plataforma no entra por aqui. |
| `public OperatorView create(AuthenticatedOperator operator, OperatorCommands.CreateOperator command)` |  |
| `public OperatorView suspend(AuthenticatedOperator operator, String userId)` | Suspende a un operador de la propia empresa. Es SUSPENDED y no DISABLED a proposito: la accion del portal es reversible y no borra la persona, que sigue siendo el actor de los pagos y las aprobaciones que ya firmo. |

<sub>`application/tenant/OnboardingCommands.java` · 49 líneas</sub>

#### `OnboardingCommands` · clase
#### `RegisterBusiness` · record

Alta minima viable en Kira. Crea el registro pero NO dispara la verificacion.

source_of_funds es obligatorio aqui aunque la API lo acepte vacio: sin el, el KYB
no arranca nunca por mucho que el resto del formulario este completo, y ese fallo
es silencioso.

| Componente |
|---|
| `String businessLegalName` |
| `String email` |
| `String sourceOfFunds` |

#### `CompleteProfile` · record

Campos del perfil KYB. Se envian tal cual los nombra Kira (business_type,
formation_date, associated_persons...), porque el formulario se renderiza desde
missing_fields y traducir nombres aqui obligaria a mantener un diccionario que
cambia cada vez que Kira pide un campo nuevo.

| Componente |
|---|
| `Map<String, Object> profile` |

#### `AcceptTerms` · record

Aceptacion de los terminos por el operador. La version debe ser la vigente
(bff.terms.version): aceptar una anterior no vale.

| Componente |
|---|
| `String version` |

#### `SaveDraft` · record

Borrador completo del formulario de vinculacion, con los nombres de campo de Kira.
Reemplaza el anterior; un objeto vacio borra el borrador. Nunca se envia a Kira.

| Componente |
|---|
| `Map<String, Object> draft` |

<sub>`application/tenant/OnboardingDraftService.java` · 97 líneas</sub>

#### `OnboardingDraftService` · clase · `@Service`

Borrador del formulario de vinculacion.

Kira no conoce borradores: un PUT escribe lo que se envia y la verificacion arranca sola
cuando el expediente esta completo. Para poder dejar el formulario a medias y volver otro
dia, el portal guarda aqui lo que lleva rellenado. Este servicio NUNCA llama a Kira: enviar
sigue siendo POST/PUT /api/onboarding.

Los documentos no caben en un borrador por la misma regla que en el resto del BFF: los
archivos los custodia Kira y no se guardan aqui. Un data URI dentro del borrador se rechaza.

| Método | Descripción |
|---|---|
| `public OnboardingDraftService(TenantRepository tenants, AuditTrail audit, ObjectMapper objectMapper)` |  |
| `public OnboardingDraftView get(AuthenticatedOperator operator)` |  |
| `public OnboardingDraftView save(AuthenticatedOperator operator, OnboardingCommands.SaveDraft command)` |  |

<sub>`application/tenant/OnboardingDraftView.java` · 8 líneas</sub>

#### `OnboardingDraftView` · record

Borrador del formulario de vinculacion tal como lo dejo el portal. `updatedAt` es nulo si nunca se guardo.

| Componente |
|---|
| `Map<String, Object> draft` |
| `Instant updatedAt` |

<sub>`application/tenant/OnboardingView.java` · 43 líneas</sub>

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
| `String rejectionReason` |
| `boolean verificationTriggered` |
| `List<String> pendingFields` |
| `List<EligibleProduct> eligibleProducts` |
| `boolean readyForVirtualAccounts` |
| `boolean enhancedDueDiligenceRequired` |

| Método | Descripción |
|---|---|
| `public static OnboardingView from(Tenant tenant)` |  |

<sub>`application/tenant/OperatorCommands.java` · 28 líneas</sub>

#### `OperatorCommands` · clase
#### `CreateOperator` · record

Alta de un operador humano de la empresa.

El rol viaja como el nombre de la constante (TREASURY_MAKER, no tesoreria_maker): es lo
que el portal ya recibe en el JWT y en /api/auth/me, asi que no hay dos vocabularios.
La empresa NO viaja en el cuerpo: sale siempre de la sesion del ADMIN (aislamiento
multiempresa), y admitirla aqui seria ofrecer un campo que el servicio va a ignorar.

| Componente |
|---|
| `String email` |
| `String firstName` |
| `String lastName` |
| `String password` |
| `String role` |

<sub>`application/tenant/OperatorView.java` · 34 líneas</sub>

#### `OperatorView` · record

Operador tal como lo ve el portal. Nunca lleva el hash de la contrasena ni el secreto TOTP:
solo si el segundo factor esta activo, que es lo que el administrador necesita saber.

| Componente |
|---|
| `String id` |
| `String email` |
| `String firstName` |
| `String lastName` |
| `String fullName` |
| `String role` |
| `String roleDescription` |
| `String status` |
| `boolean active` |
| `boolean mfaEnabled` |

| Método | Descripción |
|---|---|
| `public static OperatorView from(OperatorUser user)` |  |

<sub>`application/tenant/SubmitOnboardingService.java` · 385 líneas</sub>

#### `SubmitOnboardingService` · clase · `@Service`

Onboarding KYB de la empresa cliente contra /v1/users.

El flujo no es una linea recta sino un bucle: alta minima, y despues PUT completo + GET
hasta que no falte nada para el producto objetivo. Este servicio implementa los tres
pasos por separado para que el portal pueda repetir el del medio tantas veces como haga
falta sin volver a crear nada.

| Método | Descripción |
|---|---|
| `public SubmitOnboardingService(TenantRepository tenants, KiraApiClient kira, AuditTrail audit, ObjectMapper objectMapper, IdempotencyKeyStore idempotencyKeys, KiraProperties properties, ("$` |  |
| `public OnboardingView status(AuthenticatedOperator operator)` |  |
| `public OnboardingView register(AuthenticatedOperator operator, OnboardingCommands.RegisterBusiness command)` | Paso 1: alta minima en Kira. Idempotente por dos vias: si la empresa ya tiene kiraUserId no se vuelve a llamar, y si la llamada se corta a medias el reintento reutiliza la misma clave de idempotencia ya persistida. |
| `public OnboardingView completeProfile(AuthenticatedOperator operator, OnboardingCommands.CompleteProfile command)` | Paso 2: completa el perfil. Se repite tantas veces como haga falta. Kira solo escribe los campos que viajan, pero se reenvia la fusion de lo ya enviado con lo nuevo: el payload guardado es la unica copia de los campos que Kira no devuelve. |
| `public OnboardingView attachDocuments(AuthenticatedOperator operator, KybDocumentCommands.AttachDocuments command)` | Adjunta un registro de identifying_information[] con sus archivos. Kira no tiene endpoint de subida: el documento viaja dentro del PUT /v1/users. Los archivos se mandan en base64 y NO se guardan aqui — se limpian del payload antes de persistirlo, porque reenviarlos en cada PUT posterior reventaria el tope de 10 MB. Reenviar la entrada sin archivos no los borra en Kira. |
| `public TermsView terms(AuthenticatedOperator operator)` | Terminos vigentes y la version que la empresa acepto (arquitectura §2.1 y §7). |
| `public TermsView acceptTerms(AuthenticatedOperator operator, OnboardingCommands.AcceptTerms command)` | Registra la aceptacion de los terminos vigentes y la manda a Kira como tos_accepted_version (Kira sella tos_accepted_at). Queda auditada con el operador y su IP. |
| `public OnboardingView refresh(AuthenticatedOperator operator)` | Paso 3: el recurso es la autoridad. Tambien cubre el hueco de un webhook perdido. |
| `public boolean reconcile(TenantId tenantId)` | Lo mismo que refresh, sin operador: lo usa el worker de reconciliacion. Recupera un user.status_changed que no llego (Kira reintenta ~80 min y despues lo da por perdido). Devuelve true si el estado cambio. |
#### `TermsView` · record

version y url son null si no hay terminos configurados; acceptedVersion, si nunca se aceptaron.

| Componente |
|---|
| `String version` |
| `String url` |
| `String acceptedVersion` |

<sub>`application/tenant/SyncUbosService.java` · 381 líneas</sub>

#### `SyncUbosService` · clase · `@Service`

Beneficiarios finales: registro local, sincronizacion con Kira y enlaces de prueba de vida.

El registro local no es una copia por comodidad: Kira no devuelve todos los datos de
cada persona, y fusiona associated_persons[] por email, asi que este lado es la unica
fuente completa de quienes son y de lo que ya se le envio.

| Método | Descripción |
|---|---|
| `public SyncUbosService(UboRepository ubos, TenantRepository tenants, SubmitOnboardingService onboarding, KiraApiClient kira, AuditTrail audit)` |  |
| `public UboView.Roster list(AuthenticatedOperator operator)` |  |
| `public UboView save(AuthenticatedOperator operator, UboCommands.SaveUbo command)` | Alta o edicion local. No toca Kira: eso lo hace #syncToKira. |
| `public UboView.Roster delete(AuthenticatedOperator operator, String uboId)` | Quita un beneficiario cargado por error. Solo antes de que Kira lo conozca: alli las personas se fusionan por email y quitar una del array no la borra, asi que borrarla aqui dejaria las dos listas descuadradas. |
| `public OnboardingView syncToKira(AuthenticatedOperator operator)` | Envia el array completo de beneficiarios a Kira. Se valida el grupo antes de llamar: sin una persona con propiedad >= 5 %, Kira acepta el PUT y deja el KYB atascado pidiendo "associated_persons:beneficial_owner". Fallar aqui es mas barato que descubrirlo tres pantallas mas adelante. |
| `public UboView attachDocuments(AuthenticatedOperator operator, String uboId, KybDocumentCommands.AttachDocuments command, boolean biometricConsent)` | Adjunta un documento de identidad a UNA persona. El documento va anidado en la entrada de esa persona dentro de associated_persons[], que Kira empareja por email: de ahi que el beneficiario necesite uno antes de subir nada. La persona viaja con sus datos conocidos para que la fusion no la deje a medias. Mandar la selfie junto al documento le basta a Kira para el face match, sin sesion interactiva: no sustituye al enlace de liveness, pero adelanta esa parte. |
| `public UboView.Roster requestLivenessLinks(AuthenticatedOperator operator, UboCommands.RequestLivenessLinks command)` | Pide un enlace de prueba de vida por beneficiario final. Llamadas repetidas devuelven el mismo enlace salvo que cambien las URLs de redireccion, asi que reintentar es seguro. El enlace vive 7 dias y el resultado real NO llega por la landing de redireccion, sino por el webhook user.liveness_completed. |
| `public void applyLivenessResult(String personReferenceId, com.example.autransactional.domain.tenant.LivenessStatus status)` | Asienta el resultado del webhook user.liveness_completed. Es la unica fuente de verdad del resultado: si no se proyecta aqui, el dato no se recupera por GET. Kira reintenta la entrega 4 veces y despues lo da por perdido. |

<sub>`application/tenant/UboCommands.java` · 78 líneas</sub>

#### `UboCommands` · clase

| Método | Descripción |
|---|---|
| `public PostalAddress toDomain()` |  |
#### `SaveUbo` · record

Alta o edicion de un beneficiario final.

hasOwnership es obligatorio y explicito: el cargo no identifica al beneficiario, y
si se omite, Kira bloquea el KYB pidiendo "associated_persons:has_ownership" sin mas
pistas. pepStatus y countryOfBirth son igual de obligatorios para Kira.

#### `ResidentialAddress` · record

Direccion de residencia. Pais en ISO-3, como el resto de datos de persona en Kira.

| Componente |
|---|
| `String streetName` |
| `String city` |
| `String state` |
| `String postalCode` |
| `String country` |

| Método | Descripción |
|---|---|
| `public PostalAddress toDomain()` |  |

#### `RequestLivenessLinks` · record

biometricConsent: el operador declara que cada persona consintio el tratamiento biometrico
antes de recibir su enlace (arquitectura §7). Sin esa declaracion no se piden enlaces.

| Componente |
|---|
| `String successUrl` |
| `String rejectUrl` |
| `Boolean biometricConsent` |

<sub>`application/tenant/UboView.java` · 93 líneas</sub>

#### `UboView` · record

| Componente |
|---|
| `String id` |
| `String personReferenceId` |
| `String fullName` |
| `String firstName` |
| `String lastName` |
| `String email` |
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
| `LocalDate birthDate` |
| `String nationality` |
| `String occupation` |
| `String gender` |
| `String phoneNumber` |
| `String documentCountry` |
| `Address address` |
| `boolean knownToKira` |
| `String livenessStatus` |
| `String livenessLink` |
| `Instant livenessExpiresAt` |

| Método | Descripción |
|---|---|
| `public static UboView from(Ubo u)` |  |
| `public static Roster from(UboRoster roster)` |  |
#### `Address` · record

Direccion de residencia tal como la ve el portal (pais ISO-3).

| Componente |
|---|
| `String streetName` |
| `String city` |
| `String state` |
| `String postalCode` |
| `String country` |

#### `Roster` · record

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

<sub>`application/account/DepositView.java` · 62 líneas</sub>

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
| `boolean held` |
| `Instant createdAt` |
| `Instant updatedAt` |

| Método | Descripción |
|---|---|
| `public static DepositView from(Deposit d)` |  |

<sub>`application/account/KiraDepositEvent.java` · 106 líneas</sub>

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

<sub>`application/account/OpenVirtualAccountService.java` · 288 líneas</sub>

#### `OpenVirtualAccountService` · clase · `@Service`

Apertura y seguimiento de cuentas virtuales.

Abrir una cuenta exige que el KYB este VERIFIED Y que el producto este elegible: son dos
condiciones, no una. Y una vez abierta, 'aprobada' no significa que pueda mover fondos:
eso lo dice un numero de cuenta real o el evento virtual_account.activated.

| Método | Descripción |
|---|---|
| `public OpenVirtualAccountService(VirtualAccountRepository accounts, TenantRepository tenants, KiraApiClient kira, KiraProperties properties, AuditTrail audit, IdempotencyKeyStore idempotencyKeys)` |  |
| `public List<VirtualAccountView> list(AuthenticatedOperator operator)` |  |
| `public VirtualAccountView get(AuthenticatedOperator operator, String accountId)` |  |
| `public VirtualAccountView open(AuthenticatedOperator operator, VirtualAccountCommands.OpenAccount command)` |  |
| `public VirtualAccountView refresh(AuthenticatedOperator operator, String accountId)` | El recurso es la autoridad. Tambien cubre el hueco de un evento de activacion perdido. |
| `public boolean reconcile(VirtualAccount account)` | Relee la cuenta sin operador, para el worker de reconciliacion: failed y deactivated no tienen webhook propio y frozen tampoco, asi que solo se ven consultando el recurso. Devuelve true si cambio el estado o la disponibilidad de fondos. |
| `public VirtualAccountView refreshBalance(AuthenticatedOperator operator, String accountId)` | Refresca el saldo. Durante la activacion, GET /balance puede responder 400: eso no es un fallo sino "todavia calculando", y se devuelve el ultimo saldo conocido en lugar de un error. |
| `public VirtualAccountView simulateDeposit(AuthenticatedOperator operator, String accountId, VirtualAccountCommands.SimulateDeposit command)` | Simulacion de deposito. Solo existe en el sandbox; en produccion Kira responde 403. |

<sub>`application/account/RecordDepositService.java` · 178 líneas</sub>

#### `RecordDepositService` · clase · `@Service`

Depositos entrantes.

El espejo local no es una comodidad: en el sandbox un deposito entrante NO aparece en
GET /deposits, asi que el webhook es la unica constancia que va a existir de que ese
dinero llego.

| Método | Descripción |
|---|---|
| `public RecordDepositService(DepositRepository deposits, VirtualAccountRepository accounts, KiraApiClient kira)` |  |
| `public List<DepositView> syncFromKira(AuthenticatedOperator operator, String accountId)` | Trae de Kira los depositos de una cuenta y los asienta con la misma proyeccion que los webhooks, asi que converge en las mismas filas. Es la red de seguridad de un evento perdido (entrega unica, sin reintentos). En el sandbox Kira no devuelve nada aqui. |
| `public List<DepositView> list(AuthenticatedOperator operator, int limit)` |  |
| `public List<DepositView> listByAccount(AuthenticatedOperator operator, String accountId, int limit)` |  |
| `public void apply(KiraDepositEvent event)` | Proyecta un evento de deposito. Es idempotente por kira_deposit_id: la familia tiene seis eventos que describen el mismo deposito en distintos momentos, y todos deben converger en una sola fila. |
| `public List<DepositView> listForTenant(TenantId tenantId, int limit)` | Solo para lecturas internas del reconciliador. |

<sub>`application/account/VirtualAccountCommands.java` · 34 líneas</sub>

#### `VirtualAccountCommands` · clase
#### `OpenAccount` · record

Apertura de cuenta virtual.

El banco no se pide: lo fija la configuracion del entorno, porque el valor valido
depende de si se apunta al sandbox o a produccion y equivocarlo devuelve
400 "Invalid bank".

| Componente |
|---|
| `String description` |
| `String mode` |
| `String currency` |

#### `SimulateDeposit` · record

Solo sandbox. En produccion, Kira responde 403.

| Componente |
|---|
| `BigDecimal amount` |
| `String paymentType` |

<sub>`application/account/VirtualAccountView.java` · 54 líneas</sub>

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

<sub>`application/treasury/CreateQuoteService.java` · 209 líneas</sub>

#### `CreateQuoteService` · clase · `@Service`

Cotizacion de una transferencia contra POST /v1/quotations.

Se cotiza en modo redimible (con virtual_account_id) y con inverse=true: el importe que
teclea el operador es lo que recibe el destinatario, y las comisiones se suman por
encima. El modo preview (quote_for) devuelve quote_id nulo y no sirve para pagar.

| Método | Descripción |
|---|---|
| `public CreateQuoteService(QuotationRepository quotations, RecipientRepository recipients, VirtualAccountRepository accounts, TenantRepository tenants, KiraApiClient kira, AuditTrail audit)` |  |
| `public List<QuotationView> list(AuthenticatedOperator operator, int limit)` |  |
| `public QuotationView get(AuthenticatedOperator operator, String quotationId)` |  |
| `public QuotationView create(AuthenticatedOperator operator, QuotationCommands.CreateQuote command)` |  |
| `public Quotation requote(AuthenticatedOperator operator, Quotation expired, BigDecimal amount)` | Cotizacion nueva para un pago pendiente cuya cotizacion vencio (D9). La llama ExecutePayoutService, que ya comprobo que el operador puede preparar o aprobar pagos. |

<sub>`application/treasury/ExecutePayoutService.java` · 521 líneas</sub>

#### `ExecutePayoutService` · clase · `@Service`

Preparacion, aprobacion y ejecucion de pagos.

El maker-checker vive aqui porque la API de Kira no lo ofrece a los integradores:
el pago solo sale hacia Kira despues de que un segundo operador lo autoriza.

| Método | Descripción |
|---|---|
| `public ExecutePayoutService(PayoutRepository payouts, QuotationRepository quotations, VirtualAccountRepository accounts, RecipientRepository recipients, TenantRepository tenants, RfiRepository rfis, KiraApiClient kira, AuditTrail audit, ObjectMapper objectMapper, PayoutApprovalPolicy approvalPolicy, CreateQuoteService quotes)` |  |
| `public PayoutPreviewView preview(AuthenticatedOperator operator, PayoutCommands.PreviewPayout command)` | Vista previa de comisiones contra POST /v1/virtual-accounts/{id}/payout/preview. No reserva precio ni crea nada: sirve para mostrar el coste mientras el operador teclea. El margen de la plataforma viaja igual que en el pago sin cotizacion, para que lo que se muestra aqui sea lo que se cobraria. |
| `public List<PayoutEventView> events(AuthenticatedOperator operator, String payoutId)` | Linea de tiempo del pago (events[] de GET /v1/payouts/{id}). Vacia si aun no se envio. |
| `public KiraPayoutPage kiraHistory(AuthenticatedOperator operator, String status, int page, int limit, String fromDate, String toDate)` | Historial de pagos de la empresa en Kira (GET /v1/payouts?user_id=...). Kira trata los pagos como globales del integrador: ademas del filtro user_id, se descarta cualquier fila de otro user. Solo se envian los filtros que Kira documenta, porque un parametro desconocido es un 400. |
| `public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command)` |  |
| `public PayoutView create(AuthenticatedOperator operator, PayoutCommands.CreatePayout command, String clientIdempotencyKey)` | Con la clave del portal, repetir la peticion devuelve el pago ya creado en lugar de crear otro (G-07). La clave es la misma que viaja despues a Kira al aprobar. |
| `public PayoutView approveAndSubmit(AuthenticatedOperator operator, String payoutId, PayoutCommands.ApprovePayout command)` |  |
| `public PayoutView requote(AuthenticatedOperator operator, String payoutId)` | D9: la cotizacion vence a los 15 min y la aprobacion puede llegar mas tarde. Se pide una nueva a Kira con la misma cuenta, destinatario, riel e importe, y el pago muestra el precio nuevo antes de aprobarlo (arquitectura §7). Lo pueden pedir quien prepara y quien aprueba. |
| `public PayoutView reject(AuthenticatedOperator operator, String payoutId, String reason)` |  |
| `public PayoutView refreshFromKira(AuthenticatedOperator operator, String payoutId)` | Reconciliacion puntual: los eventos llegan una sola vez, el recurso es la autoridad final. |
| `public List<PayoutView> list(AuthenticatedOperator operator, int limit)` |  |
| `public PayoutView get(AuthenticatedOperator operator, String payoutId)` |  |

<sub>`application/treasury/KiraPayoutPage.java` · 31 líneas</sub>

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
#### `Item` · record

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

<sub>`application/treasury/KiraQuoteResponse.java` · 99 líneas</sub>

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

<sub>`application/treasury/KiraRecipientView.java` · 19 líneas</sub>

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

<sub>`application/treasury/PayoutApprovalPolicy.java` · 32 líneas</sub>

#### `PayoutApprovalPolicy` · record · `@ConfigurationProperties(prefix = "bff.payouts.approval")`

Limites internos de aprobacion (arquitectura §5 y §8), fijos en configuracion por decision del
15-sep. A partir del umbral un pago necesita dos aprobadores distintos, ninguno el que lo creo.

| Componente |
|---|
| `BigDecimal dualApprovalThreshold` |
| `Map<String, BigDecimal> tenantThresholds` |

| Método | Descripción |
|---|---|
| `public BigDecimal thresholdFor(TenantId tenantId)` |  |
| `public int requiredApprovals(TenantId tenantId, Money amount)` |  |

<sub>`application/treasury/PayoutCommands.java` · 56 líneas</sub>

#### `PayoutCommands` · clase
#### `CreatePayout` · record

Ids del portal (los de /api/virtual-accounts y /api/recipients), no los de Kira.

| Componente |
|---|
| `String virtualAccountId` |
| `String recipientId` |
| `BigDecimal amount` |
| `String currency` |
| `String quotationId` |

#### `PreviewPayout` · record

Vista previa de comisiones. Por defecto 'amount' es lo que RECIBE el destinatario, igual
que al cotizar; con recipientReceivesAmount=false es lo que sale de la cuenta.

| Componente |
|---|
| `String virtualAccountId` |
| `String recipientId` |
| `BigDecimal amount` |
| `Boolean recipientReceivesAmount` |

#### `ApprovePayout` · record

Datos que solo se conocen al autorizar.

memo es obligatorio para WIRE en algunos bancos corresponsales y viaja en extra_info;
los documentos de soporte van como data URI base64, maximo dos y 3 MB cada uno.

| Componente |
|---|
| `String comment` |
| `String natureOfPayment` |
| `String memo` |
| `List<SupportingDocument> documents` |

#### `RejectPayout` · record

| Componente |
|---|
| `String reason` |

<sub>`application/treasury/PayoutEventView.java` · 5 líneas</sub>

#### `PayoutEventView` · record

Un paso de la linea de tiempo de un pago (events[] de GET /v1/payouts/{id}).

| Componente |
|---|
| `String eventId` |
| `String status` |
| `String message` |
| `String createdAt` |

<sub>`application/treasury/PayoutPreviewView.java` · 17 líneas</sub>

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

<sub>`application/treasury/PayoutView.java` · 73 líneas</sub>

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
| `String recipientName` |
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
| `String makerName` |
| `String approverUserId` |
| `String approverName` |
| `String firstApproverUserId` |
| `String firstApproverName` |
| `int requiredApprovals` |
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
| `public static PayoutView from(Payout p, String blockedByRfiId, int requiredApprovals)` | Sin directorio a mano (consola de plataforma): los ids viajan, los nombres no. |
| `public static PayoutView from(Payout p, String blockedByRfiId, int requiredApprovals, String recipientName, String makerName, String approverName, String firstApproverName)` |  |

<sub>`application/treasury/QuotationCommands.java` · 30 líneas</sub>

#### `QuotationCommands` · clase
#### `CreateQuote` · record

Peticion de cotizacion.

'amount' es lo que RECIBE el destinatario: las comisiones se suman por encima y el
debito de la cuenta virtual es mayor. El desglose vuelve en la respuesta.

'rail' es opcional: por defecto se deriva del destinatario, que es la unica fuente
valida. Enviarlo sirve para elegir entre ACH_STANDARD y ACH_SAME_DAY.

| Componente |
|---|
| `String virtualAccountId` |
| `String recipientId` |
| `BigDecimal amount` |
| `String rail` |
| `String targetCurrency` |

<sub>`application/treasury/QuotationView.java` · 55 líneas</sub>

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

<sub>`application/treasury/RecipientCommands.java` · 62 líneas</sub>

#### `RecipientCommands` · clase
#### `Address` · record

Direccion postal. El pais del destinatario va en ISO-2 ("US"), no en ISO-3.

| Componente |
|---|
| `String streetName` |
| `String city` |
| `String state` |
| `String postalCode` |
| `String country` |

#### `RegisterRecipient` · record

Alta de un destinatario. El bloque que se rellena depende del riel:
ACH y WIRE llevan datos bancarios, WALLET lleva token, red y direccion.

Un destinatario = un riel. Enviar campos de dos rieles a la vez se rechaza.

#### `ArchiveRecipient` · record

Motivo del archivado. Kira no borra: se archiva local y se crea un reemplazo.

| Componente |
|---|
| `String replacedByRecipientId` |

<sub>`application/treasury/RecipientView.java` · 59 líneas</sub>

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

<sub>`application/treasury/RegisterRecipientService.java` · 326 líneas</sub>

#### `RegisterRecipientService` · clase · `@Service`

Alta de destinatarios contra POST /v1/recipients.

Un destinatario = un riel, y ese riel es el que va a usar cada pago suyo. Kira no ofrece
actualizacion ni borrado: corregir uno significa dar de alta un reemplazo y archivar el
anterior, asi que aqui no hay un metodo 'update'.

| Método | Descripción |
|---|---|
| `public RegisterRecipientService(RecipientRepository recipients, TenantRepository tenants, KiraApiClient kira, AuditTrail audit)` |  |
| `public List<RecipientView> list(AuthenticatedOperator operator)` |  |
| `public RecipientView get(AuthenticatedOperator operator, String recipientId)` |  |
| `public List<KiraRecipientView> listInKira(AuthenticatedOperator operator)` | Destinatarios de la empresa en Kira (GET /v1/recipients?user_id=...). Kira no pagina esta lista. Se descarta cualquier destinatario que no pueda confirmarse como de esta empresa. |
| `public KiraRecipientView getInKira(AuthenticatedOperator operator, String recipientId)` | Un destinatario del directorio, leido de Kira. |
| `public RecipientView register(AuthenticatedOperator operator, RecipientCommands.RegisterRecipient command)` |  |
| `public RecipientView register(AuthenticatedOperator operator, RecipientCommands.RegisterRecipient command, String clientIdempotencyKey)` | Con la clave del portal, un reintento devuelve el mismo destinatario (G-07). |
| `public RecipientView archive(AuthenticatedOperator operator, String recipientId, RecipientCommands.ArchiveRecipient command)` | Archiva un destinatario. Es un reemplazo logico: Kira no borra, asi que el registro remoto sigue existiendo y lo que cambia es que aqui deja de ofrecerse para pagos. |

### A.11 Aplicación — compliance

<sub>`application/compliance/AnswerRfiService.java` · 682 líneas</sub>

#### `AnswerRfiService` · clase · `@Service`

Solicitudes de informacion (RFI) de Kira: bandeja, sincronizacion y respuesta.

Nunca se crea un RFI desde aqui. Kira lo genera, casi siempre para detener una
transferencia o un KYB en revision, y su reloj (due_at) no se prorroga: al vencer cierra
en not_resolved y lo bloqueado sigue bloqueado. Por eso la bandeja importa.

Kira trata los RFIs como globales del integrador; el aislamiento por organizacion lo
imponemos aqui atribuyendo cada RFI a su empresa por user_id.

| Método | Descripción |
|---|---|
| `public AnswerRfiService(RfiRepository rfis, TenantRepository tenants, PayoutRepository payouts, DepositRepository deposits, KiraApiClient kira, AuditTrail audit, ObjectMapper objectMapper)` |  |
| `public List<RfiView> list(AuthenticatedOperator operator, boolean onlyOpen)` |  |
| `public RfiView get(AuthenticatedOperator operator, String rfiId)` |  |
| `public List<RfiView> sync(AuthenticatedOperator operator)` | Trae de Kira los RFIs de la empresa y los asienta. Es la red de seguridad del webhook: rfi.* exige suscripcion explicita en Kira y, como todo webhook, se entrega una sola vez. |
| `public int syncForTenant(TenantId tenantId)` | Lo mismo, sin operador: lo usa el worker de reconciliacion, que no actua en nombre de nadie. Devuelve cuantos RFIs quedaron asentados. |
| `public RfiView refresh(AuthenticatedOperator operator, String rfiId)` |  |
| `public RfiUboLink mintUboLink(AuthenticatedOperator operator, String rfiId, String itemId)` | Enlace de verificacion de un beneficiario para un item ubo_link sin url ya hecha. No se persiste: caduca en una hora y es una credencial de esa persona. Un 409 asienta el cierre del RFI; un 404 que fue retirado. |
| `public RfiView answer(AuthenticatedOperator operator, String rfiId, RfiCommands.AnswerItems command)` | Responde items de texto con PATCH /v1/rfis/{id}/items. Se valida todo antes de llamar, y lo que Kira rechace se devuelve por item_id. Tras un PATCH aceptado se relee el RFI: el estado del RFI (answered o sigue pending, si la respuesta fue parcial) lo decide Kira, no la respuesta del PATCH. noRollbackFor: ante un 409 se asienta el cierre antes de avisar al operador. |
| `public RfiView uploadDocuments(AuthenticatedOperator operator, String rfiId, String itemId, List<RfiCommands.UploadedFile> files)` | Sube archivos a un item de tipo documento (POST /v1/rfis/{id}/items/{item}/documents). Se valida contra los limites de Kira y el answer_spec del item antes de enviar nada: un archivo rechazado por Kira significa haber subido hasta 20 x 30 MB para nada. |
| `public RfiView removeDocument(AuthenticatedOperator operator, String rfiId, String itemId, String documentId)` | Elimina un archivo. Kira no deja borrar el ultimo de un item ya respondido. |
| `public RfiDocumentLink documentLink(AuthenticatedOperator operator, String rfiId, String itemId, String documentId)` | Enlace temporal de descarga. La URL no se registra: es una credencial al portador. Si se registra quién la pidió (arquitectura §7: descargas sensibles auditadas). |
| `public void applyWebhook(String kiraRfiId, String rawStatus)` | Proyeccion de la familia rfi.* de webhooks. Sin @Transactional a proposito: corre dentro de la transaccion del procesador de webhooks, y un fallo al consultar Kira debe quedar como processing_error del evento, no marcar la transaccion entera para rollback y perder la fila del evento. |

<sub>`application/compliance/RfiAnswerRejectedException.java` · 24 líneas</sub>

#### `RfiAnswerRejectedException` · clase

Una o varias respuestas de un RFI no son validas. El error va POR item_id: el PATCH de
Kira es all-or-nothing, asi que ningun item se guardo y el portal no debe marcar ninguno
como respondido.

| Método | Descripción |
|---|---|
| `public RfiAnswerRejectedException(String message, Map<String, String> itemErrors)` |  |
| `public Map<String, String> itemErrors()` |  |

<sub>`application/compliance/RfiCommands.java` · 34 líneas</sub>

#### `RfiCommands` · clase
#### `AnswerItems` · record

Respuesta a uno o varios items. Responder un subconjunto es valido.

Un item 'document' se responde subiendo archivos y nunca lleva answer_value, asi que
aqui se rechaza antes de llamar a Kira.

| Componente |
|---|
| `List<ItemAnswer> items` |

#### `ItemAnswer` · record

answerValue es texto, numero o booleano segun el answer_type del item (text_short,
number, boolean, choice, date, identifier...). Kira lo valida contra el answer_spec.

| Componente |
|---|
| `String itemId` |
| `Object answerValue` |

#### `UploadedFile` · record

Archivo recibido del portal para un item de tipo documento.

| Componente |
|---|
| `String fileName` |
| `String contentType` |
| `byte[] content` |

<sub>`application/compliance/RfiDocumentLink.java` · 12 líneas</sub>

#### `RfiDocumentLink` · record

Enlace temporal de descarga de un archivo de un RFI.

La URL es una credencial al portador que caduca en minutos: el portal la abre al momento y
pide otra si caduca. No se guarda ni se registra en ningun log.

| Componente |
|---|
| `String downloadUrl` |
| `Instant expiresAt` |

<sub>`application/compliance/RfiUboLink.java` · 12 líneas</sub>

#### `RfiUboLink` · record

Enlace de verificacion de identidad de un beneficiario pedido por un RFI (item ubo_link).

Caduca en torno a una hora y es de un solo uso para esa persona: se acuna cuando la persona
pulsa, no se guarda ni se registra en ningun log.

| Componente |
|---|
| `String url` |
| `Instant expiresAt` |

<sub>`application/compliance/RfiView.java` · 73 líneas</sub>

#### `RfiView` · record

RFI para la bandeja y el formulario de respuesta.

Los items van tal como los entrega Kira: el formulario se genera desde answer_spec y
cada tipo nuevo de requerimiento debe poder mostrarse sin desplegar el BFF.

| Componente |
|---|
| `String id` |
| `String kiraRfiId` |
| `String status` |
| `String resolutionReason` |
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
| `public static RfiView from(Rfi rfi, List<Map<String, Object>> items, Payout blockedPayout, Deposit blockedDeposit)` |  |
#### `Blocking` · record

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

### A.12 Aplicación — platform

<sub>`application/platform/PlatformConsoleService.java` · 224 líneas</sub>

#### `PlatformConsoleService` · clase · `@Service`

Consola de operaciones y cumplimiento de AU (arquitectura §2.2): todas las organizaciones, de
solo lectura, mas "Actualizar desde el proveedor". Cada consulta a una ficha queda en la
bitacora: ver datos de otra empresa es un acceso que tiene que poder rendirse cuentas.

| Método | Descripción |
|---|---|
| `public PlatformConsoleService(TenantRepository tenants, UboRepository ubos, VirtualAccountRepository accounts, PayoutRepository payouts, DepositRepository deposits, RfiRepository rfis, SubmitOnboardingService onboarding, OpenVirtualAccountService accountService, AuditTrail audit, PayoutApprovalPolicy approvalPolicy)` |  |
| `public List<TenantSummary> tenants(AuthenticatedOperator operator)` |  |
| `public Tenant360 tenant(AuthenticatedOperator operator, String tenantId)` |  |
| `public Tenant360 refresh(AuthenticatedOperator operator, String tenantId)` | Relee en Kira la empresa y sus cuentas: el mismo trabajo que los workers, a demanda. |
| `public List<ReviewItem> reviewQueue(AuthenticatedOperator operator)` | Lo que pide atencion humana en todas las organizaciones, lo mas grave primero. |
#### `TenantSummary` · record

| Componente |
|---|
| `String id` |
| `String name` |
| `String kiraUserId` |
| `String status` |
| `boolean verificationTriggered` |
| `boolean readyForVirtualAccounts` |
| `int pendingFields` |
| `String rejectionReason` |
| `int beneficialOwners` |
| `int virtualAccounts` |
| `int openRfis` |
| `int overdueRfis` |
| `int heldPayouts` |
| `Instant createdAt` |
| `Instant updatedAt` |

#### `RfiSummary` · record

| Componente |
|---|
| `String id` |
| `String kiraRfiId` |
| `String status` |
| `String resolutionReason` |
| `Instant dueDate` |
| `boolean overdue` |
| `String blockingType` |

#### `Tenant360` · record

| Componente |
|---|
| `TenantSummary summary` |
| `OnboardingView onboarding` |
| `UboView.Roster beneficialOwners` |
| `List<VirtualAccountView> accounts` |
| `List<PayoutView> payouts` |
| `List<DepositView> deposits` |
| `List<RfiSummary> rfis` |

#### `ReviewItem` · record

| Componente |
|---|
| `String tenantId` |
| `String tenantName` |
| `String kind` |
| `String severity` |
| `String title` |
| `String detail` |
| `Instant since` |

### A.13 Aplicación — notification

<sub>`application/notification/Notification.java` · 24 líneas</sub>

#### `Notification` · record

Aviso de negocio para las personas de una organizacion (arquitectura §2.6).

Nace de un evento de Kira ya proyectado: el aviso dice que algo cambio, pero lo que manda es
el recurso, que la pantalla vuelve a leer. No lleva datos personales ni importes completos.

| Componente |
|---|
| `String id` |
| `TenantId tenantId` |
| `String kind` |
| `String severity` |
| `String title` |
| `String message` |
| `String resourceType` |
| `String resourceId` |
| `Instant createdAt` |

<sub>`application/notification/NotificationRepository.java` · 20 líneas</sub>

#### `NotificationRepository` · interfaz

<sub>`application/notification/NotificationService.java` · 72 líneas</sub>

#### `NotificationService` · clase · `@Service`

Centro de avisos dentro de la aplicacion. Los leidos se calculan por usuario con una marca de
"visto hasta": marcar todo como leido es mover esa marca, no tocar cada aviso.

| Método | Descripción |
|---|---|
| `public NotificationService(NotificationRepository notifications)` |  |
| `public void notify(TenantId tenantId, String kind, String severity, String title, String message, String resourceType, String resourceId)` |  |
| `public NotificationFeed feed(AuthenticatedOperator operator, int limit)` |  |
| `public long unreadCount(AuthenticatedOperator operator)` |  |
| `public void markAllRead(AuthenticatedOperator operator)` |  |
#### `NotificationView` · record

| Componente |
|---|
| `String id` |
| `String kind` |
| `String severity` |
| `String title` |
| `String message` |
| `String resourceType` |
| `String resourceId` |
| `Instant createdAt` |
| `boolean unread` |

#### `NotificationFeed` · record

| Componente |
|---|
| `List<NotificationView> items` |
| `long unread` |

### A.14 Aplicación — audit

<sub>`application/audit/AuditQueryService.java` · 81 líneas</sub>

#### `AuditQueryService` · clase · `@Service`

Lectura de la trazabilidad propia (arquitectura §2.2 y §2.6): la bitacora de acciones y el
centro de eventos de Kira. Siempre filtrado por la organizacion del operador.

| Método | Descripción |
|---|---|
| `public AuditQueryService(AuditLogRepository audit, OperatorUserRepository users, WebhookEventJpaRepository events)` |  |
| `public List<AuditEntryView> auditTrail(AuthenticatedOperator operator, int limit)` |  |
| `public List<EventView> events(AuthenticatedOperator operator, int limit)` | Sin payload: puede traer datos personales y aqui solo interesa que paso y si se proceso. |
#### `AuditEntryView` · record

| Componente |
|---|
| `String id` |
| `String action` |
| `String resourceType` |
| `String resourceId` |
| `String actorName` |
| `String actorEmail` |
| `String actorRole` |
| `String detail` |
| `Instant createdAt` |

#### `EventView` · record

| Componente |
|---|
| `String eventId` |
| `String eventType` |
| `String resourceId` |
| `String status` |
| `boolean processed` |
| `String processingError` |
| `int retryCount` |
| `Instant receivedAt` |
| `Instant processedAt` |

### A.15 Aplicación — reference

<sub>`application/reference/CountryView.java` · 14 líneas</sub>

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
#### `Subdivision` · record

| Componente |
|---|
| `String name` |
| `String code` |

<sub>`application/reference/ReferenceCatalogService.java` · 56 líneas</sub>

#### `ReferenceCatalogService` · clase · `@Service`

Catalogos de referencia de Kira.

El de paises es estable (sin cambios desde 2025-01-01) y lo usan todos los formularios de
direccion: se cachea 24 h para no gastar una llamada autenticada cada vez que se pinta uno.
Un fallo no se cachea: la siguiente peticion vuelve a intentarlo.

| Método | Descripción |
|---|---|
| `public ReferenceCatalogService(KiraApiClient kira)` |  |
| `public List<CountryView> countries()` |  |

### A.16 Aplicación — shared

<sub>`application/shared/IdempotencyKeyStore.java` · 46 líneas</sub>

#### `IdempotencyKeyStore` · clase · `@Service`

Consolida en base de datos la clave de idempotencia ANTES de llamar a Kira.

El caso de uso que abre la empresa o la cuenta corre dentro de una transaccion y relanza la
excepcion si Kira falla: ese rollback tambien deshacia el guardado de la clave, justo lo que
no debe perderse. Si Kira llego a crear el recurso y la respuesta se perdio, el reintento
tiene que viajar con LA MISMA clave o se crea un duplicado.

Por eso cada metodo abre su propia transaccion (REQUIRES_NEW) y confirma antes de volver:
pase lo que pase despues, la clave ya esta en MySQL. Vive en una clase aparte a proposito,
porque una llamada interna al propio servicio no pasa por el proxy de Spring y la propagacion
no se aplicaria.

| Método | Descripción |
|---|---|
| `public IdempotencyKeyStore(TenantRepository tenants, VirtualAccountRepository accounts)` |  |
| `public void persistNow(Tenant tenant)` | Guarda la empresa con su clave de alta reservada, en una transaccion propia. |
| `public void persistNow(VirtualAccount account)` | Guarda la cuenta virtual con su clave de apertura reservada, en una transaccion propia. |

### A.17 Aplicación — webhook

<sub>`application/webhook/KiraWebhookEnvelope.java` · 39 líneas</sub>

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

<sub>`application/webhook/ProcessWebhookUseCase.java` · 496 líneas</sub>

#### `ProcessWebhookUseCase` · clase · `@Service`

Procesamiento asincrono de los eventos de Kira.

Dos pasos: record() guarda el evento dentro de la peticion de Kira (si falla, 5xx y Kira
reintenta) y projectLater() lo proyecta despues de responder. La idempotencia se apoya en la
unicidad de data.event_id en base de datos.

| Método | Descripción |
|---|---|
| `public ProcessWebhookUseCase(WebhookEventJpaRepository events, PayoutRepository payouts, TenantRepository tenants, VirtualAccountRepository accounts, RecordDepositService deposits, SyncUbosService ubos, AnswerRfiService rfis, ObjectMapper objectMapper, NotificationService notifications)` |  |
| `public Optional<String> record(String rawPayload)` | Paso 1, dentro de la peticion de Kira: guarda el evento y confirma la escritura. Se hace ANTES de responder 2xx (webhooks/best-practices: "write the event to your own queue or table, answer 2xx, process from there"). Si la base falla, la excepcion llega al controlador como 5xx y Kira reintenta (1, 5, 15 y 60 min). Devuelve el id de la fila, o vacio si el evento ya estaba registrado. |
| `public void projectLater(String storedId)` | Paso 2, despues de responder: proyecta el evento ya guardado. Nunca propaga: Kira ya tiene su 2xx. Si falla, la fila queda con processed = false y processing_error, y la recoge WebhookReprojectionWorker. |
| `public void process(String rawPayload) throws Exception` | Guardar y proyectar en el mismo hilo. Lo usan las pruebas y quien no pasa por HTTP. |
| `public void reproject(WebhookEventEntity stored) throws Exception` | Reintenta la proyeccion de un evento ya almacenado que nunca se proyecto. No se puede reutilizar #process(String): ese metodo empieza deduplicando por event_id y, como la fila ya existe, saldria sin proyectar nada, que es justo lo contrario de lo que se busca aqui. Deja que la excepcion suba: quien llama decide si la fila queda marcada con el error o si el lote entero se corta (por ejemplo, cuando faltan las credenciales de Kira). |

### A.18 Infraestructura — security

<sub>`infrastructure/security/AuthenticatedOperator.java` · 8 líneas</sub>

#### `AuthenticatedOperator` · record

Principal autenticado. Se expone como principal de Spring Security.

| Componente |
|---|
| `String userId` |
| `String email` |
| `TenantId tenantId` |
| `Role role` |

<sub>`infrastructure/security/BffSecurityProperties.java` · 19 líneas</sub>

#### `BffSecurityProperties` · record · `@ConfigurationProperties(prefix = "bff.security")`

| Componente |
|---|
| `String jwtSecret` |
| `String jwtIssuer` |
| `long tokenExpirationMs` |
| `String mfaEncryptionKey` |
| `boolean mfaEnforced` |
| `long mfaChallengeTtlMs` |
| `String mfaIssuer` |

<sub>`infrastructure/security/JwtService.java` · 97 líneas</sub>

#### `JwtService` · clase · `@Service`

Emite y verifica el JWT propio del BFF. Nada de esto viaja a Kira.

| Método | Descripción |
|---|---|
| `public JwtService(BffSecurityProperties properties)` |  |
| `public String issue(OperatorUser user)` |  |
| `public String issueMfaChallenge(OperatorUser user)` | Reto entre la contrasena y el codigo TOTP. No autentica ninguna ruta de negocio. |
| `public long mfaChallengeExpiresInSeconds()` |  |
| `public MfaChallenge verifyMfaChallenge(String token)` | Devuelve el id del usuario y el identificador del reto. Lanza si no es un reto valido. |
| `public long expiresInSeconds()` |  |
| `public AuthenticatedOperator verify(String token)` | Lanza JWTVerificationException si la firma, el emisor o la vigencia no cuadran. |
#### `MfaChallenge` · record

| Componente |
|---|
| `String userId` |
| `String challengeId` |

<sub>`infrastructure/security/JwtTenantFilter.java` · 71 líneas</sub>

#### `JwtTenantFilter` · clase · `@Component`

Autentica la peticion con el JWT propio del BFF y fija la organizacion en el contexto del hilo.
Un token invalido no autentica y no fija tenant: la peticion sigue como anonima y es
la cadena de autorizacion la que decide si el recurso exige sesion.

| Método | Descripción |
|---|---|
| `public JwtTenantFilter(JwtService jwtService)` |  |
| `protected boolean shouldNotFilter(HttpServletRequest request)` |  |
| `protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException` |  |

<sub>`infrastructure/security/MfaSecretCipher.java` · 77 líneas</sub>

#### `MfaSecretCipher` · clase · `@Component`

Cifra el secreto TOTP en reposo (AES-256-GCM). Con la base de datos sola no se pueden generar
codigos: hace falta tambien BFF_MFA_ENCRYPTION_KEY, que vive en el gestor de secretos.

Formato: base64(iv de 12 bytes || texto cifrado con etiqueta).

| Método | Descripción |
|---|---|
| `public MfaSecretCipher(BffSecurityProperties properties)` |  |
| `public String encrypt(String plain)` |  |
| `public String decrypt(String stored)` |  |

<sub>`infrastructure/security/SecurityConfig.java` · 50 líneas</sub>

#### `SecurityConfig` · clase · `@Configuration` `@EnableMethodSecurity` `@EnableConfigurationProperties(BffSecurityProperties.class)`

| Método | Descripción |
|---|---|
| `public SecurityFilterChain filterChain(HttpSecurity http, JwtTenantFilter jwtTenantFilter, UnauthorizedEntryPoint unauthorizedEntryPoint) throws Exception` |  |
| `public PasswordEncoder passwordEncoder()` |  |

<sub>`infrastructure/security/TenantContext.java` · 37 líneas</sub>

#### `TenantContext` · clase

Contexto de la organizacion activa para el hilo que atiende la peticion.
Regla de oro: siempre limpiar en un finally, o el siguiente request reutiliza el hilo
del pool y hereda el tenant equivocado.

| Método | Descripción |
|---|---|
| `public static void set(TenantId tenantId)` |  |
| `public static TenantId get()` |  |
| `public static TenantId require()` |  |
| `public static void clear()` |  |

<sub>`infrastructure/security/Totp.java` · 119 líneas</sub>

#### `Totp` · clase

Codigos de un solo uso por tiempo (RFC 6238): HMAC-SHA1, pasos de 30 s y 6 digitos, lo que
entienden Google Authenticator, Microsoft Authenticator y compania.

Sin dependencias: el algoritmo es corto y una libreria para esto es superficie de mas.

| Método | Descripción |
|---|---|
| `public static String newSecret()` | 160 bits, el tamano que recomienda la RFC 4226 para SHA-1, en base32 sin relleno. |
| `public static String otpauthUri(String issuer, String account, String secret)` |  |
| `public static OptionalLong verify(String secret, String code, Instant now)` | Devuelve el paso de tiempo que valida el codigo, para que quien llama pueda rechazar su reutilizacion dentro de la misma ventana. Vacio si el codigo no vale. |

<sub>`infrastructure/security/UnauthorizedEntryPoint.java` · 35 líneas</sub>

#### `UnauthorizedEntryPoint` · clase · `@Component`

Respuesta a una peticion sin sesion (F7).

Sin esto, Spring Security devolvia un 403 con cuerpo vacio cuando faltaba la cabecera
Authorization, y el portal tenia que adivinar por el hueco: un 403 vacio significaba
"no hay sesion" y un 403 con cuerpo, "rol sin permiso". Ahora la falta de credencial es
401 con el mismo codigo `unauthorized` que ya emite JwtTenantFilter para un token invalido,
y el 403 queda solo para lo que de verdad es falta de permiso.

| Método | Descripción |
|---|---|
| `public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException` |  |

### A.19 Infraestructura — kira

<sub>`infrastructure/kira/KiraAmounts.java` · 106 líneas</sub>

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

| Método | Descripción |
|---|---|
| `public static BigDecimal fromMinor(long amount, int precision)` | amount / 10^precision, con BigDecimal. Es la formula G5 del documento de integracion. |
| `public static long toMinor(BigDecimal amount, int precision)` |  |
| `public static String amountString(BigDecimal amount)` | Formato del campo 'amount': patron ^\d+\.\d{2}$ exactamente. Ni mas ni menos decimales, y "0.00" se rechaza del lado de Kira. |
| `public static Map<String, Object> markupForQuotation(BigDecimal fixedFee, int percentageBps)` | client_markup para POST /v1/quotations: entero en unidades menores + puntos basicos. Es el ingreso de la plataforma, y vuelve en totals.client_markup_total. |
| `public static Map<String, Object> markupForPayout(BigDecimal fixedFee, int percentageBps)` | El MISMO markup para POST /payout y /payout/preview, donde la API lo espera como cadenas decimales. Misma cifra, otra forma de onda: por eso las dos funciones estan juntas. percentage_fee es una FRACCION entre 0 y 1 ("0.01" = 1 %), no un porcentaje ni puntos basicos. Por eso se divide entre 10.000 y no entre 100: con 100, 50 bps viajaban como "0.50" y Kira cobraria un 50 %. Cuatro decimales representan exacto cualquier bps. |
| `public static int precisionOf(String currency)` | Precision por defecto de una moneda cuando la respuesta no la trae. |

<sub>`infrastructure/kira/KiraApiClient.java` · 320 líneas</sub>

#### `KiraApiClient` · clase · `@Component`

Adaptador HTTP unico hacia KiraFin.

Responsabilidades que la documentacion exige y que ningun caso de uso debe repetir:
 - x-api-key en TODA peticion, incluida POST /auth.
 - Authorization: Bearer en toda peticion salvo POST /auth.
 - X-Api-Version en cada peticion, siempre la misma (KiraProperties.API_VERSION): la
   cabecera gana al pin de la cuenta y el go-live checklist exige una sola version.
 - Idempotency-Key (UUID v4) en POST /v1/users, /v1/recipients, /v1/virtual-accounts
   y /v1/virtual-accounts/{id}/payout.
 - Reautenticar y reintentar una vez ante un 401.
 - Normalizar las varias formas de error que conviven hoy en la API.

| Método | Descripción |
|---|---|
| `public KiraApiClient(RestClient kiraRestClient, KiraCredentialManager credentialManager, KiraProperties properties, KiraErrorParser errorParser, ObjectMapper objectMapper, IntegrationMetrics metrics)` |  |
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
| `public String getFilename()` |  |
| `public JsonNode removeRfiDocument(String rfiId, String itemId, String documentId)` | 422 "The last file cannot be removed": un item respondido necesita al menos un archivo. |
| `public JsonNode getRfiDocumentLink(String rfiId, String itemId, String documentId)` | La URL es una credencial al portador que caduca en minutos: no se guarda ni se registra. |
| `public JsonNode mintRfiUboLink(String rfiId, String itemId)` | Acuna el enlace de verificacion de un beneficiario para un item ubo_link cuyo answer_spec trae applicant_id y person_id en vez de url. 409 si el RFI esta cerrado; 422 si el item ya trae url; 404 si el RFI fue retirado. |
| `public JsonNode listCountries()` | Ruta verificada: /v1/countries. /countries o /api/countries responden 403. |
| `public JsonNode exchange(HttpMethod method, String path, Object body, IdempotencyKey idempotencyKey)` |  |
| `public KiraResponse exchangeWithStatus(HttpMethod method, String path, Object body, IdempotencyKey idempotencyKey)` |  |

<sub>`infrastructure/kira/KiraApiException.java` · 23 líneas</sub>

#### `KiraApiException` · clase · `@Getter`

Error devuelto por Kira, ya normalizado.

| Método | Descripción |
|---|---|
| `public KiraApiException(int statusCode, String code, String message, String rawBody)` |  |
| `public boolean isUnauthorized()` |  |

<sub>`infrastructure/kira/KiraAuthResponse.java` · 16 líneas</sub>

#### `KiraAuthResponse` · record · `@JsonIgnoreProperties(ignoreUnknown = true)`

Envoltura estandar { message, data } de POST /auth.

| Componente |
|---|
| `String message` |
| `Data data` |
#### `Data` · record · `@JsonIgnoreProperties(ignoreUnknown = true)`

| Componente |
|---|
| `String accessToken` |
| `Long expiresIn` |
| `String tokenType` |

<sub>`infrastructure/kira/KiraClientConfig.java` · 46 líneas</sub>

#### `KiraClientConfig` · clase · `@Configuration` `@EnableConfigurationProperties(KiraProperties.class)`

| Método | Descripción |
|---|---|
| `public RestClient kiraRestClient(KiraProperties properties)` |  |
| `public Cache<String, String> kiraTokenCache(KiraProperties properties)` | Cache del bearer token de Kira. Vive 3600s; expiramos antes por el margen configurado para no usar nunca un token a punto de vencer. |

<sub>`infrastructure/kira/KiraCredentialManager.java` · 96 líneas</sub>

#### `KiraCredentialManager` · clase · `@Service`

Obtiene y cachea el bearer token de Kira.

POST /auth es el unico endpoint que se autentica solo con x-api-key; el cuerpo lleva
client_id y password. El token vive 3600s y no hay refresh token: se vuelve a autenticar.
Cacheamos con margen para no llamar en cada peticion, e invalidamos ante cualquier 401.

| Método | Descripción |
|---|---|
| `public KiraCredentialManager(RestClient kiraRestClient, KiraProperties properties, KiraErrorParser errorParser, Cache<String, String> kiraTokenCache, ObjectMapper objectMapper)` |  |
| `public String getAccessToken()` |  |
| `public void invalidate()` | Se invoca cuando una llamada responde 401: el siguiente getAccessToken reautentica. |

<sub>`infrastructure/kira/KiraErrorParser.java` · 57 líneas</sub>

#### `KiraErrorParser` · clase · `@Component`

Kira convive hoy con varias formas de error: {error, details}, {message},
{code, error, message} y {statusCode, error, message}. La documentacion advierte
explicitamente de no escribir un parser que asuma una sola forma.

| Método | Descripción |
|---|---|
| `public KiraErrorParser(ObjectMapper objectMapper)` |  |
| `public KiraApiException parse(int statusCode, String body)` |  |

<sub>`infrastructure/kira/KiraFile.java` · 5 líneas</sub>

#### `KiraFile` · record

Archivo que se reenvia a Kira en una peticion multipart.

| Componente |
|---|
| `String fileName` |
| `String contentType` |
| `byte[] content` |

<sub>`infrastructure/kira/KiraNotConfiguredException.java` · 12 líneas</sub>

#### `KiraNotConfiguredException` · clase

Faltan las credenciales de Kira en este entorno. No es un fallo de la peticion ni de Kira:
la integracion no esta configurada, y el portal debe decirlo asi en vez de "error inesperado".

| Método | Descripción |
|---|---|
| `public KiraNotConfiguredException(String message)` |  |

<sub>`infrastructure/kira/KiraProperties.java` · 55 líneas</sub>

#### `KiraProperties` · record · `@ConfigurationProperties(prefix = "kira")`

| Componente |
|---|
| `String baseUrl` |
| `String apiKey` |
| `String clientId` |
| `String password` |
| `String apiVersion` |
| `String webhookSecret` |
| `String webhookSecretPrevious` |
| `long tokenTtlSeconds` |
| `long tokenRefreshMarginSeconds` |
| `int connectTimeoutMs` |
| `int readTimeoutMs` |
| `String bank` |
| `boolean sandbox` |

<sub>`infrastructure/kira/KiraResponse.java` · 21 líneas</sub>

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

<sub>`infrastructure/kira/KiraWebhookVerifier.java` · 66 líneas</sub>

#### `KiraWebhookVerifier` · clase · `@Component`

Verifica la cabecera x-signature-sha256: HMAC-SHA256 en hexadecimal sobre los BYTES CRUDOS
del cuerpo, con el secreto de firma de la URL a la que llego la entrega.

Dos reglas que la documentacion subraya:
 - no re-serializar el JSON antes de firmar (cambian espacios y orden de claves);
 - comparar en tiempo constante.

| Método | Descripción |
|---|---|
| `public KiraWebhookVerifier(KiraProperties properties)` |  |
| `public boolean isConfigured()` |  |
| `public boolean verify(byte[] rawBody, String signatureHeader)` | Acepta la firma del secreto vigente o, si esta configurado, la del anterior: al rotar el secreto, Kira sigue firmando con el viejo durante cerca de un minuto y no hay ventana en la que acepte ambos (webhooks/overview). Tras la rotacion se borra KIRA_WEBHOOK_SECRET_PREVIOUS. |

### A.20 Infraestructura — persistence

<sub>`infrastructure/persistence/AuditLogEntity.java` · 57 líneas</sub>

#### `AuditLogEntity` · clase · `@Entity` `@Table(name = "audit_logs", indexes = @Index(name = "idx_audit_tenant", columnList = "tenant_id, created_at"))` `@Getter` `@Setter` `@NoArgsConstructor`

Bitacora de auditoria B2B. Tabla `audit_logs`.
Kira no ofrece historial de cambios al integrador, asi que el registro es propio.

<sub>`infrastructure/persistence/AuditLogJpaRepository.java` · 10 líneas</sub>

#### `AuditLogJpaRepository` · interfaz

<sub>`infrastructure/persistence/DepositEntity.java` · 75 líneas</sub>

#### `DepositEntity` · clase · `@Entity` `@Table(name = "deposits", uniqueConstraints = @UniqueConstraint(name = "uk_deposits_kira_id", columnNames = "kira_deposit_id"), indexes = @Index(name = "idx_deposits_tenant", columnList = "tenant_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Historial de depositos entrantes. Tabla `deposits`.

<sub>`infrastructure/persistence/DepositJpaRepository.java` · 15 líneas</sub>

#### `DepositJpaRepository` · interfaz

<sub>`infrastructure/persistence/JpaAuditLogRepository.java` · 53 líneas</sub>

#### `JpaAuditLogRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaAuditLogRepository(AuditLogJpaRepository jpa)` |  |
| `public AuditLog append(AuditLog entry)` |  |
| `public List<AuditLog> findByTenant(TenantId tenantId, int limit)` |  |

<sub>`infrastructure/persistence/JpaDepositRepository.java` · 69 líneas</sub>

#### `JpaDepositRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaDepositRepository(DepositJpaRepository jpa)` |  |
| `public Deposit save(Deposit deposit)` |  |
| `public Optional<Deposit> findByKiraDepositId(String kiraDepositId)` |  |
| `public List<Deposit> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Deposit> findByVirtualAccount(String virtualAccountId, int limit)` |  |

<sub>`infrastructure/persistence/JpaNotificationRepository.java` · 68 líneas</sub>

#### `JpaNotificationRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaNotificationRepository(NotificationJpaRepository jpa, OperatorUserJpaRepository users)` |  |
| `public Notification save(Notification n)` |  |
| `public List<Notification> findByTenant(TenantId tenantId, int limit)` |  |
| `public long countByTenantSince(TenantId tenantId, Instant since)` |  |
| `public Instant seenAt(String userId)` |  |
| `public void markSeen(String userId, Instant at)` |  |

<sub>`infrastructure/persistence/JpaOperatorUserRepository.java` · 99 líneas</sub>

#### `JpaOperatorUserRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaOperatorUserRepository(OperatorUserJpaRepository jpa, RoleJpaRepository roles)` |  |
| `public Optional<OperatorUser> findByEmail(String email)` |  |
| `public Optional<OperatorUser> findById(String id)` |  |
| `public List<OperatorUser> findByTenant(TenantId tenantId)` |  |
| `public boolean existsByEmail(String email)` |  |
| `public OperatorUser create(OperatorUser user)` | El rol es una FK a `roles`: se resuelve por su nombre tecnico, no por la constante del enum. Si la fila no existe el alta falla aqui y no a mitad del flush, con un mensaje util. |
| `public void updateStatus(String userId, UserStatus status)` |  |
| `public void updateMfa(String userId, String encryptedSecret, boolean enabled)` |  |

<sub>`infrastructure/persistence/JpaPayoutRepository.java` · 67 líneas</sub>

#### `JpaPayoutRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaPayoutRepository(PayoutJpaRepository jpa)` |  |
| `public Payout save(Payout payout)` |  |
| `public Optional<Payout> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Payout> findByIdempotencyKey(IdempotencyKey key)` |  |
| `public Optional<Payout> findByKiraPayoutId(String kiraPayoutId)` |  |
| `public List<Payout> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Payout> findInFlight(int limit)` |  |

<sub>`infrastructure/persistence/JpaQuotationRepository.java` · 78 líneas</sub>

#### `JpaQuotationRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaQuotationRepository(QuotationJpaRepository jpa)` |  |
| `public Quotation save(Quotation quotation)` |  |
| `public Optional<Quotation> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public List<Quotation> findByTenant(TenantId tenantId, int limit)` |  |
| `public List<Quotation> findActiveExpiredBefore(Instant cutoff)` |  |

<sub>`infrastructure/persistence/JpaRecipientRepository.java` · 48 líneas</sub>

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

| Método | Descripción |
|---|---|
| `public JpaRfiRepository(RfiJpaRepository jpa)` |  |
| `public Rfi save(Rfi rfi)` |  |
| `public Optional<Rfi> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<Rfi> findByKiraRfiId(String kiraRfiId)` |  |
| `public List<Rfi> findByTenant(TenantId tenantId)` |  |
| `public List<Rfi> findOpenByTenant(TenantId tenantId)` |  |
| `public Optional<Rfi> findOpenBlocking(String kiraResourceId)` |  |

<sub>`infrastructure/persistence/JpaTenantRepository.java` · 98 líneas</sub>

#### `JpaTenantRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaTenantRepository(TenantJpaRepository jpa, ObjectMapper objectMapper)` |  |
| `public Tenant save(Tenant tenant)` |  |
| `public Optional<Tenant> findById(TenantId id)` |  |
| `public Optional<Tenant> findByKiraUserId(String kiraUserId)` |  |
| `public List<Tenant> findAll()` |  |

<sub>`infrastructure/persistence/JpaUboRepository.java` · 111 líneas</sub>

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
| `public void delete(Ubo ubo)` |  |

<sub>`infrastructure/persistence/JpaVirtualAccountRepository.java` · 68 líneas</sub>

#### `JpaVirtualAccountRepository` · clase · `@Repository`

| Método | Descripción |
|---|---|
| `public JpaVirtualAccountRepository(VirtualAccountJpaRepository jpa)` |  |
| `public VirtualAccount save(VirtualAccount account)` |  |
| `public Optional<VirtualAccount> findByIdAndTenant(String id, TenantId tenantId)` |  |
| `public Optional<VirtualAccount> findByKiraAccountId(String kiraAccountId)` |  |
| `public List<VirtualAccount> findByTenant(TenantId tenantId)` |  |

<sub>`infrastructure/persistence/NotificationEntity.java` · 46 líneas</sub>

#### `NotificationEntity` · clase · `@Entity` `@Table(name = "notifications", indexes = @Index(name = "idx_notifications_tenant", columnList = "tenant_id, created_at"))` `@Getter` `@Setter` `@NoArgsConstructor`

Avisos de negocio por organizacion. Tabla `notifications`.

<sub>`infrastructure/persistence/NotificationJpaRepository.java` · 14 líneas</sub>

#### `NotificationJpaRepository` · interfaz

<sub>`infrastructure/persistence/OperatorUserEntity.java` · 71 líneas</sub>

#### `OperatorUserEntity` · clase · `@Entity` `@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))` `@Getter` `@Setter` `@NoArgsConstructor`

Usuarios de cada empresa cliente. Tabla `users` del esquema v2.

<sub>`infrastructure/persistence/OperatorUserJpaRepository.java` · 13 líneas</sub>

#### `OperatorUserJpaRepository` · interfaz

<sub>`infrastructure/persistence/PayoutEntity.java` · 128 líneas</sub>

#### `PayoutEntity` · clase · `@Entity` `@Table(name = "payouts", uniqueConstraints = { @UniqueConstraint(name = "uk_payouts_idempotency", columnNames = "idempotency_key"), @UniqueConstraint(name = "uk_payouts_kira_id", columnNames = "kira_payout_id") }, indexes = { @Index(name = "idx_payouts_tenant", columnList = "tenant_id, created_at"), @Index(name = "idx_payouts_idempotency", columnList = "idempotency_key") })` `@Getter` `@Setter` `@NoArgsConstructor`

Pagos con segregacion maker-checker. Tabla `payouts`.

Las columnas approval_state, quotation_expires_at, rejection_reason y error_code no
existen en la API de Kira: son el control interno del BFF, que es justamente lo que
Kira no ofrece a los integradores.

<sub>`infrastructure/persistence/PayoutJpaRepository.java` · 22 líneas</sub>

#### `PayoutJpaRepository` · interfaz

<sub>`infrastructure/persistence/PayoutMapper.java` · 69 líneas</sub>

#### `PayoutMapper` · clase

<sub>`infrastructure/persistence/QuotationEntity.java` · 101 líneas</sub>

#### `QuotationEntity` · clase · `@Entity` `@Table(name = "quotations", indexes = @Index(name = "idx_quotations_tenant", columnList = "tenant_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Cotizaciones con desglose comisional 15 USD (Kira) + 15 USD (plataforma) = 30 USD.
Tabla `quotations`.

<sub>`infrastructure/persistence/QuotationJpaRepository.java` · 17 líneas</sub>

#### `QuotationJpaRepository` · interfaz

<sub>`infrastructure/persistence/RecipientEntity.java` · 145 líneas</sub>

#### `RecipientEntity` · clase · `@Entity` `@Table(name = "recipients", uniqueConstraints = @UniqueConstraint(name = "uk_recipients_kira_id", columnNames = "kira_recipient_id"), indexes = @Index(name = "idx_recipients_tenant", columnList = "tenant_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Directorio de destinatarios de pagos. Tabla `recipients`.

El espejo es completo a proposito. Kira no expone actualizacion ni borrado, asi que
corregir un destinatario obliga a reconstruir el alta entera; y ademas devuelve
bank_address.state y postal_code VACIOS aunque se hayan enviado. Sin esta copia, esos
datos se pierden en cuanto se guardan.

<sub>`infrastructure/persistence/RecipientJpaRepository.java` · 16 líneas</sub>

#### `RecipientJpaRepository` · interfaz

<sub>`infrastructure/persistence/RecipientMapper.java` · 112 líneas</sub>

#### `RecipientMapper` · clase

Aplana el oneOf del destinatario sobre la tabla y lo reconstruye.
El riel decide que bloque de columnas se usa; el resto quedan nulas.

<sub>`infrastructure/persistence/RfiEntity.java` · 64 líneas</sub>

#### `RfiEntity` · clase · `@Entity` `@Table(name = "rfis", uniqueConstraints = @UniqueConstraint(name = "uk_rfis_kira_id", columnNames = "kira_rfi_id"), indexes = { @Index(name = "idx_rfis_tenant", columnList = "tenant_id"), @Index(name = "idx_rfis_blocking", columnList = "blocking_resource_id")})` `@Getter` `@Setter` `@NoArgsConstructor`

Solicitudes de informacion de compliance. Tabla `rfis`.

<sub>`infrastructure/persistence/RfiJpaRepository.java` · 23 líneas</sub>

#### `RfiJpaRepository` · interfaz

<sub>`infrastructure/persistence/RoleEntity.java` · 44 líneas</sub>

#### `RoleEntity` · clase · `@Entity` `@Table(name = "roles", uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name"))` `@Getter` `@Setter` `@NoArgsConstructor`

Catalogo RBAC de la plataforma. La fila es la autoridad para la FK de `users`;
el enum com.example.autransactional.domain.tenant.Role es la autoridad
para las reglas de negocio y para @PreAuthorize.

<sub>`infrastructure/persistence/RoleJpaRepository.java` · 10 líneas</sub>

#### `RoleJpaRepository` · interfaz

<sub>`infrastructure/persistence/TenantEntity.java` · 95 líneas</sub>

#### `TenantEntity` · clase · `@Entity` `@Table(name = "tenants", uniqueConstraints = { @UniqueConstraint(name = "uk_tenants_name", columnNames = "name"), @UniqueConstraint(name = "uk_tenants_kira_user", columnNames = "kira_user_id") })` `@Getter` `@Setter` `@NoArgsConstructor`

Empresas clientes: Juriscop, Bankvision, AU Colombia. Tabla `tenants` del esquema v2.

<sub>`infrastructure/persistence/TenantJpaRepository.java` · 12 líneas</sub>

#### `TenantJpaRepository` · interfaz

<sub>`infrastructure/persistence/UboEntity.java` · 134 líneas</sub>

#### `UboEntity` · clase · `@Entity` `@Table(name = "ubos", indexes = @Index(name = "idx_ubos_tenant", columnList = "tenant_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Beneficiarios finales, directores y liveness por empresa. Tabla `ubos`.

<sub>`infrastructure/persistence/UboJpaRepository.java` · 19 líneas</sub>

#### `UboJpaRepository` · interfaz

<sub>`infrastructure/persistence/VirtualAccountEntity.java` · 87 líneas</sub>

#### `VirtualAccountEntity` · clase · `@Entity` `@Table(name = "virtual_accounts", uniqueConstraints = @UniqueConstraint(name = "uk_va_kira_account", columnNames = "kira_account_id"), indexes = @Index(name = "idx_virtual_accounts_tenant", columnList = "tenant_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Cuentas virtuales B2B. Tabla `virtual_accounts`.

<sub>`infrastructure/persistence/VirtualAccountJpaRepository.java` · 15 líneas</sub>

#### `VirtualAccountJpaRepository` · interfaz

<sub>`infrastructure/persistence/WebhookEventEntity.java` · 74 líneas</sub>

#### `WebhookEventEntity` · clase · `@Entity` `@Table(name = "webhooks_log", uniqueConstraints = @UniqueConstraint(name = "uk_webhooks_event_id", columnNames = "event_id"), indexes = @Index(name = "idx_webhooks_event", columnList = "event_id"))` `@Getter` `@Setter` `@NoArgsConstructor`

Bitacora inmutable de eventos de Kira. Tabla `webhooks_log`.

La unicidad de event_id es lo que hace idempotente el procesamiento: Kira entrega una
sola vez y sin reintento, y el mismo evento puede llegar por dos familias distintas
(payout.* y payout.status_changed).

<sub>`infrastructure/persistence/WebhookEventJpaRepository.java` · 26 líneas</sub>

#### `WebhookEventJpaRepository` · interfaz

### A.21 Infraestructura — audit

<sub>`infrastructure/audit/AuditTrail.java` · 85 líneas</sub>

#### `AuditTrail` · clase · `@Component`

Bitacora de acciones sensibles. Registra actor, organizacion, recurso, clave de idempotencia
y resultado; nunca secretos ni datos personales.

El detalle va como JSON en `changes` porque el esquema v2 tiene una sola columna para el
contexto de la accion: meter ahi campos sueltos obligaria a migrar la tabla cada vez que
una accion nueva quiera anotar algo distinto.

| Método | Descripción |
|---|---|
| `public AuditTrail(AuditLogRepository repository, ObjectMapper objectMapper)` |  |
| `public void record(AuthenticatedOperator operator, String action, String resourceType, String resourceId, String idempotencyKey, String result, String detail)` |  |

### A.22 Infraestructura — observability

<sub>`infrastructure/observability/IntegrationMetrics.java` · 62 líneas</sub>

#### `IntegrationMetrics` · clase · `@Component`

Metricas de la integracion con Kira (arquitectura §5): latencia y errores del proveedor, y
webhooks recibidos, rechazados y sin proyectar. Las etiquetas nunca llevan ids ni datos de
una empresa: todo segmento de ruta que no sea fijo se sustituye por {id}.

| Método | Descripción |
|---|---|
| `public IntegrationMetrics(MeterRegistry registry)` |  |
| `public void recordKiraCall(String method, String path, String outcome, long nanos)` | kira.api.requests: duracion por metodo, ruta normalizada y resultado (codigo HTTP o io_error). |
| `public void webhookReceived(String result)` | kira.webhooks.received: received, duplicate, invalid_signature, invalid_json o not_configured. |
| `public void webhookProjectionFailed(String eventType)` | kira.webhooks.projection.failures: eventos guardados cuya proyeccion fallo (quedan para reintento). |

<sub>`infrastructure/observability/RequestIdFilter.java` · 45 líneas</sub>

#### `RequestIdFilter` · clase · `@Component` `@Order(Ordered.HIGHEST_PRECEDENCE)`

Correlacion de una peticion (arquitectura §5, observabilidad): el id viaja en X-Request-Id, se
escribe en cada linea de log (MDC) y en la auditoria, y vuelve en la respuesta para que soporte
pueda buscarlo. Uno recibido que no tenga forma de id se sustituye: no se escribe texto ajeno
en los logs.

| Método | Descripción |
|---|---|
| `protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException` |  |

### A.23 Infraestructura — bootstrap

<sub>`infrastructure/bootstrap/DevDataSeeder.java` · 163 líneas</sub>

#### `DevDataSeeder` · clase · `@Component` `@Profile("dev")` `@ConditionalOnProperty(prefix = "bff.dev", name = "seed", havingValue = "true")` `@EnableConfigurationProperties(DevSeedProperties.class)`

Datos de arranque para desarrollo local.

Solo se activa con el perfil dev y bff.dev.seed=true, nunca en cert ni en prod.
Es idempotente: si la organizacion, el rol o el correo ya existen, no los toca.

Crea un operador por rol y por organizacion para poder probar de verdad el maker-checker:
hacen falta dos personas distintas para que un pago salga hacia Kira.

| Método | Descripción |
|---|---|
| `public DevDataSeeder(TenantJpaRepository tenants, RoleJpaRepository roles, OperatorUserJpaRepository operators, PasswordEncoder passwordEncoder, DevSeedProperties properties)` |  |
| `public void run(org.springframework.boot.ApplicationArguments args)` |  |
#### `SeedTenant` · record

| Componente |
|---|
| `String id` |
| `String name` |
| `String taxId` |

<sub>`infrastructure/bootstrap/DevSeedProperties.java` · 10 líneas</sub>

#### `DevSeedProperties` · record · `@ConfigurationProperties(prefix = "bff.dev")`

| Componente |
|---|
| `boolean seed` |
| `String seedPassword` |

<sub>`infrastructure/bootstrap/RequiredSecretsValidator.java` · 56 líneas</sub>

#### `RequiredSecretsValidator` · clase · `@Component` `@Profile({"cert", "prod"})`

En cert y prod el arranque falla si falta un secreto, en vez de descubrirlo con la primera
llamada a Kira o con un token firmado con una clave de ejemplo.
En dev no se aplica: alli hay valores por defecto deliberados.

| Método | Descripción |
|---|---|
| `public RequiredSecretsValidator(KiraProperties kira, BffSecurityProperties security)` |  |
| `public void afterPropertiesSet()` |  |

### A.24 Infraestructura — config

<sub>`infrastructure/config/AsyncConfig.java` · 31 líneas</sub>

#### `AsyncConfig` · clase · `@Configuration` `@EnableAsync` `@EnableScheduling`

Kira entrega cada webhook UNA sola vez, sin reintentos, y aborta a los 30 segundos.
Por eso el ingress responde 2xx de inmediato y el procesamiento ocurre en este pool.

| Método | Descripción |
|---|---|
| `public ThreadPoolTaskExecutor webhookExecutor()` |  |

<sub>`infrastructure/config/OpenApiConfig.java` · 54 líneas</sub>

#### `OpenApiConfig` · clase · `@Configuration`

Documentacion viva de la API del BFF.

El esquema 'bearer-jwt' es el JWT propio del BFF, no el de Kira: el token de Kira nunca
sale del servidor. Los endpoints de verificacion biometrica no llevan seguridad porque
los invoca la persona que se esta vinculando, que todavia no tiene sesion.

| Método | Descripción |
|---|---|
| `public OpenAPI bffOpenApi()` |  |

### A.25 Infraestructura — reconciliation

<sub>`infrastructure/reconciliation/LivenessReconciliationWorker.java` · 51 líneas</sub>

#### `LivenessReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Cierra los enlaces de prueba de vida vencidos.

El enlace que emite Kira vive 7 dias y su resultado real solo llega por el webhook
user.liveness_completed. Un enlace vencido que sigue en PENDING deja al portal esperando algo
que ya no va a pasar: se marca EXPIRED para que la pantalla ofrezca pedir uno nuevo, que es lo
unico que funciona (Kira no prorroga el enlace).

| Método | Descripción |
|---|---|
| `public LivenessReconciliationWorker(UboRepository ubos)` |  |
| `public void expireStaleLivenessLinks()` |  |

<sub>`infrastructure/reconciliation/PayoutReconciliationWorker.java` · 95 líneas</sub>

#### `PayoutReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Pagos en vuelo: pregunta por el recurso, que es la autoridad final.

Kira entrega cada webhook una sola vez y sin reintentos: si el BFF estaba caido o la proyeccion
fallo, ese cambio de estado no vuelve. Aqui se recuperan los pagos que Kira ya conoce y siguen
sin estado terminal (CREATED, PENDING, PROCESSING, KYT_PENDING, IN_REVIEW y UNKNOWN).

Cada pago se guarda por separado: un fallo de red en uno no debe tumbar el lote ni dejar a
medias los anteriores.

| Método | Descripción |
|---|---|
| `public PayoutReconciliationWorker(PayoutRepository payouts, KiraApiClient kira, ( "$` |  |
| `public void reconcile()` |  |

<sub>`infrastructure/reconciliation/QuotationReconciliationWorker.java` · 51 líneas</sub>

#### `QuotationReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Cierra las cotizaciones cuyo TTL de 15 minutos ya paso.

No llama a Kira: el vencimiento es local y deterministico. Sirve para que la bandeja no muestre
como ACTIVE un precio que ya no se puede redimir; el pago, por su parte, vuelve a comprobar el
vencimiento antes de enviar nada.

| Método | Descripción |
|---|---|
| `public QuotationReconciliationWorker(QuotationRepository quotations)` |  |
| `public void expireStaleQuotations()` |  |

<sub>`infrastructure/reconciliation/RfiReconciliationWorker.java` · 60 líneas</sub>

#### `RfiReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Trae de Kira los RFIs de cada empresa registrada.

La familia rfi.* exige suscripcion explicita en Kira y, como todo webhook, se entrega una sola
vez. Un RFI que no llega es un pago o un deposito detenido que nadie ve hasta que vence, y el
plazo (due_at, unas dos semanas) no se prorroga: por eso esta es la red de seguridad que mas
importa de las cuatro.

| Método | Descripción |
|---|---|
| `public RfiReconciliationWorker(TenantRepository tenants, AnswerRfiService rfis)` |  |
| `public void syncOpenRfis()` |  |

<sub>`infrastructure/reconciliation/TenantReconciliationWorker.java` · 62 líneas</sub>

#### `TenantReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Estado KYB de las empresas que aun no pueden operar.

user.status_changed es la unica senal de cada transicion, y Kira deja de reintentar una entrega
a los ~80 minutos. Si se pierde, una empresa verificada seguiria viendose en revision hasta que
alguien pulse "Actualizar". Las empresas ya verificadas y listas no se consultan.

| Método | Descripción |
|---|---|
| `public TenantReconciliationWorker(TenantRepository tenants, SubmitOnboardingService onboarding)` |  |
| `public void reconcile()` |  |

<sub>`infrastructure/reconciliation/VirtualAccountReconciliationWorker.java` · 70 líneas</sub>

#### `VirtualAccountReconciliationWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Estado de las cuentas virtuales abiertas en Kira.

virtual_account.activated es el unico evento de la cuenta: failed, deactivated y frozen no
tienen webhook, asi que solo se descubren consultando el recurso. Una cuenta congelada que se
sigue mostrando operativa es un pago que falla sin explicacion.

| Método | Descripción |
|---|---|
| `public VirtualAccountReconciliationWorker(TenantRepository tenants, VirtualAccountRepository accounts, OpenVirtualAccountService service)` |  |
| `public void reconcile()` |  |

<sub>`infrastructure/reconciliation/WebhookReprojectionWorker.java` · 100 líneas</sub>

#### `WebhookReprojectionWorker` · clase · `@Component` `@ConditionalOnProperty(prefix = "bff.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)`

Eventos almacenados y nunca proyectados: las filas de webhooks_log con processed = false.

El ingress responde 2xx en cuanto guarda el evento, asi que un fallo posterior de la proyeccion
no se le puede devolver a Kira, que ademas entrega una sola vez y sin reintentos. La fila con
processing_error es lo unico que queda de ese cambio de estado, y aqui se vuelve a intentar.

Es el complemento de los otros cuatro workers: aquellos preguntan por el recurso, este recupera
eventos cuyo dato NO esta en ningun GET (el motivo del rechazo del KYB y el resultado real de la
prueba de vida sólo viajan en el webhook).

| Método | Descripción |
|---|---|
| `public WebhookReprojectionWorker(WebhookEventJpaRepository events, ProcessWebhookUseCase webhooks, ("$` |  |
| `public void reprojectPendingEvents()` |  |

### A.26 Interfaces — rest

<sub>`interfaces/rest/ActivityController.java` · 63 líneas</sub>

#### `ActivityController` · clase · `@Tag(name = "7. Actividad", description = "Avisos de negocio, eventos de Kira recibidos y bitacora de auditoria.")` `@RestController` `@RequestMapping("/api")`

Avisos, centro de eventos y auditoria de la organizacion.

| Método | Descripción |
|---|---|
| `public ActivityController(NotificationService notifications, AuditQueryService audit)` |  |
| `public Map<String, Long> unreadCount(AuthenticatedOperator operator)` | Para el contador de la campana: barato de consultar a menudo. |
| `public ResponseEntity<Void> markAllRead(AuthenticatedOperator operator)` |  |

<sub>`interfaces/rest/AuthController.java` · 97 líneas</sub>

#### `AuthController` · clase · `@Tag(name = "1. Sesion", description = "Login del BFF y perfil del operador. El token de Kira nunca sale del servidor.")` `@RestController` `@RequestMapping("/api/auth")`

| Método | Descripción |
|---|---|
| `public AuthController(LoginUseCase loginUseCase, MfaService mfa, OperatorUserRepository users, TenantRepository tenants)` |  |
| `public ResponseEntity<LoginUseCase.LoginResult> login(LoginRequest request)` |  |
| `public LoginUseCase.LoginResult verifyMfa(MfaCodeRequest request)` | Paso 2 del inicio de sesion con segundo factor. |
| `public LoginUseCase.LoginResult enableMfa(AuthenticatedOperator operator, MfaEnableRequest request)` | Confirma el secreto con el primer codigo y devuelve una sesion. |
| `public ResponseEntity<Void> disableMfa(AuthenticatedOperator operator, MfaEnableRequest request)` |  |
| `public ResponseEntity<Map<String, Object>> me(AuthenticatedOperator operator)` |  |
#### `LoginRequest` · record

| Componente |
|---|
| `String email` |
| `String password` |

#### `MfaCodeRequest` · record

| Componente |
|---|
| `String challenge` |
| `String code` |

#### `MfaChallengeRequest` · record

| Componente |
|---|
| `String challenge` |

#### `MfaEnableRequest` · record

`challenge` solo cuando se configura durante el inicio de sesion.

| Componente |
|---|
| `String challenge` |
| `String code` |

<sub>`interfaces/rest/DepositController.java` · 51 líneas</sub>

#### `DepositController` · clase · `@Tag(name = "2.4 Depositos", description = "Historial de fondeos de las cuentas virtuales, proyectado desde los webhooks.")` `@RestController` `@RequestMapping("/api")`

Depositos entrantes.

Los depositos no se crean desde aqui: llegan por webhook y, como red de seguridad, se
sincronizan desde Kira. En el sandbox el webhook es la unica constancia que existe de ellos.

| Método | Descripción |
|---|---|
| `public DepositController(RecordDepositService deposits)` |  |
| `public List<DepositView> syncFromKira(AuthenticatedOperator operator, String id)` | Trae de Kira los depositos de la cuenta y los asienta. Idempotente por id de deposito. |

<sub>`interfaces/rest/OnboardingController.java` · 135 líneas</sub>

#### `OnboardingController` · clase · `@Tag(name = "1.1 Onboarding KYB", description = "Alta de la empresa en Kira y bucle de campos pendientes hasta VERIFIED.")` `@RestController` `@RequestMapping("/api/onboarding")`

Onboarding KYB de la empresa cliente.

El portal no debe tener un formulario estatico: GET devuelve 'pendingFields' y esa es
la lista de campos que hay que pintar. PUT se repite hasta que quede vacia.

| Método | Descripción |
|---|---|
| `public OnboardingController(SubmitOnboardingService onboarding)` |  |
| `public OnboardingView status(AuthenticatedOperator operator)` | Estado local, sin llamar a Kira. |
| `public ResponseEntity<OnboardingView> register( AuthenticatedOperator operator, OnboardingCommands.RegisterBusiness command)` |  |
| `public OnboardingView completeProfile( AuthenticatedOperator operator, OnboardingCommands.CompleteProfile command)` | Envia el perfil completo. Se puede repetir; cada llamada reenvia el objeto entero. |
| `public SubmitOnboardingService.TermsView terms(AuthenticatedOperator operator)` | Terminos vigentes y la version aceptada por la empresa. |
| `public SubmitOnboardingService.TermsView acceptTerms( AuthenticatedOperator operator, OnboardingCommands.AcceptTerms command)` | Acepta los terminos vigentes; exige el expediente creado en Kira. |
| `public OnboardingView refresh(AuthenticatedOperator operator)` |  |

<sub>`interfaces/rest/OnboardingDraftController.java` · 40 líneas</sub>

#### `OnboardingDraftController` · clase · `@Tag(name = "1.1 Onboarding KYB", description = "Alta de la empresa en Kira y bucle de campos pendientes hasta VERIFIED.")` `@RestController` `@RequestMapping("/api/onboarding/draft")`

Borrador del formulario de vinculacion: permite dejar el KYB a medias y retomarlo.
Local al BFF; enviar a Kira sigue siendo POST/PUT /api/onboarding.

| Método | Descripción |
|---|---|
| `public OnboardingDraftController(OnboardingDraftService drafts)` |  |
| `public OnboardingDraftView get(AuthenticatedOperator operator)` |  |
| `public OnboardingDraftView save(AuthenticatedOperator operator, OnboardingCommands.SaveDraft command)` | Reemplaza el borrador completo. Sin archivos: un data URI se rechaza con 422. |

<sub>`interfaces/rest/OperatorController.java` · 59 líneas</sub>

#### `OperatorController` · clase · `@Tag(name = "9. Operadores", description = "Alta, consulta y baja de los usuarios de la propia empresa. Solo ADMIN escribe.")` `@RestController` `@RequestMapping("/api/operators")`

Administracion de los operadores de la propia empresa (G-13).

El alcance es siempre la empresa de la sesion: no hay ruta para ver ni tocar los operadores
de otra organizacion, ni siquiera indicando su id.

| Método | Descripción |
|---|---|
| `public OperatorController(ManageOperatorsService operators)` |  |
| `public List<OperatorView> list(AuthenticatedOperator operator)` |  |
| `public OperatorView create(AuthenticatedOperator operator, OperatorCommands.CreateOperator command)` |  |
| `public OperatorView suspend(AuthenticatedOperator operator, String id)` |  |

<sub>`interfaces/rest/PayoutController.java` · 118 líneas</sub>

#### `PayoutController` · clase · `@Tag(name = "2. Pagos", description = "Pagos con control interno maker-checker: quien crea no aprueba.")` `@RestController` `@RequestMapping("/api/payouts")`

| Método | Descripción |
|---|---|
| `public PayoutController(ExecutePayoutService payoutService)` |  |
| `public PayoutPreviewView preview(AuthenticatedOperator operator, PayoutCommands.PreviewPayout command)` | Coste del pago sin reservar precio. Para cerrarlo, cotiza en /api/quotations. |
| `public PayoutView get(AuthenticatedOperator operator, String id)` |  |
| `public PayoutView requote(AuthenticatedOperator operator, String id)` | Renueva la cotizacion vencida de un pago pendiente; devuelve el pago con el precio nuevo. |
| `public PayoutView reject(AuthenticatedOperator operator, String id, PayoutCommands.RejectPayout command)` |  |
| `public List<PayoutEventView> events(AuthenticatedOperator operator, String id)` | Linea de tiempo del pago en Kira. Vacia mientras no se haya enviado. |
| `public PayoutView refresh(AuthenticatedOperator operator, String id)` |  |

<sub>`interfaces/rest/PlatformController.java` · 46 líneas</sub>

#### `PlatformController` · clase · `@Tag(name = "8. Consola de operaciones", description = "Solo PLATFORM_OPERATOR. Cada ficha consultada queda auditada.")` `@RestController` `@RequestMapping("/api/platform")` `@PreAuthorize("hasRole('PLATFORM_OPERATOR')")`

Consola de operaciones y cumplimiento de AU: todas las organizaciones, solo lectura.

| Método | Descripción |
|---|---|
| `public PlatformController(PlatformConsoleService console)` |  |
| `public List<PlatformConsoleService.TenantSummary> tenants(AuthenticatedOperator operator)` |  |
| `public PlatformConsoleService.Tenant360 tenant(AuthenticatedOperator operator, String id)` |  |
| `public PlatformConsoleService.Tenant360 refresh(AuthenticatedOperator operator, String id)` |  |
| `public List<PlatformConsoleService.ReviewItem> reviewQueue(AuthenticatedOperator operator)` |  |

<sub>`interfaces/rest/QuotationController.java` · 54 líneas</sub>

#### `QuotationController` · clase · `@Tag(name = "2.1 Cotizaciones", description = "Precio en firme de una transferencia: desglose de comisiones y TTL de 15 minutos.")` `@RestController` `@RequestMapping("/api/quotations")`

Cotizaciones de transferencia.

Viven 15 minutos exactos. La respuesta trae 'secondsToExpiry' para el contador: al
llegar a cero hay que recotizar, no reutilizar.

| Método | Descripción |
|---|---|
| `public QuotationController(CreateQuoteService quotes)` |  |
| `public QuotationView get(AuthenticatedOperator operator, String id)` |  |
| `public ResponseEntity<QuotationView> create( AuthenticatedOperator operator, QuotationCommands.CreateQuote command)` |  |

<sub>`interfaces/rest/RecipientController.java` · 78 líneas</sub>

#### `RecipientController` · clase · `@Tag(name = "2.2 Destinatarios", description = "Directorio de destinos de pago. Un destinatario = un riel.")` `@RestController` `@RequestMapping("/api/recipients")`

Directorio de destinatarios.

No hay PUT: Kira no expone actualizacion ni borrado de destinatarios. Para corregir uno
se da de alta el reemplazo y se archiva el anterior apuntando al nuevo.

| Método | Descripción |
|---|---|
| `public RecipientController(RegisterRecipientService recipients)` |  |
| `public List<RecipientView> list(AuthenticatedOperator operator)` |  |
| `public List<KiraRecipientView> listInKira(AuthenticatedOperator operator)` | Destinatarios de la empresa tal como los tiene Kira, para conciliar con el directorio. |
| `public KiraRecipientView getInKira(AuthenticatedOperator operator, String id)` |  |
| `public RecipientView get(AuthenticatedOperator operator, String id)` |  |

<sub>`interfaces/rest/ReferenceController.java` · 28 líneas</sub>

#### `ReferenceController` · clase · `@Tag(name = "0. Catalogos", description = "Catalogos de referencia de Kira, cacheados en el BFF.")` `@RestController` `@RequestMapping("/api/reference")`

| Método | Descripción |
|---|---|
| `public ReferenceController(ReferenceCatalogService catalog)` |  |
| `public List<CountryView> countries()` | Paises soportados con sus subdivisiones. Cacheado 24 h. |

<sub>`interfaces/rest/RestExceptionHandler.java` · 116 líneas</sub>

#### `RestExceptionHandler` · clase · `@RestControllerAdvice`

Traduce los errores a una forma unica para el frontend. La API de Kira convive con varias
formas de error; el BFF no las propaga crudas: entrega codigo estable y mensaje accionable.

| Método | Descripción |
|---|---|
| `public ResponseEntity<Map<String, Object>> handleRfiAnswer(RfiAnswerRejectedException e)` | Errores por item_id: el portal los pinta junto a cada campo y no marca ninguno como guardado. |
| `public ResponseEntity<Map<String, Object>> handleDomain(DomainException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleDenied(AccessDeniedException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleBadRequest(Exception e)` | Peticion mal formada: parte o parametro ausente, JSON ilegible o tipo equivocado. |
| `public ResponseEntity<Map<String, Object>> handleUploadTooLarge(MaxUploadSizeExceededException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e)` | Ruta inexistente: 404, no "error inesperado". |
| `public ResponseEntity<Map<String, Object>> handleKiraNotConfigured(KiraNotConfiguredException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleKira(KiraApiException e)` |  |
| `public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e)` |  |

<sub>`interfaces/rest/RfiController.java` · 124 líneas</sub>

#### `RfiController` · clase · `@Tag(name = "1.3 Solicitudes de informacion (RFI)", description = "Requerimientos de Kira: bandeja, sincronizacion y respuesta por item.")` `@RestController` `@RequestMapping("/api/rfis")`

Bandeja de solicitudes de informacion (RFI) de Kira.

No hay POST de alta: Kira genera los RFIs. El portal los sincroniza, los muestra y
responde sus items. Un RFI sin atender detiene lo que bloquea hasta que vence.

| Método | Descripción |
|---|---|
| `public RfiController(AnswerRfiService rfis)` |  |
| `public RfiView get(AuthenticatedOperator operator, String id)` |  |
| `public List<RfiView> sync(AuthenticatedOperator operator)` | Trae de Kira los RFIs de la empresa. Red de seguridad del webhook rfi.*. |
| `public RfiView refresh(AuthenticatedOperator operator, String id)` |  |
| `public RfiView answer(AuthenticatedOperator operator, String id, RfiCommands.AnswerItems command)` | Responde items de texto. Es all-or-nothing: un `422` trae `details` por item_id y significa que no se guardo ninguno. |
| `public RfiView uploadDocuments(AuthenticatedOperator operator, String id, String itemId, List<MultipartFile> files)` | Sube archivos a un item de tipo documento: multipart con la parte `files` repetida (maximo 20, 30 MB cada uno; PDF, JPEG, PNG, HEIC o WebP salvo que el item diga otra cosa). |
| `public RfiView removeDocument(AuthenticatedOperator operator, String id, String itemId, String documentId)` | Kira no permite borrar el ultimo archivo de un item ya respondido (422). |
| `public RfiUboLink mintUboLink(AuthenticatedOperator operator, String id, String itemId)` | Enlace de verificacion de un beneficiario (item ubo_link). Pedirlo cuando la persona pulsa: caduca en torno a una hora. Si el item ya trae url, se devuelve esa. |
| `public RfiDocumentLink documentLink(AuthenticatedOperator operator, String id, String itemId, String documentId)` | Enlace temporal (minutos). Abrirlo al momento; si caduca, pedir otro. |

<sub>`interfaces/rest/UboController.java` · 125 líneas</sub>

#### `UboController` · clase · `@Tag(name = "1.2 Beneficiarios finales", description = "UBOs de la empresa, sincronizacion con Kira y enlaces de prueba de vida (7 dias).")` `@RestController` `@RequestMapping("/api/ubos")`

Beneficiarios finales (UBOs) y sus enlaces de prueba de vida.

El registro es local primero y se sincroniza en bloque: Kira exige el array completo en
cada envio, asi que no hay un "alta de un UBO" contra su API.

| Método | Descripción |
|---|---|
| `public UboController(SyncUbosService ubos)` |  |
| `public UboView.Roster list(AuthenticatedOperator operator)` | Incluye la validacion del grupo: suma de participacion y si hay beneficiario final. |
| `public UboView save(AuthenticatedOperator operator, UboCommands.SaveUbo command)` | Alta o edicion local. Sin `id` crea; con `id` actualiza. |
| `public UboView.Roster delete(AuthenticatedOperator operator, String id)` | Borra un beneficiario que Kira aun no conoce. Devuelve el grupo actualizado. |
| `public OnboardingView sync(AuthenticatedOperator operator)` | Envia el array completo a Kira. Falla antes de llamar si no hay beneficiario final. |

<sub>`interfaces/rest/VirtualAccountController.java` · 80 líneas</sub>

#### `VirtualAccountController` · clase · `@Tag(name = "2.3 Cuentas virtuales", description = "Apertura, activacion y saldo de las cuentas en bancos de EE. UU.")` `@RestController` `@RequestMapping("/api/virtual-accounts")`

Cuentas virtuales.

'fundsReady' es la unica senal fiable de que la cuenta puede mover fondos; el estado por
si solo no basta. Si 'activationDelayed' es true, la activacion lleva demasiado tiempo y
el portal debe ofrecer contactar con Kira en vez de seguir esperando.

| Método | Descripción |
|---|---|
| `public VirtualAccountController(OpenVirtualAccountService accounts)` |  |
| `public List<VirtualAccountView> list(AuthenticatedOperator operator)` |  |
| `public VirtualAccountView get(AuthenticatedOperator operator, String id)` |  |
| `public ResponseEntity<VirtualAccountView> open( AuthenticatedOperator operator, VirtualAccountCommands.OpenAccount command)` | Exige KYB VERIFIED y producto elegible. Un 409 de Kira reutiliza la cuenta existente. |
| `public VirtualAccountView refresh(AuthenticatedOperator operator, String id)` | Relee la cuenta. Cubre el hueco de un virtual_account.activated que nunca llego. |
| `public VirtualAccountView refreshBalance(AuthenticatedOperator operator, String id)` |  |
| `public VirtualAccountView simulateDeposit( AuthenticatedOperator operator, String id, VirtualAccountCommands.SimulateDeposit command)` | Solo sandbox: en produccion responde 422 sin llamar a Kira. |

### A.27 Interfaces — webhook

<sub>`interfaces/webhook/KiraWebhookController.java` · 88 líneas</sub>

#### `KiraWebhookController` · clase · `@Tag(name = "6. Webhooks de Kira", description = "Ingress firmado con HMAC. Kira reintenta 4 veces ante 5xx o timeout.")` `@RestController` `@RequestMapping("/api/webhooks")`

Ingress de eventos de Kira.

Kira corta a los 30 segundos y reintenta 4 veces (1, 5, 15 y 60 min) ante 408, 429, 5xx
o falta de respuesta. Por eso este controlador solo verifica la firma sobre los bytes
crudos y guarda el evento; la proyeccion ocurre despues de haber respondido.

Se responde 2xx incluso ante un evento desconocido: un 4xx es la unica respuesta que Kira
no reintenta, asi que solo serviria para perder el evento.

| Método | Descripción |
|---|---|
| `public KiraWebhookController(ProcessWebhookUseCase processWebhook, KiraWebhookVerifier verifier, IntegrationMetrics metrics)` |  |

---

## Anexo B. Catálogo de pruebas

**53 clases de prueba, 424 métodos `@Test`.** Los nombres describen la regla de negocio que protegen.

### `AuTransactionalApplicationTests.java` — 1

- `contextLoads` — context loads

### `application/account/OpenVirtualAccountServiceTest.java` — 17

- `elBancoLoFijaElEntornoNoElFormulario` — el banco lo fija el entorno no el formulario
- `verifiedNoBastaSiElProductoNoEsElegible` — verified no basta si el producto no es elegible
- `laClaveDeIdempotenciaSePersisteAntesDeLlamar` — la clave de idempotencia se persiste antes de llamar
- `unaCuentaPendienteNoEstaListaParaFondos` — una cuenta pendiente no esta lista para fondos
- `activatingSinNumeroRealSigueSinEstarLista` — activating sin numero real sigue sin estar lista
- `activeLaHabilitaAunqueNoHayaLlegadoElEvento` — active la habilita aunque no haya llegado el evento
- `unNumeroDeCuentaRealSiLaHabilita` — un numero de cuenta real si la habilita
- `unConflictoReutilizaLaCuentaExistente` — un conflicto reutiliza la cuenta existente
- `unCuatrocientosEnElSaldoEsCalculandoNoUnError` — un cuatrocientos en el saldo es calculando no un error
- `otrosErroresDeSaldoSiSePropagan` — otros errores de saldo si se propagan
- `elSaldoLlegaEnDecimalNoEnUnidadesMenores` — el saldo llega en decimal no en unidades menores
- `simularDepositoNoExisteFueraDelSandbox` — simular deposito no existe fuera del sandbox
- `enSandboxSimularDepositoRefrescaElSaldo` — en sandbox simular deposito refresca el saldo
- `unaCuentaSinAbrirEnKiraNoSeRefresca` — una cuenta sin abrir en kira no se refresca
- `elReintentoRetomaLaAperturaSinConfirmarYNoCreaOtraCuenta` — el reintento retoma la apertura sin confirmar y no crea otra cuenta
- `unaAperturaSinConfirmarDeOtraMonedaNoSeReutiliza` — una apertura sin confirmar de otra moneda no se reutiliza
- `unaCuentaYaConfirmadaPorKiraNoSeReutiliza` — una cuenta ya confirmada por kira no se reutiliza

### `application/account/RecordDepositServiceTest.java` — 19

- `unDepositoRecibidoSeProyectaConSusTresImportes` — un deposito recibido se proyecta con sus tres importes
- `seLeeElCasingMezcladoDelPayload` — se lee el casing mezclado del payload
- `losSeisEventosConvergenEnUnaSolaFila` — los seis eventos convergen en una sola fila
- `unDepositoDevueltoNoVuelveAAcreditar` — un deposito devuelto no vuelve a acreditar
- `docs_depositFundsReceivedLeeOrdenanteYRielDeSource` — docs_deposit funds received lee ordenante y riel de source
- `docs_depositFundsRefundedNoQuedaComoAcreditado` — docs_deposit funds refunded no queda como acreditado
- `docs_depositoProgramadoOEnRevisionNoAcredita` — docs_deposito programado o en revision no acredita
- `unDepositoRetenidoNoAcreditaYPuedeLiberarse` — un deposito retenido no acredita y puede liberarse
- `laCuentaDelOrdenanteSaleEnmascarada` — la cuenta del ordenante sale enmascarada
- `unEstadoDesconocidoNuncaAcreditaSaldo` — un estado desconocido nunca acredita saldo
- `unEventoTardioNoResucitaUnDepositoDevuelto` — un evento tardio no resucita un deposito devuelto
- `elFalloSeProyectaAunqueElPayloadNoTraigaEstado` — el fallo se proyecta aunque el payload no traiga estado
- `unMicrodepositoNoCuentaComoIngreso` — un microdeposito no cuenta como ingreso
- `unDepositoAcreditadoInvalidaElSaldoCacheadoPeroNoLoInventa` — un deposito acreditado invalida el saldo cacheado pero no lo inventa
- `unMicrodepositoNoInvalidaElSaldo` — un microdeposito no invalida el saldo
- `unDepositoDeUnaCuentaDesconocidaNoRompeNada` — un deposito de una cuenta desconocida no rompe nada
- `unEventoSinIdentificadorNoSeProyecta` — un evento sin identificador no se proyecta
- `laSincronizacionDesdeKiraConvergeEnLaMismaFilaQueElWebhook` — la sincronizacion desde kira converge en la misma fila que el webhook
- `losEstadosDeRetencionSeConservanYNoSonFallos` — los estados de retencion se conservan y no son fallos

### `application/auth/MfaServiceTest.java` — 8

- `sinSegundoFactorLaContrasenaDaLaSesion` — sin segundo factor la contrasena da la sesion
- `conSegundoFactorLaContrasenaSoloDaUnRetoQueNoSirveComoSesion` — con segundo factor la contrasena solo da un reto que no sirve como sesion
- `elSecretoSeGuardaCifrado` — el secreto se guarda cifrado
- `elRetoMasUnCodigoValidoDanLaSesion` — el reto mas un codigo valido dan la sesion
- `unCodigoYaUsadoNoValeOtraVez` — un codigo ya usado no vale otra vez
- `cincoCodigosErroneosAgotanElReto` — cinco codigos erroneos agotan el reto
- `unOperadorDeLaPlataformaEntraSinEmpresa` — un operador de la plataforma entra sin empresa
- `siElEntornoLoExigeQuienNoLoTieneDebeConfigurarloAlEntrar` — si el entorno lo exige quien no lo tiene debe configurarlo al entrar

### `application/compliance/AnswerRfiServiceTest.java` — 28

- `laSincronizacionAsientaEstadoPlazoYBloqueo` — la sincronizacion asienta estado plazo y bloqueo
- `unRfiQueDesapareceDelListadoYDa404QuedaRetirado` — un rfi que desaparece del listado y da 404 queda retirado
- `refrescarUnRfiRetiradoLoCierraSinError` — refrescar un rfi retirado lo cierra sin error
- `seGuardaElMotivoDeUnRfiCerradoSinResolver` — se guarda el motivo de un rfi cerrado sin resolver
- `seAcunaElEnlaceDelBeneficiarioCuandoElItemNoTraeUrl` — se acuna el enlace del beneficiario cuando el item no trae url
- `siElItemYaTraeUrlSeDevuelveSinLlamarAKira` — si el item ya trae url se devuelve sin llamar a kira
- `noSeAcunaEnlaceParaUnItemQueNoEsDeBeneficiario` — no se acuna enlace para un item que no es de beneficiario
- `laSincronizacionNoImportaRfisDeOtraEmpresaAunqueKiraLosDevuelva` — la sincronizacion no importa rfis de otra empresa aunque kira los devuelva
- `laSincronizacionFiltraPorEmpresaYPaginaConOffset` — la sincronizacion filtra por empresa y pagina con offset
- `unaEntradaResumidaSeCompletaConElDetalle` — una entrada resumida se completa con el detalle
- `unItemDeDocumentoNuncaEnviaAnswerValue` — un item de documento nunca envia answer value
- `unItemQueNoEsDelRfiSeRechazaSinLlamarAKira` — un item que no es del rfi se rechaza sin llamar a kira
- `un422DeKiraSeDevuelvePorItemYNoSeMarcaNadaComoRespondido` — un 422 de kira se devuelve por item y no se marca nada como respondido
- `un409AsientaElCierreAntesDeAvisar` — un 409 asienta el cierre antes de avisar
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

### `application/platform/PlatformConsoleServiceTest.java` — 4

- `unOperadorDeEmpresaNoAccedeALaConsola` — un operador de empresa no accede a la consola
- `elListadoIncluyeTodasLasOrganizacionesOrdenadas` — el listado incluye todas las organizaciones ordenadas
- `consultarUnaFichaQuedaAuditado` — consultar una ficha queda auditado
- `laBandejaPoneLoCriticoPrimero` — la bandeja pone lo critico primero

### `application/reference/ReferenceCatalogServiceTest.java` — 2

- `elCatalogoDePaisesSeLeeUnaVezYSeSirveDeCache` — el catalogo de paises se lee una vez y se sirve de cache
- `unFalloNoSeCachea` — un fallo no se cachea

### `application/shared/IdempotencyKeyPersistenceTest.java` — 2

- `laClaveDelAltaSobreviveAlFalloDeKira` — la clave del alta sobrevive al fallo de kira
- `laClaveDeAperturaDeCuentaSobreviveAlFalloDeKira` — la clave de apertura de cuenta sobrevive al fallo de kira

### `application/tenant/KiraUserStateTest.java` — 5

- `leeLaRespuestaDelAlta` — lee la respuesta del alta
- `desenvuelveLaRespuestaConSobreData` — desenvuelve la respuesta con sobre data
- `unGetSinVerificationTriggeredDejaElDatoIndefinido` — un get sin verification triggered deja el dato indefinido
- `reconoceLaDiligenciaReforzada` — reconoce la diligencia reforzada
- `unEstadoDesconocidoNoRompeLaLectura` — un estado desconocido no rompe la lectura

### `application/tenant/KybDocumentsTest.java` — 10

- `elArchivoViajaComoDataUriEnBase64` — el archivo viaja como data uri en base 64
- `elPaisEmisorViajaEnIso3Mayusculas` — el pais emisor viaja en iso 3 mayusculas
- `rechazaUnArchivoCuyoContenidoNoEsElTipoDeclarado` — rechaza un archivo cuyo contenido no es el tipo declarado
- `rechazaUnTipoDeDocumentoQueKiraNoConoce` — rechaza un tipo de documento que kira no conoce
- `rechazaUnMimeQueNoViajaEnBase64` — rechaza un mime que no viaja en base 64
- `rechazaUnArchivoVacio` — rechaza un archivo vacio
- `rechazaElLoteQueReventariaElCuerpoDe10Mb` — rechaza el lote que reventaria el cuerpo de 10 mb
- `rechazaMasDeDiezArchivos` — rechaza mas de diez archivos
- `laFusionReemplazaElRegistroDelMismoTipoYConservaLosDemas` — la fusion reemplaza el registro del mismo tipo y conserva los demas
- `loQueSePersisteNoLlevaLosArchivos` — lo que se persiste no lleva los archivos

### `application/tenant/ManageOperatorsServiceTest.java` — 12

- `listaSoloLosOperadoresDeSuEmpresa` — lista solo los operadores de su empresa
- `laVistaNuncaExponeElHashNiElSecretoMfa` — la vista nunca expone el hash ni el secreto mfa
- `creaElOperadorEnLaEmpresaDeLaSesionYConLaClaveCifrada` — crea el operador en la empresa de la sesion y con la clave cifrada
- `rechazaUnCorreoYaRegistrado` — rechaza un correo ya registrado
- `unAdminNoPuedeFabricarOtroAdminNiUnOperadorDePlataforma` — un admin no puede fabricar otro admin ni un operador de plataforma
- `rechazaUnRolInexistente` — rechaza un rol inexistente
- `aceptaLosCuatroRolesDelegables` — acepta los cuatro roles delegables
- `suspendeAUnOperadorDeSuEmpresa` — suspende a un operador de su empresa
- `nadieSeDesactivaASiMismo` — nadie se desactiva a si mismo
- `noPuedeTocarAUnOperadorDeOtraEmpresa` — no puede tocar a un operador de otra empresa
- `noSuspendeDosVecesAlMismoOperador` — no suspende dos veces al mismo operador
- `laConsolaDePlataformaNoEntraPorLasRutasDeEmpresa` — la consola de plataforma no entra por las rutas de empresa

### `application/tenant/OnboardingDraftServiceTest.java` — 9

- `sinBorradorDevuelveVacioYSinFecha` — sin borrador devuelve vacio y sin fecha
- `guardaYRecuperaElBorradorSinLlamarAKira` — guarda y recupera el borrador sin llamar a kira
- `laBitacoraNoGuardaDatosDeLaEmpresa` — la bitacora no guarda datos de la empresa
- `unObjetoVacioBorraElBorrador` — un objeto vacio borra el borrador
- `rechazaArchivosDentroDelBorrador` — rechaza archivos dentro del borrador
- `rechazaUnBorradorDemasiadoGrande` — rechaza un borrador demasiado grande
- `unRolSinPermisoDeCumplimientoNoGuarda` — un rol sin permiso de cumplimiento no guarda
- `unaEmpresaRechazadaNoPuedeGuardarBorrador` — una empresa rechazada no puede guardar borrador
- `unBorradorIlegibleEnBaseSeDegradaAVacio` — un borrador ilegible en base se degrada a vacio

### `application/tenant/SubmitOnboardingServiceTest.java` — 20

- `elAltaEnviaSoloEmpresasYAmarraElIdInterno` — el alta envia solo empresas y amarra el id interno
- `laClaveDeIdempotenciaSePersisteAntesDeLlamarAKira` — la clave de idempotencia se persiste antes de llamar a kira
- `siLaLlamadaFallaLaClaveQuedaGuardadaParaElReintento` — si la llamada falla la clave queda guardada para el reintento
- `reenviarElAltaNoVuelveALlamarAKira` — reenviar el alta no vuelve a llamar a kira
- `elPutReenviaElObjetoCompletoNoSoloLoNuevo` — el put reenvia el objeto completo no solo lo nuevo
- `elPutNoLlevaLasClavesQueSoloExistenEnElAlta` — el put no lleva las claves que solo existen en el alta
- `losNombresDelAltaSeTraducenALosDelPut` — los nombres del alta se traducen a los del put
- `unaDireccionNuevaReemplazaALaGuardada` — una direccion nueva reemplaza a la guardada
- `unArrayNuevoReemplazaEnteroAlGuardado` — un array nuevo reemplaza entero al guardado
- `completarElPerfilExigeAltaPrevia` — completar el perfil exige alta previa
- `unRolDeTesoreriaNoGestionaElOnboarding` — un rol de tesoreria no gestiona el onboarding
- `elDocumentoViajaDentroDelPutDelExpediente` — el documento viaja dentro del put del expediente
- `elBase64NoSeGuardaEnElPayloadDeOnboarding` — el base 64 no se guarda en el payload de onboarding
- `elRegistroSobreviveAlSiguientePutDelPerfil` — el registro sobrevive al siguiente put del perfil
- `subirDocumentosExigeAltaPrevia` — subir documentos exige alta previa
- `unRolDeTesoreriaNoSubeDocumentosKyb` — un rol de tesoreria no sube documentos kyb
- `aceptarLosTerminosVigentesLosMandaAKiraYQuedaAuditado` — aceptar los terminos vigentes los manda a kira y queda auditado
- `unaVersionQueNoEsLaVigenteSeRechaza` — una version que no es la vigente se rechaza
- `elPerfilQueMandaElPortalNoPuedeFijarLaAceptacion` — el perfil que manda el portal no puede fijar la aceptacion
- `elEinSoloViajaParaEmpresasDeEstadosUnidos` — el ein solo viaja para empresas de estados unidos

### `application/tenant/SyncUbosServiceTest.java` — 22

- `elArrayEnviadoLlevaLosBooleanosQueKiraExige` — el array enviado lleva los booleanos que kira exige
- `laPersonaViajaConLosDatosQueKiraPideYSinPersonReferenceId` — la persona viaja con los datos que kira pide y sin person reference id
- `editarCorrigeNombreApellidoYCargo` — editar corrige nombre apellido y cargo
- `seBorraUnBeneficiarioQueKiraAunNoConoce` — se borra un beneficiario que kira aun no conoce
- `noSeRegistraUnSegundoBeneficiarioConElMismoCorreo` — no se registra un segundo beneficiario con el mismo correo
- `trasSincronizarElBeneficiarioYaNoSePuedeBorrar` — tras sincronizar el beneficiario ya no se puede borrar
- `noSeBorraUnBeneficiarioQueKiraYaConoce` — no se borra un beneficiario que kira ya conoce
- `seEnviaSiempreElArrayCompletoNoSoloElUltimoAlta` — se envia siempre el array completo no solo el ultimo alta
- `sinBeneficiarioNoSeGastaLaLlamadaAKira` — sin beneficiario no se gasta la llamada a kira
- `sinVerificacionEnCursoNoSePidenEnlaces` — sin verificacion en curso no se piden enlaces
- `losEnlacesSeRepartenPorReferenciaDePersona` — los enlaces se reparten por referencia de persona
- `sinReferenciaPreviaElEnlaceSeEmparejaPorNombreYLaGuarda` — sin referencia previa el enlace se empareja por nombre y la guarda
- `lasUrlsDeRedireccionViajanSoloSiEstanCompletas` — las urls de redireccion viajan solo si estan completas
- `elWebhookDeLivenessAsientaElResultadoEnSuPersona` — el webhook de liveness asienta el resultado en su persona
- `unWebhookSinPersonaNoTocaANadie` — un webhook sin persona no toca a nadie
- `unRolDeTesoreriaNoGestionaBeneficiarios` — un rol de tesoreria no gestiona beneficiarios
- `sinConsentimientoBiometricoNiSelfieNiEnlaces` — sin consentimiento biometrico ni selfie ni enlaces
- `elConsentimientoDeLaPruebaDeVidaQuedaAuditado` — el consentimiento de la prueba de vida queda auditado
- `elDocumentoDeUnaPersonaViajaAnidadoEnSuEntradaYSeEmparejaPorEmail` — el documento de una persona viaja anidado en su entrada y se empareja por email
- `sinEmailNoSePuedenSubirSusDocumentos` — sin email no se pueden subir sus documentos
- `noSePuedenSubirDocumentosDeUnBeneficiarioDeOtraEmpresa` — no se pueden subir documentos de un beneficiario de otra empresa
- `elEmailViajaEnLaSincronizacionDelGrupo` — el email viaja en la sincronizacion del grupo

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

### `application/treasury/ExecutePayoutServiceTest.java` — 32

- `recotizarAtaUnaCotizacionNuevaYAnulaLaPrimeraFirma` — recotizar ata una cotizacion nueva y anula la primera firma
- `unPagoSinPrecioFijadoNoSeRecotiza` — un pago sin precio fijado no se recotiza
- `desdeElUmbralLaPrimeraFirmaNoEnviaElPago` — desde el umbral la primera firma no envia el pago
- `laSegundaFirmaTieneQueSerDeOtraPersonaYEntoncesSeEnvia` — la segunda firma tiene que ser de otra persona y entonces se envia
- `elUmbralDeUnaEmpresaMandaSobreElGeneral` — el umbral de una empresa manda sobre el general
- `quienRegistroElDestinatarioNoApruebaPagosHaciaEl` — quien registro el destinatario no aprueba pagos hacia el
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
- `conLaMismaClaveDelPortalNoSeCreaUnSegundoPago` — con la misma clave del portal no se crea un segundo pago
- `unaClaveDelPortalQueNoEsUuidSeRechaza` — una clave del portal que no es uuid se rechaza
- `crearUnPagoConUnDestinatarioInexistenteSeRechazaAlPreparar` — crear un pago con un destinatario inexistente se rechaza al preparar
- `elUserDeKiraDelPagoEsElDeLaEmpresa` — el user de kira del pago es el de la empresa
- `unaCotizacionDeOtroDestinatarioNoSeAtaAlPago` — una cotizacion de otro destinatario no se ata al pago
- `laVistaPreviaUsaLosIdsDeKiraYElMargenDeLaPlataforma` — la vista previa usa los ids de kira y el margen de la plataforma
- `laLineaDeTiempoSaleDeLosEventosDelPagoEnKira` — la linea de tiempo sale de los eventos del pago en kira
- `unPagoSinEnviarNoTieneLineaDeTiempoEnKira` — un pago sin enviar no tiene linea de tiempo en kira
- `elHistorialDeKiraDescartaPagosDeOtrasEmpresasYEnlazaLosDelPortal` — el historial de kira descarta pagos de otras empresas y enlaza los del portal
- `unEstadoQueKiraNoConoceSeRechazaAntesDeLlamar` — un estado que kira no conoce se rechaza antes de llamar
- `unPagoDetenidoPorUnRfiLoIndicaEnSuDetalle` — un pago detenido por un rfi lo indica en su detalle

### `application/treasury/RegisterRecipientServiceTest.java` — 14

- `elTitularSeInfiereDeLosNombresPorqueNoExisteHolderName` — el titular se infiere de los nombres porque no existe holder name
- `enWireLaDireccionDelBancoEsUnObjeto` — en wire la direccion del banco es un objeto
- `enAchLaDireccionDelBancoEsTextoPlano` — en ach la direccion del banco es texto plano
- `unaWalletViajaConTokenYRed` — una wallet viaja con token y red
- `unParTokenRedInvalidoSeCortaAntesDeLlamar` — un par token red invalido se corta antes de llamar
- `seLeeRecipientIdNoId` — se lee recipient id no id
- `unDoscientosDosEsExitoNoError` — un doscientos dos es exito no error
- `unReintentoConLaMismaClaveNoGuardaUnSegundoDestinatario` — un reintento con la misma clave no guarda un segundo destinatario
- `elEstadoYElCodigoPostalSalenDelEspejoLocal` — el estado y el codigo postal salen del espejo local
- `laCuentaSeMuestraEnmascarada` — la cuenta se muestra enmascarada
- `sinKybAprobadoNoHayDestinatarios` — sin kyb aprobado no hay destinatarios
- `archivarEnlazaConElReemplazo` — archivar enlaza con el reemplazo
- `unRolAprobadorNoRegistraDestinatarios` — un rol aprobador no registra destinatarios
- `losDestinatariosDeKiraSeEnmascaranYSeEnlazanConElDirectorio` — los destinatarios de kira se enmascaran y se enlazan con el directorio

### `application/webhook/KiraWebhookEnvelopeTest.java` — 3

- `leeLaEnvolturaPlana` — lee la envoltura plana
- `desanidaLaEnvolturaV2DePayoutStatusChanged` — desanida la envoltura v 2 de payout status changed
- `noHayEventIdEnLaRaiz` — no hay event id en la raiz

### `application/webhook/UserEventProjectionTest.java` — 19

- `elMotivoDelRechazoSeCapturaPorqueElGetNoLoExpone` — el motivo del rechazo se captura porque el get no lo expone
- `docs_verificationFailedGuardaLosReasons` — docs_verification failed guarda los reasons
- `docs_statusChangedMueveElEstadoConNewStatus` — docs_status changed mueve el estado con new status
- `unaEmpresaVerificadaGeneraUnAvisoYElEventoQuedaAtribuido` — una empresa verificada genera un aviso y el evento queda atribuido
- `unEventoQueNoCambiaElEstadoNoAvisa` — un evento que no cambia el estado no avisa
- `docs_livenessCompletedLeeResult` — docs_liveness completed lee result
- `docs_livenessDeLaPropiaEmpresaNoTocaBeneficiarios` — docs_liveness de la propia empresa no toca beneficiarios
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

- `alLeerLaCuentaSeConservaSuFechaDeAlta` — al leer la cuenta se conserva su fecha de alta
- `unaCuentaSinActivarTrasCincoMinutosEstaDemorada` — una cuenta sin activar tras cinco minutos esta demorada

### `domain/account/VirtualAccountReadinessTest.java` — 7

- `approvedSinNumeroDeCuentaNoEstaListaParaFondos` — approved sin numero de cuenta no esta lista para fondos
- `elCentinelaDeActNoCuentaComoCuentaReal` — el centinela de act no cuenta como cuenta real
- `unNumeroDeCuentaRealSiLaHabilita` — un numero de cuenta real si la habilita
- `elEventoActivatedEsSenalSuficiente` — el evento activated es senal suficiente
- `activeEsLaSenalDe20260601` — active es la senal de 20260601
- `unaCuentaRechazadaNuncaEstaLista` — una cuenta rechazada nunca esta lista
- `unaCuentaCongeladaNoMueveFondosAunqueSeHayaActivado` — una cuenta congelada no mueve fondos aunque se haya activado

### `domain/compliance/RfiTest.java` — 8

- `notResolvedEsUnCierreYNoUnRfiPendiente` — not resolved es un cierre y no un rfi pendiente
- `losEstadosSeLeenSinDistinguirMayusculas` — los estados se leen sin distinguir mayusculas
- `unEstadoDesconocidoQuedaVisibleEnLaBandeja` — un estado desconocido queda visible en la bandeja
- `unRfiRespondidoSigueAdmitiendoRespuestasPorqueKiraPuedeDevolverUnItem` — un rfi respondido sigue admitiendo respuestas porque kira puede devolver un item
- `unRfiCerradoNoAdmiteRespuestas` — un rfi cerrado no admite respuestas
- `unEventoTardioNoReabreUnRfiResuelto` — un evento tardio no reabre un rfi resuelto
- `unRfiVencidoYAbiertoEstaAtrasado` — un rfi vencido y abierto esta atrasado
- `alRehidratarSeConservaLaFechaDeCreacion` — al rehidratar se conserva la fecha de creacion

### `domain/shared/FileSignatureTest.java` — 3

- `reconoceLosFormatosQueKiraAcepta` — reconoce los formatos que kira acepta
- `elTipoDeclaradoTieneQueCoincidirConElContenido` — el tipo declarado tiene que coincidir con el contenido
- `elDocumentoDeSoporteDelPagoTambienSeComprueba` — el documento de soporte del pago tambien se comprueba

### `domain/tenant/TenantOnboardingTest.java` — 10

- `laClaveDeIdempotenciaSeReservaUnaSolaVez` — la clave de idempotencia se reserva una sola vez
- `elAltaNoDisparaLaVerificacion` — el alta no dispara la verificacion
- `noSeReasignaLaEmpresaAOtroUsuarioDeKira` — no se reasigna la empresa a otro usuario de kira
- `losCamposPendientesSonLosDelProductoNoLaUnionGeneral` — los campos pendientes son los del producto no la union general
- `siElProductoNoApareceSeUsaLaListaGeneral` — si el producto no aparece se usa la lista general
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

### `domain/treasury/PayoutStatusTest.java` — 5

- `comparaSinDistinguirMayusculas` — compara sin distinguir mayusculas
- `returnedResuelveEnFailed` — returned resuelve en failed
- `cancelledEsUnEstadoFinalPropio` — cancelled es un estado final propio
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

- `elRoutingNumberTieneNueveDigitos` — el routing number tiene nueve digitos
- `elSwiftTieneOchoUOnceCaracteres` — el swift tiene ocho u once caracteres
- `usdcNoExisteEnTron` — usdc no existe en tron
- `usdtSiExisteEnTron` — usdt si existe en tron
- `copmSoloVivEnPolygon` — copm solo viv en polygon
- `elRielSaleDelTipoDeCuenta` — el riel sale del tipo de cuenta
- `unaEmpresaNecesitaRazonSocialYUnaPersonaNombreCompleto` — una empresa necesita razon social y una persona nombre completo
- `elTelefonoTieneTope` — el telefono tiene tope
- `unDestinatarioBancarioNecesitaDireccionEnIso2` — un destinatario bancario necesita direccion en iso 2
- `unaWalletNoNecesitaDireccionPostal` — una wallet no necesita direccion postal
- `archivarEnlazaConElReemplazo` — archivar enlaza con el reemplazo
- `sinAltaEnKiraNoSePuedeUsar` — sin alta en kira no se puede usar

### `infrastructure/bootstrap/CertProfileStartupTest.java` — 2

- `certNoArrancaSinLosSecretosDeKira` — cert no arranca sin los secretos de kira
- `certArrancaConTodosLosSecretosPresentes` — cert arranca con todos los secretos presentes

### `infrastructure/bootstrap/DevDataSeederTest.java` — 4

- `creaLasTresOrganizacionesConUnOperadorPorRolYUnOperadorDePlataforma` — crea las tres organizaciones con un operador por rol y un operador de plataforma
- `laContrasenaQuedaCifradaNoEnClaro` — la contrasena queda cifrada no en claro
- `makerYApproverSonOperadoresDistintosDelMismoTenant` — maker y approver son operadores distintos del mismo tenant
- `volverARegarNoDuplicaNada` — volver a regar no duplica nada

### `infrastructure/bootstrap/RequiredSecretsValidatorTest.java` — 3

- `arrancaCuandoTodosLosSecretosEstanPresentes` — arranca cuando todos los secretos estan presentes
- `falloAlArrancarNombraTodoLoQueFalta` — fallo al arrancar nombra todo lo que falta
- `rechazaUnaClaveDeFirmaDemasiadoCorta` — rechaza una clave de firma demasiado corta

### `infrastructure/kira/KiraAmountsTest.java` — 9

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

- `lasRutasDeRfiViajanConLaVersionQueLasContiene` — las rutas de rfi viajan con la version que las contiene
- `laCotizacionViajaConLaVersionQueTraeElDesglose` — la cotizacion viaja con la version que trae el desglose
- `elRestoDeRutasViajaConLaMismaVersion` — el resto de rutas viaja con la misma version
- `losDocumentosDeUnRfiViajanComoMultipartConLaParteFilesYLaMismaVersion` — los documentos de un rfi viajan como multipart con la parte files y la misma version
- `elCatalogoDePaisesVivePorDebajoDeV1` — el catalogo de paises vive por debajo de v 1

### `infrastructure/kira/KiraCredentialManagerTest.java` — 2

- `sinApiKeyLaIntegracionNoEstaConfigurada` — sin api key la integracion no esta configurada
- `sinClientIdOPasswordTampocoYNoEsUnNullPointer` — sin client id o password tampoco y no es un null pointer

### `infrastructure/kira/KiraPropertiesTest.java` — 3

- `aceptaLaVersionYElBancoSoportados` — acepta la version y el banco soportados
- `rechazaOtraVersion` — rechaza otra version
- `rechazaUnBancoNoDocumentadoONoSoportado` — rechaza un banco no documentado o no soportado

### `infrastructure/kira/KiraWebhookVerifierTest.java` — 7

- `aceptaUnaFirmaValidaSobreLosBytesCrudos` — acepta una firma valida sobre los bytes crudos
- `rechazaSiElCuerpoCambiaUnSoloByte` — rechaza si el cuerpo cambia un solo byte
- `reserializarElJsonInvalidaLaFirma` — reserializar el json invalida la firma
- `duranteLaRotacionAceptaTambienElSecretoAnterior` — durante la rotacion acepta tambien el secreto anterior
- `rechazaConOtroSecreto` — rechaza con otro secreto
- `rechazaSinCabeceraDeFirma` — rechaza sin cabecera de firma
- `sinSecretoConfiguradoNoValidaNada` — sin secreto configurado no valida nada

### `infrastructure/observability/ObservabilityTest.java` — 6

- `cadaRespuestaLlevaSuIdDePeticionYRespetaUnoValido` — cada respuesta lleva su id de peticion y respeta uno valido
- `lasMetricasNoSonParaUnaEmpresa` — las metricas no son para una empresa
- `laPlataformaPasaLaReglaDeSeguridadDeLasMetricas` — la plataforma pasa la regla de seguridad de las metricas
- `lasRutasDeKiraSeEtiquetanSinIds` — las rutas de kira se etiquetan sin ids
- `registraLatenciaYResultadoDeKiraYLosWebhooks` — registra latencia y resultado de kira y los webhooks
- `laAuditoriaGuardaElIdDeLaPeticion` — la auditoria guarda el id de la peticion

### `infrastructure/reconciliation/LivenessReconciliationWorkerTest.java` — 3

- `unEnlaceVencidoQuedaMarcadoComoExpirado` — un enlace vencido queda marcado como expirado
- `sinEnlacesVencidosNoSeGuardaNada` — sin enlaces vencidos no se guarda nada
- `unResultadoFinalYaRecibidoNoSePisa` — un resultado final ya recibido no se pisa

### `infrastructure/reconciliation/PayoutReconciliationWorkerTest.java` — 5

- `unPagoEnVueloSeActualizaConElEstadoDelRecurso` — un pago en vuelo se actualiza con el estado del recurso
- `unFalloEnUnPagoNoDetieneElLote` — un fallo en un pago no detiene el lote
- `sinCredencialesDeKiraSeCortaElLoteSinTocarNada` — sin credenciales de kira se corta el lote sin tocar nada
- `sinPagosEnVueloNoSeLlamaAKira` — sin pagos en vuelo no se llama a kira
- `unEstadoTardioNoRevierteUnPagoYaTerminal` — un estado tardio no revierte un pago ya terminal

### `infrastructure/reconciliation/QuotationReconciliationWorkerTest.java` — 3

- `unaCotizacionActivaYVencidaQuedaMarcadaComoExpirada` — una cotizacion activa y vencida queda marcada como expirada
- `sinCotizacionesVencidasNoSeGuardaNada` — sin cotizaciones vencidas no se guarda nada
- `unFalloAlGuardarUnaNoDetieneALasDemas` — un fallo al guardar una no detiene a las demas

### `infrastructure/reconciliation/ReconciliationWorkersDisabledTest.java` — 1

- `conElInterruptorApagadoNoSeRegistraNinguno` — con el interruptor apagado no se registra ninguno

### `infrastructure/reconciliation/ReconciliationWorkersEnabledTest.java` — 1

- `losSieteWorkersSeRegistranCuandoLaReconciliacionEstaActiva` — los siete workers se registran cuando la reconciliacion esta activa

### `infrastructure/reconciliation/RfiReconciliationWorkerTest.java` — 4

- `soloSeSincronizanLasEmpresasDadasDeAltaEnKira` — solo se sincronizan las empresas dadas de alta en kira
- `unFalloEnUnaEmpresaNoDetieneALasDemas` — un fallo en una empresa no detiene a las demas
- `sinCredencialesDeKiraSeCortaSinRecorrerElResto` — sin credenciales de kira se corta sin recorrer el resto
- `sinEmpresasRegistradasNoSeLlamaAlServicio` — sin empresas registradas no se llama al servicio

### `infrastructure/reconciliation/TenantAndAccountReconciliationWorkerTest.java` — 3

- `soloSeConsultanLasEmpresasRegistradasQueAunNoPuedenOperar` — solo se consultan las empresas registradas que aun no pueden operar
- `sinCredencialesElLoteDeEmpresasSeCorta` — sin credenciales el lote de empresas se corta
- `seConsultanLasCuentasAbiertasSalvoLasDesactivadas` — se consultan las cuentas abiertas salvo las desactivadas

### `infrastructure/reconciliation/WebhookReprojectionWorkerTest.java` — 9

- `unEventoPendienteSeVuelveAProyectar` — un evento pendiente se vuelve a proyectar
- `unEventoQueSigueFallandoConservaElMotivoYNoSeMarcaComoProcesado` — un evento que sigue fallando conserva el motivo y no se marca como procesado
- `cadaFalloCuentaUnIntentoMas` — cada fallo cuenta un intento mas
- `alQuintoIntentoElEventoQuedaComoFallidoDefinitivo` — al quinto intento el evento queda como fallido definitivo
- `losEventosAgotadosNoSeVuelvenAPedir` — los eventos agotados no se vuelven a pedir
- `unFalloEnUnEventoNoDetieneALosDemas` — un fallo en un evento no detiene a los demas
- `sinCredencialesDeKiraSeCortaElLoteYLaFilaSiguePendiente` — sin credenciales de kira se corta el lote y la fila sigue pendiente
- `sinEventosPendientesNoSeLlamaAlCasoDeUso` — sin eventos pendientes no se llama al caso de uso
- `elLoteRespetaElTamanoConfigurado` — el lote respeta el tamano configurado

### `infrastructure/security/TotpTest.java` — 5

- `reproduceLosVectoresDeLaRfc6238` — reproduce los vectores de la rfc 6238
- `toleraUnPasoDeDesfaseYNoMas` — tolera un paso de desfase y no mas
- `rechazaFormatosQueNoSonSeisDigitos` — rechaza formatos que no son seis digitos
- `elSecretoNuevoEsBase32DeCientoSesentaBits` — el secreto nuevo es base 32 de ciento sesenta bits
- `laUriLaEntiendenLasAppsAutenticadoras` — la uri la entienden las apps autenticadoras

### `interfaces/rest/KybDocumentUploadTest.java` — 4

- `laParteTypesSeResuelveSinReventarLaCapaWeb` — la parte types se resuelve sin reventar la capa web
- `unTypePorCadaFileOSeRechazaAntesDeLlamarAKira` — un type por cada file o se rechaza antes de llamar a kira
- `elMismoEngancheValeParaLosDocumentosDeUnBeneficiario` — el mismo enganche vale para los documentos de un beneficiario
- `tesoreriaNoSubeDocumentosDeCumplimiento` — tesoreria no sube documentos de cumplimiento

### `interfaces/rest/OpenApiDocsTest.java` — 6

- `laInterfazDeSwaggerSeSirveSinAutenticacion` — la interfaz de swagger se sirve sin autenticacion
- `losEndpointsDeNegocioEstanDocumentados` — los endpoints de negocio estan documentados
- `elEsquemaDeSeguridadEsElJwtPropioDelBff` — el esquema de seguridad es el jwt propio del bff
- `laDocumentacionNoFiltraSecretosNiLaUrlDeKira` — la documentacion no filtra secretos ni la url de kira
- `laVerificacionBiometricaPropiaYaNoExiste` — la verificacion biometrica propia ya no existe
- `elHealthCheckRespondeSinAutenticacion` — el health check responde sin autenticacion

### `interfaces/rest/OperatorControllerTest.java` — 9

- `sinCabeceraDeAutorizacionResponde401ConCuerpo` — sin cabecera de autorizacion responde 401 con cuerpo
- `laFaltaDeSesionNuncaEsUn403Vacio` — la falta de sesion nunca es un 403 vacio
- `unRolSinPermisoSigueSiendo403ConCodigo` — un rol sin permiso sigue siendo 403 con codigo
- `elAdministradorListaCreaYDesactiva` — el administrador lista crea y desactiva
- `cumplimientoLosConsultaPeroNoLosAdministra` — cumplimiento los consulta pero no los administra
- `tesoreriaNoAdministraOperadores` — tesoreria no administra operadores
- `unAltaSinCorreoValidoNoLlegaAlServicio` — un alta sin correo valido no llega al servicio
- `unaContrasenaCortaNoLlegaAlServicio` — una contrasena corta no llega al servicio
- `elComandoDeAltaNoAdmiteEmpresa` — el comando de alta no admite empresa

### `interfaces/rest/ProviderQueryAuthorizationTest.java` — 3

- `soloLecturaNoConsultaAKira` — solo lectura no consulta a kira
- `tesoreriaSiConsultaElEstadoDeSusOperaciones` — tesoreria si consulta el estado de sus operaciones
- `elEnlaceDeUnDocumentoDeRfiEsDeCumplimiento` — el enlace de un documento de rfi es de cumplimiento

### `interfaces/webhook/KiraWebhookControllerTest.java` — 6

- `elWebhookNoExigeJwtPeroSiFirmaValida` — el webhook no exige jwt pero si firma valida
- `rechazaUnaFirmaInvalidaSinTocarLaBase` — rechaza una firma invalida sin tocar la base
- `rechazaSiFaltaLaCabeceraDeFirma` — rechaza si falta la cabecera de firma
- `elMismoEventIdSoloSeAlmacenaUnaVez` — el mismo event id solo se almacena una vez
- `elEventoYaEstaGuardadoCuandoSeResponde200` — el evento ya esta guardado cuando se responde 200
- `unJsonIlegibleConFirmaValidaDa400` — un json ilegible con firma valida da 400

---

## Anexo C. DDL que espera Hibernate (MySQL)

Generado el 16-sep-2026 desde los metadatos JPA con el dialecto `MySQLDialect` (procedimiento en §12.5). Es exactamente el esquema que valida `ddl-auto: validate` en cert y prod.

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

create table notifications (
    created_at datetime(6) not null,
    severity varchar(20) not null,
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    resource_type varchar(40),
    kind varchar(60) not null,
    resource_id varchar(100),
    title varchar(160) not null,
    message varchar(500),
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
    first_approver_user_id varchar(36),
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
    created_by_user_id varchar(36),
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
    resolution_reason varchar(20),
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
    onboarding_draft_updated_at datetime(6),
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
    onboarding_draft json,
    onboarding_payload json,
    primary key (id)
) engine=InnoDB;

create table ubos (
    address_country varchar(3),
    birth_date date,
    country_of_birth varchar(3),
    document_country varchar(3),
    has_control bit not null,
    has_ownership bit not null,
    is_signer bit not null,
    nationality varchar(3),
    ownership_percentage decimal(5,2) not null,
    politically_exposed bit not null,
    synced_to_kira bit not null,
    created_at datetime(6) not null,
    liveness_expires_at datetime(6),
    updated_at datetime(6) not null,
    gender varchar(10),
    address_zip_code varchar(20),
    phone_number varchar(20),
    id varchar(36) not null,
    tenant_id varchar(36) not null,
    document_type varchar(50),
    liveness_status varchar(50) not null,
    address_city varchar(100),
    address_state varchar(100),
    document_number varchar(100),
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    occupation varchar(100),
    person_reference_id varchar(100),
    role_in_company varchar(100),
    address_street varchar(255),
    email varchar(255),
    liveness_link text,
    primary key (id)
) engine=InnoDB;

create table users (
    mfa_enabled bit not null,
    created_at datetime(6) not null,
    notifications_seen_at datetime(6),
    updated_at datetime(6) not null,
    id varchar(36) not null,
    role_id varchar(36) not null,
    tenant_id varchar(36),
    status varchar(50) not null,
    first_name varchar(100) not null,
    last_name varchar(100) not null,
    email varchar(255) not null,
    mfa_secret varchar(255),
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
    tenant_id varchar(36),
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
create index idx_notifications_tenant on notifications (tenant_id, created_at);
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
