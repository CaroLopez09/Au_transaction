# Revisión integral: front, BFF, arquitectura y Kira

**Fecha:** 15 de septiembre de 2026
**Alcance:** `AuTransactional` (BFF) · `au-transactional-web` (front Angular) ·
`docs/arquitectura_frontend_kirafin (1).docx` (arquitectura pedida) · `docs.kirafin.ai` (MCP `kira-docs`)
**Estado (15-sep, noche):** §1 (B1–B4, más B6 encontrado al corregir), §2 salvo D2–D5 y D9, el plan de §5 y el alcance ampliado (MFA, avisos, eventos, auditoría y consola de operaciones) están hechos y verificados. Siguen abiertos: D2 formato de países, D3 banco solicitado, D4 términos y consentimiento, D5 `ein` solo EE. UU. y D9 duración de la cotización. Detalle en [`ESTADO.md`](ESTADO.md) §3.11–§3.13.

> Complementa [`REVISION-DOCS-KIRA.md`](REVISION-DOCS-KIRA.md) (webhooks y estados, W1–W7), que no se repite aquí.
> Los huecos `G-NN` son los del front: `au-transactional-web/docs/frontend-backend-contract.md §4`.

---

## 0. Estado verificado hoy

| | Resultado | Cómo |
|---|---|---|
| BFF | **314 pruebas, 0 fallos, 0 errores** | `./mvnw test` (informes de surefire) |
| Front: compilación | OK | `ng build` |
| Front: pruebas unitarias | **118 / 118** en 8 ficheros | `ng test --watch=false` |
| Front: lint | Sin avisos | `ng lint` |
| Front: E2E (Playwright) | **No ejecutado** | Necesita el BFF arrancado |
| Contrato de campos front ↔ BFF | Los DTO del front coinciden con las vistas y comandos del BFF | Comparación campo a campo |
| Rutas | El front usa 46 de las 49 operaciones del BFF | Inventario de llamadas HTTP |

Rutas del BFF que el front no usa: `POST /api/payouts/preview`, `GET /api/quotations` (listado) y
`GET /api/recipients/{id}/kira`. Ninguna es necesaria para el flujo actual.

> ⚠ **El repositorio del front no tiene ni un commit** (`main` vacía, todo sin seguimiento). Un
> borrado accidental se lleva el front entero. Es lo primero que conviene resolver, y cuesta 5 minutos.

**Conclusión:** las dos piezas están bien construidas y hablan el mismo idioma entre sí. Los
huecos graves están **entre el BFF y Kira**: lo que se manda y lo que se lee no coincide con lo
que Kira documenta. Ninguna prueba lo detecta, porque todas simulan a Kira.

---

## 1. 🔴 Bloqueantes: fallarán contra Kira real

Todos rompen la **vinculación**, que es la puerta de todo lo demás: sin empresa `VERIFIED` no hay
cuentas, depósitos ni pagos.

### B1. El asistente manda a `PUT /v1/users` campos que sólo existen al crear

El front construye el perfil en `onboarding-draft.ts:231` (`toProfile`) y lo envía por
`PUT /api/onboarding`. El BFF lo fusiona y lo reenvía tal cual a `PUT /v1/users/{id}`
(`SubmitOnboardingService.java:129`). El alta (`POST /api/onboarding`) sólo manda tres campos, así
que **todo el expediente viaja por el PUT**. Pero Kira nombra distinto varios campos en la
actualización:

| Lo que manda el front | En `POST /v1/users` | En `PUT /v1/users/{id}` (documentación) |
|---|---|---|
| `representative_date_of_birth` | ✅ | ❌ se llama **`representative_birth_date`** |
| `registered_address { street_line_1, city, … }` | ✅ | ❌ no está en el cuerpo del PUT; existen `address_street`, `address_city`, `address_state`, `address_zip_code`, `address_country` |
| `business_trade_name` | ✅ (no se devuelve) | ❌ usar **`doing_business_as`** |
| `has_material_intermediary_ownership` | ✅ (no se devuelve) | ❌ no existe en el PUT |

El 14-sep (§3.10 de ESTADO) Kira respondió `400 Invalid request data` a un PUT con un campo que
no le correspondía (`type`). **Lo más probable es que estos cuatro provoquen el mismo 400** y el
paso «Enviar al proveedor» falle siempre. Se comprueba en 15 minutos contra el sandbox con
`juriscop`.

**Arreglo recomendado (BFF, no front):**
1. Mandar el expediente completo en el **alta** (`POST /v1/users`), donde todos esos nombres son
   válidos, en vez de alta mínima + PUT.
2. En el PUT, traducir nombres en un único sitio (`representative_date_of_birth` →
   `representative_birth_date`…) y quitar lo que el PUT no acepta.
3. Quitar `type` y `external_id` del PUT (AUT-015).

### B2. La sincronización de beneficiarios pasa por el mismo PUT roto

`POST /api/ubos/sync` → `SyncUbosService.syncToKira` → `completeProfile` (`SyncUbosService.java:101`),
que reenvía el payload fusionado con `type` y `external_id`. **Mientras AUT-015 siga abierto,
tampoco se pueden sincronizar los UBO.** Arreglar AUT-015 desbloquea los dos.

### B3. Los webhooks de vinculación no mueven nada

Detalle en [`REVISION-DOCS-KIRA.md`](REVISION-DOCS-KIRA.md) §1. En resumen, para el front:

| Evento | Qué verá el operador |
|---|---|
| `user.status_changed` (W3) | La empresa se queda en `CREATED`/`VERIFYING` aunque Kira ya la haya verificado, hasta pulsar «Actualizar» |
| `user.verification.failed` (W4) | «No aprobada» sin motivo, y el motivo ya no se puede recuperar (G-08) |
| `user.liveness_completed` (W5) | Los beneficiarios nunca aparecen con la prueba de vida completada |

### B4. Los beneficiarios no llevan los datos que piden los bancos

Kira acepta por persona `birth_date`, `nationality`, `residential_address`, `occupation`,
`gender`, `phone_number` y `document_country`. La documentación dice de `occupation`, `gender` y
`phone_number`: *«Required by some sponsor banks to verify the person»*.

- **BFF:** `Ubo` no tiene ninguno de esos campos; `toAssociatedPerson` (`SyncUbosService.java:215`)
  no los manda. Además manda `person_reference_id`, que no es un campo de entrada.
- **Front:** `owner-form.ts` no los pide.
- **Arquitectura, criterio de aceptación UBO:** *«Actualizar una persona no borra nacionalidad ni
  datos del documento»*. Hoy la nacionalidad no existe.

**Efecto:** la vinculación puede quedarse pidiendo datos de personas (`missing_fields`) que el
portal no tiene forma de capturar.

---

## 2. 🟠 Desalineaciones importantes

| # | Tema | Estado | Qué hacer |
|---|---|---|---|
| D1 | **Industria** (`business_industry`, G-27) | Kira ya publica los **93 valores NAICS** en `reference/users/values`. El asistente no pide el campo | Catálogo estático en el front (como `BUSINESS_TYPES`) y paso «Empresa». Ya no bloquea en el BFF |
| D2 | **Países de operación** (`transaction_countries`, G-28) | Documentado como «ISO 3166-1 codes», sin aclarar alfa-2 o alfa-3 | Preguntar a Kira o probar en sandbox |
| D3 | **Banco solicitado** (`capabilities.requested_banks`) | No se manda. El banco de la cuenta viene de configuración (`KiraProperties.bank`) | Verificar si la elegibilidad depende de él; la arquitectura pide «seleccionar producto/banco» al iniciar |
| D4 | **Términos aceptados** (`tos_accepted_version`) | No se manda ni se registra consentimiento | Confirmar con Kira si es obligatorio para salir a producción |
| D5 | **`ein` sólo para EE. UU.** | *«do NOT send for non-US businesses»*. El front ofrece carta EIN y NIT/RFC/CNPJ como documentos | Validar que una empresa no estadounidense nunca manda `ein` |
| D6 | **`unsupported_reason`** | El BFF lo lee y lo expone, y el front lo mapea (`onboarding-status.ts:11` y `:26`), pero **ninguna pantalla lo muestra** | Mostrarlo antes que `missing_fields`: con `enhanced_due_diligence_required` no hay dato que lo arregle, y hoy el operador seguiría viendo una lista de faltantes inútil |
| D7 | **Estados nuevos** en el front | Depósito sin `KYT_PENDING`/`KYT_REJECTED`; cuenta sin `FROZEN` | Se añaden cuando el BFF los deje de plegar (`REVISION-DOCS-KIRA.md` §2) |
| D8 | **`ubo_link` en RFIs** (G-24) | El front tiene el tipo, pero sin ruta no puede acuñar el enlace | `POST /api/rfis/{id}/items/{itemId}/ubo-link` en el BFF y botón en `rfi-detail-page` |
| D9 | **Cotización y aprobación** | La cotización caduca (`quote_expires_at`) y `Payout.java:115` la exige vigente. En maker-checker el aprobador actúa más tarde | Verificar en sandbox cuánto dura y si hace falta «recotizar» desde el detalle del pago |

---

## 3. Cumplimiento del documento de arquitectura

Leyenda: ✅ cumple · 🟡 parcial · ❌ no existe · ⛔ existe pero falla contra Kira real (§1)

### 3.1 Experiencias (§1 del documento)

| Experiencia | Estado | Nota |
|---|---|---|
| Portal de vinculación | ⛔ | Construido de punta a punta en ambos repos; bloqueado por B1–B4 |
| Portal financiero del cliente | 🟡 | Construido; sin recorrer contra Kira (fases 2 y 3 del cronograma) |
| Consola de operaciones y cumplimiento | ❌ | G-01: todos los roles son de empresa; no hay vista multiempresa |

### 3.2 Portal de vinculación (§2.1)

| Pantalla | Front | BFF | Nota |
|---|---|---|---|
| Inicio de solicitud (producto, banco, idioma, modalidad) | 🟡 | 🟡 | Sin banco (D3), idioma ni `verification_mode` |
| Datos de la empresa | ⛔ | ⛔ | B1. Falta industria (D1) |
| Perfil de actividad y riesgo | ⛔ | ⛔ | B1 (`has_material_intermediary_ownership`) |
| Propiedad y control | 🟡 | ⛔ | B2, B4. Sin borrar (G-17) ni corregir nombres (G-21) |
| Documentos corporativos | ✅ | ✅ | Verificado en sandbox el 14-sep. Sin listar ni reemplazar: Kira no lo ofrece |
| Documentos personales y selfie | ✅ | ✅ | Verificado en sandbox el 14-sep |
| Prueba de vida | 🟡 | ⛔ | El enlace se genera; el resultado nunca llega (W5) |
| Revisión y envío | 🟡 | ✅ | **Sin pantalla de declaraciones ni consentimiento** (D4 y §3.6) |
| Estado de vinculación | 🟡 | ⛔ | Sin motivo de rechazo (W4); el estado no avanza solo (W3) |
| Faltantes y elegibilidad | ✅ | ✅ | `pendingFields` agrupado por paso; «verificada pero no elegible» diferenciado |

### 3.3 Gestión de clientes para operaciones (§2.2)

Listado de clientes, ficha 360, remediación, bandeja `REVIEW`, rechazos y auditoría: **❌ nada**
(G-01, G-08, G-12). La arquitectura lo pone en el MVP de onboarding (fase 1 de su roadmap).
**Decisión de alcance pendiente:** ¿entra en el 15 de octubre?

### 3.4 Cuentas, depósitos, destinatarios y pagos (§2.3–2.4)

| Pantalla | Estado | Nota |
|---|---|---|
| Solicitud de cuenta | 🟡 | Sin elegir banco (lo fija la configuración) |
| Listado, detalle, saldo, activación | ✅ | `fundsReady` y `activationDelayed` cumplen §6.2 (`approved` no es «lista») |
| Depósitos global y por cuenta | 🟡 | Ordenante y riel vacíos (W6); devoluciones como acreditadas (W1) |
| Conciliación y exportación | ❌ | Fase 5 de la arquitectura |
| Directorio, alta, detalle, reemplazo lógico | ✅ | Archivar con reemplazo existe |
| Cotizador con temporizador | ✅ | |
| Crear pago / aprobaciones maker-checker | ✅ | El creador no puede aprobar (`Payout.java:139`) |
| Límites por monto y aprobaciones múltiples | ❌ | Sólo una aprobación, sin umbrales |
| Segregación: quien crea el destinatario no aprueba pagos a él | ❌ | El destinatario no guarda quién lo creó |
| Seguimiento con KYT, revisión y devoluciones | 🟡 | Estados sí; `CANCELLED` se pierde y `COMPLETED` se da por final |
| Comprobante, referencia bancaria o `txn_hash` | 🟡 | `referenceNumber` sí; recibo no |
| Instrucciones cripto (QR, dirección, expiración) | ❌ | G-19 |

### 3.5 RFIs y centro de eventos (§2.5–2.6)

| Pantalla | Estado | Nota |
|---|---|---|
| Bandeja, detalle, formulario tipado (9 tipos), gestor documental | ✅ | |
| Envío atómico | ✅ | 422 por ítem |
| Eliminar documentos como permiso configurable | 🟡 | Hay permiso por rol, pero fijo en código |
| `ubo_link` con acuñado | ❌ | D8 |
| RFI retirado (`withdrawn` → 404) | ❌ | `REVISION-DOCS-KIRA.md` §3.2 |
| Notificaciones | ❌ | G-11 |
| Centro de eventos | ❌ | `webhooks_log` existe; no hay lectura |
| Incidencias de integración / soporte | ❌ | |

### 3.6 Servicios backend indispensables (§5)

| Servicio | Estado | Nota |
|---|---|---|
| Autenticación propia y RBAC | 🟡 | JWT de 8 h con 5 roles. **Sin MFA** (hay una columna `mfa_secret` sin uso), sin logout de servidor (G-04), sin refresh (G-05). Varias rutas que llaman a Kira sin `@PreAuthorize` (G-09) |
| Gestor de credenciales de Kira | ✅ | Credenciales pendientes de rotar |
| Adaptador de Kira | 🟡 | Mezcla dos versiones de la API (checklist de producción: una sola) |
| Base de datos de dominio | 🟡 | Sin las razones de rechazo (W4) |
| Almacenamiento documental | 🟡 | Valida tipo y tamaño. **Sin análisis antimalware** ni registro de consentimiento o retención |
| Recepción de webhooks | 🟡 | Encola en memoria antes de guardar; con los reintentos de Kira conviene guardar primero |
| Procesador de eventos | ⛔ | W1–W6 |
| Workers de reconciliación | 🟡 | 5 workers. **Faltan empresas y cuentas virtuales**: §6.2 exige reconciliar `failed`/`deactivated` por consulta porque no tienen webhook |
| Gestor de idempotencia | 🟡 | El BFF genera la clave, pero no acepta la del cliente: un doble clic crea dos pagos pendientes (G-07) |
| Motor de aprobaciones | 🟡 | Maker-checker simple; sin límites ni aprobaciones múltiples |
| Log de auditoría inmutable | 🟡 | Se escribe `audit_log`; no hay lectura (G-12). Revisar que se auditen descargas y borrados de documentos |
| Pasarela de notificaciones | ❌ | |
| Observabilidad | ❌ | Sólo `/actuator/health` |
| Parametrización y feature flags (§8) | ❌ | Bancos, rieles, límites y textos van en código o configuración |

### 3.7 Criterios de aceptación críticos (§10)

| Área | Estado | Por qué |
|---|---|---|
| Seguridad (ninguna credencial de Kira en el navegador) | ✅ | Todo pasa por el BFF |
| Multiempresa | ✅ | Filtros por empresa en el BFF (D2 corregido el 11-sep) |
| Onboarding (salir, reanudar, faltantes accionables) | 🟡 | Borrador y faltantes sí; el envío falla (B1) |
| UBO (no borra nacionalidad ni duplica) | ⛔ | Sin nacionalidad (B4); emparejamiento por email corregido |
| Liveness (sólo el evento confirma) | ⛔ | El evento se lee mal (W5): nunca confirma |
| Cuenta activa (no por `approved`) | ✅ | `fundsReady` |
| Pagos (doble clic y cotización vencida) | 🟡 | La cotización vencida se bloquea; el doble clic no (G-07) |
| Maker-checker configurable | 🟡 | Fijo: el creador nunca aprueba |
| Eventos (duplicados y perdidos) | 🟡 | Duplicados sí (`event_id`); pérdida posible si la app cae tras el 200 |
| Estados sin distinguir mayúsculas y tolerantes | 🟡 | Sí, pero lo desconocido cae en `COMPLETED` en depósitos |
| Documentos (validación y auditoría) | 🟡 | Validación sí; auditoría de descargas por confirmar |
| Errores con acción concreta | ✅ | `error-mapping.ts` en el front |

> La arquitectura dice en §5 que Kira hace *«entrega única sin reintento»*. **Ya no es así**: la
> documentación nueva documenta 4 reintentos. El requisito de reconciliar sigue siendo válido.

---

## 4. Huecos del front revisados contra el código de hoy

| G | Resumen | Hoy |
|---|---|---|
| G-07 | Idempotencia de pagos y destinatarios desde el cliente | **Sigue abierto** |
| G-08 | Razón de rechazo KYB | **Sigue abierto**, y además el webhook la lee mal (W4) |
| G-09 | Rutas que llaman a Kira sin permiso por rol | **Sigue abierto**: `DepositController` y `ReferenceController` sin `@PreAuthorize` |
| G-15 | Documentos corporativos KYB | ✅ **Cerrado** el 14-sep (`/api/onboarding/documents`, `/api/ubos/{id}/documents`) |
| G-17 | Borrar UBO | Sigue abierto |
| G-21 | Editar nombre y cargo del UBO | Sigue abierto (`SyncUbosService.save` no los aplica) |
| G-24 | `ubo_link` en RFI | Sigue abierto; la ruta de Kira ya está documentada (D8) |
| G-25 | Cuenta del ordenante sin enmascarar | Sigue abierto |
| G-27 | Catálogo de industrias | **Desbloqueado**: Kira publica los 93 valores (D1) |
| G-29 | Esquema en cert/prod sin migraciones | Sigue abierto; suma una columna más (`ubos.email`) |

El documento de contrato del front dice que G-15 está abierto: conviene actualizarlo.

---

## 5. Plan priorizado

Encaja en el cronograma a 15 de octubre. Orden por lo que desbloquea.

| Prioridad | Qué | Repo | Días | Cuándo |
|---|---|---|---|---|
| 0 | Primer commit del front | front | 0,05 | Hoy |
| 1 | AUT-015 + B1 (expediente completo en el alta, traducción en el PUT) + prueba contra sandbox | BFF | 1 | Mié 16 |
| 2 | W3–W5 (webhooks de vinculación) + persistir y exponer `reasons[]` + mostrarlo en el front | ambos | 1 | Jue 17 (antes del recorrido) |
| 3 | B4: campos de persona en `Ubo`, `toAssociatedPerson` y formulario; quitar `person_reference_id` | ambos | 1 | Vie 18 |
| 4 | D1 industria + D5 `ein` + D6 `unsupported_reason` en pantalla | front | 0,5 | Vie 18 |
| 5 | W1, W2, W6 y estados de depósito y pago; `KYT_*` y `FROZEN` en el front | ambos | 1 | Fase 2 (21–25 sep) |
| 6 | G-07 idempotencia desde el cliente | ambos | 0,5 | Fase 3 |
| 7 | D8 `ubo_link` + RFI retirado | ambos | 0,5 | Fase 3 |
| 8 | Workers de reconciliación de empresas y cuentas | BFF | 0,5 | Fase 3 |
| 9 | Guardar el webhook antes del 2xx + segundo secreto | BFF | 0,5 | Fase 3 |
| 10 | Consentimiento y `tos_accepted_version` (D4), tras confirmarlo con Kira | ambos | 0,5 | Fase 4 |

**Total: 7 días.** El cronograma tiene reservados ~4 viernes de corrección y la semana 5–9 de
octubre, así que **cabe, pero consume casi toda la holgura**.

**Fuera del 15 de octubre salvo decisión expresa** (la arquitectura los pide, pero son semanas de
trabajo): consola multiempresa (G-01), notificaciones y centro de eventos (G-11), lectura de
auditoría (G-12), administración de operadores (G-13), MFA, límites y aprobaciones múltiples,
parametrización y feature flags, antimalware de documentos, observabilidad, conciliación y
exportaciones, instrucciones cripto (G-19).

---

## 6. Decisiones que no son técnicas

1. **¿Entra la consola de operaciones y cumplimiento en esta entrega?** La arquitectura la pone en
   el MVP; hoy no existe nada.
2. **¿MFA antes de producción?** La arquitectura lo pide como control de seguridad.
3. **Para Kira:** formato de `transaction_countries` (D2), si `requested_banks` condiciona la
   elegibilidad (D3), si `tos_accepted_version` es obligatorio (D4) y duración real de una
   cotización (D9).
