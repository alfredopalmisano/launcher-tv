plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.alfredo.launchertv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.alfredo.launchertv"
        minSdk = 28
        targetSdk = 28
        versionCode = 46
        versionName = "0.46"
    }

    // Firma fija para poder actualizar el launcher sin desinstalarlo.
    // Es una identidad de app, no una credencial: no da acceso a nada.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/launcher.jks")
            storePassword = "launchertv"
            keyAlias = "launchertv"
            keyPassword = "launchertv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // No va a Google Play: no aplicar sus reglas de publicación.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
