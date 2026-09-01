import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.sleepwell.provisioning"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.sleepwell.provisioning"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        buildConfigField(
            "String",
            "WORKER_BASE_URL",
            buildConfigString("https://odd-river-673a.srect2017.workers.dev"),
        )
        buildConfigField(
            "String",
            "WORKER_API_TOKEN",
            buildConfigString(localProperties.getProperty("APP_API_TOKEN", "")),
        )
        buildConfigField("String", "GITHUB_ACTIONS_OWNER", buildConfigString("sRect"))
        buildConfigField("String", "GITHUB_ACTIONS_REPOSITORY", buildConfigString("esp32"))
        buildConfigField(
            "String",
            "GITHUB_ACTIONS_WORKFLOW",
            buildConfigString("send-esp32-message.yml"),
        )
        buildConfigField(
            "String",
            "GITHUB_ACTIONS_REF",
            buildConfigString(localProperties.getProperty("GITHUB_ACTIONS_REF", "feature/initial-project")),
        )
        buildConfigField(
            "String",
            "GITHUB_ACTIONS_TOKEN",
            buildConfigString(localProperties.getProperty("GITHUB_ACTIONS_TOKEN", "")),
        )
        buildConfigField("String", "DEVICE_ID", buildConfigString("7CE8B1B1FC9C"))

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.compose.ui:ui:1.11.4")
    implementation("androidx.compose.ui:ui-tooling-preview:1.11.4")
    implementation("androidx.compose.foundation:foundation:1.11.4")
    implementation("androidx.compose.material3:material3:1.4.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.11.4")

    testImplementation("junit:junit:4.13.2")
}
