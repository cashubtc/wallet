// Packages an already-built CDK Kotlin binding (generated sources + jniLibs)
// as an AAR with the same Gradle/AGP/Kotlin versions as the app. CDK's own
// bindings/kotlin Gradle project pins an older AGP that does not run on current
// toolchains, so it is not used for local builds.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "local-cdk-aar"
