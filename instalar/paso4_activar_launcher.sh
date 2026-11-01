#!/bin/bash
# LauncherTV · Paso 4: hacer que el botón Inicio abra TU launcher.
# El inicio de Google tiene prioridad fija sobre cualquier otro, así que hay que
# desactivarlo (reversible: restaurar.sh lo vuelve a activar). Antes de hacerlo,
# el script comprueba que tu launcher arranca bien; si no, no toca nada.
# Uso:  bash instalar/paso4_activar_launcher.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/paso4_log.txt") 2>&1

PKG="com.alfredo.launchertv"
echo "== LauncherTV · Paso 4: activar el launcher =="
conectar
echo

echo "1) Abriendo tu launcher para comprobar que funciona..."
tv am start -n "$PKG/.MainActivity" >/dev/null
sleep 6
captura 03_prueba_launcher
if [ -z "$(tv pidof "$PKG")" ]; then
  echo "ERROR: el launcher se ha cerrado solo al abrirlo. No cambio nada."
  echo "Vuelve al chat con Claude y pásale este log: paso4_log.txt"
  tv logcat -d -t 200 "*:E" > "$DIR/paso4_logcat.txt"
  exit 1
fi
echo "   ✓ Funciona (está en pantalla)."

echo
echo "2) Desactivando el inicio de Google (reversible)..."
tv pm disable-user --user 0 com.google.android.tvlauncher | tail -1
echo "com.google.android.tvlauncher" >> "$DIR/desactivados.txt"

echo
echo "3) Probando el botón Inicio desde dentro de otra app..."
tv am start -n com.android.tv.settings/.MainSettings >/dev/null
sleep 3
tv input keyevent KEYCODE_HOME >/dev/null
sleep 4
TOP="$(tv dumpsys activity activities | grep -m1 -oE 'mResumedActivity.*' )"
echo "   En pantalla: $TOP"
captura 04_boton_inicio
INICIO="$(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo "   Inicio del sistema: $INICIO"

if echo "$TOP $INICIO" | grep -q "$PKG"; then
  echo
  echo "✅ El botón Inicio abre tu launcher."
else
  echo
  echo "⚠ Otra app ha cogido el botón Inicio. Desactivando también el asistente de configuración de Google..."
  tv pm disable-user --user 0 com.google.android.tungsten.setupwraith | tail -1
  echo "com.google.android.tungsten.setupwraith" >> "$DIR/desactivados.txt"
  tv input keyevent KEYCODE_HOME >/dev/null
  sleep 4
  captura 04_boton_inicio
  echo "   En pantalla: $(tv dumpsys activity activities | grep -m1 -oE 'mResumedActivity.*')"
fi
sort -u "$DIR/desactivados.txt" -o "$DIR/desactivados.txt"
echo "RAM libre: $(ram_libre) MB"
echo
echo "Listo. Pulsa Inicio en el mando y cuéntale a Claude qué ves."
