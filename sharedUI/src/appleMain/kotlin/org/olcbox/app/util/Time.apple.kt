package org.olcbox.app.util

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
