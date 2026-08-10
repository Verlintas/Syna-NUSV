/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.util

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine
import java.io.File

/** 桌面：WAV PCM 录音（16kHz 单声道 16bit） */
actual object VoiceRecorder {

    private var line: TargetDataLine? = null
    private var outputFile: File? = null
    private var startedAt = 0L
    private var thread: Thread? = null

    actual fun start(): Boolean {
        return try {
            val format = AudioFormat(16000f, 16, 1, true, false)
            val info = DataLine.Info(TargetDataLine::class.java, format)
            val l = AudioSystem.getLine(info) as TargetDataLine
            l.open(format)
            l.start()
            line = l
            startedAt = System.currentTimeMillis()
            val f = File(System.getProperty("java.io.tmpdir"), "syna-voice-${System.currentTimeMillis()}.wav")
            outputFile = f
            // 后台线程写 WAV（PCM + 44 字节头）
            thread = Thread {
                try {
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (line?.isOpen == true) {
                        val n = l.read(buf, 0, buf.size)
                        if (n > 0) out.write(buf, 0, n)
                    }
                    val pcm = out.toByteArray()
                    java.io.FileOutputStream(f).use { fos ->
                        // WAV 头
                        val total = 44 + pcm.size
                        fos.write("RIFF".toByteArray())
                        fos.write(intLe(total - 8))
                        fos.write("WAVE".toByteArray())
                        fos.write("fmt ".toByteArray())
                        fos.write(intLe(16))
                        fos.write(shortLe(1)) // PCM
                        fos.write(shortLe(1)) // mono
                        fos.write(intLe(16000))
                        fos.write(intLe(32000)) // byte rate
                        fos.write(shortLe(2)) // block align
                        fos.write(shortLe(16)) // bits
                        fos.write("data".toByteArray())
                        fos.write(intLe(pcm.size))
                        fos.write(pcm)
                    }
                } catch (e: Exception) {
                }
            }.apply { start() }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
        ((v shr 16) and 0xff).toByte(), ((v shr 24) and 0xff).toByte(),
    )

    private fun shortLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xff).toByte(), ((v shr 8) and 0xff).toByte(),
    )

    actual fun stop(): Pair<String, Long>? {
        val l = line ?: return null
        return try {
            l.stop()
            l.close()
            line = null
            thread?.join(1_000)
            thread = null
            val dur = System.currentTimeMillis() - startedAt
            val f = outputFile ?: return null
            if (dur < 500 || !f.exists() || f.length() < 500L) {
                f.delete()
                null
            } else {
                f.absolutePath to dur
            }
        } catch (e: Exception) {
            null
        }
    }

    actual fun cancel() {
        try {
            line?.stop()
            line?.close()
        } catch (e: Exception) {
        }
        line = null
        thread?.interrupt()
        thread = null
        outputFile?.delete()
        outputFile = null
    }
}

actual fun playVoiceAudio(path: String) {
    try {
        // AMR 是 Android 专有格式，Java Sound 无法播放——明确提示而非静默失败
        if (path.endsWith(".amr")) {
            System.err.println("[Syna:Voice] 桌面端无法播放 AMR 语音（请用 Android 端收听）")
            return
        }
        val file = File(path)
        val stream = AudioSystem.getAudioInputStream(file)
        val format = stream.format
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val line = AudioSystem.getLine(info) as SourceDataLine
        line.open(format)
        line.start()
        Thread {
            try {
                val buf = ByteArray(4096)
                var n = stream.read(buf)
                while (n > 0) {
                    line.write(buf, 0, n)
                    n = stream.read(buf)
                }
                line.drain()
                line.close()
            } catch (e: Exception) {
                line.close()
            }
        }.apply { start() }
    } catch (e: Exception) {
    }
}

actual fun requestRecordAudioPermission() = Unit

actual fun canRecordVoice(): Boolean = true
