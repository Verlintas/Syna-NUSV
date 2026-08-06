package com.syna.util

inline fun synaLog(tag: String, message: () -> String) {
    println("[Syna:$tag] ${message()}")
}
