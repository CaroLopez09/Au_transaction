#!/usr/bin/env bash
# Genera el fichero de "grabacion" que espera POST /api/v1/number-challenge/{id}/verify
# en el perfil dev. El DevSpeechTranscriber no transcribe: busca los primeros 4 digitos
# en los primeros 4096 bytes del fichero, y considera que NO hubo movimiento labial si
# encuentra la palabra NOLIPS.
#
#   ./generar-grabacion.sh 4821                -> camino feliz
#   ./generar-grabacion.sh 4821 --nolips       -> simula video pregrabado (sin labios)
#   ./generar-grabacion.sh 0000 salida.webm    -> nombre de fichero propio
set -euo pipefail

numero="${1:?Uso: $0 <numero-de-4-digitos> [--nolips] [fichero]}"
destino="muestras/recording.txt"
marca=""

for arg in "${@:2}"; do
  case "$arg" in
    --nolips) marca=" NOLIPS" ;;
    *) destino="$arg" ;;
  esac
done

printf 'grabacion de prueba numero=%s%s\n' "$numero" "$marca" > "$destino"
echo "Escrito $destino ($(stat -c%s "$destino") bytes) con el numero $numero${marca:+ y marca NOLIPS}"
