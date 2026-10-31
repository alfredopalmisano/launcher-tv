package com.alfredo.launchertv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Inyector de teclas que se queda arrancado (mismo método que scrcpy).
 *
 * No es parte de la app: lo lanza {@link TvAdb} por ADB, como usuario "shell", con
 *   CLASSPATH=&lt;apk&gt; app_process /system/bin com.alfredo.launchertv.KeyInjector
 * y lee de la entrada estándar un nombre de tecla por línea (p. ej. KEYCODE_DPAD_UP).
 * El usuario shell tiene permiso para inyectar teclas, igual que el comando `input`,
 * pero así no hay que arrancar un programa nuevo en cada pulsación (eso tarda ~1 s en la tele).
 *
 * Solo acepta nombres de tecla: cualquier otra línea se ignora.
 */
public final class KeyInjector {

    private KeyInjector() {}

    public static void main(String[] args) throws Exception {
        Class<?> imClass = Class.forName("android.hardware.input.InputManager");
        Object im = imClass.getDeclaredMethod("getInstance").invoke(null);
        Method inject = imClass.getMethod("injectInputEvent", InputEvent.class, int.class);
        final int ASYNC = 0;   // INJECT_INPUT_EVENT_MODE_ASYNC

        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, "UTF-8"));
        System.out.println("listo");
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (!line.startsWith("KEYCODE_")) continue;
            int code = KeyEvent.keyCodeFromString(line);
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue;
            long now = SystemClock.uptimeMillis();
            KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD);
            try {
                inject.invoke(im, down, ASYNC);
                inject.invoke(im, up, ASYNC);
            } catch (Exception e) {
                System.out.println("error " + e);
            }
        }
    }
}
