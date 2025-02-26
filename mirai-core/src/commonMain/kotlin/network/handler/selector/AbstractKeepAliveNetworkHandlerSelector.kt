/*
 * Copyright 2019-2021 Mamoe Technologies and contributors.
 *
 *  此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 *  Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 *  https://github.com/mamoe/mirai/blob/master/LICENSE
 */

package net.mamoe.mirai.internal.network.handler.selector

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.yield
import net.mamoe.mirai.internal.network.handler.NetworkHandler
import net.mamoe.mirai.internal.network.handler.NetworkHandlerFactory
import net.mamoe.mirai.utils.systemProp
import net.mamoe.mirai.utils.toLongUnsigned
import org.jetbrains.annotations.TestOnly

/**
 * A lazy stateful implementation of [NetworkHandlerSelector].
 *
 * - Calls [factory.create][NetworkHandlerFactory.create] to create [NetworkHandler]s.
 * - Re-initialize [NetworkHandler] instances if the old one is dead.
 * - Suspends requests when connection is not available.
 *
 * No connection is created until first invocation of [getResumedInstance],
 * and new connections are created only when calling [getResumedInstance] if the old connection was dead.
 */
// may be replaced with a better name.
internal abstract class AbstractKeepAliveNetworkHandlerSelector<H : NetworkHandler>(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
) : NetworkHandlerSelector<H> {

    init {
        require(maxAttempts >= 1) { "maxAttempts must >= 1" }
    }

    private val current = atomic<H?>(null)

    @TestOnly
    internal fun setCurrent(h: H) {
        current.value = h
    }

    protected abstract fun createInstance(): H

    final override fun getResumedInstance(): H? = current.value

    final override suspend fun awaitResumeInstance(): H = awaitResumeInstanceImpl(0)

    private tailrec suspend fun awaitResumeInstanceImpl(attempted: Int): H {
        if (attempted >= maxAttempts) error("Failed to resume instance. Maximum attempts reached.")
        yield()
        val current = getResumedInstance()
        return if (current != null) {
            when (val thisState = current.state) {
                NetworkHandler.State.CLOSED -> {
                    this.current.compareAndSet(current, null) // invalidate the instance and try again.
                    awaitResumeInstanceImpl(attempted + 1) // will create new instance.
                }
                NetworkHandler.State.CONNECTING,
                NetworkHandler.State.INITIALIZED -> {
                    current.resumeConnection() // once finished, it should has been LOADING or OK
                    check(current.state != thisState) { "State is still $thisState after successful resumeConnection." }
                    return awaitResumeInstanceImpl(attempted) // does not count for an attempt.
                }
                NetworkHandler.State.LOADING -> {
                    return current
                }
                NetworkHandler.State.OK -> {
                    current.resumeConnection()
                    return current
                }
            }
        } else {
            synchronized(this) { // avoid concurrent `createInstance()`
                if (getResumedInstance() == null) this.current.compareAndSet(null, createInstance())
            }
            awaitResumeInstanceImpl(attempted) // directly retry, does not count for attempts.
        }
    }

    companion object {
        @JvmField
        var DEFAULT_MAX_ATTEMPTS =
            systemProp("mirai.network.handler.selector.max.attempts", 3)
                .coerceIn(1..Int.MAX_VALUE.toLongUnsigned()).toInt()
    }
}