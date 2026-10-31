# LauncherTV · Bitácora del proyecto

> **Si se reinicia o comprime el chat:** abre un chat nuevo en el proyecto "Proyectos Personales" y escribe:
> *"Sigue con LauncherTV. Lee BITACORA.md del repo github.com/alfredopalmisano/launcher-tv y la carpeta ~/Desktop/LauncherTV del Mac."*
> Claude tendrá que: (1) conectar el repo con `add_repo` (ya está autorizado en GitHub), (2) pedir acceso a la carpeta
> `~/Desktop/LauncherTV`, (3) leer este archivo y la sección "Pendiente". Los scripts del Mac se ejecutan en Terminal con
> `bash ~/Desktop/LauncherTV/<script>.sh`; la tele responde por ADB en 192.168.0.102 (si no, reiniciarla).

Proyecto personal: pantalla de inicio propia, estilo Apple TV, para la tele **Xiaomi MiTV-MSSP3 (Android TV 9)**, más una limpieza reversible del sistema.
Carpeta de trabajo en el Mac: `~/Desktop/LauncherTV`. Código: https://github.com/alfredopalmisano/launcher-tv

---

## 0. Para la mañana del 31-oct (resumen de la noche)

Lo que quedó hecho mientras dormías, y cómo probarlo, en orden:

1. **Actualizar la tele a la 0.28** (trae Dormir por ADB interno, Reiniciar, tile Mando, AirPlay):
   `bash ~/Desktop/LauncherTV/paso3_instalar_launcher.sh`
2. **Dormir**: en Conexiones y sistema → Dormir → "Ahora". La primera vez la tele mostrará
   **"¿Permitir depuración USB?" → marca "Permitir siempre" y acepta.** Vuelve a Dormir → Ahora: la tele se va a reposo.
   (Antes fallaba porque esta tele no admite device admin; ahora el launcher le da la orden por ADB a sí misma, 127.0.0.1:5555.)
3. **AirPlay** (receptor de código abierto, se instala aparte): `bash ~/Desktop/LauncherTV/paso6_airplay.sh`
   Luego en el iPhone: Centro de control → Duplicar pantalla → "Salón". En el Mac: Duplicar pantalla / AirPlay. No sirve para Netflix u otras apps con DRM (se ve negro: es una limitación de Apple, no nuestra).
4. **Mando desde el iPhone**: en la tele, Conexiones y sistema → **Mando**: sale una dirección (`http://192.168.0.102:8080`) y un **PIN**.
   Ábrela en Safari, mete el PIN una vez y "Añadir a pantalla de inicio". Tiene cruceta, volumen, teclado para escribir en la tele, apps, HDMI, Encender, Dormir y temporizador. Solo funciona en tu Wi-Fi.
   Si "control total" no aparece en la cabecera, es que falta aceptar el permiso del punto 2.
5. Si algo falla: `bash ~/Desktop/LauncherTV/diagnostico.sh` y pásame `capturas/diag.png`, `diag.txt` y `logcat.txt`.

**Mando del Fire TV** (investigado, sección 6): el mando de Amazon solo habla con el Fire TV (Bluetooth propietario, no se puede emparejar con la tele) y hacia la tele solo manda **encender/apagar, volumen y silencio** (por infrarrojos o CEC). No puede mover el cursor del launcher. Opciones reales abajo.

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
| Device admin | **No lo admite** (`dumpsys device_policy` vacío) → Dormir usa ADB interno |

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

### Paso 3 · Instalar / actualizar el launcher (`paso3_instalar_launcher.sh`)
- Descarga el APK de la release `latest` de GitHub, `adb install -r -g`, lo pone como inicio (`cmd package set-home-activity`), reaplica permisos (`READ_TV_LISTINGS` para Continuar viendo) y guarda `hdmi_info.txt` y `logcat.txt`.

### Paso 4 · Activar el launcher (`paso4_activar_launcher.sh`)
- El inicio de Google tiene prioridad fija (priority=2) y gana siempre, así que hay que desactivarlo. El script primero comprueba que el launcher propio arranca; si no, no toca nada.

### Paso 5 · Dormir y sin salvapantallas (`paso5_dormir.sh`)
- `screensaver_enabled 0`, reposo automático a los 60 min sin uso (`sleep_timeout 3600000`), permisos del launcher.

### Paso 6 · AirPlay (`paso6_airplay.sh`)
- Descarga la última release de **jqssun/android-airplay-server** (UxPlay, GPL-3) tal como la publica su autor, la instala, la deja correr en segundo plano y pone el nombre de la tele en "Salón" (`bash paso6_airplay.sh "Otro nombre"` para cambiarlo). El tile AirPlay del launcher la abre.
- Decisión: **no** se compila dentro de nuestro repo ni se firma con nuestra clave; se usa el APK del autor. Si algún día no funciona, alternativas de pago en Play Store: AirScreen, AirReceiver.

### Diagnóstico (`diagnostico.sh`)
- Captura `capturas/diag.png`, `logcat.txt` filtrado por LauncherTV, `diag.txt` (versión, permisos, Watch Next, device policy).

### Restaurar (`restaurar.sh`)
Vuelve a activar todo lo que estaba activo en la copia de seguridad original, pone el inicio de Google y devuelve la zona horaria original. Deja la tele como al principio.

---

## 3. El launcher (app Android)

Repositorio `launcher-tv`, Kotlin, `minSdk 28`, `targetSdk 28`. Paquete `com.alfredo.launchertv`. Dependencias: RecyclerView y **AdbLib** (Cameron Gutman, BSD-3, copiada en `com/cgutman/adblib/`).

### Cómo se compila
- Cada `push` a `main` → GitHub Actions compila (`.github/workflows/build.yml`) y publica el APK en la release **latest**:
  `https://github.com/alfredopalmisano/launcher-tv/releases/download/latest/launcher-tv.apk`
- Antes de compilar: `scripts/descargar_fondos.sh` baja las fotos de `wallpapers/fondos.txt` (Unsplash, licencia libre) y las tipografías **Antonio** (reloj) y **Nunito** (textos), ambas OFL.
- Luego `scripts/analizar_fondos.py` (Python, SegFormer `nvidia/segformer-b2-finetuned-ade-512-512` + filtro guiado) separa **cielo / montaña** en cada foto, decide dónde va el reloj y genera `NN.mask.png` + `layout.json` dentro de los assets, y **previsualizaciones** en `wallpapers/preview/` (`hoja.jpg` = todas juntas). Así se comprueba el resultado aquí antes de instalarlo en la tele. Por foto se puede forzar `fg=no` en `fondos.txt` (sin montaña delante).
- Si falla, el error se publica como comentario del commit (los logs de Actions no se pueden leer desde la sesión de Claude).
- Firma fija: `signing/launcher.jks` (contraseña `launchertv`). Es una identidad de app para poder actualizar sin desinstalar; no da acceso a nada.
- Problemas resueltos en CI: `setup-android` fallaba → SDK preinstalado; lint de Google Play → desactivado; tras los commits automáticos de CI hay que hacer `git pull --rebase` antes de `push`.

### Cómo está hecho el reloj (lo que más costó)
- `GlassTextView`: dibuja las cifras con **celdas tabulares** (todas las cifras del mismo ancho, los dos puntos más estrechos) y las rellena con un `BitmapShader` del **propio fondo** (a cuarto de resolución, aclarado +14): cada píxel del reloj tiene el color exacto de lo que hay detrás, más un velo blanco del 8 %, un brillo superior, un borde fino y sombra. Fuente Antonio 200 (estilo pantalla de bloqueo del iPhone), 180 sp, siempre del mismo tamaño.
- **Profundidad**: capa foto → reloj → capa "montaña" (la foto recortada con la máscara) → oscurecido → filas. La montaña tapa solo el **20 % inferior** de las cifras (`OVERLAP=0.20` en el script). El reloj nunca baja de la zona del dock.
- Las dos capas hacen el mismo zoom lento (Ken Burns) para que no se note la costura.

### Funciones (v0.28)
- **Filas**: 1) Favoritos sin título ni nombres, sobre una **repisa esmerilada** estilo tvOS, iconos 168 dp, brillo al enfocar, bordes con fundido; 2) **Conexiones y sistema**: HDMI conectados con icono del aparato (Fire TV detectado), AirPlay, Wi-Fi, Bluetooth, Dormir, Reiniciar, **Mando**, Tienda, Ajustes; 3) **Continuar viendo** (Watch Next de Netflix/Prime/Disney+/Max/Flujo, carátula + logo de la app). Las filas 2 y 3 no se ven hasta bajar.
- **Modo ambiente**: arriba desde el dock o 5 min sin tocar → solo fondo + reloj; cualquier tecla vuelve y el foco se queda donde estaba.
- **Menú de app** (mantener OK): añadir/quitar del dock, **Mover** (← → varias casillas, OK suelta, Atrás cancela), desinstalar, información.
- **Dormir**: Ahora / 15…120 min. Da la orden `KEYCODE_SLEEP` por **ADB interno** (`TvAdb.kt`, conexión a 127.0.0.1:5555 con clave propia guardada en la app). Requiere aceptar una vez "Permitir depuración USB → Permitir siempre" en la tele. Reiniciar usa lo mismo.
- **Mando web** (`RemoteService.kt`): servidor HTTP en el puerto 8080, arranca con el launcher y al encender la tele. Página para el móvil con cruceta, volumen, teclado, apps (con sus carátulas), HDMI, Encender (wake lock + `KEYCODE_WAKEUP`), Dormir, temporizador, Reiniciar. **Seguridad**: solo acepta direcciones de la red local, exige un **PIN** de 6 cifras propio de la tele (se ve en el tile Mando), solo teclas de una lista cerrada y el texto se filtra antes de llegar a la shell.
- **Pantalla Apps**: cuadrícula sobre la foto difuminada; el crédito de la foto va aquí, no en la principal. Sin apps de sistema sin icono (Netflix Observer, etc.).

### Historial de versiones
| v | Cambios |
|---|---|
| 0.1–0.6 | Dock, HDMI, fondos, reloj de cristal, Nunito, menú de app, Tienda, Ajustes, AirPlay, Dormir (device admin) |
| 0.7–0.10 | Reloj detrás de las montañas (1.ª versión, recorte en la tele: no fiable), Antonio, scroll vertical, Wi-Fi/BT, modo ambiente, Mover |
| 0.11–0.16 | Sin títulos ni nombres en la 1.ª fila, Conexiones 2.ª fila, Continuar viendo con carátulas y logo, HDMI solo en Conexiones |
| 0.17–0.20 | **Análisis de fotos en GitHub Actions** (SegFormer), máscaras y `layout.json`, previsualizaciones; reloj tamaño fijo arriba; foco se mantiene al salir del ambiente |
| 0.21–0.24 | Repisa esmerilada del dock, iconos más pequeños, barrido de luz al enfocar, bordes con fundido, filas inferiores ocultas hasta bajar |
| 0.25–0.26 | Reloj **cristal real** (color exacto de detrás, celdas tabulares, sin rayas), máscara LA (arreglo: la montaña tapaba todo el reloj), borde fino adaptativo |
| 0.27 | **Dormir y Reiniciar por ADB interno** (AdbLib), tile AirPlay abre el receptor de código abierto |
| 0.28 | **Mando web para el iPhone** (puerto 8080, PIN, solo red local), tile Mando, arranque al encender |
| 0.29 | Cristal del reloj **alineado de verdad**: se redibuja mientras el reloj se mueve y sigue el zoom Ken Burns (antes enseñaba el fondo de la posición anterior); el cristal se funde en 1,6 s con la foto; dock con 5 tarjetas exactas de 152 dp; estante más visible |
| 0.30 | ❌ **Rota**: medir el estante en tiempo real llamaba a `notifyDataSetChanged` mientras Android medía la lista → la app se cerraba en bucle (pantalla negra, Home sin respuesta). Confirmado con el logcat de `rescate.sh` |
| 0.31 | Vuelta al código de la 0.29 |
| 0.32 | El dock recorta lo que queda fuera del estante (`clipToPadding/clipChildren`): ya no asoman trocitos de las vecinas. Geometría simulada antes en todas las posiciones |
| 0.33 | **Dock en carrusel** (tras la última tarjeta sigue la primera, si hay más de 5) y **Mover en Conexiones y sistema** (`SystemOrder.kt`, se guarda el orden) |
| 0.34 | Conexiones y sistema alineada con el dock: tarjetas de 152 dp en las mismas columnas (52 dp + n×176) |
| 0.35 | **Mando del móvil rápido**: conexión ADB persistente y `KeyInjector` (proceso `app_process` que se queda arrancado e inyecta teclas, como scrcpy); si falla, vuelve a `input keyevent` |
| 0.36 | Carrusel en todas las filas, ← → gestionadas por el launcher (foco estable pulsando rápido), brillo según la dirección |
| 0.37–0.38 | Foto siguiente preparada en segundo plano, reloj por GPU, fundido sin bajón de brillo, cristal con la misma curva que la foto |
| 0.39–0.43 | **Medido en la tele** (`medir_fluidez.sh`, `medir_piezas.sh`): reposo ~38 fps → 0 redibujos; cambio de foto 20→30 fps. Sin capas de montaña (el reloj se recorta con la máscara), sin refracción (cristal exacto, ½ res.), foto 07 por encima de la cumbre, reloj pre-renderizado (A8 + sombra + brillo una vez por minuto). La 0.43 (degradado dentro de la foto) se revirtió: se veía peor |
| 0.44 | Efecto 3D al enfocar (inclinación 7° + rebote), clic sintetizado, zoom lento de fotos a 5 actualizaciones/s, Atrás: filas → dock → imágenes; imágenes solo se salen con ↓ |
| 0.45 | **Analizado con grabaciones de pantalla** (`grabar_animaciones.sh`): reflejo sin bordes (`ShineDrawable`), icono de extremo sin cortar, apertura de apps con el icono creciendo y fundiendo a negro; **modo noche** (`Night.kt`): con temporizador de Dormir `picture_backlight`=10 y fotos 38 % más oscuras, al quitarlo vuelve al brillo anterior; clic al 15 % |
| 0.46 | La capa negra de la apertura ya no puede quedarse encima al volver |

---

## 4. Pendiente / ideas acordadas
- **31-oct mañana**: Alfredo nota "todo en la tele mucho más rápido" tras quitar los redibujos continuos. Antes de tocar algo que no ha pedido, **preguntarle**.
- **Carabobo FC**: falta el vídeo del reel (grabación de pantalla del iPhone) y saber qué envía el botón Mi (`boton_mi.sh` salió vacío). Fotos en `~/Desktop/LauncherTV/carabobo/fotos` (no van al repo: es público).
- Herramientas nuevas: `medir_fluidez.sh`, `medir_piezas.sh` (interruptores por adb, se reinician solos), `grabar_animaciones.sh` (screenrecord), `buscar_brillo.sh`, `rescate.sh`.
- **Hecho y probado por Alfredo (31-oct madrugada)**: Dormir con permiso ADB ✅, AirPlay ✅ (le gusta; algo lento, **no cambiarlo**), mando web ✅ pero lento → arreglado en 0.35, **falta confirmar en la tele que ya va rápido**. Reloj y dock: "perfecto" con la 0.32.
- **Probar 0.35**: carrusel del dock, Mover en Conexiones, alineación de filas, velocidad del mando.
- **Mañana: botón Mi con tema del Carabobo FC** (lo pidió para mañana).
- Lección: **nunca** llamar a `notify*` de un adaptador desde un listener de layout/medida ni desde `onCreateViewHolder`. Antes de publicar cambios de UI, simular la geometría/lógica aquí.
- Rescate si la tele queda en negro: `bash ~/Desktop/LauncherTV/rescate.sh` (reactiva el inicio de Google, guarda errores, reinstala la última versión); después `paso4_activar_launcher.sh` para volver al nuestro.
- **Botón Mi** → pantalla propia con tema del **Carabobo FC** (PatchWall está desactivado; hay que ver qué intent lanza el botón: `adb shell getevent` / logcat al pulsarlo).
- **Mando del Fire TV**: decidir entre las opciones de la sección 6.
- Limpieza de código: quitar `RecentsRepository.kt` (ya no se usa); `Sleep.kt` conserva el camino device admin por si acaso.
- Comprobar que Continuar viendo trae contenido tras conceder `READ_TV_LISTINGS` (en `diag.txt`, sección Watch Next).
- Idea: código QR en el diálogo del tile Mando para no teclear la dirección.
- Idea: elegir fondos desde la tele. (El tiempo en modo ambiente: Alfredo no mostró interés.)

## 5. Cosas a recordar
- Mientras un script trabaja, no escribir en Terminal (lo escrito se ejecuta después).
- Si ADB no conecta: Ctrl+C, reiniciar la tele, volver a lanzar.
- La tele nunca se apaga del todo con el mando (reposo); un reinicio semanal le viene bien (tile Reiniciar).
- Para volver atrás del todo: `bash ~/Desktop/LauncherTV/restaurar.sh`.
- Nada de claves personales en el repo: la única "clave" es la firma del APK, que no da acceso a nada. La clave ADB del launcher se genera en la propia tele y no sale de ella.

## 6. Mando del Fire TV: qué se puede y qué no

**Cómo funciona ese mando.** El Alexa Voice Remote se empareja por Bluetooth **solo con dispositivos Fire TV** (protocolo propio de Amazon; no es un mando Bluetooth estándar, así que la tele Xiaomi no lo puede emparejar como mando). Para hablar con la tele usa **infrarrojos** (los mandos con botones de encendido/volumen) o **HDMI-CEC**, y por esas vías solo manda: **encender/apagar la tele, volumen, silencio y, en algunos modelos, cambiar de entrada**. Se configura en el Fire TV: *Ajustes → Mandos y dispositivos Bluetooth → Gestionar equipos → TV*. La cruceta y OK **siempre** van al Fire TV: no hay forma de que muevan el cursor de la tele.

**Opciones reales, de más fácil a más ambiciosa:**

1. **Al revés, y es lo que recomiendo**: usar el **mando de la Xiaomi para todo**. Con HDMI-CEC activado en la tele (Ajustes → Preferencias del dispositivo → Entradas → "Control HDMI") y en el Fire TV, al abrir la tarjeta Fire TV en el launcher, la cruceta, OK, Atrás y reproducción del mando Xiaomi controlan el Fire TV; el botón Inicio de la Xiaomi vuelve a nuestro launcher. Un solo mando en la mesa.
2. **El iPhone como mando** (hecho esta noche): el mando web controla el launcher y la tele; para el Fire TV existe la app oficial "Amazon Fire TV".
3. **"Puente" en el Fire TV** (1–2 tardes de trabajo, sin garantías): una app propia instalada en el Fire TV por ADB que, al abrirla, captura las teclas del mando de Amazon y las reenvía por Wi-Fi al mando web del launcher (`/api/key`), y una tecla para "volver al Fire TV". Limitaciones: el botón Inicio del mando de Amazon no se puede capturar (siempre abre el inicio del Fire TV), y habría que abrir la app puente cada vez. Funciona, pero es incómodo comparado con la opción 1.
4. **Un mando universal** con base de datos de ambos (Xiaomi + Fire TV), p. ej. Logitech Harmony (descatalogado) o SofaBaton: controla ambos aparatos con un solo mando, sin tocar software.

Fuentes consultadas: guías de configuración del control de equipos del Fire TV ([androidexperto](https://androidexperto.com/how-to-change-the-tv-input-with-a-firestick-remote-2/), [Tom's Guide](https://forums.tomsguide.com/threads/how-to-enable-hdmi-cec-on-your-fire-tv.212722/latest)), [redimpulz/firetv-cec-volume](https://github.com/redimpulz/firetv-cec-volume) (confirma que desde el Fire TV solo salen órdenes CEC de volumen/encendido), documentación de emparejamiento del mando ([Sony: mandos Bluetooth en Android TV](https://www.sony.ee/electronics/support/articles/00199201)).
