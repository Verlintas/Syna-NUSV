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
