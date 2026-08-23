plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.ghostty.android"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 24

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // The pty support library. The renderer's own libraries are prebuilt with
    // Zig and picked up from jniLibs instead.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}

// Task to build native libraries for all ABIs
tasks.register<Exec>("buildNativeLibs") {
    description = "Build libghostty-vt native libraries for all Android ABIs"
    group = "build"

    workingDir = file("../..")

    // Use wrapper script that handles nix-shell environment
    commandLine("bash", "scripts/gradle-build-native.sh")

    // Configure inputs - track source files
    inputs.files(fileTree("../renderer/src") {
        include("**/*.zig")
        include("**/*.glsl")
    })
    inputs.files(fileTree("../../libghostty-vt") {
        include("**/*.zig")
        exclude("zig-cache/**")
        exclude("zig-out/**")
    })
    inputs.file("../renderer/build.zig")
    inputs.file("../../libghostty-vt/build.zig")

    // Configure outputs - track generated libraries
    outputs.dir("src/main/jniLibs/arm64-v8a")
    outputs.dir("src/main/jniLibs/armeabi-v7a")
    outputs.dir("src/main/jniLibs/x86_64")

    // Set environment variables
    environment("ANDROID_HOME", System.getenv("ANDROID_HOME") ?: "/home/tapan/Android/Sdk")
    environment("ANDROID_NDK_ROOT", System.getenv("ANDROID_NDK_ROOT") ?:
        "${System.getenv("ANDROID_HOME") ?: "/home/tapan/Android/Sdk"}/ndk/29.0.14206865")

    // For debug builds, only build for arm64-v8a to speed up iteration
    val isDebug = gradle.startParameter.taskNames.any {
        it.contains("Debug", ignoreCase = true) || it.contains("assembleDebug", ignoreCase = true)
    }
    if (isDebug) {
        environment("ANDROID_ABIS", "arm64-v8a")
        println("Building native libs for DEBUG: arm64-v8a only")
    } else {
        println("Building native libs for RELEASE: all ABIs")
    }
}

// Run native build before preBuild.
// The Zig toolchain may live outside the Gradle host (a WSL distro, a remote box), in
// which case the native libraries are built out-of-band by
// scripts/build-android-nonix.sh. -PskipNativeBuild=true makes Gradle consume whatever
// is already present in jniLibs.
tasks.named("preBuild") {
    if (!project.hasProperty("skipNativeBuild")) {
        dependsOn("buildNativeLibs")
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.ghostty"
            artifactId = "terminal-library"
            version = "0.8.1"

            afterEvaluate {
                from(components["release"])
            }
        }
    }

    repositories {
        maven {
            name = "local"
            url = uri("${project.rootDir}/../releases")
        }
    }
}
