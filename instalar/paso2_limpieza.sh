#!/bin/bash
# LauncherTV · Paso 2: desactivar anuncios, telemetría y basura.
# Es REVERSIBLE: nunca desinstala nada, solo desactiva (pm disable-user).
# Para deshacerlo todo:  bash instalar/restaurar.sh
# Uso:                   bash instalar/paso2_limpieza.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/paso2_log.txt") 2>&1

echo "== LauncherTV · Paso 2: limpieza (reversible) =="
if ! copia_original >/dev/null; then
  echo "ERROR: no hay copia de seguridad. Ejecuta antes el paso 1."
  exit 1
fi
conectar
echo

PAQUETES=(
  # Telemetría y anuncios
  "com.miui.tv.analytics|Telemetría de Xiaomi"
  "com.xiaomi.statistic|Estadísticas de Xiaomi"
  "android.autoinstalls.config.xiaomi.amelie|Instalador automático de apps promocionadas"
  "com.google.android.feedback|Envío de comentarios a Google"
  "com.google.android.tv.bugreportsender|Envío de informes de errores"
  "com.mitv.tvhome.michannel|Canales y recomendaciones de Xiaomi"
  "com.google.android.tvrecommendations|Recomendaciones de Google"
  "com.google.android.leanbacklauncher.recommendations|Recomendaciones del launcher antiguo"
  # Apps de Xiaomi que no usas
  "com.mitv.tvhome.atv|PatchWall (botón Mi)"
  "com.mitv.tvhome.mitvplus|Xiaomi TV+"
  "com.xiaomi.mimusic2|Música de Xiaomi"
  "com.xiaomi.mitv.tvmanager|Gestor/limpiador de Xiaomi"
  "com.xiaomi.mitv.updateservice|Actualizaciones de sistema de Xiaomi"
  # Apps de Google que no usas
  "com.google.android.videos|Google TV / Play Películas"
  "com.google.android.play.games|Play Juegos"
  "com.google.android.youtube.tvmusic|YouTube Music"
  # Funciones que no usas
  "com.google.android.katniss|Búsqueda por voz (Asistente de Google)"
  "com.google.android.speech.pumpkin|Reconocimiento de voz sin conexión"
  "com.mitv.gallery|Galería de Xiaomi (fotos por USB)"
  "com.xiaomi.mitv.mediaexplorer|Explorador de archivos USB de Xiaomi"
  "com.mitv.videoplayer|Reproductor de vídeo de Xiaomi (USB)"
  # Otros
  "com.android.printspooler|Servicio de impresión"
)

RAM_ANTES="$(ram_libre)"
TODOS="$(tv pm list packages)"

echo "Desactivando (no se desinstala nada):"
for e in "${PAQUETES[@]}"; do
  p="${e%%|*}"
  nombre="${e#*|}"
  if ! printf '%s\n' "$TODOS" | grep -qx "package:$p"; then
    echo "  - $nombre: no existe en esta tele, se omite"
    continue
  fi
  r="$(tv pm disable-user --user 0 "$p")"
  case "$r" in
    *disabled*) echo "  ✓ $nombre"; echo "$p" >> "$DIR/desactivados.txt" ;;
    *)          echo "  ✗ $nombre: $r" ;;
  esac
done
sort -u "$DIR/desactivados.txt" -o "$DIR/desactivados.txt" 2>/dev/null

echo
echo "Zona horaria:"
ZONA="${ZONA:-}"
if [ -z "$ZONA" ]; then echo "  (sin cambios: para fijarla, ZONA=America/Caracas bash instalar/paso2_limpieza.sh)"; else
TZ_ANTES="$(tv getprop persist.sys.timezone)"
AUTO_ANTES="$(tv settings get global auto_time_zone)"
[ -f "$DIR/zona_horaria_original.txt" ] || echo "$TZ_ANTES $AUTO_ANTES" > "$DIR/zona_horaria_original.txt"
tv settings put global auto_time_zone 0 >/dev/null
tv service call alarm 3 s16 "$ZONA" >/dev/null
sleep 1
TZ_AHORA="$(tv getprop persist.sys.timezone)"
if [ "$TZ_AHORA" = "$ZONA" ]; then
  echo "  ✓ $ZONA (antes: $TZ_ANTES). Hora en la tele: $(tv date +%H:%M)"
else
  tv settings put global auto_time_zone "$AUTO_ANTES" >/dev/null
  echo "  ✗ No pude cambiarla (sigue en $TZ_AHORA)."
  echo "    Hazlo a mano: Ajustes > Preferencias del dispositivo > Fecha y hora >"
  echo "    desactiva 'Zona horaria automática' y elige tu ciudad."
fi
fi

echo
echo "Comprobando que la tele sigue bien..."
tv input keyevent KEYCODE_HOME >/dev/null
sleep 5
captura 01_despues_limpieza
RAM_DESPUES="$(ram_libre)"
echo "Inicio actual: $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo "RAM libre: antes ${RAM_ANTES} MB → ahora ${RAM_DESPUES} MB"
echo
echo "Listo. Si algo no te gusta: bash instalar/restaurar.sh"
echo "Vuelve al chat y dile a Claude: hecho"
