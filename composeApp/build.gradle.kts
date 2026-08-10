import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        val jvmShared by creating {
            dependsOn(commonMain.get())
        }
        val androidMain by getting {
            dependsOn(jvmShared)
        }
        val desktopMain by getting {
            dependsOn(jvmShared)
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.multiplatform.settings.test)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        // 测试稳定性：大堆（200MB 文件测试）、每类独立 JVM、串行避免多播/端口竞争
        tasks.withType<Test>().configureEach {
            maxHeapSize = "2g"
            forkEvery = 1
            maxParallelForks = 1
            testLogging {
                showStandardStreams = true
                events("failed")
            }
        }

        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(libs.material.icons.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.multiplatform.settings)
            implementation(libs.multiplatform.settings.noarg)
        }
        androidMain.dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            implementation("androidx.biometric:biometric:1.1.0")
            implementation("androidx.lifecycle:lifecycle-process:2.8.7")
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.syna"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = "27.0.12077973"
    buildFeatures {
        buildConfig = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/androidMain/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    defaultConfig {
        applicationId = "com.syna"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        externalNativeBuild {
            cmake {
                // 全 ABI 构建（含模拟器 x86 以支持测试环境）
                abiFilters("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            }
        }
        versionCode = 1
        versionName = "0.1.0"
        // 官方签名指纹（SHA-256 十六进制）：release 签名 keystore 固定，
        // 运行时校验 APK 签名一致性，防止重打包/重新签名绕过护盾
        buildConfigField("String", "SYNA_SIGNATURE_HASH", "\"745317298590e69ddd48c94902c24209918fe1c19e104bb3ce1ca05263c2c4d7\"")
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // so 解压为真实文件（否则 dli_fname 指向 APK 内 zip 路径，
            // native 代码段完整性校验无法读取磁盘文件比对）
            useLegacyPackaging = true
        }
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file(localProps.getProperty("syna.keystore.file", ""))
            storePassword = localProps.getProperty("syna.keystore.password", "")
            keyAlias = localProps.getProperty("syna.key.alias", "")
            keyPassword = localProps.getProperty("syna.key.password", "")
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "com.syna.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Syna"
            packageVersion = "1.0.0"
            macOS {
                iconFile.set(rootProject.file("assets/icons/Syna.icns"))
            }
            windows {
                iconFile.set(rootProject.file("assets/icons/Syna.ico"))
            }
            linux {
                iconFile.set(rootProject.file("assets/icons/Syna-512.png"))
            }
        }
    }
}

// ===== Syna Server（无头服务器，Win/macOS/Linux 通用，与 GUI 共用同一 JVM 编译）=====
val serverFatJar by tasks.registering(Jar::class) {
    group = "server"
    description = "打包 Syna 服务器为可执行 fat jar"
    archiveFileName.set("syna-server.jar")
    destinationDirectory.set(layout.buildDirectory.dir("server"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.syna.server.ServerMainKt"
        attributes["Implementation-Version"] = "0.7.4"
    }
    from(kotlin.targets.getByName("desktop").compilations.getByName("main").output.allOutputs)
    from(configurations.getByName("desktopRuntimeClasspath").map { file ->
        if (file.isDirectory) file else zipTree(file)
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    }
}

val runServer by tasks.registering(JavaExec::class) {
    group = "server"
    description = "本地运行 Syna 服务器"
    classpath = kotlin.targets.getByName("desktop").compilations.getByName("main").output.allOutputs +
        kotlin.targets.getByName("desktop").compilations.getByName("main").runtimeDependencyFiles
    mainClass.set("com.syna.server.ServerMainKt")
}

// ===== 服务器启动器独立应用（jpackage，双击即用）=====
val launcherAppImage by tasks.registering(Exec::class) {
    group = "server"
    description = "用 jpackage 生成服务器启动器独立应用（macOS .app / Windows 目录 / Linux 目录）"
    dependsOn(serverFatJar)
    val inputDir = layout.buildDirectory.dir("server/launcher-input")
    val destDir = layout.buildDirectory.dir("launcher")
    val osName = System.getProperty("os.name").lowercase()
    val icon = when {
        osName.contains("mac") -> rootProject.file("assets/icons/Syna.icns")
        osName.contains("win") -> rootProject.file("assets/icons/Syna.ico")
        else -> rootProject.file("assets/icons/Syna-512.png")
    }
    doFirst {
        val input = inputDir.get().asFile
        input.mkdirs()
        file(serverFatJar.get().archiveFile).copyTo(input.resolve("syna-server.jar"), overwrite = true)
        val dest = destDir.get().asFile
        // jpackage 不覆盖已存在目录，先清理上次产物
        delete(dest.resolve("SynaServer.app"))
        delete(dest.resolve("SynaServer"))
        dest.mkdirs()
    }
    commandLine(
        "jpackage",
        "--type", "app-image",
        "--input", inputDir.get().asFile.absolutePath,
        "--main-jar", "syna-server.jar",
        "--name", "SynaServer",
        "--icon", icon.absolutePath,
        "--app-version", "1.0.0",
        "--dest", destDir.get().asFile.absolutePath,
    )
}
