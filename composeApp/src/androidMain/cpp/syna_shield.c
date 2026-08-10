/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * ◇Mirtazapine Shield native 对抗层（v0.7.3）：
 *
 * 1. syscall 直通：所有检测 I/O 走 SYS_openat/SYS_read/SYS_close/SYS_getdents64，
 *    不经过 libc —— LD_PRELOAD / GOT / PLT hook 全部失效（它们只能改 GOT 条目，
 *    改不到 syscall 指令）；
 * 2. 自代码段完整性：自身 so 的 PT_LOAD 可执行段做"内存哈希 vs 文件哈希"比对——
 *    inline hook（Frida Interceptor / 字节补丁）会改写代码段，任何字节差异即命中；
 * 3. libc 关键函数入口校验：dlsym 定位真实地址（不经过 GOT），比对入口 16 字节
 *    与磁盘 libc 文件 —— inline hook libc 命中；
 * 4. 字符串原语自实现：不依赖可能被 hook 的 libc strstr/strlen/atoi/memcmp。
 *
 * 结果经 integrity() 位掩码上报：
 *   bit0 = 自身代码段被修改（含全部 JNI 导出函数入口被 inline hook）
 *   bit1 = libc 关键函数入口被修改
 * JVM 通道将位掩码接入威胁管道（SHIELD_TAMPERED / FRIDA_DETECTED）。
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <dirent.h>
#include <link.h>
#include <dlfcn.h>
#include <elf.h>

/* ================= 自实现字符串原语（防 libc hook） ================= */

static size_t s_len(const char *s) {
    const char *p = s;
    while (*p) p++;
    return (size_t)(p - s);
}

static int s_contains(const char *hay, const char *needle) {
    size_t nl = s_len(needle);
    if (nl == 0) return 1;
    for (; *hay; hay++) {
        size_t i = 0;
        while (hay[i] && needle[i] && hay[i] == needle[i]) i++;
        if (i == nl) return 1;
    }
    return 0;
}

static int s_atoi(const char *s) {
    int v = 0;
    while (*s >= '0' && *s <= '9') {
        v = v * 10 + (*s - '0');
        s++;
    }
    return v;
}

static int s_bytes_equal(const void *a, const void *b, size_t n) {
    const unsigned char *x = (const unsigned char *)a;
    const unsigned char *y = (const unsigned char *)b;
    for (size_t i = 0; i < n; i++) if (x[i] != y[i]) return 0;
    return 1;
}

/* ================= syscall 直通 I/O（绕过 libc） ================= */

static long sys_open(const char *path, int flags) {
    return syscall(SYS_openat, AT_FDCWD, path, flags, 0);
}

static long sys_read(int fd, void *buf, size_t count) {
    return syscall(SYS_read, fd, buf, count);
}

static long sys_close(int fd) {
    return syscall(SYS_close, fd);
}

static int sys_getdents64(int fd, void *buf, unsigned int count) {
    return (int)syscall(SYS_getdents64, fd, buf, count);
}

/* 读文件到 buf（≤ max），返回字节数或 -1 */
static int sys_read_file(const char *path, char *buf, int max) {
    long fd = sys_open(path, O_RDONLY);
    if (fd < 0) return -1;
    int total = 0;
    while (total < max) {
        long n = sys_read((int)fd, buf + total, (size_t)(max - total));
        if (n < 0) { sys_close((int)fd); return -1; }
        if (n == 0) break;
        total += (int)n;
    }
    sys_close((int)fd);
    return total;
}

/* ================= SHA-256（自包含实现） ================= */

typedef struct {
    uint32_t h[8];
    uint64_t len;
    unsigned char block[64];
    size_t block_len;
} sha256_ctx;

static const uint32_t SHA256_K[64] = {
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u,
    0x923f82a4u, 0xab1c5ed5u, 0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
    0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u, 0xe49b69c1u, 0xefbe4786u,
    0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u,
    0x06ca6351u, 0x14292967u, 0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
    0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u, 0xa2bfe8a1u, 0xa81a664bu,
    0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au,
    0x5b9cca4fu, 0x682e6ff3u, 0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
    0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u
};

static uint32_t sha_ror(uint32_t x, int n) { return (x >> n) | (x << (32 - n)); }

static void sha256_init(sha256_ctx *c) {
    c->h[0] = 0x6a09e667u; c->h[1] = 0xbb67ae85u;
    c->h[2] = 0x3c6ef372u; c->h[3] = 0xa54ff53au;
    c->h[4] = 0x510e527fu; c->h[5] = 0x9b05688cu;
    c->h[6] = 0x1f83d9abu; c->h[7] = 0x5be0cd19u;
    c->len = 0; c->block_len = 0;
}

static void sha256_block(sha256_ctx *c, const unsigned char *p) {
    uint32_t w[64];
    for (int i = 0; i < 16; i++) {
        w[i] = ((uint32_t)p[i * 4] << 24) | ((uint32_t)p[i * 4 + 1] << 16) |
               ((uint32_t)p[i * 4 + 2] << 8) | (uint32_t)p[i * 4 + 3];
    }
    for (int i = 16; i < 64; i++) {
        uint32_t s0 = sha_ror(w[i - 15], 7) ^ sha_ror(w[i - 15], 18) ^ (w[i - 15] >> 3);
        uint32_t s1 = sha_ror(w[i - 2], 17) ^ sha_ror(w[i - 2], 19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    uint32_t a = c->h[0], b = c->h[1], cc = c->h[2], d = c->h[3];
    uint32_t e = c->h[4], f = c->h[5], g = c->h[6], h = c->h[7];
    for (int i = 0; i < 64; i++) {
        uint32_t S1 = sha_ror(e, 6) ^ sha_ror(e, 11) ^ sha_ror(e, 25);
        uint32_t ch = (e & f) ^ (~e & g);
        uint32_t t1 = h + S1 + ch + SHA256_K[i] + w[i];
        uint32_t S0 = sha_ror(a, 2) ^ sha_ror(a, 13) ^ sha_ror(a, 22);
        uint32_t maj = (a & b) ^ (a & cc) ^ (b & cc);
        uint32_t t2 = S0 + maj;
        h = g; g = f; f = e; e = d + t1;
        d = cc; cc = b; b = a; a = t1 + t2;
    }
    c->h[0] += a; c->h[1] += b; c->h[2] += cc; c->h[3] += d;
    c->h[4] += e; c->h[5] += f; c->h[6] += g; c->h[7] += h;
}

static void sha256_update(sha256_ctx *c, const unsigned char *data, size_t n) {
    c->len += (uint64_t)n;
    while (n > 0) {
        size_t take = 64 - c->block_len;
        if (take > n) take = n;
        for (size_t i = 0; i < take; i++) c->block[c->block_len + i] = data[i];
        c->block_len += take;
        data += take;
        n -= take;
        if (c->block_len == 64) {
            sha256_block(c, c->block);
            c->block_len = 0;
        }
    }
}

static void sha256_final(sha256_ctx *c, unsigned char out[32]) {
    uint64_t bits = c->len * 8;
    unsigned char pad = 0x80;
    sha256_update(c, &pad, 1);
    unsigned char zero = 0;
    while (c->block_len != 56) sha256_update(c, &zero, 1);
    unsigned char lenb[8];
    for (int i = 0; i < 8; i++) lenb[i] = (unsigned char)(bits >> (56 - i * 8));
    sha256_update(c, lenb, 8);
    for (int i = 0; i < 8; i++) {
        out[i * 4] = (unsigned char)(c->h[i] >> 24);
        out[i * 4 + 1] = (unsigned char)(c->h[i] >> 16);
        out[i * 4 + 2] = (unsigned char)(c->h[i] >> 8);
        out[i * 4 + 3] = (unsigned char)(c->h[i]);
    }
}

/* ================= 检测函数（syscall 直通） ================= */

static int read_tracer_pid(void) {
    char buf[8192];
    int n = sys_read_file("/proc/self/status", buf, (int)sizeof(buf) - 1);
    if (n <= 0) return 0;
    buf[n] = 0;
    const char *p = buf;
    for (;;) {
        const char *line = p;
        const char *nl = p;
        while (*nl && *nl != '\n') nl++;
        if (line + 10 <= nl) {
            if (s_len(line) >= 10 && line[0] == 'T' && line[1] == 'r' && line[2] == 'a' &&
                line[3] == 'c' && line[4] == 'e' && line[5] == 'r' && line[6] == 'P' &&
                line[7] == 'i' && line[8] == 'd' && line[9] == ':') {
                const char *v = line + 10;
                while (*v == ' ' || *v == '\t') v++;
                return s_atoi(v);
            }
        }
        if (!*nl) break;
        p = nl + 1;
    }
    return 0;
}

static int scan_maps(const char *needle) {
    char buf[16384];
    int n = sys_read_file("/proc/self/maps", buf, (int)sizeof(buf) - 1);
    if (n <= 0) return 0;
    buf[n] = 0;
    return s_contains(buf, needle);
}

static int scan_threads(void) {
    long dfd = sys_open("/proc/self/task", O_RDONLY | O_DIRECTORY);
    if (dfd < 0) return 0;
    char dents[8192];
    int hit = 0;
    for (;;) {
        int n = sys_getdents64((int)dfd, dents, (unsigned int)sizeof(dents));
        if (n <= 0) break;
        int off = 0;
        while (off < n) {
            struct dirent *de = (struct dirent *)(dents + off);
            off += (int)de->d_reclen;
            if (de->d_name[0] == '.') continue;
            char path[96];
            char comm[64];
            int pathlen = 0;
            const char *p = "/proc/self/task/";
            while (*p) path[pathlen++] = *p++;
            const char *q = de->d_name;
            while (*q && pathlen < 90) path[pathlen++] = *q++;
            path[pathlen++] = '/'; path[pathlen++] = 'c'; path[pathlen++] = 'o';
            path[pathlen++] = 'm'; path[pathlen++] = 'm';
            path[pathlen] = 0;
            int c = sys_read_file(path, comm, (int)sizeof(comm) - 1);
            if (c > 0) {
                comm[c] = 0;
                if (s_contains(comm, "frida") || s_contains(comm, "gum-js")) {
                    hit = 1;
                    break;
                }
            }
        }
        if (hit) break;
    }
    sys_close((int)dfd);
    return hit;
}

/* ================= 代码段完整性校验 ================= */

/* 内存段哈希（按页读内存可能越界到守卫页？PT_LOAD 段内存连续映射 ✓） */
static void hash_mem(sha256_ctx *c, const unsigned char *addr, size_t len) {
    while (len > 0) {
        size_t take = len < 4096 ? len : 4096;
        sha256_update(c, addr, take);
        addr += take;
        len -= take;
    }
}

static int g_self_modified = 0;
static int g_libc_modified = 0;

static int cmp_segments(const char *fname, const void *base, const ElfW(Phdr) *ph,
                        int phnum) {
    long ffd = sys_open(fname, O_RDONLY);
    if (ffd < 0) return 0;
    int modified = 0;
    for (int i = 0; i < phnum; i++) {
        const ElfW(Phdr) *p = &ph[i];
        if (p->p_type != PT_LOAD) continue;
        if (!(p->p_flags & PF_X)) continue;   /* 只看可执行代码段 */
        size_t seg_len = (size_t)p->p_memsz;
        if (seg_len == 0) continue;
        const unsigned char *maddr = (const unsigned char *)base + p->p_vaddr;
        /* 文件偏移 p_offset，长度 p_filesz（≤ memsz） */
        unsigned char *fbuf = (unsigned char *)malloc(p->p_filesz);
        if (!fbuf) { sys_close((int)ffd); return 0; }
        /* 定位到文件内代码段偏移再读（文件头在 p_offset 之前） */
        long seeked = syscall(SYS_lseek, (int)ffd, (long)p->p_offset, SEEK_SET);
        if (seeked < 0) { free(fbuf); continue; }
        size_t got = 0;
        while (got < p->p_filesz) {
            long r = sys_read((int)ffd, fbuf + got, p->p_filesz - got);
            if (r <= 0) break;
            got += (size_t)r;
        }
        size_t cmp_len = got < seg_len ? got : seg_len;
        sha256_ctx cm, cf;
        sha256_init(&cm); sha256_init(&cf);
        hash_mem(&cm, maddr, cmp_len);
        sha256_update(&cf, fbuf, cmp_len);
        unsigned char dm[32], df[32];
        sha256_final(&cm, dm); sha256_final(&cf, df);
        if (!s_bytes_equal(dm, df, 32)) modified = 1;
        free(fbuf);
        if (modified) break;
    }
    sys_close((int)ffd);
    return modified;
}

/* 自代码段校验：dl_iterate_phdr 定位自身 so，比对可执行段 */
static int self_phdr_cb(struct dl_phdr_info *info, size_t size, void *data) {
    (void)size;
    if (!s_contains(info->dlpi_name, "libsyna_shield.so")) return 0;
    if (info->dlpi_name[0] == 0) return 0;
    if (cmp_segments(info->dlpi_name, (const void *)info->dlpi_addr,
                     info->dlpi_phdr, (int)info->dlpi_phnum)) {
        g_self_modified = 1;
    }
    return 1; /* 停止迭代 */
}

/* 通用入口校验：addr 所在 ELF 的入口 16 字节 vs 磁盘文件（inline hook 命中） */
static int verify_entry_file(void *addr) {
    if (!addr) return 0;
    Dl_info di;
    if (!dladdr(addr, &di) || !di.dli_fname || di.dli_fname[0] == 0) return 0;
    long ffd = sys_open(di.dli_fname, O_RDONLY);
    if (ffd < 0) return 0;
    /* 用 dl_iterate_phdr 拿 libc 段映射：虚地址 → 文件偏移 */
    struct libc_seg { const void *base; const ElfW(Phdr) *ph; int phnum; const char *fname; } seg;
    /* 经 dladdr 的 dli_fbase + ELF program header 解析 */
    int modified = 0;
    /* 读取 ELF header + program headers 解析 PT_LOAD 映射 */
    unsigned char ehdr_buf[sizeof(ElfW(Ehdr))];
    if (sys_read((int)ffd, ehdr_buf, sizeof(ehdr_buf)) != (long)sizeof(ehdr_buf)) {
        sys_close((int)ffd);
        return 0;
    }
    const ElfW(Ehdr) *eh = (const ElfW(Ehdr) *)ehdr_buf;
    if (!s_bytes_equal(eh->e_ident, ELFMAG, SELFMAG)) { sys_close((int)ffd); return 0; }
    /* 读 program headers（ELF64 下 phoff/entsize） */
    long phoff = (long)eh->e_phoff;
    int phnum = (int)eh->e_phnum;
    size_t entsize = eh->e_phentsize;
    if (entsize < sizeof(ElfW(Phdr))) { sys_close((int)ffd); return 0; }
    size_t phbytes = (size_t)phnum * entsize;
    unsigned char *phbuf = (unsigned char *)malloc(phbytes);
    if (!phbuf) { sys_close((int)ffd); return 0; }
    syscall(SYS_lseek, (int)ffd, phoff, SEEK_SET);
    if (sys_read((int)ffd, phbuf, (int)phbytes) != (long)phbytes) {
        free(phbuf); sys_close((int)ffd); return 0;
    }
    const unsigned char *base = (const unsigned char *)di.dli_fbase;
    const unsigned char *target = (const unsigned char *)addr;
    for (int i = 0; i < phnum; i++) {
        const ElfW(Phdr) *p = (const ElfW(Phdr) *)(phbuf + (size_t)i * entsize);
        if (p->p_type != PT_LOAD) continue;
        if (!(p->p_flags & PF_X)) continue;
        if (target >= base + p->p_vaddr &&
            target < base + p->p_vaddr + p->p_memsz) {
            /* 段内偏移 → 文件偏移 */
            long in_seg = (long)(target - (base + p->p_vaddr));
            long file_off = (long)p->p_offset + in_seg;
            unsigned char fbytes[16], mbytes[16];
            /* 用 SYS_lseek + SYS_read 读指定偏移（pread 直通） */
            syscall(SYS_lseek, (int)ffd, file_off, SEEK_SET);
            long got = sys_read((int)ffd, fbytes, 16);
            for (size_t k = 0; k < 16; k++) mbytes[k] = target[k];
            if (got == 16) {
                /* 文件前 16 字节 vs 内存前 16 字节（ELF 头部校验避免读文件头） */
                if (!s_bytes_equal(fbytes, mbytes, 16)) modified = 1;
            }
            break;
        }
    }
    free(phbuf);
    sys_close((int)ffd);
    return modified;
}

/* libc 关键函数入口校验（dlsym 取真实地址，不经过 GOT） */
static void verify_libc_entries(void) {
    static const char *syms[] = { "openat", "read", "close", "getdents64",
                                  "dlsym", "dlopen", "pthread_create" };
    for (size_t i = 0; i < sizeof(syms) / sizeof(syms[0]); i++) {
        void *addr = dlsym(RTLD_DEFAULT, syms[i]);
        if (!addr) continue;
        Dl_info di;
        if (dladdr(addr, &di) && s_contains(di.dli_fname ? di.dli_fname : "", "libc.so")) {
            if (verify_entry_file(addr)) {
                g_libc_modified = 1;
                return;
            }
        }
    }
}

/* 自身导出函数入口校验：hook 检测函数本身（最常见的 frida 操作）也会命中 */
jint JNICALL Java_com_syna_shield_NativeShield_tracerPid(JNIEnv *, jobject);
jint JNICALL Java_com_syna_shield_NativeShield_fridaMaps(JNIEnv *, jobject);
jint JNICALL Java_com_syna_shield_NativeShield_fridaThreads(JNIEnv *, jobject);
jint JNICALL Java_com_syna_shield_NativeShield_integrity(JNIEnv *, jobject);

static void verify_self_entries(void) {
    void *targets[] = {
        (void *)&Java_com_syna_shield_NativeShield_tracerPid,
        (void *)&Java_com_syna_shield_NativeShield_fridaMaps,
        (void *)&Java_com_syna_shield_NativeShield_fridaThreads,
        (void *)&Java_com_syna_shield_NativeShield_integrity,
        (void *)&verify_entry_file,
        (void *)&verify_self_entries,
    };
    for (size_t i = 0; i < sizeof(targets) / sizeof(targets[0]); i++) {
        if (verify_entry_file(targets[i])) {
            g_self_modified = 1;
            return;
        }
    }
}

/* ================= JNI 导出 ================= */

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_tracerPid(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return read_tracer_pid();
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_fridaMaps(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return scan_maps("frida-gadget") || scan_maps("frida-agent") ||
           scan_maps("gum-js") || scan_maps("libgadget");
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_fridaThreads(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return scan_threads();
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_integrity(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    /* 每次扫描全量重验（轻量：SHA-256 数 KB 级，毫秒内） */
    g_self_modified = 0;
    g_libc_modified = 0;
    dl_iterate_phdr(self_phdr_cb, NULL);
    verify_self_entries();
    verify_libc_entries();
    int mask = 0;
    if (g_self_modified) mask |= 1;
    if (g_libc_modified) mask |= 2;
    return mask;
}
