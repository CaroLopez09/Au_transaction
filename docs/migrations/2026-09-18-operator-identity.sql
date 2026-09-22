-- Operadores de empresas cliente: identidad documental y biometrica gestionada por el BFF.
-- Ejecutar una sola vez en certificacion y produccion antes de desplegar el BFF.
-- No guarda archivos, enlaces de liveness ni el numero completo del documento.

-- 1. El enum UserStatus gano el valor PENDING_IDENTITY, pero la restriccion CHECK que creo
--    Hibernate al nacer la tabla solo admite ACTIVE, SUSPENDED y DISABLED, y `ddl-auto: update`
--    nunca modifica una restriccion existente. Sin este paso fallan tanto el UPDATE de abajo
--    como, en caliente, ManageOperatorsService (alta de operador) e IdentityVerificationService.
--    El nombre lo genera MySQL (users_chk_1, users_chk_2...) y cambia entre entornos, asi que
--    se busca por su definicion en vez de darlo por sabido.
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

-- 2. Columnas de identidad. El DEFAULT cubre las filas que ya existen: sin el, MySQL las
--    rellena con cadena vacia y la restriccion de abajo se viola al crearse.
ALTER TABLE users
  ADD COLUMN identity_status VARCHAR(50) NOT NULL DEFAULT 'PENDING_DOCUMENTS',
  ADD COLUMN kira_person_reference_id VARCHAR(100) NULL,
  ADD COLUMN identity_document_type VARCHAR(100) NULL,
  ADD COLUMN identity_document_last_four VARCHAR(4) NULL,
  ADD COLUMN identity_issuing_country VARCHAR(3) NULL,
  ADD COLUMN biometric_consent_at DATETIME(6) NULL,
  ADD COLUMN identity_requested_at DATETIME(6) NULL,
  ADD COLUMN identity_verified_at DATETIME(6) NULL,
  ADD COLUMN identity_rejection_reason VARCHAR(500) NULL,
  ADD INDEX idx_users_tenant_identity (tenant_id, identity_status);

-- La restriccion va aparte del ADD COLUMN: en la misma sentencia MySQL la evalua contra las
-- filas ya rellenadas y aborta el ALTER completo.
ALTER TABLE users
  ADD CONSTRAINT users_chk_identity_status
  CHECK (identity_status IN ('PENDING_DOCUMENTS', 'PENDING_LIVENESS', 'IN_REVIEW',
                             'VERIFIED', 'REJECTED', 'EXPIRED'));

-- 3. La verificacion es obligatoria para toda persona de una empresa cliente.
--    Las cuentas de plataforma no pertenecen a un cliente y no se modifican.
--    OJO: esto deja sin sesion a todos los operadores existentes hasta que completen la
--    verificacion, asi que BIOMETRY_BASE_URL y BIOMETRY_API_KEY deben estar configurados
--    en el entorno ANTES de ejecutar este paso.
UPDATE users
SET status = 'PENDING_IDENTITY', identity_status = 'PENDING_DOCUMENTS'
WHERE tenant_id IS NOT NULL AND status = 'ACTIVE';
