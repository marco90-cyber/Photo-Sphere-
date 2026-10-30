plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.marco.photosphere"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.marco.photosphere"
        minSdk = 26
        targetSdk = 34
        // Numero di build di GitHub Actions: ogni versione è più nuova della precedente (aggiornamenti in app)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "3").toInt()
        versionCode = run
        versionName = "1.2.$run"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Firma: chiave privata dai segreti di GitHub (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
    // Serve una firma sempre uguale perché gli aggiornamenti in app si installino sopra la versione precedente.
    // Se i segreti non ci sono, si usa la chiave di debug.
    val appKeystore = System.getenv("APP_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (appKeystore != null) {
            create("private") {
                storeFile = appKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (appKeystore != null) signingConfigs.getByName("private") else signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.opencv:opencv:4.9.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
