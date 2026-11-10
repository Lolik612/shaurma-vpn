plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shaurma.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shaurma.vpn"
        minSdk = 24
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0"

        buildConfigField("String", "API_URL", "\"${System.getenv("API_URL") ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${System.getenv("GOOGLE_WEB_CLIENT_ID") ?: ""}\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Постоянный ключ подписи: каждая новая сборка ставится поверх старой без удаления приложения
    signingConfigs {
        create("shaurma") {
            storeFile = file("shaurma.jks")
            storePassword = "shaurma123"
            keyAlias = "shaurma"
            keyPassword = "shaurma123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shaurma")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true // sslocal лежит как libsslocal.so и должен извлекаться на диск
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
