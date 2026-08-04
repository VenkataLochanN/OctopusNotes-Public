plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

kapt {
    correctErrorTypes = true
}

android {
    namespace = "com.lochan.octopusnotes"
    compileSdk = 36

    flavorDimensions += "app"
    productFlavors {
        create("prod") {
            dimension = "app"
        }
        create("tst") {
            dimension = "app"
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            buildConfigField("String", "APP_DISPLAY_NAME", "\"Test Octo\"")
            buildConfigField("String", "DB_NAME", "\"notes_database_test\"")
        }
    }

    defaultConfig {
        applicationId = "com.lochan.octopusnotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "2026.8.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "APP_DISPLAY_NAME", "\"Octopus Notes\"")
        buildConfigField("String", "DB_NAME", "\"notes_database\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
        viewBinding = true
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // PDF parsing/manipulation (insert/delete/duplicate pages, templates). Rendering is native PdfRenderer.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    val room_version = "2.8.4"
    implementation("androidx.room:room-runtime:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    implementation("androidx.room:room-ktx:$room_version")

    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
}