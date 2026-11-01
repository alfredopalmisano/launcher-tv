# LauncherTV · funciones comunes (los otros scripts cargan este archivo; no hace falta ejecutarlo)

TV_IP="${TV_IP:?Falta la IP de la tele: export TV_IP=192.168.X.Y}"
TV="$TV_IP:5555"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$DIR/herramientas"

if command -v adb >/dev/null 2>&1; then
  ADB="$(command -v adb)"
else
  ADB="$TOOLS/platform-tools/adb"
fi
if [ ! -x "$ADB" ]; then
  echo "ERROR: falta adb. Ejecuta antes el paso 1."
  exit 1
fi

# Conectar con la tele y esperar a que responda de forma estable.
conectar() {
  echo "Conectando con la tele ($TV)..."
  "$ADB" connect "$TV" >/dev/null 2>&1
  local ok=0 r="" i
  for i in $(seq 1 60); do
    r="$("$ADB" -s "$TV" shell echo listo 2>&1 | tr -d '\r')"
    if [ "$r" = "listo" ]; then
      ok=$((ok + 1))
      [ "$ok" -ge 2 ] && break
    else
      ok=0
      case "$r" in
        *unauthorized*) [ $((i % 5)) -eq 1 ] && echo ">> Acepta '¿Permitir depuración USB?' en la tele (marca 'Permitir siempre')." ;;
        *) [ $((i % 5)) -eq 1 ] && echo "Esperando a la tele (intento $i de 60)... ¿Está encendida?" ;;
      esac
      [ "$i" -eq 15 ] && { echo "Reiniciando adb..."; "$ADB" kill-server >/dev/null 2>&1; }
      "$ADB" disconnect "$TV" >/dev/null 2>&1
      "$ADB" connect "$TV" >/dev/null 2>&1
    fi
    sleep 2
  done
  if [ "$ok" -lt 2 ]; then
    echo "ERROR: no se pudo conectar con la tele ($r)."
    echo "Comprueba que está encendida, con 'Depuración USB' activada y en la IP $TV_IP."
    echo "Si sigue fallando: reinicia la tele y vuelve a ejecutar el script."
    exit 1
  fi
  echo "Conectado ✅"
}

# Ejecutar un comando en la tele.
tv() { "$ADB" -s "$TV" shell "$@" 2>&1 | tr -d '\r'; }

# Guardar una captura de la pantalla de la tele: captura NOMBRE
# (la tele escribe texto basura antes de la imagen; perl lo quita).
captura() {
  mkdir -p "$DIR/capturas"
  "$ADB" -s "$TV" exec-out screencap -p 2>/dev/null \
    | perl -0777 -pe 's/\A.*?(?=\x89PNG)//s' > "$DIR/capturas/$1.png"
  echo "Captura guardada: capturas/$1.png"
}

# RAM disponible en la tele, en MB.
ram_libre() { tv cat /proc/meminfo | awk '/MemAvailable/ {print int($2 / 1024)}'; }

# Carpeta de la copia de seguridad original (la más antigua con datos).
copia_original() {
  local d
  for d in "$DIR"/copia_seguridad/*/; do
    grep -q '^package:' "$d/paquetes_activos.txt" 2>/dev/null && { echo "${d%/}"; return 0; }
  done
  return 1
}
