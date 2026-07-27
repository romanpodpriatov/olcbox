package org.olcbox.app.util

/**
 * Wall-clock milliseconds since the epoch.
 *
 * Wall clock rather than [kotlin.time.TimeSource.Monotonic] on purpose. The
 * monotonic source on Darwin is `mach_absolute_time`, which stops while the
 * device is asleep — a session timer built on it loses the night and reads back
 * an hour when the tunnel has been up since yesterday. The session clock has to
 * agree with the user's own sense of elapsed time, and only the wall clock does.
 */
expect fun nowMillis(): Long

/**
 * `27.07.2026 18:54` in the device's own zone.
 *
 * Platform formatters rather than date arithmetic here: turning an epoch into a
 * civil date means a calendar and a timezone, and every platform already ships
 * one that is right about both. kotlinx-datetime would be the other answer, and
 * a dependency for two strings.
 */
expect fun formatDateTime(epochMs: Long): String

/** `26.08.2034`, same reasoning. */
expect fun formatDate(epochMs: Long): String
