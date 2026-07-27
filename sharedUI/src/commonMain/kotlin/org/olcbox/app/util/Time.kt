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
