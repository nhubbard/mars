package edu.missouristate.mars.plugin.analysis

import java.util.List.copyOf as copyOfList

/** Stable logical source identity. It does not retain a document, VirtualFile, or PSI tree. */
data class SourceId(val value: String) {
    init { require(value.isNotBlank()) { "Source identity must not be blank" } }
}

data class OccurrenceId(val source: SourceId, val localId: String) {
    init { require(localId.isNotBlank()) { "Occurrence identity must not be blank" } }
}

enum class SymbolKind { LABEL, MACRO, CONSTANT, EXTERNAL }

data class SymbolKey(val name: String, val kind: SymbolKind) {
    init { require(name.isNotBlank()) { "Symbol name must not be blank" } }
}

data class SymbolDeclaration(val id: OccurrenceId, val key: SymbolKey)

/** A symbolic sentinel stays unresolved in the reference index, even when a query finds its target. */
data class SymbolReference(val id: OccurrenceId, val target: SymbolKey)

enum class IndexPartition { DECLARATIONS, REFERENCES }

/** Each producer contributes facts from one file only. An empty contribution means known-empty. */
sealed class IndexContribution(val source: SourceId, val revision: Long) {
    init { require(revision >= 0) { "Source revision must not be negative" } }

    class Declarations(source: SourceId, revision: Long, declarations: List<SymbolDeclaration>) :
        IndexContribution(source, revision) {
        val declarations: List<SymbolDeclaration> = copyOfList(declarations)

        init { validateIds(source, this.declarations.map { it.id }) }
    }

    class References(source: SourceId, revision: Long, references: List<SymbolReference>) :
        IndexContribution(source, revision) {
        val references: List<SymbolReference> = copyOfList(references)

        init { validateIds(source, this.references.map { it.id }) }
    }
}

private fun validateIds(source: SourceId, ids: List<OccurrenceId>) {
    require(ids.all { it.source == source }) { "An index contribution must contain only its own file" }
    require(ids.toSet().size == ids.size) { "Occurrence identities must be unique within a partition" }
}
