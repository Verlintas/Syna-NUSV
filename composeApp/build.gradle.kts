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
        }
        desktopMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

android {
    namespace = "com.syna"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.syna"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
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
        attributes["Implementation-Version"] = "0.2.0"
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
    classpath = kotlin.targets.getByName("desktop").compilations.getByName("main").runtimeDependencyFiles
    mainClass.set("com.syna.server.ServerMainKt")
}
