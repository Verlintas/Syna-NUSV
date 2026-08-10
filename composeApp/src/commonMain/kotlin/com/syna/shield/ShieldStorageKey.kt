package com.syna.shield

/**
 * 聊天记录静态加密：AES-GCM。
 * 文件格式：`SYNA1\n` + 12 字节随机 nonce + 密文。
 * Android 用 Keystore AES 密钥（TEE，不可导出）；桌面用 0600 权限密钥文件。
 * 防止存储拷贝 / 备份提取 / 静态读取明文。
 */
expect object ShieldStorageKey {
    /** 加密（返回带 nonce 的完整负载；失败返回 null） */
    fun encrypt(data: ByteArray): ByteArray?

    /** 解密（输入为 encrypt 的完整负载；失败返回 null） */
    fun decrypt(payload: ByteArray): ByteArray?

    /**
     * 销毁存储密钥（自毁协议最强手段）：
     * Android 删除 Keystore 中的主密钥与会话认证密钥（TEE 内销毁）——
     * 即使加密文件被取证恢复，没有密钥也永久不可解；
     * 桌面覆写删除密钥文件。
     */
    fun wipe()
}
