#!/bin/bash
# LauncherTV · Paso 3: descargar la última versión del launcher e instalarla en la tele.
# NO desactiva el launcher de Google: solo instala el nuevo y lo pone como inicio.
# Si algo falla, el inicio de Google sigue ahí. Para volver a él: bash restaurar.sh
# Uso:  bash instalar/paso3_instalar_launcher.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/paso3_log.txt") 2>&1

APK_URL="${APK_URL:-https://github.com/alfredopalmisano/launcher-tv/releases/download/latest/launcher-tv.apk}"
APK="$DIR/launcher-tv.apk"
PKG="com.alfredo.launchertv"

echo "== LauncherTV · Paso 3: instalar el launcher =="
echo "Descargando la última versión desde GitHub..."
if ! curl -fL --progress-bar -o "$APK" "$APK_URL"; then
  echo "ERROR: no se pudo descargar el APK. ¿Ha terminado de compilar GitHub?"
  exit 1
fi
echo "APK: $(du -h "$APK" | cut -f1)"
echo
conectar
echo

echo "Instalando en la tele..."
r="$("$ADB" -s "$TV" install -r -g "$APK" 2>&1 | tr -d '\r')"
echo "$r" | tail -2
if ! echo "$r" | grep -q "Success"; then
  if echo "$r" | grep -qi "INSTALL_FAILED_UPDATE_INCOMPATIBLE\|signatures"; then
    echo "La versión instalada tiene otra firma; la quito y vuelvo a instalar..."
    tv pm uninstall "$PKG" >/dev/null
    "$ADB" -s "$TV" install -r -g "$APK" 2>&1 | tr -d '\r' | tail -1
  else
    echo "ERROR al instalar."
    exit 1
  fi
fi

echo
echo "Permisos (se vuelven a aplicar en cada actualización):"
echo "  Dormir: $(tv dpm set-active-admin --user 0 "$PKG/.SleepAdminReceiver" | tail -1)"
tv pm grant "$PKG" android.permission.READ_TV_LISTINGS 2>/dev/null && echo "  Continuar viendo: ✓"

echo
echo "Poniéndolo como pantalla de inicio..."
tv cmd package set-home-activity "$PKG/.MainActivity"
echo "Inicio actual: $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"

echo
echo "Abriendo el launcher..."
tv input keyevent KEYCODE_HOME >/dev/null
sleep 6
captura 02_launcher_nuevo
echo "RAM libre: $(ram_libre) MB"
tv dumpsys tv_input > "$DIR/hdmi_info.txt" 2>/dev/null
tv logcat -d -t 400 > "$DIR/logcat.txt" 2>/dev/null
echo
echo "Listo. Mira la tele y vuelve al chat: dile a Claude qué ves."
