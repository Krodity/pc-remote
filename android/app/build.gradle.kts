plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "uk.krodity.pcremote"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "uk.krodity.pcremote"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        base.archivesName = "pc-remote-$versionName"
    }

    signingConfigs {
        // Pinned rather than left to AGP's default.
        //
        // AGP resolves the debug keystore relative to the Android config
        // directory, and that moved to the XDG location -- so the same source
        // tree started producing APKs signed with a *different* key than the
        // ones already on the phones, and an update failed with
        // INSTALL_FAILED_UPDATE_INCOMPATIBLE on whichever device was last
        // installed from the other side of the move. Naming the file makes the
        // signature a property of the project instead of the environment.
        getByName("debug") {
            storeFile = File(System.getProperty("user.home"), ".config/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // Debug-signed for sideloading; this is a personal tailnet app,
            // not a store build.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.gson)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
