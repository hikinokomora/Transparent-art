import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing is optional: without keystore.properties the release build stays unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Every ABI gets its own APK and version code (versionCode * 10 + n), as F-Droid requires.
// A single one can be built with -Pabi=arm64-v8a.
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)
val abis = providers.gradleProperty("abi").orNull?.let { listOf(it) } ?: abiVersionCodes.keys.toList()

android {
    namespace = "io.github.transparentart"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.transparentart"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    // One APK per phone CPU type: ONNX Runtime ships a large native library for each.
    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }

    packaging {
        // Compress native libraries inside the APK: a much smaller download.
        jniLibs.useLegacyPackaging = true
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.orNull ?: 0
            output.versionCode.set(base * 10 + (abiVersionCodes[abi] ?: 0))
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}
