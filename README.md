# launcher-tv

Pantalla de inicio minimalista, estilo Apple TV, para mi Xiaomi TV (MiTV-MSSP3, Android TV 9).

- Fondos de naturaleza que rotan con fundido y zoom lento (fotos con licencia Unsplash, ver `wallpapers/fondos.txt`).
- Reloj y fecha grandes.
- Dock con las apps favoritas (mantén pulsado OK sobre una app para añadirla o quitarla).
- Entradas HDMI con algo conectado, con icono según el aparato (consola, stick, etc.).
- Botón "Apps" con todas las aplicaciones.

## Compilar

Cada `push` a `main` compila el APK en GitHub Actions y lo publica en la release **latest**:
`https://github.com/alfredopalmisano/launcher-tv/releases/download/latest/launcher-tv.apk`

Para compilar en local: Android Studio o `gradle assembleRelease` (antes, `bash scripts/descargar_fondos.sh`).

## Firma

`signing/launcher.jks` es la identidad con la que se firma el APK, para poder actualizar el launcher sin desinstalarlo. No es una credencial ni da acceso a nada.
