plugins {
    id("com.android.application")
}

android {
    namespace = "fr.portal.reveilalexa"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.portal.reveilalexa"
        minSdk = 26
        // Portal runs Android 10; targeting 29 keeps background mic + activity launch rules simple.
        targetSdk = 29
        // Bump on any model change: the model is re-copied out of the APK when this changes.
        versionCode = 5
        versionName = "2.3"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
