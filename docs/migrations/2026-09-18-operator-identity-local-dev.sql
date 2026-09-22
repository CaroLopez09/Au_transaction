-- SOLO PARA LA BASE DE DATOS LOCAL DE DESARROLLO. No ejecutar en certificacion ni produccion:
-- alli va 2026-09-18-operator-identity.sql, que es la migracion real.
--
-- Por que existe: en local, `ddl-auto: update` intento crear identity_status por su cuenta y
-- fallo (rellena las filas existentes con cadena vacia y eso viola la restriccion CHECK que el
-- propio Hibernate genera). El error solo queda como WARN en el arranque, asi que la columna
-- nunca se creo y cualquier lectura de `users` reventaba con "Unknown column identity_status".
-- El BFF no llegaba a levantar porque DevDataSeeder consulta usuarios al arrancar.
--
-- Diferencia deliberada con la migracion real: aqui los operadores de prueba quedan en
-- VERIFIED y ACTIVE. En local BIOMETRY_BASE_URL esta vacio, asi que nadie podria completar la
-- verificacion y la coleccion de Bruno se quedaria sin poder obtener un accessToken.

-- 1. Misma correccion que en la migracion real: el enum UserStatus gano PENDING_IDENTITY pero
--    la restriccion CHECK que nacio con la tabla solo admite los tres valores antiguos.
--    Sin esto fallan el alta de operadores (ManageOperatorsService) y el guardado del
--    resultado de identidad (IdentityVerificationService).
SET @status_check := (
    SELECT tc.CONSTRAINT_NAME
    FROM information_schema.CHECK_CONSTRAINTS cc
    JOIN information_schema.TABLE_CONSTRAINTS tc
      ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
     AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
    WHERE cc.CONSTRAINT_SCHEMA = DATABASE()
      AND tc.TABLE_NAME = 'users'
      AND cc.CHECK_CLAUSE LIKE '%`status`%'
      AND cc.CHECK_CLAUSE NOT LIKE '%identity_status%'
    LIMIT 1);

SET @drop_status_check := IF(@status_check IS NULL,
    'SELECT ''Sin restriccion previa sobre status: nada que eliminar''',
    CONCAT('ALTER TABLE users DROP CHECK ', @status_check));
PREPARE stmt FROM @drop_status_check;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

ALTER TABLE users
  ADD CONSTRAINT users_chk_status
  CHECK (status IN ('PENDING_IDENTITY', 'ACTIVE', 'SUSPENDED', 'DISABLED'));

-- 2. El indice que la migracion real crea junto a las columnas. Las nueve columnas ya existen
--    en local (ocho las creo Hibernate; identity_status se anadio a mano al diagnosticar).
ALTER TABLE users
  ADD INDEX idx_users_tenant_identity (tenant_id, identity_status);
