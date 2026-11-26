// Versions track ../../gradle/libs.versions.toml (agp, coroutines). AGP 9 compiles
// Kotlin itself, so no Kotlin plugin is applied.
plugins {
    id("com.android.library") version "9.3.0"
    `maven-publish`
}

val aarVersion = providers.gradleProperty("aarVersion").get()

android {
    namespace = "org.cashudevkit"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("net.java.dev.jna:jna:5.17.0@aar") { isTransitive = false }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "org.cashudevkit"
                artifactId = "cdk-android"
                version = aarVersion
            }
        }
    }
}
