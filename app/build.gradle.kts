plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.securityalarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.securityalarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach {
    doLast {
        val defaultApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        val namedApk = layout.buildDirectory.file("outputs/apk/debug/SecurityServices.apk").get().asFile
        if (defaultApk.exists()) {
            defaultApk.copyTo(namedApk, overwrite = true)
            defaultApk.delete()
        }
    }
}

// No third-party libraries on purpose: the app only uses the Android framework.
dependencies {
}
