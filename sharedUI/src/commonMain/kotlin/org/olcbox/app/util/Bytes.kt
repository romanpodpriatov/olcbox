package org.olcbox.app.util

/**
 * Bytes as a person reads them.
 *
 * Binary units, because that is what every other VPN client on the device
 * shows and a figure that disagrees with the system's own reads as a bug. One
 * decimal below ten and none above: "9.4 MB" is useful, "947.3 MB" is noise.
 */
fun formatByteSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    val rounded = if (value < 10) {
        val tenths = (value * 10).toLong()
        "${tenths / 10}.${tenths % 10}"
    } else {
        value.toLong().toString()
    }
    return "$rounded ${units[unit]}"
}
