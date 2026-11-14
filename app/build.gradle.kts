import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "engineer.cookie.wordswithlearners"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "engineer.cookie.wordswithlearners"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/wwl-jniLibs"))
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val gameDir = (findProperty("gameDir") as String?)
    ?: System.getenv("GAME_DIR")
    ?: rootProject.file("../words-with-learners-game").absolutePath

val generatedJniDir = layout.buildDirectory.dir("generated/wwl-jniLibs")

val buildGoServer = tasks.register<Exec>("buildGoServer") {
    group = "build"
    description = "Cross-compiles the Words with Learners Go server for Android."
    workingDir = rootProject.projectDir
    commandLine("bash", "scripts/build-server.sh")
    environment("GAME_DIR", gameDir)
    environment("OUT_DIR", generatedJniDir.get().asFile.absolutePath)
    outputs.dir(generatedJniDir)
    outputs.upToDateWhen { false }
}

tasks.named("preBuild") {
    dependsOn(buildGoServer)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
