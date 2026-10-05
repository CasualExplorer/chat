import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The protocol, conversation and markdown logic, with no Android dependency,
// so its tests run on the JVM.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Providers take an OkHttpClient, so it is part of this module's API.
    api(libs.okhttp)
    // Android ships org.json; the JVM tests use the Maven Central build.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Full stack traces in the CI log, where the reports can't be read.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}
