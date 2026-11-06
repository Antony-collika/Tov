import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val signingKeyFile = rootProject.file("build/tov-development.p12")
if (!signingKeyFile.exists()) {
    val encodedKey = rootProject.file("signing/tov-development.p12.base64").readText()
    signingKeyFile.parentFile.mkdirs()
    signingKeyFile.writeBytes(Base64.getDecoder().decode(encodedKey))
}

android {
    namespace = "com.example.byokchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.byokchat"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        create("temporaryDevelopment") {
            storeFile = signingKeyFile
            storePassword = "tov-dev-temp-" + "2026"
            keyAlias = "tov-development"
            keyPassword = "tov-dev-temp-" + "2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("temporaryDevelopment")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.material)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)

    implementation(libs.markwon.core)
    implementation(libs.markwon.strikethrough)
    implementation(libs.markwon.tables)
    implementation(libs.markwon.tasklist)
    implementation(libs.markwon.linkify)
}
