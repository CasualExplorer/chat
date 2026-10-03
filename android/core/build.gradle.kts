import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The protocol, conversation and markdown logic, with no Android dependency,
// so its tests run on the JVM.
plugins {
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
    // Android ships org.json; the JVM tests use the Maven Central build.
    compileOnly(libs.json)
    testImplementation(libs.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
