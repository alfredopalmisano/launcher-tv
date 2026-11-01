#!/bin/bash
# LauncherTV · Paso 5: sin salvapantallas + temporizador de apagado.
#  - Desactiva el salvapantallas del sistema.
#  - Reposo automático tras 1 hora sin tocar el mando (por si te duermes con algo en pausa).
#  - Da al launcher el permiso para poner la tele en reposo (tile "Dormir").
# Reversible: restaurar.sh deshace todo.
# Uso:  bash instalar/paso5_dormir.sh

set -u
source "$(cd "$(dirname "$0")" && pwd)/comun.sh"
exec > >(tee "$DIR/paso5_log.txt") 2>&1

PKG="com.alfredo.launchertv"
echo "== LauncherTV · Paso 5: salvapantallas y apagado =="
conectar
echo

# Guardar valores originales una sola vez
if [ ! -f "$DIR/energia_original.txt" ]; then
  {
    echo "screensaver_enabled $(tv settings get secure screensaver_enabled)"
    echo "sleep_timeout $(tv settings get secure sleep_timeout)"
    echo "screen_off_timeout $(tv settings get system screen_off_timeout)"
  } > "$DIR/energia_original.txt"
fi
echo "Valores originales guardados en energia_original.txt:"
cat "$DIR/energia_original.txt"
echo

echo "Salvapantallas: desactivado"
tv settings put secure screensaver_enabled 0 >/dev/null
echo "Reposo automático tras 60 min sin actividad"
tv settings put secure sleep_timeout 3600000 >/dev/null
# Sin salvapantallas, Android apaga la pantalla al llegar a screen_off_timeout (venía en ~5 min):
# dentro del menú de una app la tele se apagaba a los 5 minutos. Igualarlo a los 60 minutos.
tv settings put system screen_off_timeout 3600000 >/dev/null

echo
echo "Permiso para que el launcher pueda poner la tele en reposo..."
r="$(tv dpm set-active-admin --user 0 "$PKG/.SleepAdminReceiver")"
echo "  $r"
if echo "$r" | grep -qi "error\|not found\|exception"; then
  echo "  (Si el launcher instalado aún es anterior a la 0.6, instala primero la nueva versión con paso3 y repite este paso.)"
fi

echo
echo "Permiso para la fila 'Continuar viendo' (lo que publican Netflix, Prime, Disney+, Max…)..."
tv pm grant "$PKG" android.permission.READ_TV_LISTINGS && echo "  ✓ concedido"
tv appops set "$PKG" GET_USAGE_STATS allow >/dev/null 2>&1

echo
echo "Ahora: $(tv settings get secure screensaver_enabled | sed 's/0/sin salvapantallas/;s/1/salvapantallas activo/'), reposo a los $(( $(tv settings get secure sleep_timeout) / 60000 )) min, pantalla a los $(( $(tv settings get system screen_off_timeout) / 60000 )) min."
echo "Listo. Vuelve al chat y dile a Claude: hecho"
