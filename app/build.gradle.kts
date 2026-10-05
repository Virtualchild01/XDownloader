plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.xdownloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.xdownloader"
        minSdk = 24
        targetSdk = 34
        versionCode = (project.findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = "1.0.${(project.findProperty("versionCode") as? String) ?: "1"}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
}

configurations.all {
    resolutionStrategy {
        force("com.google.code.findbugs:jsr305:3.0.2")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Asynchronous network operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTTP client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Image loading for thumbnails and photos
    implementation("com.github.bumptech.glide:glide:4.16.0")

    // Pure Java H.264 / MP4 frame extractor (100% device & OEM independent)
    implementation("org.jcodec:jcodec:0.2.5")
    implementation("org.jcodec:jcodec-android:0.2.5")
}
