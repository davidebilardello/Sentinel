import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = providers.environmentVariable("SENTINEL_SIGNING_PROPERTIES").orNull
    ?.let { rootProject.file(it) } ?: rootProject.file("signing.properties")
val releaseSigning = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "it.bilar.sentinel"
    compileSdk = 36
    defaultConfig {
        applicationId = "it.bilar.sentinel"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "0.8.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    bundle { language { enableSplit = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    androidResources { noCompress += listOf("glb", "ktx", "filamat") }
    signingConfigs {
        if (signingPropertiesFile.isFile) create("sentinelRelease") {
            fun required(name: String) = requireNotNull(releaseSigning.getProperty(name)?.takeIf { it.isNotBlank() }) {
                "Missing $name in release signing properties"
            }
            storeFile = rootProject.file(required("storeFile"))
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingPropertiesFile.isFile) signingConfig = signingConfigs.getByName("sentinelRelease")
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.sceneview:sceneview:2.3.4")
    implementation("org.maplibre.gl:android-sdk:12.2.0")
    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
