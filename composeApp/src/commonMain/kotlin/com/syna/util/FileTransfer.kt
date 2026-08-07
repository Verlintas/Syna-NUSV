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
