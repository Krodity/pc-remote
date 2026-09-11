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
