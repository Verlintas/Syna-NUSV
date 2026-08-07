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
package com.syna.crypto

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists

class JvmIdentityStore(private val dir: Path) : IdentityStore {

    private val privateFile: Path = dir.resolve("identity.key")
    private val publicFile: Path = dir.resolve("identity.pub")

    override fun loadOrCreate(): IdentityKey {
        if (privateFile.exists() && publicFile.exists()) {
            return IdentityKey(
                privateBytes = Files.readAllBytes(privateFile),
                publicBytes = Files.readAllBytes(publicFile),
            )
        }
        val key = SynaCrypto.generateKeyPair()
        Files.createDirectories(dir)
        Files.write(privateFile, key.privateBytes)
        Files.write(publicFile, key.publicBytes)
        try {
            Files.setPosixFilePermissions(
                privateFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } catch (_: UnsupportedOperationException) {
        } catch (_: Exception) {
        }
        return key
    }
}
