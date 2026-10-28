plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.clearcap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clearcap"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Real phones are arm; dropping x86 keeps the APK small (sherpa-onnx ships large native libraries).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Fixed signing key: a new random debug key on every CI run would stop Android installing an update
    // over the previous build ("App not installed as package conflicts with an existing package").
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06") // encrypts the Gemini key
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // sherpa-onnx: streaming Zipformer (live), Parakeet / Whisper (accurate), Silero VAD.
    // Prebuilt AAR from https://github.com/k2-fsa/sherpa-onnx/releases (v1.13.8, arm only).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.26.2") // unpack the .tar.bz2 model downloads

    testImplementation("junit:junit:4.13.2")
}
