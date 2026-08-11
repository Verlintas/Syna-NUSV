/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 */
package com.syna.util

import kotlinx.serialization.Serializable

/**
 * 版本更新检查：启动时异步拉取 GitHub Releases 最新版，对比本地版本，
 * 有新版本时通知用户。网络不可用/解析失败时静默（不打扰）。
 */
object UpdateChecker {

    @Serializable
    private data class GitHubRelease(val tag_name: String? = null, val html_url: String? = null)

    private const val RELEASES_API = "https://api.github.com/repos/Verlintas/Syna-NUSV/releases/latest"

    /** 检查最新版本；onResult(newVersion, url)（无更新或失败不回调或回调空） */
    fun checkAsync(currentVersion: String, onResult: (String, String) -> Unit) {
        Thread {
            try {
                val url = java.net.URI(RELEASES_API).toURL()
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("User-Agent", "Syna/$currentVersion")
                val body = conn.inputStream.bufferedReader().readText()
                val release = com.syna.net.synaJson.decodeFromString(GitHubRelease.serializer(), body)
                val tag = release.tag_name ?: return@Thread
                val latest = tag.removePrefix("v")
                val current = currentVersion.removePrefix("v")
                if (latest != current && isNewer(latest, current)) {
                    onResult(tag, release.html_url ?: "")
                }
            } catch (e: Exception) {
            }
        }.apply { isDaemon = true; start() }
    }

    private fun isNewer(latest: String, current: String): Boolean {
        val lp = latest.split(".").mapNotNull { it.toIntOrNull() }
        val cp = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(lp.size, cp.size)) {
            val l = lp.getOrElse(i) { 0 }
            val c = cp.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }
}
