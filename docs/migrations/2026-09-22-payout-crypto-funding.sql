-- G-19: financiar un pago con un deposito cripto en vez de con el saldo de la cuenta
-- (mode=CRYPTO en POST /v1/virtual-accounts/{id}/payout) y guardar las instrucciones de
-- deposito que Kira devuelve (direccion, red, vencimiento). El JSON no se tipa columna a
-- columna porque el contrato de Kira declara 'deposit_instructions' con additionalProperties
-- libres: el frontend interpreta las claves que vengan.
ALTER TABLE payouts ADD COLUMN funding_network VARCHAR(20) NULL;
ALTER TABLE payouts ADD COLUMN funding_currency VARCHAR(10) NULL;
ALTER TABLE payouts ADD COLUMN deposit_instructions TEXT NULL;
