# Cronograma — Integración AuTransactional con Kira

**Responsable única:** Carolina Lopez · **Entrega:** jueves 15 de octubre de 2026 · **Estado del build:** 314 pruebas verdes, BUILD SUCCESS (15-sep)

## 1. Calendario laboral

| | |
|---|---|
| Jornada | Lunes a viernes, 8:40 a 18:00 |
| Daily de arranque | 8:40 – 9:00 (20 min) |
| Bloque de foco AM | 9:00 – 12:30 |
| Pausa AM | 10:30 – 10:45 |
| Almuerzo | 12:30 – 13:30 |
| Bloque de foco PM | 13:30 – 17:30 |
| Pausa PM | 15:45 – 16:00 |
| Cierre y registro en ESTADO.md | 17:30 – 18:00 |
| Revisión semanal | Viernes 17:00 – 18:00 |
| Descanso | Sábados, domingos y festivos |

**Capacidad efectiva:** 7,5 h/día de lunes a jueves · 6,5 h los viernes (revisión semanal) · **36,5 h por semana completa**

**Festivo en el rango:** lunes 12 de octubre de 2026 — Día de la Raza

**Total disponible del 16-sep al 15-oct: 21 días hábiles = 153,5 horas efectivas**

## 2. Lo que ya está entregado

### 10 de septiembre — RFIs
- Modelo de RFI con lo que bloquean: `blocking_type`, `blocking_resource_id` e índice

### 11 de septiembre — Depuración, defectos graves y reconciliación
- **Depuración del alcance:** fuera la verificación biométrica propia (8 endpoints, 29 clases, 33 pruebas, tabla `verification_sessions`), fuera la colección Postman, fuera Thymeleaf, fuera los listados globales de Kira
- **Defecto grave corregido:** cotizar y pagar enviaban a Kira los ids locales del BFF en vez de `kiraAccountId` / `kiraRecipientId`. Contra el sandbox real habrían fallado siempre
- **Defectos D1–D6:** `createdAt` perdido en 7 agregados · validación de cuenta y destinatario dentro de la empresa · `503 kira_not_configured` en vez de `500` · `/actuator/health` · `400/404/413` en vez de `500` · semilla en `CREATED`
- **9 rutas de Kira expuestas al portal:** documentos de RFI, preview de pago, eventos de pago, pagos y destinatarios de Kira, sincronización de depósitos, catálogo de países
- **5 workers de reconciliación:** pagos (10 min), cotizaciones (5 min), liveness (1 h), RFIs (15 min), reproyección de webhooks (30 min, tope de 5 intentos)
- **Idempotencia F1:** `IdempotencyKeyStore` consolida la clave en transacción propia antes de llamar a Kira
- **Cotización itemizada** con `X-Api-Version: 2026-06-01`
- Resultado: 283 pruebas verdes

### 14 de septiembre — Credenciales, documentos KYB y verificación real
- **Credenciales de sandbox funcionando:** `POST /auth` da 200 y `GET /api/reference/countries` devuelve el catálogo real de Kira
- **Gestión de secretos:** `.env` en local (ignorado por git), `.env.example` versionado, cert y prod desde el gestor de secretos
- **Documentos KYB — era el bloqueador nº 1 del MVP:** `POST /api/onboarding/documents` y `POST /api/ubos/{id}/documents`, en base64 dentro del `PUT /v1/users`, con tope de 10 archivos y 7 MB
- **Defecto de emparejamiento corregido:** Kira empareja `associated_persons[]` por email y la tabla `ubos` no tenía esa columna; cada sincronización creaba personas duplicadas
- **Verificación end-to-end contra el sandbox** con la empresa `juriscop`: archivo subido y recuperable, fusión del array correcta, payload persistido en 312 bytes sin base64, 5 UBO duplicados resueltos en 1 sola persona en Kira
- Resultado: 305 pruebas verdes · colección Bruno 86/86 peticiones, 48/48 tests

### 15 de septiembre — Borrador de onboarding y auditoría de alcance
- Servicio y controlador de borrador de onboarding con sus pruebas
- Auditoría completa del repositorio y construcción de este cronograma
- Resultado: **314 pruebas verdes, BUILD SUCCESS**

## 3. Lo que falta

El BFF está funcionalmente completo: 171 clases de producción, 49 operaciones REST sobre 42 rutas, 12 tablas, 5 workers. **Lo que queda no es construir, es ejercitar contra Kira real.** Las pruebas simulan Kira, y ahí es justo donde han aparecido los defectos graves: los ids del portal el 11-sep, el `400` de `completeProfile` el 14-sep.

**Defecto abierto y confirmado hoy:** `SubmitOnboardingService.completeProfile` reenvía la fusión completa del payload, que incluye `type` y `external_id`. Kira sólo los acepta en el alta, así que **`PUT /api/onboarding` falla siempre contra Kira real**. Es el primer punto del plan.

## 4. Fases

| Fase | Fechas | Días | Foco |
|---|---|---|---|
| 1. Desbloqueo de onboarding | 16 – 18 sep | 3 | Arreglar el 400, commitear la rama, recorrer KYB y UBOs en sandbox |
| 2. Tesorería en sandbox | 21 – 25 sep | 5 | Cuentas virtuales, depósitos, destinatarios, cotizaciones |
| 3. Pagos, webhooks y RFIs | 28 sep – 2 oct | 5 | El flujo de mayor riesgo, más entregas reales de webhook |
| 4. Cierre funcional | 5 – 9 oct | 5 | Deuda menor, decisiones abiertas, documentación, congelación |
| 5. Certificación y entrega | 13 – 15 oct | 3 | DDL, despliegue a cert, smoke, acta |

## 5. Calendario día a día

### Semana 1 · 16 al 18 de septiembre

**Miércoles 16 — Arreglar el `400` de onboarding** (7,5 h)
- 09:00–11:00 · Excluir `type` y `external_id` del cuerpo del `PUT` en `completeProfile`
- 11:00–12:30 · Prueba de regresión que fije el contrato del PUT, y revisar el comentario del código que afirma que Kira borra lo que no viaja (la documentación dice lo contrario)
- 13:30–15:45 · Verificar contra el sandbox con `juriscop`: PUT 200 y `missing_fields` correcto
- 16:00–17:30 · `business_type` es un enum cerrado: exponer la lista al portal en vez de texto libre
- 17:30–18:00 · Registro en ESTADO.md

**Jueves 17 — Commit de la rama y recorrido de identidad** (7,5 h)
- 09:00–10:30 · Revisar `git diff master` y commitear `depuracion-bff` en commits temáticos
- 10:45–12:30 · Bruno modo B contra sandbox: carpetas 00 Sesión y 01 Onboarding KYB
- 13:30–17:30 · Bruno modo B: carpeta 02 Beneficiarios finales · registrar cada defecto encontrado

**Viernes 18 — Corrección** (6,5 h)
- 09:00–12:30 y 13:30–17:00 · Corregir los defectos de onboarding y UBOs del recorrido
- 17:00–18:00 · Revisión semanal

### Semana 2 · 21 al 25 de septiembre

**Lunes 21 — Cuentas virtuales** (7,5 h) · Bruno 04 contra sandbox: apertura, activación, readiness, modos
**Martes 22 — Depósitos** (7,5 h) · Bruno 05 contra sandbox · decidir si hace falta un sexto worker que llame a `RecordDepositService.syncFromKira()` por cuenta
**Miércoles 23 — Destinatarios** (7,5 h) · Bruno 06 contra sandbox · verificar el `RecipientMapper` de 29 columnas y el enmascarado de `account_number` y `address`
**Jueves 24 — Cotizaciones** (7,5 h) · Bruno 07 contra sandbox · subir `CreateQuoteService` a `X-Api-Version: 2026-06-01` y comprobar qué campos se pierden hoy con `2026-04-14`
**Viernes 25 — Corrección** (6,5 h) · Defectos de la semana · revisión semanal 17:00–18:00

### Semana 3 · 28 de septiembre al 2 de octubre

**Lunes 28 — Pagos, parte 1** (7,5 h) · Bruno 08: preview y ejecución contra sandbox. Es el flujo con más traducción de ids y donde apareció el defecto grave del 11-sep
**Martes 29 — Pagos, parte 2** (7,5 h) · Eventos, comprobante, `blockedByRfiId`, maker-checker, atribución por `user_id`
**Miércoles 30 — Webhooks reales** (7,5 h) · Túnel público, verificación de firma contra entregas reales de Kira, reproyección
**Jueves 1 de octubre — RFIs** (7,5 h) · Bruno 03 contra sandbox · confirmar la suscripción `rfi.*` · guardar `resolution_reason` de los RFI cerrados
**Viernes 2 — Corrección** (6,5 h) · Defectos de la semana · revisión semanal 17:00–18:00

### Semana 4 · 5 al 9 de octubre

**Lunes 5 — Deuda menor** (7,5 h) · Propagar el `warnings[]` de Kira al portal · cerrar los pendientes de la pieza de documentos KYB
**Martes 6 — Decisiones abiertas** (7,5 h) · Tablas huérfanas de la base de dev (`audit_log`, `operator_user`, `payout`, `tenant`, `webhook_event`) · ¿pago sin cotización? · ¿`memo` obligatorio en WIRE?
**Miércoles 7 — Robustez del cliente** (7,5 h) · Honrar el `expires_in` del token en vez del TTL fijo de 3300 s · endpoint de gestión de operadores si se decide incluirlo
**Jueves 8 — Documentación** (7,5 h) · API-GUIA, GUIA-BRUNO, ARQUITECTURA y ESTADO al día · colección Bruno completa y verificada
**Viernes 9 — Congelación de alcance** (6,5 h) · Suite completa verde · nada nuevo entra después de hoy · revisión semanal

### Semana 5 · 13 al 15 de octubre

**Lunes 12 — FESTIVO, Día de la Raza** · Sin trabajo
**Martes 13 — Despliegue a certificación** (7,5 h) · Aplicar a mano las 4 sentencias DDL (cert y prod van con `ddl-auto: validate`, sin Flyway) · cargar los secretos rotados en el gestor · desplegar
**Miércoles 14 — Smoke en certificación** (7,5 h) · Colección Bruno completa contra cert con las credenciales nuevas · verificar los 5 workers en marcha
**Jueves 15 — ENTREGA** (7,5 h) · Acta de entrega, evidencias del recorrido, traspaso · **Integración completa entregada**

## 6. Cambios de esquema a aplicar a mano antes de desplegar

Cert y prod van con `ddl-auto: validate` y no hay Flyway: si estas sentencias no se aplican antes de subir la versión, el arranque falla la validación.

```sql
ALTER TABLE rfis
  ADD COLUMN blocking_type VARCHAR(30) NULL,
  ADD COLUMN blocking_resource_id VARCHAR(100) NULL,
  ADD INDEX idx_rfis_blocking (blocking_resource_id);

DROP TABLE IF EXISTS verification_sessions;

ALTER TABLE webhooks_log ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

ALTER TABLE ubos ADD COLUMN email VARCHAR(255) NULL;
```

## 7. Dependencias externas — pedir a Kira esta semana

Ninguna depende de mí, y las tres pueden mover la fecha de entrega si llegan tarde.

| Qué | Para cuándo se necesita | Por qué |
|---|---|---|
| Suscripción explícita a los eventos `rfi.*` | Antes del 1 de octubre | Sin ella los RFIs no llegan por webhook y sólo funcionan por sincronización manual |
| Credenciales nuevas de Cognito | Antes del 13 de octubre | La `api_key` y el secreto actuales se compartieron por chat, un canal no seguro. No pueden ir a producción |
| Host público o dominio preautorizado | Antes del 30 de septiembre | Para recibir entregas reales de webhook y verificar la firma |

## 8. Riesgos

| Riesgo | Impacto | Mitigación en el plan |
|---|---|---|
| El recorrido en sandbox destapa defectos como los del 11-sep y el 14-sep | Alto — es lo más probable | Cada semana cierra con un viernes entero reservado a corregir |
| Pagos es el flujo con más traducción de ids | Alto | Dos días completos, 28 y 29 de septiembre |
| Las dependencias de Kira llegan tarde | Medio | Pedirlas esta semana, no cuando toquen |
| Cola de reproyección bloqueada por una fila `rfi.*` sin credenciales | Bajo — no se da con credenciales | Ya hay tope de 5 intentos; con cert y prod no ocurre |
| Cambios de esquema a mano en cert y prod | Medio | Sección 6, aplicados el 13 de octubre antes del despliegue |

## 9. Holgura

El trabajo neto estimado es de unos 8 días. El plan ocupa 21 días hábiles. **La holgura es de unos 9 a 10 días**, repartida a propósito en los viernes de corrección y en la semana del 5 al 9 de octubre, porque en este proyecto el sandbox ha destapado un defecto grave en cada recorrido nuevo. Si las tres semanas de sandbox salen limpias, la entrega puede adelantarse a la semana del 5 de octubre.
