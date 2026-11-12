package edu.missouristate.mars.api

import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/** Thread-safe event delivery. Listener changes apply to subsequent emissions. */
class EventEmitter<T> {
    private val listeners = CopyOnWriteArrayList<Consumer<T>>()

    fun subscribe(listener: Consumer<T>) {
        listeners.add(listener)
    }

    fun unsubscribe(listener: Consumer<T>) {
        listeners.remove(listener)
    }

    fun emit(event: T) {
        listeners.forEach { it.accept(event) }
    }
}
