/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package cloud.orbit.common.exception

import java.lang.RuntimeException

/**
 * The root type of all custom Orbit exceptions.
 */
open class OrbitException(message: String? = null, cause: Throwable? = null): RuntimeException(message, cause)

/**
 * An exception of this type is thrown when an internal capacity in Orbit is exceeded.
 */
class CapacityExceededException(message: String): OrbitException(message)