#!/bin/bash
# LauncherTV · Paso 1: conectar con la tele por ADB (Wi-Fi) y hacer copia de seguridad.
# Este paso NO cambia nada en la tele: solo lee información y la guarda en esta carpeta.
# Uso:  bash instalar/paso1_conectar_y_backup.sh [IP_DE_LA_TELE]

set -u
TV_IP="${1:-${TV_IP:?Uso: bash paso1_conectar_y_backup.sh IP_DE_LA_TELE}}"
TV="$TV_IP:5555"
DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$DIR/herramientas"
LOG="$DIR/paso1_log.txt"

exec > >(tee "$LOG") 2>&1

echo "== LauncherTV · Paso 1: conexión y copia de seguridad =="
echo "Tele: $TV"
echo

# --- 1) Conseguir adb (herramientas oficiales de Google) -------------------
if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
elif [ -x "$TOOLS/platform-tools/adb" ]; then
  ADB="$TOOLS/platform-tools/adb"
else
  echo "Descargando adb (Android platform-tools oficiales de Google)..."
  mkdir -p "$TOOLS"
  if ! curl -fL --progress-bar -o "$TOOLS/platform-tools.zip" \
      "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"; then
    echo "ERROR: no se pudo descargar adb. Revisa la conexión a internet del Mac."
    exit 1
  fi
  unzip -q -o "$TOOLS/platform-tools.zip" -d "$TOOLS" && rm -f "$TOOLS/platform-tools.zip"
  xattr -dr com.apple.quarantine "$TOOLS" 2>/dev/null
  ADB="$TOOLS/platform-tools/adb"
fi
echo "adb: $ADB"
if ! "$ADB" version 2>/dev/null | head -1; then
  echo "ERROR: macOS no deja ejecutar adb. Díselo a Claude (hay plan B con Python)."
  exit 1
fi
echo

# --- 2) Conectar con la tele ------------------------------------------------
"$ADB" start-server >/dev/null 2>&1
echo "Conectando con la tele..."
echo ">> Si en la tele aparece '¿Permitir depuración USB?', marca 'Permitir siempre"
echo ">> desde este ordenador' y pulsa ACEPTAR."
"$ADB" connect "$TV"
# Esperar a que la tele responda de verdad a un comando (no solo a que diga "device").
OK=0
for i in $(seq 1 60); do
  R="$("$ADB" -s "$TV" shell echo listo 2>&1 | tr -d '\r')"
  if [ "$R" = "listo" ]; then
    OK=$((OK + 1))
    [ "$OK" -ge 2 ] && break     # dos respuestas seguidas = conexión estable
  else
    OK=0
    case "$R" in
      *unauthorized*) [ $((i % 5)) -eq 1 ] && echo "Esperando a que aceptes en la tele..." ;;
      *offline*|*closed*|*"not found"*)
        "$ADB" disconnect "$TV" >/dev/null 2>&1
        "$ADB" connect "$TV" >/dev/null 2>&1 ;;
      *) "$ADB" connect "$TV" >/dev/null 2>&1 ;;
    esac
  fi
  sleep 2
done
if [ "$OK" -lt 2 ]; then
  echo
  echo "ERROR: no se pudo conectar con la tele ($R)."
  echo "Revisa: 1) Opciones para desarrolladores > Depuración USB activada,"
  echo "        2) tele y Mac en la misma Wi-Fi, 3) la IP de la tele es $TV_IP."
  exit 1
fi
echo "Conectado ✅"
echo

# Borrar copias de seguridad anteriores que salieron vacías (intentos fallidos).
for d in "$DIR"/copia_seguridad/*/; do
  [ -d "$d" ] || continue
  grep -q '^package:' "$d/paquetes_activos.txt" 2>/dev/null || rm -rf "$d"
done

A=("$ADB" -s "$TV")
tv() { "${A[@]}" shell "$@" 2>&1 | tr -d '\r'; }

# --- 3) Copia de seguridad (solo lectura) -----------------------------------
TS="$(date +%Y%m%d-%H%M%S)"
B="$DIR/copia_seguridad/$TS"
mkdir -p "$B" "$DIR/capturas"
echo "Guardando copia de seguridad en: copia_seguridad/$TS"

tv getprop                          > "$B/propiedades.txt"
tv pm list packages -u              > "$B/paquetes_todos.txt"
tv pm list packages -e              > "$B/paquetes_activos.txt"
tv pm list packages -d              > "$B/paquetes_desactivados.txt"
tv pm list packages -s              > "$B/paquetes_sistema.txt"
tv pm list packages -3              > "$B/paquetes_instalados_por_ti.txt"
tv pm list packages -f              > "$B/paquetes_rutas.txt"
tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
                                    > "$B/inicio_actual.txt"
tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME \
                                    > "$B/inicios_disponibles.txt"
tv cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER \
                                    > "$B/apps_con_icono.txt"
tv dumpsys package preferred        > "$B/preferidas.txt"
for ns in global secure system; do
  tv settings list "$ns"            > "$B/ajustes_$ns.txt"
done
tv wm size                          > "$B/pantalla.txt"
tv wm density                      >> "$B/pantalla.txt"
tv df -h                            > "$B/almacenamiento.txt"
tv cat /proc/meminfo                > "$B/memoria_total.txt"
tv dumpsys meminfo | head -120      > "$B/memoria_por_app.txt"
tv ps -A -o RSS,NAME | sort -rn | head -60 > "$B/procesos_por_memoria.txt"

"${A[@]}" exec-out screencap -p > "$DIR/capturas/00_antes.png" 2>/dev/null

if ! grep -q '^package:' "$B/paquetes_activos.txt"; then
  echo
  echo "ERROR: la conexión se cortó durante la copia (la copia salió vacía)."
  echo "Vuelve a ejecutar este mismo comando."
  rm -rf "$B"
  exit 1
fi

echo
echo "Resumen:"
echo "  Modelo:            $(grep -m1 'ro.product.model' "$B/propiedades.txt" | cut -d'[' -f3 | tr -d ']')"
echo "  Android:           $(grep -m1 'ro.build.version.release' "$B/propiedades.txt" | cut -d'[' -f3 | tr -d ']')"
echo "  Apps activas:      $(grep -c '^package:' "$B/paquetes_activos.txt")"
echo "  Apps desactivadas: $(grep -c '^package:' "$B/paquetes_desactivados.txt")"
echo "  Inicio actual:     $(tail -1 "$B/inicio_actual.txt")"
echo "  Captura de la tele: capturas/00_antes.png"
echo
echo "Listo. No he cambiado nada en la tele. Vuelve al chat y dile a Claude: hecho"
