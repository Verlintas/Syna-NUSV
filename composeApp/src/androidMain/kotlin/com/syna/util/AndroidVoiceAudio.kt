/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.util

import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import java.io.File

/** Android：AMR-NB 录音（系统编码器，体积小） */
actual object VoiceRecorder {

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt = 0L

    actual fun start(): Boolean {
        return try {
            // 权限检查（调用方负责请求；此处仅探测）
            if (ContextCompat.checkSelfPermission(
                    com.syna.SynaApp.context,
                    android.Manifest.permission.RECORD_AUDIO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            val dir = File(com.syna.SynaApp.context.cacheDir, "voice")
            dir.mkdirs()
            val f = File(dir, "voice-${System.currentTimeMillis()}.amr")
            val r = if (Build.VERSION.SDK_INT >= 31) {
                MediaRecorder(com.syna.SynaApp.context)
            } else {
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AMR_NB)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            outputFile = f
            startedAt = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            false
        }
    }

    actual fun stop(): Pair<String, Long>? {
        val r = recorder ?: return null
        return try {
            r.stop()
            r.release()
            recorder = null
            val dur = System.currentTimeMillis() - startedAt
            val f = outputFile ?: return null
            if (dur < 500 || !f.exists() || f.length() < 200L) {
                f.delete()
                null
            } else {
                f.absolutePath to dur
            }
        } catch (e: Exception) {
            try {
                r.release()
            } catch (e2: Exception) {
            }
            recorder = null
            null
        }
    }

    actual fun cancel() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
        }
        try {
            recorder?.release()
        } catch (e: Exception) {
        }
        recorder = null
        outputFile?.delete()
        outputFile = null
    }
}

actual fun playVoiceAudio(path: String) {
    try {
        val player = MediaPlayer()
        player.setDataSource(path)
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ ->
            mp.release()
            true
        }
        player.prepare()
        player.start()
    } catch (e: Exception) {
    }
}

actual fun requestRecordAudioPermission() {
    try {
        val activity = com.syna.shield.AndroidShieldEngine.activeActivityOrNull()
        if (activity != null) {
            activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 0x2002)
        }
    } catch (e: Exception) {
    }
}

actual fun canRecordVoice(): Boolean =
    ContextCompat.checkSelfPermission(
        com.syna.SynaApp.context,
        android.Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
