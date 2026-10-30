# LauncherTV · Bitácora del proyecto

> **Si se reinicia o comprime el chat:** abre un chat nuevo en el proyecto "Proyectos Personales" y escribe:
> *"Sigue con LauncherTV. Lee BITACORA.md del repo github.com/alfredopalmisano/launcher-tv y la carpeta ~/Desktop/LauncherTV del Mac."*
> Claude tendrá que: (1) conectar el repo con `add_repo` (ya está autorizado en GitHub), (2) pedir acceso a la carpeta
> `~/Desktop/LauncherTV`, (3) leer este archivo y la sección "Pendiente". Los scripts del Mac se ejecutan en Terminal con
> `bash ~/Desktop/LauncherTV/<script>.sh`; la tele responde por ADB en 192.168.0.102 (si no, reiniciarla).

Proyecto personal: pantalla de inicio propia, estilo Apple TV, para la tele **Xiaomi MiTV-MSSP3 (Android TV 9)**, más una limpieza reversible del sistema.
Carpeta de trabajo en el Mac: `~/Desktop/LauncherTV`. Código: https://github.com/alfredopalmisano/launcher-tv

---

## 1. Datos de la tele

| Dato | Valor |
|---|---|
| Modelo | MiTV-MSSP3 (nombre interno "machuca") |
| Android | 9 (API 28), Android TV con launcher de Google + PatchWall de Xiaomi |
| IP en casa | 192.168.0.102 (ADB por Wi-Fi, puerto 5555) |
| Pantalla | 1920×1080, densidad 320 (2×) → 960×540 dp |
| RAM | 2 GB (al empezar solo 494 MB libres) |
| Almacenamiento | 4,1 GB, al 85 % |
| Zona horaria | Estaba en America/Chicago → cambiada a **Europe/Madrid** |

---

## 2. Lo que se hizo en la tele (en orden)

### Paso 1 · Conexión y copia de seguridad (`paso1_conectar_y_backup.sh`)
- Descarga `adb` oficial de Google a `herramientas/platform-tools/`.
- Conecta por Wi-Fi y guarda en `copia_seguridad/<fecha>/`: lista de paquetes (todos, activos, desactivados, sistema, instalados por el usuario), inicio por defecto, ajustes global/secure/system, propiedades, memoria, almacenamiento.
- Captura: `capturas/00_antes.png`.
- Lección: el ADB de esta tele se cuelga a veces ("device offline" / "timed out"). **Solución: reiniciar la tele** y volver a lanzar el script.

### Paso 2 · Limpieza reversible (`paso2_limpieza.sh`)
Todo con `pm disable-user --user 0` (desactivar, **nunca desinstalar**). Lista en `desactivados.txt`.

Desactivado:
- Telemetría y anuncios: `com.miui.tv.analytics`, `com.xiaomi.statistic`, `android.autoinstalls.config.xiaomi.amelie` (instala apps promocionadas), `com.google.android.feedback`, `com.google.android.tv.bugreportsender`, `com.mitv.tvhome.michannel`, `com.google.android.tvrecommendations`, `com.google.android.leanbacklauncher.recommendations`.
- Xiaomi: `com.mitv.tvhome.atv` (PatchWall, botón Mi), `com.mitv.tvhome.mitvplus` (Xiaomi TV+), `com.xiaomi.mimusic2`, `com.xiaomi.mitv.tvmanager`, `com.xiaomi.mitv.updateservice` (actualizaciones; decidido desactivar), `com.mitv.gallery`, `com.xiaomi.mitv.mediaexplorer`, `com.mitv.videoplayer`.
- Google: `com.google.android.videos` (Google TV/Play Películas), `com.google.android.play.games`, `com.google.android.youtube.tvmusic`, `com.google.android.katniss` (búsqueda por voz), `com.google.android.speech.pumpkin`.
- Otros: `com.android.printspooler`.
- Más tarde (paso 4): `com.google.android.tvlauncher` (inicio de Google).

Se mantuvo a propósito: **enviar desde el móvil (Chromecast/`mediashell`)**, Netflix, Prime Video, YouTube, Disney+, Max, SkyShowtime, Flujo, Televen, Liga Futve, NordVPN, Downloader, Play Store, TV en directo/HDMI, Ajustes, servicios de Google (gms).

Resultado: RAM libre 494 → 837 MB tras la limpieza, **978 MB** con el launcher propio activo.

### Paso 3 · Instalar el launcher (`paso3_instalar_launcher.sh`)
- Descarga el APK de la release `latest` de GitHub, `adb install -r`, y lo pone como inicio (`cmd package set-home-activity`).
- Guarda `hdmi_info.txt` (estado de las entradas HDMI) y `logcat.txt` para diagnóstico.
- Sirve también para **actualizar** a nuevas versiones.

### Paso 4 · Activar el launcher (`paso4_activar_launcher.sh`)
- El inicio de Google tiene prioridad fija (priority=2) y gana siempre, así que hay que desactivarlo. El script primero comprueba que el launcher propio arranca; si no, no toca nada.
- Probó el botón Inicio desde dentro de Ajustes → abre el launcher propio ✅. No hizo falta desactivar `setupwraith`.

### Restaurar (`restaurar.sh`)
Vuelve a activar todo lo que estaba activo en la copia de seguridad original, pone el inicio de Google y devuelve la zona horaria original. Deja la tele como al principio.

---

## 3. El launcher (app Android)

Repositorio `launcher-tv`, Kotlin, `minSdk 28`, `targetSdk 28`, sin librerías salvo RecyclerView. Paquete `com.alfredo.launchertv`.

### Cómo se compila
- Cada `push` a `main` → GitHub Actions compila (`.github/workflows/build.yml`) y publica el APK en la release **latest**:
  `https://github.com/alfredopalmisano/launcher-tv/releases/download/latest/launcher-tv.apk`
- Antes de compilar, `scripts/descargar_fondos.sh` baja las fotos de `wallpapers/fondos.txt` (Unsplash, licencia libre) y la tipografía **Nunito** (OFL) a `assets/`.
- Si falla, el error se publica como comentario del commit (los logs de Actions no se pueden leer desde la sesión de Claude).
- Firma fija: `signing/launcher.jks` (contraseña `launchertv`). Es una identidad de app para poder actualizar sin desinstalar; no da acceso a nada.
- Problemas resueltos en CI: `android-actions/setup-android` fallaba en el runner → se usa el SDK preinstalado; lint de Google Play (`ExpiredTargetSdkVersion`) → desactivado.

### Funciones (v0.16)
- **Filas**: 1) Favoritos (sin nombres), 2) Conexiones y sistema (HDMI con nombre CEC, AirPlay, Wi-Fi, Bluetooth, Dormir, Tienda, Ajustes), 3) Continuar viendo (Watch Next de Netflix/Prime/Disney+/Max…, con logo de la app). Al bajar se oscurece el fondo.
- **Modo ambiente**: arriba desde el dock o 5 min sin tocar el mando → solo fondo + reloj; cualquier tecla vuelve.
- **Mover**: mantener OK → Mover → ← → recolocan la app (amarillo), OK suelta, Atrás cancela.
- **Dormir**: Ahora / 15…120 min; usa device admin (lockNow). Paso 5 lo concede; el paso 3 lo reaplica en cada actualización.

### Funciones (v0.5, histórico)
- **Fondos** de montañas/paisajes rotando cada 40 s con fundido y zoom lento (Ken Burns). Bug corregido en 0.3: el zoom cancelaba el fundido y el fondo salía negro.
- **Reloj de cristal** (`GlassTextView`): las cifras dejan ver el fondo difuminado y aclarado, con brillo superior y sombra; arriba centrado, como el iPhone. Sin fecha (decisión del usuario).
- **Dock**: favoritas (mantén OK → menú: añadir/quitar del dock, mover, desinstalar, info), entradas **HDMI** conectadas con icono según el aparato (consola/stick/portátil/HDMI; detección por el nombre CEC), **AirPlay** (abre AirScreen o lleva a instalarlo), **Tienda** (Play Store), **Ajustes**, **Apps**.
- **Pantalla Apps**: cuadrícula con la foto difuminada de fondo; el crédito de la foto va aquí abajo a la derecha, no en la principal.
- Tarjetas: dock 176 dp, cuadrícula 144 dp (las primeras de 224 dp eran enormes).

### Historial de versiones
| v | Cambios |
|---|---|
| 0.1 | Primera versión: dock, HDMI, fondos, reloj y fecha |
| 0.2 | Arreglo del fondo negro |
| 0.3 | Reloj de cristal centrado, Inter, sin fecha, crédito movido a Apps |
| 0.4 | Nunito, fotos de montañas épicas, menú de app, Tienda, tarjetas pequeñas, Apps con fondo difuminado |
| 0.5 | Tiles de Ajustes y AirPlay |
| 0.6 | Tile Dormir (temporizador, device admin), pantalla siempre encendida |
| 0.7 | Reloj detrás de las montañas (recorte del cielo en la tele), fuente Antonio, HDMI vacíos ocultos |
| 0.8 | Filas con scroll vertical, Wi-Fi/Bluetooth, fondos de "mountain-wallpaper" |
| 0.9 | Modo ambiente (arriba / 5 min), modo Mover con flechas, fondos depurados |
| 0.10 | Reloj ahumado sobre cielos claros, filas inferiores ocultas hasta bajar |
| 0.11–0.13 | Sin título ni nombres en la primera fila, tarjetas 196 dp, reloj nunca en la zona del dock, Conexiones 2ª fila |
| 0.14–0.16 | Fila Continuar viendo (Watch Next) con carátulas y logo de la app, HDMI solo en Conexiones, sin Netflix Observer |

---

## 4. Pendiente / ideas acordadas
- **PRIORIDAD · Reloj detrás de las montañas**: el recorte en la tele no es fiable. Nuevo método: analizar las fotos en GitHub Actions (Python), generar máscaras y posiciones (`layout.json`) y **previsualizar aquí** antes de publicar. La app solo carga la máscara. Requisitos del usuario: reloj arriba, tamaño fijo, la montaña tapa solo el borde inferior (~20 %), nunca en la zona de las apps.
- **Bug**: al volver del modo ambiente el foco salta a la primera app (debe quedarse donde estaba).
- **Dormir**: la app dice "falta permiso" aunque `dpm set-active-admin` da Success. Ver `diag.txt` (dumpsys device_policy) y el log "Dormir: admin activo=".
- **AirPlay (fase 2)**: compilar jqssun/android-airplay-server (UxPlay, GPL-3, armeabi-v7a) en Actions como segundo APK; paso para instalarlo; el tile AirPlay lo abre. Probado por otros en Xiaomi MiTV Android 9.
- Ver la **0.5 en la tele** y hacer la pasada de diseño "premium" (dock, tarjetas, foco, brillos).
- **Fire TV por HDMI**: revisar con `hdmi_info.txt` por qué no se ve bien su tarjeta.
- **Segunda fila** (elegir): Continuar viendo · Recientes · Tiempo · Atajos de sistema · Mando desde el móvil · Elegir fondos · Carabobo FC.
- **AirPlay propio** (fase 2): receptor dentro del launcher basado en UxPlay/RPiPlay (pasaría a GPL). 2-4 h de trabajo.
- **Botón Mi** → pantalla propia con tema del **Carabobo FC**.
- Encender la tele desde el iPhone (ya funciona enviando desde YouTube/Netflix; mando web pendiente).

## 5. Cosas a recordar
- Mientras un script trabaja, no escribir en Terminal (lo escrito se ejecuta después).
- Si ADB no conecta: Ctrl+C, reiniciar la tele, volver a lanzar.
- La tele nunca se apaga del todo con el mando (reposo); un reinicio semanal le viene bien.
- Para volver atrás del todo: `bash ~/Desktop/LauncherTV/restaurar.sh`.
