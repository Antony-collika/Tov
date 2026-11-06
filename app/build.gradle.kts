plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val stableKeystorePath = providers.environmentVariable("TOV_SIGNING_KEYSTORE").orNull
val stableKeyAlias = providers.environmentVariable("TOV_SIGNING_KEY_ALIAS").orNull
val stableStorePassword = providers.environmentVariable("TOV_SIGNING_STORE_PASSWORD").orNull
val stableKeyPassword = providers.environmentVariable("TOV_SIGNING_KEY_PASSWORD").orNull
val hasStableSigning = listOf(
    stableKeystorePath,
    stableKeyAlias,
    stableStorePassword,
    stableKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.example.byokchat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.byokchat"
        minSdk = 26
        targetSdk = 35
        // Keep the application identity/version stable for development installs.
        // Do not auto-increment these values in CI.
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stableDevelopment") {
                storeFile = file(stableKeystorePath!!)
                storePassword = stableStorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
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

            // CI uses a persistent keystore supplied through GitHub Actions secrets.
            // Local builds fall back to the machine's Android debug key.
            signingConfig = if (hasStableSigning) {
                signingConfigs.getByName("stableDevelopment")
            } else {
                signingConfigs.getByName("debug")
            }
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
