# Instalar LauncherTV en otra tele

Guía para quien quiera la misma pantalla de inicio en su tele, o para su Claude. El launcher se hizo para una
Xiaomi MiTV-MSSP3 (Android TV 9, 1080p), pero el APK es genérico y debería funcionar en otras.

## 1. ¿Sirve mi aparato?

| Aparato | ¿Funciona? |
|---|---|
| Tele con **Android TV / Google TV** (Xiaomi, TCL, Sony, Philips, Hisense con Google TV…) | Sí |
| Caja o stick con Android TV / Google TV (Xiaomi TV Box S, Google TV Streamer, Chromecast con Google TV, onn 4K…) | Sí |
| Amazon Fire TV | Se instala, pero Fire OS no deja cambiar la pantalla de inicio: solo se abriría como una app más |
| **Roku**, Samsung (Tizen), LG (webOS), Apple TV | **No.** No ejecutan apps Android. Hay que conectar a la tele una caja o un stick con Google TV |

Para saber qué tienes: Ajustes → Sistema/Información. Si pone "Roku OS", hace falta un aparato con Google TV.

## 2. Lo que hace falta

- Un ordenador (los scripts están hechos para **Mac**; en Windows o Linux, cambia en `paso1` la descarga de
  `platform-tools-latest-darwin.zip` por `-windows` o `-linux`, o instala `adb` aparte).
- Tele y ordenador **en la misma Wi-Fi**.
- En la tele: Ajustes → Información → pulsar 7 veces en "Compilación" → **Opciones para desarrolladores** →
  activar **Depuración USB** (y "Depuración por red" o "Depuración inalámbrica" si aparece).
- La **IP de la tele** (Ajustes → Red → la Wi-Fi conectada).

## 3. Pasos (cada uno es reversible)

```bash
git clone https://github.com/alfredopalmisano/launcher-tv.git && cd launcher-tv
export TV_IP=192.168.1.50            # la IP de TU tele
bash instalar/paso1_conectar_y_backup.sh   # conecta por ADB y guarda una copia de seguridad (no cambia nada)
bash instalar/paso2_limpieza.sh            # OPCIONAL: desactiva anuncios y telemetría (lista pensada para Xiaomi)
bash instalar/paso3_instalar_launcher.sh   # descarga el APK de la release "latest", lo instala y lo pone de inicio
bash instalar/paso4_activar_launcher.sh    # desactiva el inicio de Google para que el botón Inicio abra el nuevo
bash instalar/paso5_dormir.sh              # sin salvapantallas, reposo a los 60 min y permisos (Dormir, Continuar viendo)
bash instalar/paso6_airplay.sh "Salón"     # OPCIONAL: receptor AirPlay de código abierto (iPhone/Mac → tele)
bash instalar/restaurar.sh                 # deshace todo y deja la tele como estaba
```

La primera vez, la tele preguntará **"¿Permitir depuración USB?"**: marca "Permitir siempre" y acepta. Volverá a
preguntarlo la primera vez que se use Dormir, porque el launcher se da órdenes a sí mismo por ADB (127.0.0.1:5555).

## 4. Lo que puede necesitar adaptación en otra tele

- **paso2**: los paquetes son de Xiaomi. En otra marca, el script se salta los que no existen. Revisa antes la
  lista de `copia_seguridad/*/paquetes_activos.txt` y desactiva solo lo que estés seguro de no usar.
  Para fijar la zona horaria: `ZONA=America/Caracas bash instalar/paso2_limpieza.sh`.
- **paso4**: en Android TV el inicio de Google es `com.google.android.tvlauncher`. En **Google TV** es
  `com.google.android.apps.tv.launcherx` (y a veces `com.google.android.tungsten.setupwraith`). Comprueba cuál hay
  en `copia_seguridad/*/inicios_disponibles.txt` y cambia el paquete en el script.
- **ADB por red en el puerto 5555**: en Android 11 o superior puede que solo haya "Depuración inalámbrica" (puerto
  aleatorio y emparejamiento con código). Si `adb connect IP:5555` no responde, conecta una vez con
  `adb pair` y `adb connect` al puerto que muestra la tele y ejecuta `adb tcpip 5555`.
- **Resolución**: el diseño está hecho para 1080p (960×540 dp). En 4K con densidad distinta debería escalar, pero
  conviene mirar una captura (`capturas/02_launcher_nuevo.png`).
- **Entradas HDMI**: salen solas, leídas del sistema. En una caja o un stick no habrá ninguna, y es normal.

## 5. Si quieres personalizarlo

Haz un **fork** del repo: GitHub Actions compila el APK en cada push y lo publica en la release `latest` de tu fork.
Luego instala desde tu fork:

```bash
APK_URL=https://github.com/TU_USUARIO/launcher-tv/releases/download/latest/launcher-tv.apk bash instalar/paso3_instalar_launcher.sh
```

`BITACORA.md` explica cada versión y cada decisión de diseño. Las fotos de fondo son de Unsplash (ver `wallpapers/fondos.txt`).
