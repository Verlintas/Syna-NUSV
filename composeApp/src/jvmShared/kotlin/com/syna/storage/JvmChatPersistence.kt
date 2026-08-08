package com.syna.storage

import java.nio.file.Paths

actual fun chatPersistencePath(): String =
    Paths.get(System.getProperty("user.home") ?: ".", ".syna", "chat.jsonl").toString()
