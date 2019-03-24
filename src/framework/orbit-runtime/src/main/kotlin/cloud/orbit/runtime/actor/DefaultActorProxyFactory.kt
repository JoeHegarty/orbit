/*
 Copyright (C) 2015 - 2019 Electronic Arts Inc.  All rights reserved.
 This file is part of the Orbit Project <https://www.orbit.cloud>.
 See license in LICENSE.
 */

package cloud.orbit.runtime.actor

import cloud.orbit.core.actor.Actor
import cloud.orbit.core.actor.ActorProxyFactory
import cloud.orbit.core.key.Key
import cloud.orbit.runtime.remoting.AddressableInterfaceClientProxyFactory

class DefaultActorProxyFactory(private val addressableInterfaceClientProxyFactory: AddressableInterfaceClientProxyFactory) :
    ActorProxyFactory {
    override fun <T : Actor> getReferenceInternal(grainType: Class<T>, grainKey: Key): T =
        addressableInterfaceClientProxyFactory.getReference(grainType, grainKey)
}