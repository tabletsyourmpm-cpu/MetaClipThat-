plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metaclipthat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metaclipthat"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        getByName("debug") {
            // Debug build only; no signing config needed.
        }
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // Shizuku: shell-level access (used to read the Quest refresh rate).
    // The provider artifact auto-registers init + merges the Shizuku permission.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

// The Freckle Face font (OFL-licensed, matches the app logo) is fetched at
// build time from the google/fonts repo instead of being committed as a
// binary. Runs before resources are merged so R.font.freckle_face resolves.
tasks.register("downloadFreckleFaceFont") {
    val dest = file("src/main/res/font/freckle_face.ttf")
    outputs.file(dest)
    doLast {
        if (!dest.exists()) {
            dest.parentFile.mkdirs()
            println("Downloading Freckle Face font...")
            java.net.URI(
                "https://github.com/google/fonts/raw/main/ofl/freckleface/FreckleFace-Regular.ttf"
            ).toURL().openStream().use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            println("Freckle Face font downloaded.")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadFreckleFaceFont")
}
