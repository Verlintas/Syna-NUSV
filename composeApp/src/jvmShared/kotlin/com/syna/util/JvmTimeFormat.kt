package com.syna.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
