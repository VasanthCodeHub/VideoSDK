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
    }

    // No buildTypes/optimization block: an AAR is never minified on its own. Shrinking is
    // the consuming app's decision, and `consumer-rules.pro` above is what protects the
    // SDK's reflective WebRTC entry points when that app does minify.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
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
