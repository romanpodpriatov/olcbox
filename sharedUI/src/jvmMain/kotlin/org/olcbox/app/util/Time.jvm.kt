package org.olcbox.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochMs))

actual fun formatDate(epochMs: Long): String =
    SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(epochMs))
