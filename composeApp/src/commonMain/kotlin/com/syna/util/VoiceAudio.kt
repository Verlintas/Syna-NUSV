/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.util

/**
 * 语音消息音频能力（expect/actual）：
 * - 录音：返回音频文件路径与时长（毫秒）；失败返回 null
 * - 播放：播放语音文件（异步）
 * 音频文件走既有文件传输通道（分块 + 加密），端到端保护。
 */
expect object VoiceRecorder {
    /** 开始录音（返回 false 表示无法录音：权限缺失/无麦克风） */
    fun start(): Boolean

    /** 停止录音，返回 (文件路径, 时长毫秒)；未在录音返回 null */
    fun stop(): Pair<String, Long>?

    /** 录音文件（供取消时清理） */
    fun cancel()
}

/** 播放语音文件（异步，无阻塞） */
expect fun playVoiceAudio(path: String)

/** 是否具备语音录制能力（权限/硬件） */
expect fun canRecordVoice(): Boolean

/** 请求录音权限（Android 运行时权限弹窗；桌面 no-op） */
expect fun requestRecordAudioPermission()
