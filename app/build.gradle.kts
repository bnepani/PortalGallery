import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing credentials live outside the repo (keystore.properties is gitignored).
// If the file is absent the release build still configures, just unsigned — so a fresh
// clone is never blocked, it simply cannot produce an installable release APK.
val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasSigning = keystoreProperties.getProperty("storeFile") != null

// Personal configuration, kept out of the repo. local.properties is gitignored by
// convention and already holds the SDK path.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultAlbumUrl: String = localProperties.getProperty("portalgallery.albumUrl") ?: ""

android {
    namespace = "com.example.portalgallery"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.portalgallery"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Baked-in default so a fresh install works with no setup. Override per device
        // without rebuilding:
        //   adb shell am start -n com.example.portalgallery/.ui.slideshow.SlideshowActivity \
        //     -e album_url "https://photos.app.goo.gl/..."
        // Read from local.properties (gitignored) rather than hard-coded here. A share
        // link is effectively a capability: anyone holding it can view the album, so it
        // must never be committed. Empty by default, which is the correct behaviour for
        // anyone cloning this repo — they set their own.
        //
        //   local.properties:  portalgallery.albumUrl=https://photos.app.goo.gl/XXXX
        //
        // Overridable per device at runtime over adb regardless; see README.
        buildConfigField("String", "DEFAULT_ALBUM_URL", "\"$defaultAlbumUrl\"")

        // (HTTP_LOGGING removed — it was added for the OkHttp logging interceptor that
        // went away with the Photos API client, and nothing referenced it.)
    }

    buildFeatures {
        viewBinding = true
        // AGP 8.0+ defaults this to false, so BuildConfig is not generated at all.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Deliberately NOT applicationIdSuffix: the package name is baked into
            // deploy-portal.sh, verify.sh and every adb command in the README.
            isMinifyEnabled = false
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // A frame that ships a crash is worse than one that ships a warning.
        disable += setOf("GoogleAppIndexingWarning", "UnusedResources")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    // Required: both themes in themes.xml parent Theme.MaterialComponents.*
    implementation("com.google.android.material:material:1.11.0")

    // Dropped with the OAuth stack and album picker:
    //   retrofit, converter-gson, logging-interceptor  (Photos Library API client)
    //   recyclerview, cardview, constraintlayout       (album picker layouts only)
    //   lifecycle-viewmodel-ktx                        (never used — no ViewModels)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Presence detection. CameraX over raw Camera2 for the lifecycle handling and
    // ImageAnalysis backpressure alone.
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")

    // BUNDLED face detection, not play-services-mlkit-face-detection: Portal is a
    // non-GMS device, so the Play-Services-backed variant would never initialise.
    // Costs roughly 20MB of APK for the on-device model.
    implementation("com.google.mlkit:face-detection:16.1.6")

    testImplementation("junit:junit:4.13.2")
}
