import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Server address. Default 10.0.2.2 = the host machine as seen from the Android emulator.
// For a physical device, add `grpc.host=<your Mac's LAN IP>` to local.properties.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val grpcHost: String = localProps.getProperty("grpc.host", "10.0.2.2")
val grpcPort: String = localProps.getProperty("grpc.port", "50051")

android {
    namespace = "com.ridenow.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ridenow.android"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "GRPC_HOST", "\"$grpcHost\"")
        buildConfigField("int", "GRPC_PORT", grpcPort)
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // gRPC: generated stubs + messages come from the shared :proto module
    implementation(project(":proto"))
    implementation(libs.grpc.okhttp)  // HTTP/2 transport for Android (lighter than Netty)
    implementation(libs.grpc.android) // AndroidChannelBuilder: reacts to network changes
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}