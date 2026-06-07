package io.github.gabrielhuff.ohttp.cronet.internal

import java.util.ArrayDeque
import java.util.concurrent.Executor

/**
 * Executor that delivers submitted tasks in FIFO order, one at a time, on top
 * of a (potentially multi-threaded) [delegate]. We need this for the relay
 * UrlRequest's callback executor so that `onReadCompleted` and `onSucceeded`
 * cannot run in parallel — the relay callback writes response bytes into a
 * shared sink that the decapsulation step then reads.
 */
internal class SerialExecutor(private val delegate: Executor) : Executor {

    private val pending = ArrayDeque<Runnable>()
    private var active: Runnable? = null

    @Synchronized
    override fun execute(command: Runnable) {
        pending.addLast(Runnable {
            try {
                command.run()
            } finally {
                scheduleNext()
            }
        })
        if (active == null) scheduleNext()
    }

    @Synchronized
    private fun scheduleNext() {
        val next = pending.pollFirst()
        active = next
        if (next != null) delegate.execute(next)
    }
}
