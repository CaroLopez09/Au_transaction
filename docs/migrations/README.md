# Migraciones de esquema

El proyecto no usa Flyway ni Liquibase. Los cambios de esquema se escriben aquí a mano y se
aplican con `mysql` antes de desplegar el BFF que los necesita.

En `dev` el perfil usa `ddl-auto: update`, así que Hibernate intenta adivinar el cambio solo.
No te fíes: **cuando un `ALTER` le falla lo deja como `WARN` y sigue arrancando**, de modo que
el esquema queda a medias sin que nadie se entere. Eso es justo lo que pasó el 18-sep-2026 con
`identity_status`. Aplica siempre la migración a mano, también en local.

## Cómo aplicar

```bash
mysql -u root -p autransactional < docs/migrations/<fichero>.sql
```

Los scripts **no son idempotentes**: una segunda ejecución falla con columnas duplicadas.
Si necesitas repetir, revisa antes qué parte ya está aplicada.

## Orden

| Fecha | Fichero | Entornos |
|---|---|---|
| 2026-09-18 | `2026-09-18-operator-identity.sql` | dev, cert, prod |
| 2026-09-18 | `2026-09-18-identity-verification-attempts.sql` | dev, cert, prod |
| 2026-09-18 | `2026-09-18-operator-identity-local-dev.sql` | **solo local** |

`operator-identity` va antes que `identity-verification-attempts`: la segunda crea una clave
ajena contra `users`.

`operator-identity-local-dev.sql` es un parche de reconciliación para la máquina de desarrollo,
donde Hibernate ya había creado parte de las columnas. No se ejecuta en cert ni en prod.

## Antes de ejecutar `operator-identity` en cert o prod

El último paso pone a **todos** los operadores de empresas cliente en `PENDING_IDENTITY`, es
decir, sin sesión hasta que completen la verificación de identidad. Configura
`BIOMETRY_BASE_URL` y `BIOMETRY_API_KEY` en el entorno **antes**, o dejarás a los usuarios
existentes sin forma de entrar.
