package edu.missouristate.mars.api

import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import java.nio.file.Path
import java.util.List.copyOf as copyList
import java.util.Map.copyOf as copyMap
import java.util.Set.copyOf as copySet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmSuppressWildcards

/** Final symbol definitions and written references from one assembled image. */
@JvmSuppressWildcards
class AssemblySymbolMap @JvmOverloads constructor(
    locals: Map<Path, Map<String, Definition>>,
    globals: Map<String, Definition>,
    macroCalls: Map<SourceToken, Set<MacroCall>> = emptyMap(),
    symbolUses: Map<SourceToken, Set<SymbolUse>> = emptyMap(),
    macros: Map<Path, List<MacroDefinition>> = emptyMap(),
    equivalences: Map<Path, Set<EquivalenceOccurrence>> = emptyMap(),
) {
    private val locals: Map<Path, Map<String, Definition>> = copyMap(locals.mapValues { copyMap(it.value) })
    private val globals: Map<String, Definition> = copyMap(globals)
    private val macroCalls: Map<SourceToken, Set<MacroCall>> = copyMap(macroCalls.mapValues { copySet(it.value) })
    private val symbolUses: Map<SourceToken, Set<SymbolUse>> = copyMap(symbolUses.mapValues { copySet(it.value) })
    private val macros: Map<Path, List<MacroDefinition>> = copyMap(macros.mapValues { copyList(it.value) })
    private val equivalences: Map<Path, Set<EquivalenceOccurrence>> = copyMap(equivalences.mapValues { copySet(it.value) })

    fun locals(): Map<Path, Map<String, Definition>> = locals

    fun globals(): Map<String, Definition> = globals

    fun macroCalls(): Map<SourceToken, Set<MacroCall>> = macroCalls

    fun symbolUses(): Map<SourceToken, Set<SymbolUse>> = symbolUses

    fun macros(): Map<Path, List<MacroDefinition>> = macros

    fun equivalences(): Map<Path, Set<EquivalenceOccurrence>> = equivalences

    override fun equals(other: Any?): Boolean =
        other is AssemblySymbolMap && locals == other.locals && globals == other.globals &&
            macroCalls == other.macroCalls && symbolUses == other.symbolUses && macros == other.macros &&
            equivalences == other.equivalences

    override fun hashCode(): Int {
        var result = locals.hashCode()
        result = 31 * result + globals.hashCode()
        result = 31 * result + macroCalls.hashCode()
        result = 31 * result + symbolUses.hashCode()
        result = 31 * result + macros.hashCode()
        return 31 * result + equivalences.hashCode()
    }

    override fun toString(): String =
        "AssemblySymbolMap[locals=$locals, globals=$globals, macroCalls=$macroCalls, symbolUses=$symbolUses, " +
            "macros=$macros, equivalences=$equivalences]"

    /** A token at a one-based column in physical source before expansion. */
    @JvmRecord
    data class SourceToken(val location: Location, val column: Int)

    @JvmRecord
    data class EquivalenceOccurrence(
        val site: SourceToken,
        val spelling: SourceToken?,
        val definition: SourceToken?,
        val name: String,
        val declaration: Boolean,
    )

    @JvmRecord
    data class SymbolUse @JvmOverloads constructor(
        val definition: SourceToken,
        val name: String,
        val address: Int,
        val equivalenceSource: SourceToken? = null,
    )

    /** One realized macro call and its one-based argument columns. */
    class MacroCall @JvmOverloads constructor(
        private val definition: SourceToken,
        private val name: String,
        parameters: List<String>,
        argumentColumns: List<Int>,
        private val closingParenthesisColumn: Int?,
        private val spelling: SourceToken? = null,
    ) {
        private val parameters: List<String> = copyList(parameters)
        private val argumentColumns: List<Int> = copyList(argumentColumns)

        fun definition(): SourceToken = definition

        fun name(): String = name

        fun parameters(): List<String> = parameters

        fun argumentColumns(): List<Int> = argumentColumns

        fun closingParenthesisColumn(): Int? = closingParenthesisColumn

        fun spelling(): SourceToken? = spelling

        override fun equals(other: Any?): Boolean =
            other is MacroCall && definition == other.definition && name == other.name &&
                parameters == other.parameters && argumentColumns == other.argumentColumns &&
                closingParenthesisColumn == other.closingParenthesisColumn && spelling == other.spelling

        override fun hashCode(): Int {
            var result = definition.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + parameters.hashCode()
            result = 31 * result + argumentColumns.hashCode()
            result = 31 * result + (closingParenthesisColumn ?: 0)
            return 31 * result + (spelling?.hashCode() ?: 0)
        }

        override fun toString(): String =
            "MacroCall[definition=$definition, name=$name, parameters=$parameters, argumentColumns=$argumentColumns, " +
                "closingParenthesisColumn=$closingParenthesisColumn, spelling=$spelling]"
    }

    /** A committed macro signature, including definitions that were never called. */
    class MacroDefinition(private val site: SourceToken, private val name: String, parameters: List<String>) {
        private val parameters: List<String> = copyList(parameters)

        fun site(): SourceToken = site

        fun name(): String = name

        fun parameters(): List<String> = parameters

        override fun equals(other: Any?): Boolean =
            other is MacroDefinition && site == other.site && name == other.name && parameters == other.parameters

        override fun hashCode(): Int = 31 * (31 * site.hashCode() + name.hashCode()) + parameters.hashCode()

        override fun toString(): String = "MacroDefinition[site=$site, name=$name, parameters=$parameters]"
    }

    /** A symbol's final address and original source position when available. */
    @JvmRecord
    data class Definition @JvmOverloads constructor(
        val address: Int,
        val kind: Kind,
        val origin: Origin?,
        val macroPrivate: Boolean = false,
        val column: Int? = null,
    )

    enum class Kind {
        TEXT,
        DATA,
        EXTERNAL,
    }

    companion object {
        @JvmField val EMPTY = AssemblySymbolMap(emptyMap(), emptyMap())
    }
}
