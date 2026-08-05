version = "1.0.0"

@Suppress("UnstableApiUsage")
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.meshcall.sdk"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    // Public deps — surface to consumers of the AAR automatically.
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // Mobile-WebRTC-native media transport. Becomes implementation in the published POM
    // so consumers get it transitively without declaring it themselves.
    api(libs.google.webrtc)
    api(libs.socketio.client)

    // Lifecycle for the foreground call service.
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.junit)
}
