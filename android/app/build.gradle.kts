plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.room)
}

android {
    namespace = "com.casualexplorer.chat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.casualexplorer.chat"
        minSdk = 26
        // Google Play requires API 36 for new apps and updates from 2026-08-31.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Built-in Kotlin takes its jvmTarget from targetCompatibility.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric and the screenshot tests need the app's resources.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric's FileDescriptor interceptor reaches into
            // jdk.internal.access, which Java 21 doesn't export by default.
            test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // Full stack traces in the CI log, where the reports can't be read.
            test.testLogging {
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                showStackTraces = true
            }
        }
    }

    lint {
        abortOnError = true
    }
}

room {
    // One schema file per database version, checked in, for migrations.
    // CI commits the file when it changes.
    schemaDirectory("$projectDir/schemas")
}

composeCompiler {
    // core/ is compiled without the Compose compiler; this tells it which of
    // its classes are immutable, so composables taking them can skip.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf"))
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    // Only to read the API keys the app stored with it before (LegacySecretsMigration).
    implementation(libs.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    ksp(libs.kotlin.metadata)

    // createComposeRule's activity, for the debug-only screenshot tests.
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
