#!/bin/bash
# Descarga los fondos de wallpapers/fondos.txt a app/src/main/assets/wallpapers (1920 px de ancho).
set -u
cd "$(dirname "$0")/.."
OUT="app/src/main/assets/wallpapers"
mkdir -p "$OUT"
rm -f "$OUT"/*.jpg "$OUT/creditos.txt"
n=0
while IFS= read -r linea; do
  linea="${linea%%#*}"
  [ -z "${linea// /}" ] && continue
  url="$(echo "${linea%%|*}" | xargs)"
  autor="$(echo "${linea#*|}" | xargs)"
  url="${url%%\?*}?fm=jpg&w=1920&h=1080&fit=crop&q=82"
  n=$((n + 1))
  nombre="$(printf '%02d.jpg' "$n")"
  if curl -fsSL --retry 3 -o "$OUT/$nombre" "$url"; then
    echo "$nombre|$autor" >> "$OUT/creditos.txt"
    echo "✓ $nombre ($autor)"
  else
    echo "✗ no se pudo descargar: $url"
    rm -f "$OUT/$nombre"
  fi
done < wallpapers/fondos.txt
echo "Fondos descargados: $(ls "$OUT"/*.jpg 2>/dev/null | wc -l)"

# Tipografía Nunito (licencia OFL, libre): redondeada, como la del reloj del iPhone.
FONTS="app/src/main/assets/fonts"
mkdir -p "$FONTS"
if curl -fsSL --retry 3 -o "$FONTS/Nunito.ttf" "https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/Nunito%5Bwght%5D.ttf"; then
  curl -fsSL --retry 3 -o "$FONTS/LICENSE-Nunito.txt" "https://raw.githubusercontent.com/google/fonts/main/ofl/nunito/OFL.txt" || true
  echo "✓ fuente Nunito ($(du -h "$FONTS/Nunito.ttf" | cut -f1))"
else
  echo "✗ no se pudo descargar Nunito; se usará la fuente del sistema"
  rm -f "$FONTS/Nunito.ttf"
fi
