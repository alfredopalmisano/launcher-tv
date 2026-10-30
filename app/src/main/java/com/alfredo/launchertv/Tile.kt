package com.alfredo.launchertv

import android.graphics.drawable.Drawable

/** Un elemento del dock o de la cuadrícula de apps. */
sealed class Tile {
    abstract val id: String
    abstract val label: String

    data class App(
        val packageName: String,
        val activityName: String,
        override val label: String,
        val banner: Drawable?,
        val icon: Drawable?
    ) : Tile() {
        override val id: String get() = "app:$packageName"
    }

    data class Input(
        val inputId: String,
        override val label: String,
        val kind: DeviceKind
    ) : Tile() {
        override val id: String get() = "in:$inputId"
    }

    object AllApps : Tile() {
        override val id: String get() = "all"
        override val label: String get() = "Apps"
    }

    object Store : Tile() {
        override val id: String get() = "store"
        override val label: String get() = "Tienda"
    }

    object SettingsTile : Tile() {
        override val id: String get() = "settings"
        override val label: String get() = "Ajustes"
    }

    object SleepTile : Tile() {
        override val id: String get() = "sleep"
        override val label: String get() = "Dormir"
    }

    /** Acceso directo genérico (Wi-Fi, Bluetooth…). */
    data class Action(override val id: String, override val label: String, val iconRes: Int) : Tile()

    object AirPlay : Tile() {
        override val id: String get() = "airplay"
        override val label: String get() = "AirPlay"
    }
}

enum class DeviceKind(val iconRes: Int) {
    PLAYSTATION(R.drawable.ic_gamepad),
    XBOX(R.drawable.ic_gamepad),
    SWITCH(R.drawable.ic_handheld),
    FIRE_TV(R.drawable.ic_stick),
    APPLE_TV(R.drawable.ic_stick),
    CHROMECAST(R.drawable.ic_stick),
    GENERIC(R.drawable.ic_hdmi);

    companion object {
        /** Identifica el aparato por el nombre que anuncia por HDMI-CEC (o por el nombre de la entrada). */
        fun detect(name: String): DeviceKind {
            val n = name.lowercase()
            return when {
                "playstation" in n || Regex("\\bps[1-5]?\\b").containsMatchIn(n) -> PLAYSTATION
                "xbox" in n -> XBOX
                "switch" in n || "nintendo" in n -> SWITCH
                "fire" in n || "amazon" in n || "aftm" in n -> FIRE_TV
                "apple" in n -> APPLE_TV
                "chromecast" in n || "google tv" in n -> CHROMECAST
                else -> GENERIC
            }
        }
    }
}
