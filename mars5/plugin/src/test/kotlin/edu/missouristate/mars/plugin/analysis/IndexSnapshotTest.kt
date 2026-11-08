package edu.missouristate.mars.plugin.analysis

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class IndexSnapshotTest {
    private val a = SourceId("file:///a.s")
    private val b = SourceId("file:///b.s")
    private val main = SymbolKey("main", SymbolKind.LABEL)
    private fun declaration(source: SourceId, local: String = "definition", key: SymbolKey = main) =
        SymbolDeclaration(OccurrenceId(source, local), key)
    private fun reference(source: SourceId, local: String = "reference", key: SymbolKey = main) =
        SymbolReference(OccurrenceId(source, local), key)
    private fun known(source: SourceId, revision: Long = 1) = listOf(
        IndexContribution.Declarations(source, revision, emptyList()),
        IndexContribution.References(source, revision, emptyList()),
    )

    @Test
    fun `sentinels join in both directions regardless of producer arrival order`() {
        val target = declaration(b)
        val usage = reference(a)
        val facts = listOf(
            IndexContribution.References(a, 1, listOf(usage)),
            IndexContribution.Declarations(a, 1, emptyList()),
            IndexContribution.References(b, 1, emptyList()),
            IndexContribution.Declarations(b, 1, listOf(target)),
        )
        for (ordered in listOf(facts, facts.reversed())) {
            val query = IndexSnapshot.build(ordered).inScope(setOf(a, b))
            assertEquals(target, query.lookup(usage.target).singleDeclaration)
            assertEquals(listOf(usage), query.lookup(target.key).references.matches)
            assertTrue(query.lookup(main).declarations.complete)
            assertTrue(query.lookup(main).references.complete)
        }
        assertEquals(main, usage.target)
    }

    @Test
    fun `missing partitions never masquerade as a missing symbol or a unique target`() {
        val snapshot = IndexSnapshot.build(listOf(IndexContribution.Declarations(a, 1, listOf(declaration(a)))))
        val result = snapshot.inScope(setOf(a, b)).lookup(main)
        assertFalse(result.declarations.complete)
        assertEquals(setOf(PendingPartition(b, null, IndexPartition.DECLARATIONS)), result.declarations.pending)
        assertNull(result.singleDeclaration)
        assertEquals(setOf(
            PendingPartition(a, 1, IndexPartition.REFERENCES),
            PendingPartition(b, null, IndexPartition.REFERENCES),
        ), result.references.pending)
    }

    @Test
    fun `known empty is complete and empty scope needs no indexes`() {
        val complete = IndexSnapshot.build(known(a)).inScope(setOf(a)).lookup(main)
        assertTrue(complete.declarations.complete)
        assertTrue(complete.references.complete)
        assertTrue(complete.declarations.matches.isEmpty())
        assertNull(complete.singleDeclaration)
        val empty = IndexSnapshot.build(emptyList()).inScope(emptySet()).lookup(main)
        assertTrue(empty.declarations.complete)
        assertTrue(empty.references.complete)
    }

    @Test
    fun `newest source revision invalidates older partitions without mixing facts`() {
        val renamed = SymbolKey("renamed", SymbolKind.LABEL)
        val snapshot = IndexSnapshot.build(listOf(
            IndexContribution.Declarations(a, 1, listOf(declaration(a))),
            IndexContribution.References(a, 1, listOf(reference(a))),
            IndexContribution.Declarations(a, 2, listOf(declaration(a, key = renamed))),
        ))
        val query = snapshot.inScope(setOf(a))
        assertTrue(query.lookup(main).declarations.complete)
        assertTrue(query.lookup(main).declarations.matches.isEmpty())
        assertTrue(query.lookup(main).references.matches.isEmpty())
        assertEquals(setOf(PendingPartition(a, 2, IndexPartition.REFERENCES)), query.lookup(main).references.pending)
        assertEquals(renamed, query.lookup(renamed).singleDeclaration?.key)
    }

    @Test
    fun `duplicate candidates stay ambiguous and other source scopes stay separate`() {
        val facts = listOf(
            IndexContribution.Declarations(b, 1, listOf(declaration(b, "z"), declaration(b, "a"))),
            IndexContribution.References(b, 1, listOf(reference(b, "z"), reference(b, "a"))),
            IndexContribution.Declarations(a, 1, listOf(declaration(a))),
            IndexContribution.References(a, 1, listOf(reference(a))),
        )
        val snapshot = IndexSnapshot.build(facts)
        val all = snapshot.inScope(setOf(b, a)).lookup(main)
        assertNull(all.singleDeclaration)
        assertEquals(listOf("definition", "a", "z"), all.declarations.matches.map { it.id.localId })
        assertEquals(listOf("reference", "a", "z"), all.references.matches.map { it.id.localId })
        val local = snapshot.inScope(setOf(a)).lookup(main)
        assertEquals(declaration(a), local.singleDeclaration)
        assertEquals(listOf(reference(a)), local.references.matches)
    }

    @Test
    fun `different symbol kinds never cross join`() {
        for (kind in SymbolKind.entries) {
            val key = SymbolKey("main", kind)
            val snapshot = IndexSnapshot.build(listOf(
                IndexContribution.Declarations(a, 1, listOf(declaration(a, key = key))),
                IndexContribution.References(a, 1, listOf(reference(a, key = key))),
            ))
            val query = snapshot.inScope(setOf(a))
            assertEquals(key, query.lookup(key).singleDeclaration?.key)
            for (other in SymbolKind.entries.filter { it != kind }) {
                val result = query.lookup(SymbolKey("main", other))
                assertTrue(result.declarations.matches.isEmpty())
                assertTrue(result.references.matches.isEmpty())
            }
        }
    }

    @Test
    fun `input mutation cannot change contributions snapshots scopes or query results`() {
        val defs = mutableListOf(declaration(a))
        val refs = mutableListOf(reference(a))
        val facts = mutableListOf<IndexContribution>(
            IndexContribution.Declarations(a, 1, defs),
            IndexContribution.References(a, 1, refs),
        )
        defs.clear()
        refs.clear()
        val snapshot = IndexSnapshot.build(facts)
        facts.clear()
        val scope = mutableSetOf(a)
        val query = snapshot.inScope(scope)
        scope.clear()
        val result = query.lookup(main)
        assertEquals(declaration(a), result.singleDeclaration)
        assertEquals(listOf(reference(a)), result.references.matches)
        assertThrows<UnsupportedOperationException> {
            (result.declarations.matches as MutableList).clear()
        }
        val pending = snapshot.inScope(setOf(b)).lookup(main).declarations.pending
        assertThrows<UnsupportedOperationException> { (pending as MutableSet).clear() }
    }

    @Test
    fun `replacement snapshots remove deleted or renamed facts without changing prior queries`() {
        val old = IndexSnapshot.build(listOf(
            IndexContribution.Declarations(a, 1, listOf(declaration(a))),
            IndexContribution.References(a, 1, listOf(reference(a))),
        )).inScope(setOf(a))
        val removed = IndexSnapshot.build(emptyList()).inScope(setOf(a))
        assertFalse(removed.lookup(main).declarations.complete)
        assertEquals(declaration(a), old.lookup(main).singleDeclaration)
        val replaced = IndexSnapshot.build(known(a, 2)).inScope(setOf(a))
        assertTrue(replaced.lookup(main).declarations.complete)
        assertTrue(replaced.lookup(main).declarations.matches.isEmpty())
    }

    @Test
    fun `project sized facts support repeated keyed queries without unrelated matches`() {
        val sources = (1..2_000).map { SourceId("file:///source$it.s") }
        val facts = sources.flatMapIndexed { i, source ->
            val key = SymbolKey("label$i", SymbolKind.LABEL)
            listOf(
                IndexContribution.Declarations(source, 1, listOf(declaration(source, key = key))),
                IndexContribution.References(source, 1, listOf(reference(source, key = key))),
            )
        }
        val query = IndexSnapshot.build(facts).inScope(sources.toSet())
        for (i in sources.indices) {
            val result = query.lookup(SymbolKey("label$i", SymbolKind.LABEL))
            assertEquals(sources[i], result.singleDeclaration?.id?.source)
            assertEquals(listOf(sources[i]), result.references.matches.map { it.id.source })
        }
    }

    @Test
    fun `invalid producer facts fail before publication`() {
        assertThrows<IllegalArgumentException> { SourceId(" ") }
        assertThrows<IllegalArgumentException> { OccurrenceId(a, "") }
        assertThrows<IllegalArgumentException> { SymbolKey("", SymbolKind.LABEL) }
        assertThrows<IllegalArgumentException> { IndexContribution.Declarations(a, -1, emptyList()) }
        assertThrows<IllegalArgumentException> { IndexContribution.References(a, -1, emptyList()) }
        assertThrows<IllegalArgumentException> { IndexContribution.Declarations(a, 1, listOf(declaration(b))) }
        assertThrows<IllegalArgumentException> { IndexContribution.References(a, 1, listOf(reference(b))) }
        assertThrows<IllegalArgumentException> {
            IndexContribution.Declarations(a, 1, listOf(declaration(a), declaration(a)))
        }
        assertThrows<IllegalArgumentException> {
            IndexContribution.References(a, 1, listOf(reference(a), reference(a)))
        }
        assertThrows<IllegalArgumentException> { IndexSnapshot.build(known(a) + known(a)) }
    }

    @Test
    fun `large joins check cancellation inside occurrence loops`() {
        val facts = listOf(
            IndexContribution.Declarations(a, 1, (1..100).map { declaration(a, "$it") }),
            IndexContribution.References(a, 1, (1..100).map { reference(a, "$it") }),
        )
        for (cancelAt in listOf(1, 25, 150)) {
            var checks = 0
            assertThrows<InterruptedException> {
                IndexSnapshot.build(facts) {
                    if (++checks == cancelAt) throw InterruptedException("cancelled")
                }
            }
            assertEquals(cancelAt, checks)
        }
    }
}
