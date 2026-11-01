#!/bin/bash
# LauncherTV · Paso 6: instalar el receptor AirPlay (iPhone / iPad / Mac → tele).
# Usa la app de código abierto "AirPlay Server" de jqssun (motor UxPlay, GPL-3), tal como la
# publica su autor en GitHub: https://github.com/jqssun/android-airplay-server
# Probada por otros usuarios en una Xiaomi MiTV con Android 9. No admite contenido con DRM (Netflix…).
# Uso:  bash instalar/paso6_airplay.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/paso6_log.txt") 2>&1

PKG="io.github.jqssun.airplay"
REPO="jqssun/android-airplay-server"
APK="$DIR/airplay.apk"
NOMBRE_TELE="${1:-Salón}"

echo "== LauncherTV · Paso 6: AirPlay =="
echo "Buscando la última versión publicada por el autor..."
URL="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["tag_name"]); [print(a["browser_download_url"]) for a in d["assets"] if a["name"].endswith(".apk")]')"
TAG="$(echo "$URL" | head -1)"
APK_URL="$(echo "$URL" | grep -m1 '\.apk$')"
if [ -z "$APK_URL" ]; then echo "ERROR: no encuentro el APK en la release $TAG."; exit 1; fi
echo "Versión $TAG → $APK_URL"
curl -fL --progress-bar -o "$APK" "$APK_URL" || { echo "ERROR descargando."; exit 1; }
echo "APK: $(du -h "$APK" | cut -f1)"
echo
conectar
echo

echo "Instalando en la tele..."
r="$("$ADB" -s "$TV" install -r -g "$APK" 2>&1 | tr -d '\r')"
echo "$r" | tail -1
echo "$r" | grep -q Success || { echo "ERROR al instalar."; exit 1; }

echo
echo "Ajustes para que funcione siempre:"
tv cmd appops set "$PKG" RUN_IN_BACKGROUND allow >/dev/null 2>&1 && echo "  ✓ puede correr en segundo plano"
tv dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 && echo "  ✓ sin límites de ahorro de energía"
tv settings put global device_name "$NOMBRE_TELE" >/dev/null 2>&1 && echo "  ✓ la tele se anunciará como: $NOMBRE_TELE"

echo
echo "Abriendo el receptor para que arranque su servicio..."
tv monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || tv monkey -p "$PKG" -c android.intent.category.LEANBACK_LAUNCHER 1 >/dev/null 2>&1
sleep 6
captura 06_airplay
echo
echo "Listo. En el iPhone: Centro de control → Duplicar pantalla → debería salir '$NOMBRE_TELE'."
echo "En el Mac: barra de menús → Duplicar pantalla / AirPlay. Dile a Claude qué ha pasado."
