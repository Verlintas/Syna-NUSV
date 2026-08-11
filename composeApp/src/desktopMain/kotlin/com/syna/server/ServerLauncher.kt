/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.syna.server

import com.syna.net.synaJson
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
data class LauncherConfig(
    var port: Int = 45880,
    var password: String = "syna",
    var groupName: String = "Syna 私服",
    var dataDir: String = Path.of(System.getProperty("user.home") ?: ".", ".syna-server-data").toString(),
    var autoRestart: Boolean = false,
    var autoStart: Boolean = false,
)

/** 启动器配置持久化：~/.syna-server/launcher.json（跨平台统一位置） */
class LauncherConfigStore(
    private val dir: Path = Path.of(System.getProperty("user.home") ?: ".", ".syna-server"),
) {
    private val file: Path = dir.resolve("launcher.json")

    fun load(): LauncherConfig {
        return try {
            if (Files.exists(file)) {
                synaJson.decodeFromString(LauncherConfig.serializer(), Files.readString(file)).also { cfg ->
                    // 旧配置中的相对数据目录归一化为绝对路径（.app 启动时工作目录不定）
                    if (!Path.of(cfg.dataDir).isAbsolute) {
                        cfg.dataDir = Path.of(System.getProperty("user.home") ?: ".", ".syna-server-data").toString()
                    }
                }
            } else {
                LauncherConfig()
            }
        } catch (e: Exception) {
            println("[SynaLauncher] 配置读取失败，使用默认值: ${e.message}")
            LauncherConfig()
        }
    }

    fun save(config: LauncherConfig) {
        try {
            Files.createDirectories(dir)
            Files.writeString(file, synaJson.encodeToString(LauncherConfig.serializer(), config))
        } catch (e: Exception) {
            println("[SynaLauncher] 配置保存失败: ${e.message}")
        }
    }

    companion object {
        fun defaultConfigPath(): String =
            Path.of(System.getProperty("user.home") ?: ".", ".syna-server", "launcher.json").toString()
    }
}

/** 开机自启管理（用户级，无需管理员权限） */
object AutoStartManager {

    private val os = System.getProperty("os.name").lowercase()

    /** 生成开机自启所需的命令行（CLI 模式，无需 GUI） */
    private fun commandLine(jarPath: String, config: LauncherConfig): List<String> = listOf(
        "\"${Path.of(System.getProperty("java.home"), "bin", "java").toString()}\"",
        "-jar",
        "\"$jarPath\"",
        "-p", config.port.toString(),
        "-w", config.password,
        "-g", config.groupName,
        "-d", config.dataDir,
    )

    /** 定位当前运行的 jar 路径（用于自启命令） */
    fun locateJar(): String? {
        return try {
            val url = AutoStartManager::class.java.protectionDomain?.codeSource?.location
            val path = url?.toURI()?.path ?: return null
            if (path.endsWith(".jar")) path else null
        } catch (e: Exception) {
            null
        }
    }

    fun isSupported(): Boolean =
        os.contains("mac") || os.contains("win") || os.contains("linux")

    fun currentState(config: LauncherConfig): Boolean {
        val jar = locateJar() ?: return false
        return when {
            os.contains("mac") -> {
                val plist = launchAgentFile()
                Files.exists(plist) && Files.readString(plist).contains("syna-server.jar")
            }
            os.contains("win") -> {
                val bat = startupBatFile()
                Files.exists(bat)
            }
            else -> {
                val desktop = xdgAutostartFile()
                Files.exists(desktop)
            }
        }
    }

    fun enable(config: LauncherConfig) {
        val jar = locateJar() ?: run {
            println("[SynaLauncher] 无法定位 jar，跳过开机自启")
            return
        }
        val cmd = commandLine(jar, config).joinToString(" ")
        when {
            os.contains("mac") -> {
                val plist = launchAgentFile()
                Files.createDirectories(plist.parent)
                Files.writeString(
                    plist,
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.syna.server</string>
    <key>ProgramArguments</key>
    <array>
        <string>${Path.of(System.getProperty("java.home"), "bin", "java").toString()}</string>
        <string>-jar</string>
        <string>$jar</string>
        <string>-p</string>
        <string>${config.port}</string>
        <string>-w</string>
        <string>${config.password}</string>
        <string>-g</string>
        <string>${config.groupName}</string>
        <string>-d</string>
        <string>${config.dataDir}</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
""",
                )
            }
            os.contains("win") -> {
                val bat = startupBatFile()
                Files.createDirectories(bat.parent)
                Files.writeString(bat, "@echo off\n$cmd\n")
            }
            else -> {
                val desktop = xdgAutostartFile()
                Files.createDirectories(desktop.parent)
                Files.writeString(
                    desktop,
                    """[Desktop Entry]
Type=Application
Name=Syna Server
Exec=${cmd.replace(" ", " ")}
X-GNOME-Autostart-enabled=true
""",
                )
            }
        }
        println("[SynaLauncher] 开机自启已启用")
    }

    fun disable() {
        when {
            os.contains("mac") -> Files.deleteIfExists(launchAgentFile())
            os.contains("win") -> Files.deleteIfExists(startupBatFile())
            else -> Files.deleteIfExists(xdgAutostartFile())
        }
        println("[SynaLauncher] 开机自启已关闭")
    }

    private fun launchAgentFile(): Path =
        Path.of(System.getProperty("user.home") ?: ".", "Library", "LaunchAgents", "com.syna.server.plist")

    private fun startupBatFile(): Path =
        Path.of(
            System.getenv("APPDATA") ?: (System.getProperty("user.home") ?: "."),
            "Microsoft", "Windows", "Start Menu", "Programs", "Startup", "SynaServer.bat",
        )

    private fun xdgAutostartFile(): Path =
        Path.of(System.getProperty("user.home") ?: ".", ".config", "autostart", "syna-server.desktop")
}
