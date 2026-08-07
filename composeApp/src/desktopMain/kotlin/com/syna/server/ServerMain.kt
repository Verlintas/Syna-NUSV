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

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if (args.contains("--ui") || args.contains("-ui")) {
        val config = parseArgs(args)
        serverUiMain(config)
        return
    }
    serverCliMain(args)
}

/** CLI 无头模式 */
private fun serverCliMain(args: Array<String>) {
    val config = parseArgs(args)
    println("Syna 私人聊天服务器 v0.3.0 启动中…（带界面模式: 加 --ui 参数）")
    val server = SynaServer(
        port = config.port,
        password = config.password,
        groupName = config.groupName,
        dataDir = Path.of(config.dataDir),
        historyLimit = config.historyLimit,
    )
    runBlocking {
        server.start()
        while (true) {
            kotlinx.coroutines.delay(86_400_000L)
        }
    }
}

/** GUI 界面模式 */
private fun serverUiMain(config: ServerConfig) {
    val controller = ServerController()
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Syna 私人聊天服务器",
            state = rememberWindowState(width = 680.dp, height = 760.dp),
        ) {
            ServerUiScreen(controller = controller, initialConfig = config)
        }
    }
}

data class ServerConfig(
    val port: Int = 45880,
    val password: String = "syna",
    val groupName: String = "Syna 私服",
    val dataDir: String = "./syna-server-data",
    val historyLimit: Int = 200,
)

private fun parseArgs(args: Array<String>): ServerConfig {
    var config = ServerConfig()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port", "-p" -> config = config.copy(port = args.getOrNull(i + 1)?.toIntOrNull() ?: config.port)
            "--password", "-w" -> config = config.copy(password = args.getOrNull(i + 1) ?: config.password)
            "--group", "-g" -> config = config.copy(groupName = args.getOrNull(i + 1) ?: config.groupName)
            "--data-dir", "-d" -> config = config.copy(dataDir = args.getOrNull(i + 1) ?: config.dataDir)
            "--history" -> config = config.copy(historyLimit = args.getOrNull(i + 1)?.toIntOrNull() ?: config.historyLimit)
            "--help", "-h" -> {
                printHelp()
                kotlin.system.exitProcess(0)
            }
        }
        i++
    }
    return config
}

private fun printHelp() {
    println(
        """
        Syna 私人聊天服务器

        用法: java -jar syna-server.jar [选项]
              java -jar syna-server.jar --ui    # 带图形界面模式

        选项:
          -p, --port <端口>        监听端口（默认 45880）
          -w, --password <密码>    连接密码（默认 syna，请务必修改）
          -g, --group <名称>       群名称（默认 "Syna 私服"）
          -d, --data-dir <路径>    数据目录（默认 ./syna-server-data，消息持久化于此）
              --history <条数>     历史消息条数上限（默认 200）
              --ui                 使用图形界面（配置、状态、成员、日志一目了然）

        内网穿透（公网访问）:
          服务器只需监听端口；用任意外网隧道工具把端口映射到公网即可，
          客户端输入穿透后的地址:端口 + 密码 加入群聊。
          frp:    frpc.ini → [synaserver] type=tcp local_port=45880 remote_port=45880
          ngrok:  ngrok tcp 45880
          Tailscale: 同一 Tailnet 内直接使用节点 IP:45880

        示例:
          java -jar syna-server.jar -p 45880 -w MySecret -g "朋友群"   # 命令行
          java -jar syna-server.jar --ui                              # 图形界面
        """.trimIndent(),
    )
}
