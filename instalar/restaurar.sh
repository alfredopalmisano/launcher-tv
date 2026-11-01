#!/bin/bash
# LauncherTV · RESTAURAR: deja la tele como estaba antes de empezar.
#  - Vuelve a activar todas las apps que estaban activas en la copia de seguridad original.
#  - Vuelve a poner el inicio original de Google.
#  - Devuelve la zona horaria original.
# Uso:  bash instalar/restaurar.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/restaurar_log.txt") 2>&1

echo "== LauncherTV · Restaurar =="
B="$(copia_original)" || { echo "ERROR: no encuentro la copia de seguridad original."; exit 1; }
echo "Usando la copia: ${B##*/}"
conectar
echo

echo "Reactivando apps..."
n=0
for p in $(tv pm list packages -d | sed 's/^package://'); do
  if grep -qx "package:$p" "$B/paquetes_activos.txt"; then
    r="$(tv pm enable "$p")"
    case "$r" in
      *enabled*) echo "  ✓ $p"; n=$((n + 1)) ;;
      *)         echo "  ✗ $p: $r" ;;
    esac
  fi
done
echo "Apps reactivadas: $n"

echo
echo "Inicio original de Google..."
tv cmd package set-home-activity com.google.android.tvlauncher/.MainActivity

if [ -f "$DIR/zona_horaria_original.txt" ]; then
  read -r TZ_ORIG AUTO_ORIG < "$DIR/zona_horaria_original.txt"
  echo "Zona horaria original: $TZ_ORIG"
  tv service call alarm 3 s16 "$TZ_ORIG" >/dev/null
  tv settings put global auto_time_zone "${AUTO_ORIG:-1}" >/dev/null
fi

echo
tv input keyevent KEYCODE_HOME >/dev/null
sleep 5
captura 99_restaurado
echo "Inicio actual: $(tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
echo
echo "Listo: la tele está como al principio."
