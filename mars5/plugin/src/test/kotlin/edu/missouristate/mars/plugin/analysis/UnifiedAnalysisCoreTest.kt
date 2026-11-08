package edu.missouristate.mars.plugin.analysis

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class UnifiedAnalysisCoreTest {
    @Test
    fun `latest request alone can publish and old readers retain a coherent snapshot`() {
        val core = UnifiedAnalysisCore()
        val first = core.beginUpdate()
        val original = core.snapshot
        val a = IndexSnapshot.build(emptyList())
        assertTrue(core.publish(first, a))
        assertSame(a, core.snapshot.index)
        assertFalse(core.publish(first, IndexSnapshot.build(emptyList())))
        assertEquals(1, core.snapshot.generation)
        val second = core.beginUpdate()
        assertFalse(core.publish(first, IndexSnapshot.build(emptyList())))
        assertSame(a, core.snapshot.index)
        val b = IndexSnapshot.build(emptyList())
        assertTrue(core.publish(second, b))
        assertEquals(2, core.snapshot.generation)
        assertNotSame(b, original.index)
        assertEquals(0, original.generation)
        core.close()
    }

    @Test
    fun `tickets cannot publish into another project core`() {
        val first = UnifiedAnalysisCore()
        val second = UnifiedAnalysisCore()
        val ticket = first.beginUpdate()
        second.beginUpdate()
        assertFalse(second.publish(ticket, IndexSnapshot.build(emptyList())))
        assertEquals(0, second.snapshot.generation)
        first.close()
        second.close()
    }

    @Test
    fun `close invalidates pending requests releases facts and rejects further work`() {
        val source = SourceId("file:///main.s")
        val core = UnifiedAnalysisCore()
        val index = IndexSnapshot.build(listOf(IndexContribution.Declarations(source, 1, emptyList())))
        val ticket = core.beginUpdate()
        assertTrue(core.publish(ticket, index))
        val reader = core.snapshot
        core.close()
        core.close()
        assertFalse(core.publish(ticket, index))
        assertThrows<IllegalStateException> { core.beginUpdate() }
        val key = SymbolKey("main", SymbolKind.LABEL)
        assertFalse(core.snapshot.index.inScope(setOf(source)).lookup(key).declarations.complete)
        assertTrue(reader.index.inScope(setOf(source)).lookup(key).declarations.complete)
    }
}
