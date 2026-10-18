package com.theglobulus.claudecar.net

import kotlin.math.min
import kotlin.random.Random

/** Exponential reconnect delay, 1 s doubling to 60 s, with ±20% jitter. */
class Backoff(
    private val minMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val random: Random = Random.Default,
) {
    private var attempt = 0

    fun next(): Long {
        val base = min(maxMs, minMs shl min(attempt, 16))
        attempt++
        val jitter = 0.8 + random.nextDouble() * 0.4
        return (base * jitter).toLong().coerceIn(minMs / 2, maxMs)
    }

    fun reset() {
        attempt = 0
    }
}
