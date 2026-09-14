#!/usr/bin/env bash
#
# Verificacion en vivo del WebhookReprojectionWorker contra la base de desarrollo.
#
# Inserta en webhooks_log un evento con processed = 0, como si el ingress lo hubiera guardado
# y la proyeccion hubiera fallado despues, y espera a que el worker lo recoja y lo marque
# como procesado.
#
# El evento elegido (user.verification.failed de un usuario que no existe en local) se proyecta
# sin llamar a Kira: sirve para validar el worker sin credenciales del sandbox.
#
# Uso:
#   export DB_PASSWORD='...'            # la de tu MySQL local; nunca se guarda en el repo
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev \
#          -Dspring-boot.run.arguments=--bff.reconciliation.webhooks-ms=15000
#   scripts/verificar-reproyeccion-dev.sh
#
# El intervalo por defecto del worker es de 30 minutos: para la prueba conviene bajarlo como
# muestra la linea de arriba.
set -euo pipefail

DB_NAME=${DB_NAME:-autransactional}
DB_USER=${DB_USERNAME:-root}
INTENTOS=${INTENTOS:-20}
ESPERA=${ESPERA:-15}

: "${DB_PASSWORD:?Exporta DB_PASSWORD con la contrasena de tu MySQL local}"
export MYSQL_PWD="$DB_PASSWORD"

EVENT_ID="evt-reproyeccion-$(date +%s)"

consulta() {
  mysql --batch --skip-column-names -u "$DB_USER" "$DB_NAME" -e "$1"
}

echo "Insertando evento pendiente $EVENT_ID ..."
consulta "INSERT INTO webhooks_log
            (id, event_id, event_type, payload, processed, created_at, processing_error, retry_count)
          VALUES
            (UUID(), '$EVENT_ID', 'user.verification.failed',
             JSON_OBJECT('event', 'user.verification.failed',
                         'data', JSON_OBJECT('event_id', '$EVENT_ID',
                                             'user_id', 'usr_que_no_existe_en_local',
                                             'reason', 'fila de prueba de reproyeccion')),
             0, NOW(6), 'simulacion: la proyeccion fallo tras responder 2xx', 0);"

echo "Esperando al worker (hasta $((INTENTOS * ESPERA))s) ..."
for _ in $(seq 1 "$INTENTOS"); do
  sleep "$ESPERA"
  # processed es BIT(1): sin el +0, CONCAT devuelve un byte binario y la comparacion nunca casa.
  ESTADO=$(consulta "SELECT CONCAT(processed + 0, '|', retry_count, '|', IFNULL(processing_error, '-'))
                     FROM webhooks_log WHERE event_id = '$EVENT_ID';")
  echo "  processed|retry_count|error = $ESTADO"
  case "$ESTADO" in
    1\|*)
      echo "OK: el worker reproyecto el evento."
      consulta "DELETE FROM webhooks_log WHERE event_id = '$EVENT_ID';"
      echo "Fila de prueba eliminada."
      exit 0
      ;;
  esac
done

echo "FALLO: el evento sigue sin proyectarse. Revisa que la app corra con el perfil dev," >&2
echo "que bff.reconciliation.enabled sea true y el intervalo webhooks-ms sea corto." >&2
exit 1
