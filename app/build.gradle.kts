import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val releaseKeystoreFile = rootProject.file("keystore.properties")
val releaseKeystore = Properties().apply {
    if (releaseKeystoreFile.isFile) {
        releaseKeystoreFile.inputStream().use(::load)
    }
}
val releaseVersionCode = providers.gradleProperty("versionCodeOverride").orNull?.toIntOrNull() ?: 5

fun releaseProperty(name: String): String =
    releaseKeystore.getProperty(name) ?: error("Missing $name in ${releaseKeystoreFile.path}")

room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.putnamworkbook.offline"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.putnamworkbook.offline"
        minSdk = 29
        targetSdk = 36
        versionCode = releaseVersionCode
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystoreFile.isFile) {
            create("release") {
                storeFile = rootProject.file(releaseProperty("storeFile"))
                storePassword = releaseProperty("storePassword")
                keyAlias = releaseProperty("keyAlias")
                keyPassword = releaseProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        animationsDisabled = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.ink.authoring)
    implementation(libs.androidx.ink.brush)
    implementation(libs.androidx.ink.nativeloader)
    implementation(libs.androidx.ink.rendering)
    implementation(libs.androidx.ink.strokes)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.navigation.compose)
    ksp(libs.androidx.room.compiler)

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    testImplementation(libs.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
