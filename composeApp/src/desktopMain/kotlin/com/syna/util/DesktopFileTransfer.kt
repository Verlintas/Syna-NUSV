package com.syna.util

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Paths

actual fun saveReceivedFile(fileName: String, bytes: ByteArray): String {
    val dir = Paths.get(System.getProperty("user.home") ?: ".", "Downloads", "Syna")
    Files.createDirectories(dir)
    val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    val target = dir.resolve(safeName)
    Files.write(target, bytes)
    return target.toString()
}

actual fun readFileBytes(path: String): ByteArray = Files.readAllBytes(Paths.get(path))

@Composable
actual fun FilePickerButton(
    onFilePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = {
            Thread {
                val dialog = FileDialog(null as Frame?, "选择文件", FileDialog.LOAD)
                dialog.isVisible = true
                val dir = dialog.directory
                val file = dialog.file
                if (dir != null && file != null) {
                    try {
                        val f = java.io.File(dir, file)
                        onFilePicked(f.name, f.readBytes())
                    } catch (e: Exception) {
                        println("[Syna:File] 读取失败: ${e.message}")
                    }
                }
            }.start()
        },
        modifier = modifier,
    ) {
        Text("📎")
    }
}
