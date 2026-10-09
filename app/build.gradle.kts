import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from keystore.properties (not committed); see docs/BUILDING.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.hardline"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.hardline"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Without a keystore the release build is signed with the debug key, so it still installs.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.md", "META-INF/DEPENDENCIES")
    }
    // The dependency list AGP embeds is encrypted for Google Play; nobody else can read it.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

base {
    archivesName.set("HardLine-${android.defaultConfig.versionName}")
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

// libusb and libuvc are not committed: fetch them before anything compiles.
val fetchNativeDeps by tasks.registering(Exec::class) {
    description = "Downloads libusb and libuvc into third_party/ and applies the patches."
    group = "build setup"
    val script = rootProject.file("scripts/fetch-deps.sh")
    inputs.file(script)
    inputs.dir(layout.projectDirectory.dir("src/main/cpp/patches"))
    outputs.files(
        rootProject.file("third_party/libusb-1.0.30/.fetched"),
        rootProject.file("third_party/libuvc-0.0.8/.fetched"),
    )
    commandLine("bash", script.absolutePath)
}
tasks.named("preBuild") { dependsOn(fetchNativeDeps) }
tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("generateJsonModel") }
    .configureEach { dependsOn(fetchNativeDeps) }

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.net)
    implementation(libs.android.mail)
    implementation(libs.android.activation)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
