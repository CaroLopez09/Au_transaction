Alineación del BFF con la documentación nueva de Kira y con la arquitectura frontend

## Resumen

Seis commits que dejan el BFF alineado con `docs.kirafin.ai` (verificado contra el sandbox con `juriscop`) y con los requisitos de `docs/arquitectura_frontend_kirafin (1).docx`.

| Commit | Tema |
|---|---|
| `26d8df4` | Vinculación KYB: documentos, borrador, traducción del PUT de Kira (400 corregido), faltantes por producto, datos de identidad y borrado de beneficiarios |
| `033f399` | Tesorería y RFIs: estados KYT, CANCELLED y FROZEN; `Idempotency-Key` desde el portal; `ubo-link`, motivo de cierre y RFIs retirados |
| `59b4746` | Webhooks guardados antes del 2xx, segundo secreto de firma, avisos, centro de eventos, auditoría y workers de empresas y cuentas |
| `417d921` | Verificación en dos pasos TOTP (secreto cifrado, reto de 5 min) y rol `PLATFORM_OPERATOR` sin empresa |
| `8c7aaa2` | Consola de operaciones: clientes, ficha 360 y bandeja de revisión, auditada |
| `cfdadbf` | Documentación: revisiones, ESTADO, API-GUIA, Bruno y cronograma |

## Verificación

- `./mvnw test`: **370 pruebas, 0 fallos**. Cada commit compila por sí solo (comprobado en un worktree limpio).
- Bruno contra el sandbox de Kira: colección completa **88/88** y carpetas nuevas **26/26**.
- Contra el sandbox: `PUT /api/onboarding` y `POST /api/ubos/sync` responden 200; a `juriscop` solo le faltan dos documentos para `usa-virtual-accounts`.
- MFA de extremo a extremo con códigos generados aparte (12 comprobaciones).

## Antes de desplegar a cert o prod

- Aplicar a mano el SQL de `docs/ESTADO.md` §7 (no hay Flyway y cert/prod validan el esquema).
- Definir `BFF_MFA_ENCRYPTION_KEY` (el arranque falla sin ella). `BFF_MFA_ENFORCED` vale `true` por defecto en cert/prod.
- Opcional: `KIRA_WEBHOOK_SECRET_PREVIOUS` solo durante la rotación del secreto.

## Pendiente, fuera de este PR

- `KIRA_BANK=slovak_savings_bank` no figura en la documentación de Kira (solo `austin_capital_trust` y `jp_morgan`): verificar al abrir cuentas.
- Formato de `transaction_countries`, `tos_accepted_version` y consentimiento, `ein` solo para EE. UU. y vigencia de la cotización en maker-checker.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
