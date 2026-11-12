package edu.missouristate.mars.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

class EventEmitterTest {
    @Test
    fun `subscription changes during delivery apply to the next emission`() {
        val emitter = EventEmitter<Int>()
        val received = mutableListOf<String>()
        val removed = Consumer<Int> { received += "removed $it" }
        val added = Consumer<Int> { received += "added $it" }
        emitter.subscribe {
            received += "first $it"
            emitter.unsubscribe(removed)
            emitter.subscribe(added)
        }
        emitter.subscribe(removed)

        emitter.emit(1)
        emitter.emit(2)

        assertEquals(listOf("first 1", "removed 1", "first 2", "added 2"), received)
    }
}
