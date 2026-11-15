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

    fun emit(event: T) {
        listeners.forEach { it(event) }
    }
}
