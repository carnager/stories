import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: ~/.local/android/release-keys/stories.properties (or the
// file named by STORIES_ANDROID_SIGNING_PROPERTIES), or the same keys as
// environment variables. Without them, release builds use the debug key.
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = file(
    System.getenv("STORIES_ANDROID_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/android/release-keys/stories.properties"
)
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "io.github.carnager.stories"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.carnager.stories"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    signingConfigs {
        create("release") {
            signingValue("STORIES_ANDROID_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("STORIES_ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("STORIES_ANDROID_KEY_ALIAS")
            keyPassword = signingValue("STORIES_ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 drops unused code, which is most of Compose's icon set.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("STORIES_ANDROID_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    // Only the handful of icons used survive R8 in release builds.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.media3:media3-exoplayer:1.10.0")
    implementation("androidx.media3:media3-session:1.10.0")
    // The player's colours, taken from the cover.
    implementation("androidx.palette:palette:1.0.0")
    testImplementation("junit:junit:4.13.2")
}
