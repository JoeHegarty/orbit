/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package cloud.orbit.runtime.stage

import cloud.orbit.common.logging.debug
import cloud.orbit.common.logging.info
import cloud.orbit.common.logging.logger
import cloud.orbit.common.logging.loggingContext
import cloud.orbit.common.logging.trace
import cloud.orbit.common.logging.warn
import cloud.orbit.common.time.Clock
import cloud.orbit.common.time.stopwatch
import cloud.orbit.common.util.VersionUtils
import cloud.orbit.core.actor.ActorProxyFactory
import cloud.orbit.core.hosting.AddressableRegistry
import cloud.orbit.core.net.NodeCapabilities
import cloud.orbit.core.net.NodeInfo
import cloud.orbit.core.net.NodeStatus
import cloud.orbit.core.runtime.RuntimeContext
import cloud.orbit.runtime.actor.ActorProxyFactoryImpl
import cloud.orbit.runtime.capabilities.CapabilitiesScanner
import cloud.orbit.runtime.concurrent.RuntimePools
import cloud.orbit.runtime.concurrent.RuntimeScopes
import cloud.orbit.runtime.di.ComponentProvider
import cloud.orbit.runtime.hosting.AddressableRegistryImpl
import cloud.orbit.runtime.hosting.DirectorySystem
import cloud.orbit.runtime.hosting.ExecutionSystem
import cloud.orbit.runtime.hosting.ReferenceResolver
import cloud.orbit.runtime.hosting.ResponseTrackingSystem
import cloud.orbit.runtime.hosting.RoutingSystem
import cloud.orbit.runtime.net.NetSystem
import cloud.orbit.runtime.pipeline.PipelineSystem
import cloud.orbit.runtime.remoting.AddressableDefinitionDirectory
import cloud.orbit.runtime.remoting.AddressableInterfaceClientProxyFactory
import cloud.orbit.runtime.serialization.SerializationSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Orbit Stage.
 *
 * This represents a single instance of the Orbit runtime.
 */
class Stage(val config: StageConfig) : RuntimeContext {
    constructor() : this(StageConfig())

    private val logger by logger()

    private val errorHandler = ErrorHandler()
    private val runtimePools = RuntimePools(
        cpuPool = config.cpuPool,
        ioPool = config.ioPool
    )
    private val runtimeScopes = RuntimeScopes(
        runtimePools = runtimePools,
        exceptionHandler = errorHandler::onUnhandledException
    )

    internal val componentProvider = ComponentProvider()

    private val netSystem: NetSystem by componentProvider.inject()
    private val capabilitiesScanner: CapabilitiesScanner by componentProvider.inject()
    private val definitionDirectory: AddressableDefinitionDirectory by componentProvider.inject()
    private val pipelineSystem: PipelineSystem by componentProvider.inject()
    private val executionSystem: ExecutionSystem by componentProvider.inject()
    private val responseTrackingSystem: ResponseTrackingSystem by componentProvider.inject()


    private var tickJob: Job? = null

    override val clock: Clock by componentProvider.inject()
    override val actorProxyFactory: ActorProxyFactory by componentProvider.inject()
    override val addressableRegistry: AddressableRegistry by componentProvider.inject()

    init {
        componentProvider.configure {
            // Stage
            instance<RuntimeContext>(this@Stage)
            instance(this@Stage)
            instance(config)
            instance(runtimePools)
            instance(runtimeScopes)
            instance(errorHandler)

            // Utils
            definition<Clock>()

            // Net
            definition<NetSystem>()

            // Remoting
            definition<AddressableInterfaceClientProxyFactory>()
            definition<AddressableDefinitionDirectory>()

            // Pipeline
            definition<PipelineSystem>()

            // Hosting
            definition<RoutingSystem>()
            definition<ResponseTrackingSystem>()
            definition<ExecutionSystem>()
            definition<DirectorySystem>()
            definition<ReferenceResolver>()
            definition<AddressableRegistry>(AddressableRegistryImpl::class.java)

            // Serializer
            definition<SerializationSystem>()

            // Capabilities
            definition<CapabilitiesScanner>()

            // Actors
            definition<ActorProxyFactory>(ActorProxyFactoryImpl::class.java)

            // Cluster Components
            definition(config.clusterConfig.addressableDirectory)
        }

        netSystem.localNodeManipulator.replace(
            NodeInfo(
                clusterName = config.clusterName,
                nodeIdentity = config.nodeIdentity,
                nodeMode = config.nodeMode,
                nodeStatus = NodeStatus.STOPPED,
                nodeCapabilities = NodeCapabilities(
                    implementedAddressables = listOf()
                )
            )
        )
    }

    /**
     * Starts the Orbit stage.
     */
    fun start() = requestStart().asCompletableFuture()

    /**
     * Stops the Orbit stage
     */
    fun stop() = requestStop().asCompletableFuture()

    private fun requestStart() = runtimeScopes.cpuScope.async {
        logger.info("Starting Orbit...")
        val (elapsed, _) = stopwatch(clock) {
            onStart()
        }
        logger.info("Orbit started successfully in {}ms.", elapsed)

        Unit
    }


    private fun requestStop() = runtimeScopes.cpuScope.async {
        logger.info("Orbit stopping...")
        val (elapsed, _) = stopwatch(clock) {
            onStop()
        }

        logger.info("Orbit stopped in {}ms.", elapsed)
        Unit
    }

    private fun launchTick() = runtimeScopes.cpuScope.launch {
        val targetTickRate = config.tickRate
        while (isActive) {
            val (elapsed, _) = stopwatch(clock) {
                logger.trace { "Begin Orbit tick..." }

                try {
                    onTick()
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    errorHandler.onUnhandledException(coroutineContext, t)
                }
            }

            val nextTickDelay = (targetTickRate - elapsed).coerceAtLeast(0)

            if (elapsed > targetTickRate) {
                logger.warn {
                    "Slow Orbit Tick. The application is unable to maintain its tick rate. " +
                            "Last tick took ${elapsed}ms and the reference tick rate is ${targetTickRate}ms. " +
                            "The next tick will take place immediately."
                }
            }

            logger.trace { "Orbit tick completed in ${elapsed}ms. Next tick in ${nextTickDelay}ms." }
            delay(nextTickDelay)
        }
    }


    private suspend fun onStart() {
        netSystem.localNodeManipulator.updateNodeStatus(NodeStatus.STOPPED, NodeStatus.STARTING)

        // Log some info about the environment
        logEnvironmentInfo()
        logger.debug { "Orbit Stage Config: $config" }

        // Capabilities and definitions
        capabilitiesScanner.scan(*config.packages.toTypedArray())
        val capabilities = capabilitiesScanner.generateNodeCapabilities()
        netSystem.localNodeManipulator.updateCapabiltities(capabilities)
        definitionDirectory.setupDefinition(
            interfaceClasses = capabilitiesScanner.addressableInterfaces,
            impls = capabilitiesScanner.interfaceLookup
        )

        // Start pipeline
        pipelineSystem.start()

        // Flip status to running
        netSystem.localNodeManipulator.updateNodeStatus(NodeStatus.STARTING, NodeStatus.RUNNING)

        tickJob = launchTick()
    }

    private suspend fun onTick() {
        responseTrackingSystem.onTick()
        executionSystem.onTick()
    }

    private suspend fun onStop() {
        netSystem.localNodeManipulator.updateNodeStatus(NodeStatus.RUNNING, NodeStatus.STOPPING)

        executionSystem.onStop()

        // Stop the tick
        tickJob?.cancelAndJoin()


        // Stop pipeline
        pipelineSystem.stop()

        netSystem.localNodeManipulator.updateNodeStatus(NodeStatus.STOPPING, NodeStatus.STOPPED)
    }

    private fun logEnvironmentInfo() {
        val versionInfo = VersionUtils.getVersionInfo()
        logger.info {
            "Orbit Environment: ${config.clusterName} ${config.nodeIdentity} $versionInfo"
        }

        loggingContext {
            put("orbit.clusterName" to config.clusterName.value)
            put("orbit.nodeIdentity" to config.nodeIdentity.value)
            put("orbit.version" to versionInfo.orbitVersion)
            put("orbit.jvmVersion" to versionInfo.jvmVersion)
            put("orbit.jvmBuild" to versionInfo.jvmBuild)
            put("orbit.kotlinVersion" to versionInfo.kotlinVersion)
        }

        logger.debug {
            "Initial Orbit Component Provider State: ${componentProvider.debugString()}"
        }
    }
}