-- Contrasena temporal (alta y reset administrativo) + historial de contrasenas (no reutilizar
-- las ultimas 2), con excepcion: si la temporal vigente vino de un reset de administrador, el
-- siguiente cambio obligatorio no aplica la politica de no-reutilizacion (passwordResetByAdmin).
-- Ejecutar antes de desplegar el BFF en certificacion y produccion (ddl-auto: validate).
-- En dev, ddl-auto: update agrega columnas y tabla automaticamente al arrancar.

ALTER TABLE users
  ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN password_reset_by_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE password_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id VARCHAR(36) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_password_history_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX idx_password_history_user_id ON password_history (user_id, created_at DESC);
