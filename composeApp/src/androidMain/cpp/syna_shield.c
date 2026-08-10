/*
 * Syna — LAN instant messenger (GPL-3.0)
 *
 * Copyright (C) 2026 Verlintas
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * ◇Mirtazapine Shield native 检测层：
 * 反调试/注入检测下沉到 C 层，JVM 层 hook（Frida 的 Java hook / 重打包 hook）
 * 无法覆盖这些调用点；需要 frida-gadget 级别的 native hook 才能绕过。
 * 检测结果与 JVM 层通道双轨交叉验证（任一通道命中即报警）。
 */
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dirent.h>

/* TracerPid：内核维护的 ptrace 标记，被调试/注入时非零（最可靠的反调试信号） */
static int read_tracer_pid(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    int pid = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            pid = atoi(line + 10);
            break;
        }
    }
    fclose(f);
    return pid;
}

/* /proc/self/maps：frida / gum 特征库映射 */
static int scan_maps(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 0;
    char line[512];
    int hit = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "frida-gadget") || strstr(line, "frida-agent") ||
            strstr(line, "gum-js") || strstr(line, "libgadget")) {
            hit = 1;
            break;
        }
    }
    fclose(f);
    return hit;
}

/* task 目录 comm：frida / gum-js-loop 线程名 */
static int scan_threads(void) {
    DIR *dir = opendir("/proc/self/task");
    if (!dir) return 0;
    struct dirent *entry;
    int hit = 0;
    while ((entry = readdir(dir)) != NULL) {
        if (entry->d_name[0] == '.') continue;
        char path[96];
        snprintf(path, sizeof(path), "/proc/self/task/%s/comm", entry->d_name);
        FILE *f = fopen(path, "r");
        if (!f) continue;
        char comm[64] = {0};
        if (fgets(comm, sizeof(comm), f)) {
            if (strstr(comm, "frida") || strstr(comm, "gum-js") || strstr(comm, "gmain")) {
                /* gmain 是 glib 默认线程（frida 依赖），避免误报：仅精确匹配 frida/gum */
                if (strstr(comm, "frida") || strstr(comm, "gum-js")) {
                    hit = 1;
                }
            }
        }
        fclose(f);
        if (hit) break;
    }
    closedir(dir);
    return hit;
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_tracerPid(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return read_tracer_pid();
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_fridaMaps(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return scan_maps();
}

JNIEXPORT jint JNICALL Java_com_syna_shield_NativeShield_fridaThreads(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    return scan_threads();
}
