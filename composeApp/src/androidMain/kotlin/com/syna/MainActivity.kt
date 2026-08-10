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
package com.syna

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.syna.shield.AndroidShieldEngine

class MainActivity : FragmentActivity() {

    companion object {
        // 固定小 requestCode（平台要求 < 0x10000）：
        // 不使用 androidx ActivityResultRegistry——其生成的 requestCode 恒 ≥ 65536，
        // 会触发 platform 的 "Can only use lower 16 bits for requestCode" 崩溃。
        const val RC_PICK_IMAGE = 0x1001
        const val RC_PICK_FILE = 0x1002

        @Volatile
        private var sCurrent: MainActivity? = null

        @Volatile
        private var pendingImagePick: ((android.net.Uri?) -> Unit)? = null

        @Volatile
        private var pendingFilePick: ((android.net.Uri?) -> Unit)? = null

        fun launchImagePicker(onResult: (android.net.Uri?) -> Unit) {
            launchPicker(RC_PICK_IMAGE, "image/*", onResult)
        }

        fun launchFilePicker(onResult: (android.net.Uri?) -> Unit) {
            launchPicker(RC_PICK_FILE, "*/*", onResult)
        }

        private fun launchPicker(rc: Int, mime: String, onResult: (android.net.Uri?) -> Unit) {
            val activity = sCurrent
            if (activity == null) {
                onResult(null)
                return
            }
            when (rc) {
                RC_PICK_IMAGE -> pendingImagePick = onResult
                RC_PICK_FILE -> pendingFilePick = onResult
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).apply {
                type = mime
                addCategory(android.content.Intent.CATEGORY_OPENABLE)
            }
            try {
                activity.startActivityForResult(intent, rc)
            } catch (e: Throwable) {
                when (rc) {
                    RC_PICK_IMAGE -> pendingImagePick = null
                    RC_PICK_FILE -> pendingFilePick = null
                }
                onResult(null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 注册给 Mirtazapine Shield（生物识别宿主与防截屏）
        AndroidShieldEngine.attach(this)
    }

    // 自我保护：Shield 锁定期间拦截系统返回键，防止绕过锁定页
    private val shieldBackCallback = object : androidx.activity.OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (com.syna.shield.ShieldController.current?.state?.value == com.syna.shield.ShieldState.LOCKED) {
                // 锁定期间吞掉返回键
            } else {
                // 用后即恢复（否则同实例内 Shield 再次锁定后返回键不再被拦截）
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    init {
        onBackPressedDispatcher.addCallback(this, shieldBackCallback)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = if (resultCode == RESULT_OK) data?.data else null
        when (requestCode) {
            RC_PICK_IMAGE -> {
                pendingImagePick?.invoke(uri)
                pendingImagePick = null
            }
            RC_PICK_FILE -> {
                pendingFilePick?.invoke(uri)
                pendingFilePick = null
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AndroidShieldEngine.detach()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        sCurrent = this

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent {
            App()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (sCurrent === this) sCurrent = null
        // 清理挂起的文件选择回调：旋转重建后旧回调捕获已销毁的 engine，
        // 继续持有会导致结果写入死实例（静默丢失）
        pendingImagePick = null
        pendingFilePick = null
    }
}
