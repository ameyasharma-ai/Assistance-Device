import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.readingassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.readingassistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val geminiApiKey = readEnvProperty("GEMINI_API_KEY", "")
        val geminiModel = readEnvProperty("GEMINI_MODEL", "gemini-flash-lite-latest")

        buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "GEMINI_MODEL", "\"$geminiModel\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

fun readEnvProperty(key: String, defaultValue: String = ""): String {
    val sysEnv = System.getenv(key)
    if (!sysEnv.isNullOrBlank()) return sysEnv

    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        for (line in envFile.readLines()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("#") && trimmed.contains("=")) {
                val parts = trimmed.split("=", limit = 2)
                if (parts[0].trim() == key) {
                    val value = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                    if (value.isNotBlank()) return value
                }
            }
        }
    }

    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        val properties = Properties()
        localPropertiesFile.inputStream().use { properties.load(it) }
        val prop = properties.getProperty(key)
        if (!prop.isNullOrBlank()) return prop
    }

    return defaultValue
}

dependencies {
    implementation(project(":core-domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
