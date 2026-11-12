package edu.missouristate.mars.api

import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.assembler.TokenTypes
import java.nio.file.Path
import java.util.List.copyOf as copyList
import java.util.Map.copyOf as copyMap
import java.util.Set.copyOf as copySet
import kotlin.jvm.JvmRecord
import kotlin.jvm.JvmSuppressWildcards

/** Emitted instruction origins and the first breakpoint address of each source-line occurrence. */
@JvmSuppressWildcards
class InstructionSourceMap @JvmOverloads constructor(
    origins: Map<Int, Origin>,
    breakpoints: Map<Location, List<Int>>,
    pseudoAddresses: Set<Int> = emptySet(),
) {
    private val origins: Map<Int, Origin> = copyMap(origins)
    private val breakpoints: Map<Location, List<Int>> = copyMap(breakpoints.mapValues { copyList(it.value) })
    private val pseudoAddresses: Set<Int> = copySet(pseudoAddresses)

    fun origins(): Map<Int, Origin> = origins

    fun breakpoints(): Map<Location, List<Int>> = breakpoints

    fun pseudoAddresses(): Set<Int> = pseudoAddresses

    override fun equals(other: Any?): Boolean =
        other is InstructionSourceMap && origins == other.origins && breakpoints == other.breakpoints &&
            pseudoAddresses == other.pseudoAddresses

    override fun hashCode(): Int = 31 * (31 * origins.hashCode() + breakpoints.hashCode()) + pseudoAddresses.hashCode()

    override fun toString(): String =
        "InstructionSourceMap[origins=$origins, breakpoints=$breakpoints, pseudoAddresses=$pseudoAddresses]"

    /** An operand and its written token and macro arguments, from inner to outer invocation. */
    class SourceOperand(
        private val written: SourceToken,
        arguments: List<SourceToken>,
        private val value: String,
        private val type: TokenTypes,
        private val coprocessor0: Boolean,
    ) {
        private val arguments: List<SourceToken> = copyList(arguments)

        fun written(): SourceToken = written

        fun arguments(): List<SourceToken> = arguments

        fun value(): String = value

        fun type(): TokenTypes = type

        fun coprocessor0(): Boolean = coprocessor0

        override fun equals(other: Any?): Boolean =
            other is SourceOperand && written == other.written && arguments == other.arguments &&
                value == other.value && type == other.type && coprocessor0 == other.coprocessor0

        override fun hashCode(): Int {
            var result = written.hashCode()
            result = 31 * result + arguments.hashCode()
            result = 31 * result + value.hashCode()
            result = 31 * result + type.hashCode()
            return 31 * result + coprocessor0.hashCode()
        }

        override fun toString(): String =
            "SourceOperand[written=$written, arguments=$arguments, value=$value, type=$type, coprocessor0=$coprocessor0]"
    }

    /** A one-based physical line in a normalized absolute source path. */
    class Location(file: Path, private val line: Int) {
        private val file = file.toAbsolutePath().normalize()

        init {
            require(line > 0) { "Source lines start at one" }
        }

        fun file(): Path = file

        fun line(): Int = line

        override fun equals(other: Any?): Boolean = other is Location && file == other.file && line == other.line

        override fun hashCode(): Int = 31 * file.hashCode() + line

        override fun toString(): String = "Location[file=$file, line=$line]"
    }

    /** The emitted source statement and any enclosing macro invocation. */
    @JvmRecord
    data class Origin(val location: Location, val caller: Origin?)

    companion object {
        @JvmField val EMPTY = InstructionSourceMap(emptyMap(), emptyMap())
    }
}
