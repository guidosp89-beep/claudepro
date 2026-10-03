plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ghostlink.lab.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "ghostlink.lab.linklab"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-m1"
    }

    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // lab build: installable sideload APK
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module") }
    }
}

dependencies {
    // Pure-JVM lab modules from the included build (spikes/physical_link_lab)
    implementation("ghostlink.lab:codecs:0.1.0-m1")
    implementation("ghostlink.lab:decoder:0.1.0-m1")
    implementation("ghostlink.lab:benchmark:0.1.0-m1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // QR decoders compared in M1 (all offline):
    implementation("io.github.zxing-cpp:android:2.3.0")             // Apache-2.0, native
    implementation("com.google.mlkit:barcode-scanning:17.3.0")       // bundled model, no download
}
