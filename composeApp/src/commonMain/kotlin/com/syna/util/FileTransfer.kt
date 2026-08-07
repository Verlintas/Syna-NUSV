package com.syna.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 保存接收到的文件，返回本地路径 */
expect fun saveReceivedFile(fileName: String, bytes: ByteArray): String

/** 读取本地文件字节（用于图片预览等） */
expect fun readFileBytes(path: String): ByteArray

/** 文件选择按钮（Android 系统选择器 / 桌面 AWT 对话框） */
@Composable
expect fun FilePickerButton(
    onFilePicked: (name: String, bytes: ByteArray) -> Unit,
    modifier: Modifier = Modifier,
)
