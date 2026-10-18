plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The bridge token (created by scripts/setup.sh) is baked into the debug build
// so you don't type 64 hex characters on the phone. Don't share the APK.
val carToken: String = File(System.getProperty("user.home"), ".claude-car/token")
    .takeIf { it.isFile }?.readText()?.trim().orEmpty()
val bridgeUrl: String = (findProperty("claudeCar.bridgeUrl") as String?) ?: "http://100.122.179.88:8788"

android {
    namespace = "com.theglobulus.claudecar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.theglobulus.claudecar"
        // remoteMessaging foreground services need API 34; the S25 runs 15+.
        minSdk = 34
        targetSdk = 36
        // Minutes since 1970, so every build installs over the last one (in-app updates need that).
        versionCode = (System.currentTimeMillis() / 60_000).toInt()
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BRIDGE_URL", "\"$bridgeUrl\"")
        buildConfigField("String", "DEFAULT_TOKEN", "\"$carToken\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    // Only for CarConnection (is the phone projecting?).
    implementation("androidx.car.app:app:1.7.0")
    // The car-facing audio app: a MediaBrowserService Android Auto lists with the media apps.
    implementation("androidx.media:media:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    // Browses the car app on a device or emulator, the way Android Auto does.
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    // Android's org.json is a stub in JVM unit tests.
    testImplementation("org.json:json:20260814")
}
