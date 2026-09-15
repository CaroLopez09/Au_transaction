# Requisitos vs. código vs. documentación

**Fecha:** 15 de septiembre de 2026 · **Ramas:** BFF `develop` (`a8643b0`) · front `develop` (`dedf683`)
**Fuentes cruzadas:** `arquitectura_frontend_kirafin (1).docx` (en adelante **ARQ**) · `docs.kirafin.ai` (MCP `kira-docs`) ·
código del BFF y del front · `ESTADO.md`, `API-GUIA.md`, `REVISION-DOCS-KIRA.md`,
`REVISION-INTEGRAL-FRONT-BFF.md`, `au-transactional-web/docs/frontend-backend-contract.md`

Leyenda: ✅ hecho y verificado · ◐ parcial · ❌ no existe · ❓ depende de una respuesta de Kira

---

## 1. Resumen

- **Cubierto:** el MVP de ARQ §9 está hecho: sesión con MFA, RBAC, vinculación con UBO, documentos
  y liveness, elegibilidad, cuentas, saldo, depósitos, destinatarios, cotización, maker-checker,
  pagos, RFIs, eventos, avisos, auditoría y consola de solo lectura. Contra el sandbox, Bruno da
  88/88 y las carpetas nuevas 26/26.
- **Hueco funcional real:** no hay **instrucciones de pago cripto** (G-19). ARQ §2.4 las lista y
  forman parte de la fase 3.
- **Huecos de control (ARQ §5, §7 y §8), sin nada construido:**
  - límites por monto y aprobaciones múltiples;
  - consentimiento biométrico y de términos;
  - análisis antimalware de archivos;
  - observabilidad (métricas, trazas, correlación);
  - parametrización y feature flags;
  - administración de operadores;
  - revocación de sesión.
- **Pendiente de Kira:**
  - D2 `transaction_countries`, D3 banco (`slovak_savings_bank` no está documentado), D4
    `tos_accepted_version`, D5 `ein` y D9 vigencia de la cotización en maker-checker;
  - la versión única de la API, que el go-live checklist exige y hoy se mezcla
    (`2026-04-14` y `2026-06-01`).
- **Documentación desfasada:**
  - `ARQUITECTURA.md` y `DOCUMENTACION-CODIGO.md` son del 11-sep; la segunda dice que no hay MFA.
  - Las secciones 1, 4.1, 4.4 y 8 de `ESTADO.md` están atrasadas.
  - `API-GUIA.md` no documenta `/api/onboarding/draft`.
  - Los tres documentos del front no mencionan MFA, avisos ni consola.

Recomendación de orden para lo que queda hasta el 15-oct, en §7.

---

## 2. Experiencias y módulos (ARQ §1–§2)

### 2.1 Portal de vinculación

| Requisito ARQ | Estado | Dónde | Falta |
|---|---|---|---|
| Inicio de solicitud (producto/banco, modo `automatic` / `verification_link`) | ◐ | `POST /api/onboarding` | Siempre modo automático. El banco sale de `KIRA_BANK` y no se elige (❓ D3) |
| Datos de la empresa y perfil de riesgo | ✅ | asistente del front; `SubmitOnboardingService.forUpdate` | Lo que Kira no devuelve se conserva en `onboarding_payload` |
| Borrador persistente por etapas | ✅ | `/api/onboarding/draft` | **Sin documentar en `API-GUIA.md`** ni en Bruno |
| Propiedad y control (CRUD UBO, reenvío del conjunto completo) | ✅ | `/api/ubos`, `SyncUbosService` | Criterio ARQ §10 «no borra nacionalidad ni duplica»: cubierto por emparejamiento por correo y prueba |
| Documentos corporativos | ✅ | `POST /api/onboarding/documents` | No se puede listar ni reemplazar un documento subido (Kira no lo ofrece; ESTADO §4.3). `warnings[]` no llega al portal |
| Documentos personales y selfie | ✅ | `POST /api/ubos/{id}/documents` | — |
| Prueba de vida | ✅ | `POST /api/ubos/liveness-links`, webhook `user.liveness_completed` | Solo el evento marca el resultado |
| Revisión y envío con **declaraciones y consentimiento** | ◐ | — | **No hay pantalla ni registro de consentimiento** (ARQ §2.1 y §7), ni `tos_accepted_version` (❓ D4) |
| Estado con timeline y remediación | ✅ | `stageCopy`, `rejectionReason` | — |
| Faltantes y elegibilidad por producto | ✅ | `MissingFields.forProduct`, `unsupported_reason` | G-16: los faltantes llegan con nombres técnicos y el front usa un diccionario propio |

### 2.2 Clientes, operaciones y cumplimiento

| Requisito ARQ | Estado | Dónde | Falta |
|---|---|---|---|
| Listado de clientes con filtros | ◐ | `GET /api/platform/tenants` | Filtros solo en el cliente; faltan país, fecha y responsable interno. No hay paginación (G-14) |
| Ficha 360 | ✅ | `/api/platform/tenants/{id}` | — |
| Remediación KYB desde operaciones | ❌ | — | La consola es **de solo lectura** por decisión del 15-sep; la remediación la hace el cliente |
| Bandeja de revisión | ◐ | `/api/platform/review-queue` | Antigüedad sí; prioridad y escalamiento a Kira no |
| Rechazos con razones | ✅ | `reasonsOf(payload)` → `rejectionReason` | — |
| Auditoría con antes/después | ◐ | `GET /api/audit` | Guarda `changes` como texto libre, no un diff estructurado. **Sin exportación** (`exportAudit`) |

### 2.3 Cuentas virtuales y tesorería

| Requisito ARQ | Estado | Falta |
|---|---|---|
| Solicitud, listado, detalle, saldo | ✅ | Solo modo fiat; no hay elección de banco |
| Activación: no mostrar `approved` como lista | ✅ | `VirtualAccountReadiness`, `FROZEN` |
| Depósitos globales y por cuenta con estado | ✅ | KYT, retenidos, ordenante enmascarado |
| **Conciliación** con facturas o clientes internos y exportación | ❌ | Fase 5 de ARQ; fuera del MVP |

### 2.4 Destinatarios y pagos

| Requisito ARQ | Estado | Falta |
|---|---|---|
| Directorio, alta WIRE/ACH/WALLET, detalle enmascarado | ✅ | — |
| Corregir = reemplazo + archivar | ✅ | `POST /api/recipients/{id}/archive`. **G-26:** un pago antiguo no muestra el nombre del destinatario archivado |
| Cotizador con temporizador y bloqueo al vencer | ✅ | ❓ D9: si el aprobador actúa después de vencer, el pago falla; hay que definir si se recotiza al aprobar |
| Crear pago con soportes e idempotencia | ✅ | `Idempotency-Key` desde el portal |
| Maker-checker, maker ≠ aprobador | ✅ | `Payout.approve` lo impide |
| **Límites por monto, aprobaciones múltiples y política configurable** | ❌ | ARQ §5 *Approval engine* y §8 |
| **Segregación: quien crea el destinatario no aprueba el pago** | ❌ | ARQ §7 |
| Seguimiento: timeline, KYT, referencia, devoluciones | ✅ | `/api/payouts/{id}/events`, `CANCELLED` |
| **Instrucciones cripto** (dirección, red, QR, expiración) | ❌ | G-19: el BFF no lee `payment_instructions` y no hay pantalla. Estado `EXPIRED` de pagos cripto sin tratamiento |
| Recibo o comprobante descargable | ◐ | Hay referencia y hash en el detalle; no hay recibo |

### 2.5 RFIs

| Requisito ARQ | Estado | Falta |
|---|---|---|
| Bandeja, detalle, respuesta tipada (9 tipos), lote atómico | ✅ | — |
| Documentos: subir, enlace temporal, borrar con confirmación | ✅ | Enlace de descarga auditado desde el 15-sep |
| Borrar documento como permiso configurable por rol | ◐ | Permiso fijo por rol en código; no es configurable |
| `ubo_link`, retirados, motivo de cierre | ✅ | — |

### 2.6 Eventos, alertas y soporte

| Requisito ARQ | Estado | Falta |
|---|---|---|
| Avisos dentro de la app | ✅ | Sin preferencias de usuario (`NotificationService` ARQ §4). Sin correo o SMS, por decisión del 15-sep |
| Centro de eventos | ✅ | Falta la columna de correlación |
| **Incidencias de integración** (errores de API, webhooks fallidos, desincronizados) | ◐ | Se ven `processingError` y `retryCount` por evento; no hay vista agregada ni errores de llamadas a Kira |
| **Soporte y escalamiento** (caso con evidencia para Kira) | ❌ | Capacidad interna; baja prioridad |
| Panel de inicio por rol | ◐ | Cliente: `home-attention`. Operaciones: la bandeja de revisión hace de panel; faltan RFIs por vencer, pagos en KYT y fallos de sincronización (ARQ §3) |

---

## 3. Servicios del front (ARQ §4)

| Servicio ARQ | Estado | Nota |
|---|---|---|
| SessionService | ◐ | Sin `switchOrganization` (un usuario pertenece a una empresa); logout solo local (G-04); sin refresh (G-05) |
| ReferenceDataService | ◐ | Países de Kira, NAICS y tipos jurídicos estáticos. Sin `getProductRules` ni catálogo de bancos, rieles y redes |
| CustomerService | ✅ | Consola de plataforma |
| OnboardingService, BeneficialOwnerService, DocumentService, LivenessService, EligibilityService | ✅ | Facades y repositorios por dominio |
| VirtualAccountService, DepositService | ✅ | `reconcileDeposit` ❌ (sin conciliación) |
| RecipientService, QuotationService | ✅ | — |
| PayoutService | ✅ | `getApprovalPolicy` ❌ |
| RfiService | ✅ | — |
| NotificationService | ◐ | Sin preferencias |
| EventService | ◐ | Polling cada 60 s en avisos; sin suscripción por recurso |
| PermissionService | ✅ | `core/permissions` con capacidades |
| AuditService | ◐ | Sin `exportAudit` |
| ErrorMappingService | ✅ | Mapeo en `core/http` |
| Telemetría en la capa tipada | ❌ | ARQ §4 la menciona; nada instalado (bien: ARQ §10 prohíbe datos personales en analytics) |

---

## 4. Servicios del BFF (ARQ §5)

| Servicio ARQ | Estado | Evidencia / falta |
|---|---|---|
| Autenticación propia y RBAC | ✅ | JWT, 6 roles, MFA TOTP, `@PreAuthorize`. G-09 cerrado el 15-sep: los refrescos que consultan a Kira excluyen `READ_ONLY` y el enlace de documento RFI es de cumplimiento y queda auditado |
| Kira credential manager | ✅ | `KiraCredentialManager`. Caché fija de 3300 s que no honra `expires_in` (seguro, pero impreciso). Credenciales del sandbox por rotar |
| Kira API adapter | ◐ | **Versión mezclada**: `2026-04-14` por defecto y `2026-06-01` para RFIs y cotizaciones; el go-live checklist exige una sola |
| Base de datos de dominio | ✅ | Borradores, campos no devueltos, razones, archivados. Sin Flyway: DDL a mano (ESTADO §7) |
| Almacenamiento documental | ◐ | MIME y tamaño validados por la cabecera declarada, **no por el contenido real**. **Sin antimalware.** No hay URLs temporales propias (van a Kira) ni registro de retención |
| Webhook ingress | ✅ | HMAC sobre bytes crudos, guarda antes del 2xx, deduplica y admite dos secretos |
| Event processor | ✅ | Proyecciones, avisos y `tenant_id` |
| Reconciliation workers | ✅ | 7 workers. Queda abierto un worker de depósitos por cuenta (ESTADO §4.2) |
| Idempotency manager | ✅ | Cuentas, destinatarios y pagos. G-23: una cuenta local sin `kiraAccountId` tras un fallo de Kira (no re-verificado hoy) |
| **Approval engine** | ◐ | Maker-checker simple. **Sin límites, sin varias firmas y sin política configurable** |
| Audit log inmutable | ◐ | Solo inserción desde la aplicación, sin garantía en base de datos. Guarda la idempotency key en `changes`; **sin id de correlación** |
| Notification gateway | ✅ | Dentro de la app (alcance acordado) |
| **Observabilidad** | ❌ | Solo `actuator/health`. Sin Micrometer ni Prometheus, sin trazas, sin `X-Request-Id` o MDC y sin alertas de webhooks o latencia de Kira |

---

## 5. Estados, seguridad y parametrización (ARQ §6–§8)

**Estados (§6):** ✅ comparación sin mayúsculas y estados desconocidos tolerados. El depósito
desconocido ya no acredita. Cuentas: `approved` no se trata como operativa. Pagos: `KYT_PENDING`,
`IN_REVIEW`, `CANCELLED` y `EXPIRED` existen en `PayoutStatus`; `EXPIRED` solo tiene sentido pleno
con las instrucciones cripto (G-19), que no existen.

**Seguridad (§7):**

| Control | Estado |
|---|---|
| Mínimo privilegio (admin, maker, aprobador, compliance, lectura, plataforma) | ✅ (falta el rol de onboarding separado; lo cubre ADMIN) |
| Segregación destinatario ↔ aprobación | ❌ |
| MFA | ✅ TOTP (passkeys no) |
| **Consentimiento biométrico** | ❌ |
| Enmascarado de cuentas | ✅ cuentas y ordenante. ◐ identificadores fiscales en la ficha 360 (revisar) |
| Sesiones alojadas: enlace de liveness asociado a la persona y resultado solo por evento | ✅ |
| Documentos: MIME real, URLs temporales, auditoría de descargas | ◐ (§4) |
| Confirmación de pago con monto, comisión, neto y vencimiento | ✅ |
| Doble clic / idempotencia | ✅ |
| Privacidad por tenant | ✅ (probado: la plataforma en una ruta de empresa ve vacío; otra empresa recibe 403) |

**Parametrización (§8):** ❌ **no hay módulo administrativo.** Productos, bancos, rieles, tokens y
redes, reglas documentales, límites, permisos, textos, retención y feature flags viven en código o
en variables de entorno (`KIRA_BANK`, `bff.reconciliation.enabled`, `mfa-enforced`). Para un solo
cliente piloto esto es aceptable; hay que declararlo como deuda.

---

## 6. Criterios de aceptación (ARQ §10)

| Área | Estado | Cómo se comprueba hoy |
|---|---|---|
| Seguridad: ningún token de Kira en el navegador | ✅ | Todo pasa por el BFF |
| Multiempresa | ✅ | Pruebas de controlador y E2E |
| Onboarding: salir y reanudar; faltantes accionables | ✅ | Borrador y `step-gaps` |
| UBO no pierde datos ni duplica | ✅ | `SyncUbosServiceTest`, correo único |
| Liveness: solo el evento confirma | ✅ | `UserEventProjectionTest` |
| Cuenta activa no por `approved` | ✅ | `VirtualAccountReadinessTest` |
| Pagos: doble clic y cotización vencida | ✅ | `ExecutePayoutServiceTest` |
| Maker-checker con política configurable | ◐ | Regla fija, no configurable |
| Eventos duplicados y perdidos | ✅ | Deduplicación + reconciliación |
| Estados case-insensitive y desconocidos | ✅ | Pruebas de estados |
| Documentos: descargas y borrados auditados | ✅ | `compliance.rfi_document_link_issued` y `compliance.rfi_document_removed` |
| Errores con acción concreta | ✅ | Mapeo en el front |

---

## 7. Pendientes de Kira y del contrato

| # | Tema | Situación | Acción |
|---|---|---|---|
| D2 | `transaction_countries` ISO-2 o ISO-3 | El sandbox acepta ambos | Pregunta escrita a Kira; hoy no se pide en el asistente (G-28) |
| D3 | Banco | `KIRA_BANK=slovak_savings_bank` no está documentado; solo `austin_capital_trust` y `jp_morgan` | **Bloqueante antes de abrir cuentas reales.** Confirmar con Kira y enviar `capabilities.requested_banks` |
| D4 | `tos_accepted_version` y consentimiento | En el sandbox se usó un valor inventado (`2026-01`) | Pedir la versión real a Kira y construir la pantalla de consentimiento |
| D5 | `ein` solo para EE. UU. | El front ofrece carta EIN a cualquier empresa | Validación condicional por país |
| D9 | Cotización y aprobación tardía | La cotización vence antes de que apruebe el checker | Decidir: recotizar al aprobar o aprobar dentro de la vigencia |
| V1 | Versión única de la API | Mezcla `2026-04-14` y `2026-06-01` | Migrar todo a `2026-06-01` y retirar el apaño de `approved` |
| S1 | Credenciales compartidas por chat | Sandbox | Rotarlas antes de producción |
| S2 | Suscripción a `rfi.*` | Hay que pedirla explícitamente | Confirmar con Kira |

---

## 8. Documentación desfasada

| Documento | Problema | Arreglo |
|---|---|---|
| `ESTADO.md` §1 | Habla de la rama `depuracion-bff` sin commitear y de 283 pruebas | Rama `develop`, 370 pruebas |
| `ESTADO.md` §4.1 | «Commit de la rama» | Hecho: borrar |
| `ESTADO.md` §4.4 | `resolution_reason` no se guarda | Ya se guarda (§3.12) |
| `ESTADO.md` §6 | «Flujo real sin probar» | Recorrido contra el sandbox (§3.9–§3.12) |
| `ESTADO.md` §8 | Cifras del 11-sep (305 pruebas, 49 operaciones) | 370 pruebas, 55 operaciones |
| `DOCUMENTACION-CODIGO.md` | Del 11-sep; dice «no hay MFA implementado» y le faltan avisos, consola, workers nuevos, UBO e idempotencia | Regenerar los anexos y reescribir las secciones de seguridad y webhooks |
| `ARQUITECTURA.md` | No menciona MFA, `PLATFORM_OPERATOR` ni avisos | Añadir los tres bloques |
| `API-GUIA.md` | Falta `GET/PUT /api/onboarding/draft` | Añadir sección |
| Bruno | Falta `/api/onboarding/draft` y `mfa/disable` | Dos peticiones |
| Front `frontend-architecture.md` y `frontend-qa.md` | Sin MFA, avisos ni consola; los E2E citados son 31 | Actualizar |
| Front `frontend-backend-contract.md` | G-09 y G-23 siguen abiertos; revisar si G-26 y G-19 entran en alcance | Mantener |
| ClickUp | Muchas tareas planificadas ya están hechas; faltan 8 (AUT-044..051) | Conciliar el backlog el 16-sep |

---

## 9. Qué hacer hasta el 15-oct (propuesta priorizada)

Estimación con 7,5 h/día (6,5 los viernes).

| Prioridad | Trabajo | Motivo | Estimación |
|---|---|---|---|
| **P0** | D3 banco + `requested_banks` | Sin esto la cuenta real puede fallar | 0,5 d + respuesta de Kira |
| **P0** | V1 versión única `2026-06-01` | Go-live checklist | 1,5 d (cuentas, pagos, depósitos, pruebas y Bruno) |
| **P0** | D4 consentimiento + `tos_accepted_version` (pantalla, registro auditado, envío) | ARQ §7 y Kira | 1,5 d |
| ~~P0~~ | ~~G-09 `@PreAuthorize` en refrescos + auditar descarga de documentos RFI~~ | **Hecho 15-sep** | — |
| **P0** | DDL §7 en cert y `BFF_MFA_ENCRYPTION_KEY`; rotar credenciales | Despliegue | 0,5 d |
| **P1** | Observabilidad mínima: Micrometer/Prometheus, `X-Request-Id` en MDC y en `audit_log`, métricas de webhooks y latencia de Kira | ARQ §5, operación en producción | 2 d |
| **P1** | Validación de MIME real (firma de bytes) en KYB y RFI | ARQ §7 | 0,5 d |
| **P1** | D9 recotizar al aprobar | Evita pagos fallidos | 1 d |
| **P1** | D5 `ein` condicional | Evita rechazos | 0,5 d |
| **P1** | Límites por monto por empresa (umbral en configuración → segunda firma) + segregación destinatario/aprobador | ARQ §5 y §7 | 2,5 d |
| **P1** | Documentación desfasada (§8) | Entrega | 1 d |
| **P2** | Instrucciones cripto (G-19) | Solo si el piloto usa cripto: **preguntar** | 2 d |
| **P2** | Administración de operadores (G-13) | Hoy se dan de alta por semilla o SQL | 2 d |
| **P2** | Paginación y filtros de servidor (G-14), exportación de auditoría | Volumen | 1,5 d |
| **P3** | Antimalware, parametrización y feature flags, conciliación, soporte, preferencias de avisos, refresh token o revocación | ARQ fase 5 o infraestructura | Fuera del 15-oct salvo que se pida |

**Total P0 + P1 ≈ 12 días**, de unos 21 hábiles hasta el 15-oct. Deja margen para las pruebas en
cert, las respuestas de Kira y un P2 (cripto u operadores, según lo que confirme el cliente).

### Decisiones que necesito de Carolina

1. ¿El piloto usa pagos cripto? Si sí, G-19 pasa a P1.
2. ¿Los límites por monto son por empresa y fijos en configuración, o necesitan pantalla?
3. ¿Antimalware se resuelve en infraestructura (por ejemplo ClamAV en el despliegue) o queda como
   deuda declarada?
