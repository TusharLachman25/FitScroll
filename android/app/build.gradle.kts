import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Signing material lives outside the repository. When keystore.properties is
// absent - a fresh clone, or CI - release builds fall back to the debug key so
// `assembleRelease` still produces something installable.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

// Where a sideloaded build looks to find out whether it has been withdrawn.
//
// Overridable so the file can be moved without a code change, and blankable so
// a Play build carries no check at all: `-Pfitscroll.statusUrl=`. A build from
// the store has a supported upgrade path already and no business calling home.
val releaseStatusUrl = (project.findProperty("fitscroll.statusUrl") as String?)
    ?: "https://raw.githubusercontent.com/TusharLachman25/FitScroll/main/release/status.json"

android {
    namespace = "com.fitscroll.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fitscroll.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "RELEASE_STATUS_URL", "\"$releaseStatusUrl\"")

        // x86 and x86_64 only ever run on emulators. Excluding them here rather
        // than only in `splits` matters, because the universal APK packages
        // whatever survives this filter — without it the "safe fallback" build
        // is 82MB, nearly half of it code no phone can execute.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
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
            // Android identifies an app by package name *and* signing certificate, so
            // the key chosen here decides whether a future Play release can update
            // the copies handed out by hand or has to replace them - and replacing
            // means uninstalling, which takes the user's banked minutes with it.
            //
            // Falls back to the debug key when there is no keystore.properties, so a
            // clone still builds something installable. Anything actually given to
            // another person should be built with the real key.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    // ML Kit ships native inference libraries for four ABIs, and two of them
    // (x86, x86_64) only ever run on emulators. Bundling all four made the
    // sideloaded APK 82MB, more than half of it dead weight on any real phone.
    //
    // Splitting produces a small per-ABI APK plus a universal one as a
    // fallback for anyone unsure what their device is.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.mlkit.pose.detection)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
