/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package cloud.orbit.runtime.pipeline.steps

import cloud.orbit.runtime.net.Message
import cloud.orbit.runtime.pipeline.PipelineContext

internal interface PipelineStep {
    @JvmDefault
    suspend fun onOutbound(context: PipelineContext, msg: Message): Unit = context.nextOutbound(msg)

    @JvmDefault
    suspend fun onInbound(context: PipelineContext, msg: Message): Unit = context.nextInbound(msg)
}