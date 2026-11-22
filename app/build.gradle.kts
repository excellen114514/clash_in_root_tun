import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.roottun.clash"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.roottun.clash"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.1"
    }

    // 每个 APK 只携带一个 ABI 对应的 mihomo 内核，避免 universal APK 同时打包两份内核。
    flavorDimensions += "abi"

    productFlavors {
        create("x86") {
            dimension = "abi"
            ndk {
                abiFilters += "x86_64"
            }
        }
        create("arm64") {
            dimension = "abi"
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    signingConfigs {
        // Debug builds are signed with the debug key; the release build reuses it so the
        // acceptance APK can be installed directly. Replace for production distribution.
        getByName("debug")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        // mihomo is shipped as an executable ELF named libmihomo.so. Legacy packaging keeps it
        // as an extracted, readable file inside the APK's nativeLibraryDir, which the app then
        // copies to its private directory and executes through `su`.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/licenses/**",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("org.yaml:snakeyaml:2.2")
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Root shell (libsu core): the only privileged command channel of this app.
    // JitPack coordinates: com.github.<owner>.<repo>:<module>:<version>
    implementation("com.github.topjohnwu.libsu:core:5.2.2")

    // MaterialKolor's Material 3 color engine (SPEC_2025 dynamic schemes).
    implementation("com.materialkolor:material-color-utilities:4.0.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
