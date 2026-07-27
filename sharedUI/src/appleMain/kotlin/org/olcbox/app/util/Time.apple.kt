package org.olcbox.app.util

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun formatDateTime(epochMs: Long): String = format("dd.MM.yyyy HH:mm", epochMs)

actual fun formatDate(epochMs: Long): String = format("dd.MM.yyyy", epochMs)

private fun format(pattern: String, epochMs: Long): String {
    val formatter = NSDateFormatter().apply { dateFormat = pattern }
    return formatter.stringFromDate(
        NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    )
}
