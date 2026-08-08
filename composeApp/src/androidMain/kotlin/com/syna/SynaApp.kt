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

import android.app.Application
import android.content.Context

class SynaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // 全局崩溃日志：启动异常时写入文件，便于排查"打不开"
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val file = java.io.File(filesDir, "crash.log")
                java.io.FileWriter(file, true).use { w ->
                    w.write(
                        "${System.currentTimeMillis()} [${thread.name}] " +
                            "${throwable::class.java.name}: ${throwable.message}\n" +
                            throwable.stackTraceToString() + "\n\n",
                    )
                }
            } catch (e: Exception) {
            }
            android.util.Log.e("Syna", "Uncaught exception", throwable)
        }
    }

    companion object {
        lateinit var instance: SynaApp
            private set

        val context: Context
            get() = instance.applicationContext
    }
}
