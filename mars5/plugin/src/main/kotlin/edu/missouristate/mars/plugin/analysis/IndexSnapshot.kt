package edu.missouristate.mars.plugin.analysis

import java.util.List.copyOf as copyOfList
import java.util.Map.copyOf as copyOfMap
import java.util.Set.copyOf as copyOfSet

/** Missing facts are distinct from a complete index that contains no matching declaration. */
data class PendingPartition(val source: SourceId, val revision: Long?, val partition: IndexPartition)

class QueryResult<T> internal constructor(matches: List<T>, pending: Set<PendingPartition>) {
    val matches: List<T> = copyOfList(matches)
    val pending: Set<PendingPartition> = copyOfSet(pending)
    val complete: Boolean get() = pending.isEmpty()
}

class SymbolMatches internal constructor(
    val declarations: QueryResult<SymbolDeclaration>,
    val references: QueryResult<SymbolReference>,
) {
    /** A unique syntactic candidate is not proof of build-context semantic resolution. */
    val singleDeclaration: SymbolDeclaration?
        get() = if (declarations.complete) declarations.matches.singleOrNull() else null
}

/** Immutable join input. Building it never asks another index to resolve a sentinel. */
class IndexSnapshot private constructor(
    private val declarations: Map<SymbolKey, List<SymbolDeclaration>>,
    private val references: Map<SymbolKey, List<SymbolReference>>,
    private val revisions: Map<SourceId, Long>,
    private val ready: Set<Pair<SourceId, IndexPartition>>,
) {
    /** Prepare a scope once; symbol lookups then visit matching occurrences rather than project files. */
    fun inScope(sources: Set<SourceId>): SymbolQuery {
        val scope = copyOfSet(sources)
        val missing = IndexPartition.entries.associateWith { partition ->
            scope.filter { (it to partition) !in ready }
                .mapTo(LinkedHashSet()) { PendingPartition(it, revisions[it], partition) }
        }
        return SymbolQuery(scope, declarations, references, missing)
    }

    companion object {
        fun build(
            contributions: List<IndexContribution>,
            checkCancelled: () -> Unit = {},
        ): IndexSnapshot {
            val latest = HashMap<SourceId, Long>()
            val seen = HashSet<Triple<SourceId, Long, IndexPartition>>()
            for (fact in contributions) {
                checkCancelled()
                val partition = when (fact) {
                    is IndexContribution.Declarations -> IndexPartition.DECLARATIONS
                    is IndexContribution.References -> IndexPartition.REFERENCES
                }
                require(seen.add(Triple(fact.source, fact.revision, partition))) {
                    "Duplicate contribution for a source revision and partition"
                }
                latest.merge(fact.source, fact.revision, ::maxOf)
            }
            val declarations = HashMap<SymbolKey, MutableList<SymbolDeclaration>>()
            val references = HashMap<SymbolKey, MutableList<SymbolReference>>()
            val ready = HashSet<Pair<SourceId, IndexPartition>>()
            for (fact in contributions) {
                checkCancelled()
                if (fact.revision != latest.getValue(fact.source)) continue
                when (fact) {
                    is IndexContribution.Declarations -> {
                        ready.add(fact.source to IndexPartition.DECLARATIONS)
                        for (declaration in fact.declarations) {
                            checkCancelled()
                            declarations.getOrPut(declaration.key, ::ArrayList).add(declaration)
                        }
                    }
                    is IndexContribution.References -> {
                        ready.add(fact.source to IndexPartition.REFERENCES)
                        for (reference in fact.references) {
                            checkCancelled()
                            references.getOrPut(reference.target, ::ArrayList).add(reference)
                        }
                    }
                }
            }
            return IndexSnapshot(
                copyOfMap(declarations.mapValues { (_, values) ->
                    copyOfList(values.sortedWith(compareBy({ it.id.source.value }, { it.id.localId })))
                }),
                copyOfMap(references.mapValues { (_, values) ->
                    copyOfList(values.sortedWith(compareBy({ it.id.source.value }, { it.id.localId })))
                }),
                copyOfMap(latest),
                copyOfSet(ready),
            )
        }
    }
}

/** Both directions join on the same symbolic key. Scope/assembler semantics remain explicit consumers. */
class SymbolQuery internal constructor(
    private val scope: Set<SourceId>,
    private val declarations: Map<SymbolKey, List<SymbolDeclaration>>,
    private val references: Map<SymbolKey, List<SymbolReference>>,
    private val missing: Map<IndexPartition, Set<PendingPartition>>,
) {
    fun lookup(key: SymbolKey): SymbolMatches = SymbolMatches(
        QueryResult(
            declarations[key].orEmpty().filter { it.id.source in scope },
            missing.getValue(IndexPartition.DECLARATIONS),
        ),
        QueryResult(
            references[key].orEmpty().filter { it.id.source in scope },
            missing.getValue(IndexPartition.REFERENCES),
        ),
    )
}
