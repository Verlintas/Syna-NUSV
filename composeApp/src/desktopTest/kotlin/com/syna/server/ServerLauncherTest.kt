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
