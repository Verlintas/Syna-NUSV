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

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerLauncherTest {

    @Test
    fun configSaveLoadRoundTrip() {
        val dir = Files.createTempDirectory("syna-launcher")
        val store = LauncherConfigStore(dir)
        store.save(
            LauncherConfig(
                port = 45999,
                password = "p@ss",
                groupName = "测试群",
                dataDir = "/tmp/syna-data",
                autoRestart = true,
                autoStart = true,
            ),
        )
        val loaded = store.load()
        assertEquals(45999, loaded.port)
        assertEquals("p@ss", loaded.password)
        assertEquals("测试群", loaded.groupName)
        assertEquals("/tmp/syna-data", loaded.dataDir)
        assertTrue(loaded.autoRestart)
        assertTrue(loaded.autoStart)
        assertTrue(Files.exists(dir.resolve("launcher.json")))
    }

    @Test
    fun missingConfigFallsBackToDefaults() {
        val dir = Files.createTempDirectory("syna-launcher-empty")
        val loaded = LauncherConfigStore(dir).load()
        assertEquals(45880, loaded.port)
        assertEquals("syna", loaded.password)
        assertFalse(loaded.autoRestart)
    }

    @Test
    fun corruptConfigFallsBackToDefaults() {
        val dir = Files.createTempDirectory("syna-launcher-corrupt")
        Files.writeString(dir.resolve("launcher.json"), "{not-valid-json")
        val loaded = LauncherConfigStore(dir).load()
        assertEquals(45880, loaded.port)
    }

    @Test
    fun locateJarNeverThrows() {
        val result = AutoStartManager.locateJar()
        assertTrue(result == null || result.endsWith(".jar"))
    }
}
