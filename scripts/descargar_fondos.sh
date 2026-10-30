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

# Tipografía Inter (licencia OFL, libre): muy parecida a la de Apple.
FONTS="app/src/main/assets/fonts"
mkdir -p "$FONTS"
TMP="$(mktemp -d)"
if curl -fsSL --retry 3 -o "$TMP/inter.zip" "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip"; then
  unzip -q -o "$TMP/inter.zip" -d "$TMP/inter"
  for f in Inter-ExtraLight.ttf Inter-Light.ttf Inter-Medium.ttf; do
    src="$(find "$TMP/inter" -name "$f" | head -1)"
    [ -n "$src" ] && cp "$src" "$FONTS/$f" && echo "✓ fuente $f"
  done
  find "$TMP/inter" -iname "LICENSE*" | head -1 | xargs -I{} cp {} "$FONTS/LICENSE-Inter.txt"
else
  echo "✗ no se pudo descargar Inter; se usará la fuente del sistema"
fi
rm -rf "$TMP"
