-- Parametrizacion por tenant (arquitectura §8): que rieles, tokens y modulos (feature flags)
-- tiene habilitados una empresa cliente. Ejecutar una sola vez en certificacion y produccion
-- antes de desplegar el BFF (en dev, `ddl-auto: update` la crea sola).
--
-- La columna es JSON nullable y guarda los tres conjuntos (enabledRails/enabledTokens/
-- enabledFeatures) en un solo objeto: un tenant sin fila propia usa TenantSettings.defaults()
-- en memoria (todo habilitado), asi que no hace falta backfill ni una segunda columna cuando
-- se agregaron los feature flags.
ALTER TABLE tenants
  ADD COLUMN settings JSON NULL;
