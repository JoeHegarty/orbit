/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package cloud.orbit.common.util

import cloud.orbit.common.logging.Logger
import kotlinx.coroutines.delay

/**
 * A suspending function that makes multiple attempts to successfully execute a code block.
 * Supports variable delays, logging and back off.
 * The result of the computation on the first successful execution or the exception thrown on the last failing execution
 * is propagated to the caller.
 *
 * @param maxAttempts The maximum number of attempts before failing.
 * @param initialDelay The initial delay after the first failure.
 * @param maxDelay The maximum amount of time to wait between attempts.
 * @param factor The factor to increase the wait time by after each attempt.
 * @param logger The logger to log attempt info to. No logging occurs if null.
 * @param body The code body to attempt to run.
 * @throws Throwable Any exception that is thrown on the last attempt.
 * @return The result of the computation.
 */
suspend inline fun <T> attempt(
    maxAttempts: Int = 5,
    initialDelay: Long = 1000,
    maxDelay: Long = Long.MAX_VALUE,
    factor: Double = 1.0,
    logger: Logger? = null,
    body: () -> T
): T {
    var currentDelay = initialDelay
    for (i in 0 until maxAttempts - 1) {
        try {
            return body()
        } catch (t: Throwable) {
            logger?.warn("Attempt ${i + 1}/$maxAttempts failed. Retrying in ${currentDelay}ms.", t)
        }
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }

    try {
        return body()
    } catch (t: Throwable) {
        logger?.warn("Attempt $maxAttempts/$maxAttempts failed. No more retries.", t)
        throw t
    }
}