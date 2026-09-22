-- Limite de 3 intentos de verificacion de identidad (rostro/documento no coinciden).
-- Ejecutar antes de desplegar el BFF en certificacion y produccion (ddl-auto: validate).
-- En dev, ddl-auto: update agrega la columna automaticamente al arrancar.

ALTER TABLE users
  ADD COLUMN identity_rejected_attempts INT NOT NULL DEFAULT 0;
