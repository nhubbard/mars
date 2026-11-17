package edu.missouristate.mars.api

import java.util.concurrent.CopyOnWriteArrayList

/** Thread-safe event delivery. Listener changes apply to subsequent emissions. */
class EventEmitter<T> {
    private val listeners = CopyOnWriteArrayList<(T) -> Unit>()

    fun subscribe(listener: (T) -> Unit) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    internal fun hasListeners(): Boolean = listeners.isNotEmpty()

    fun emit(event: T) {
        listeners.forEach { it(event) }
    }

    /** Take the local snapshot before invoking the register group's listeners. */
    internal fun emit(event: T, group: EventEmitter<T>?) {
        val snapshot = listeners.iterator()
        group?.emit(event)
        for (listener in snapshot) listener(event)
    }
}
