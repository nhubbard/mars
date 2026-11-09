package edu.missouristate.mars.plugin.debug

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory
import com.intellij.platform.syntax.psi.createSyntaxGeneratedParserRuntime
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.LO_REGISTER
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLanguage
import edu.missouristate.mars.plugin.language.MipsLexer
import edu.missouristate.mars.plugin.language.MipsSyntaxDefinition
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.syntax.DebuggerExpressionParsing
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.channels.SendChannel

/** An evaluator captures scalar values and delegates memory reads to the owner of its suspension. */
internal class MarsExpressionEvaluator(
    private val project: Project,
    machine: MarsContext,
    private val localSymbols: Map<String, Definition>,
    private val globalSymbols: Map<String, Definition>,
    private val watchRequests: SendChannel<WatchMemoryRequest>,
) : XDebuggerEvaluator() {
    private val registers = buildMap<String, Scalar> {
        val cpu = machine.registerFile
        for (register in cpu.registers) {
            val value = Word(register.valueNoNotify)
            put(register.name, value)
            put("\$${register.number}", value)
        }
        put("pc", Word(cpu.programCounterRegister.valueNoNotify))
        put("hi", Word(cpu.getValueNoNotify(HI_REGISTER)))
        put("lo", Word(cpu.getValueNoNotify(LO_REGISTER)))
        for (register in machine.coprocessor0.registers) {
            put("cp0.\$${register.number}", Word(register.valueNoNotify))
        }
        val fpu = machine.coprocessor1
        for (register in fpu.registers) {
            val bits = register.valueNoNotify
            put(register.name, Real(Float.fromBits(bits).toDouble(), bits))
        }
        put("FCC", Word(fpu.conditionFlagsNoNotify))
    }

    override fun isCodeFragmentEvaluationSupported() = false

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        val source = expression.trim()
        val result = runReadActionBlocking { evaluateSource(source) }
        when (result) {
            null -> callback.invalidExpression(runMessages.getMessage("debug.evaluate.invalid"))
            is Immediate -> callback.evaluated(RegisterValue(source, result.value::display))
            is ReadMemory -> {
                val request = WatchMemoryRequest(
                    inspect = { memory ->
                        result.inspect(memory).fold(
                            { value -> callback.evaluated(RegisterValue(source, value::display)) },
                            { error -> callback.errorOccurred(error.message!!) },
                        )
                    },
                    unavailable = { callback.errorOccurred(runMessages.getMessage("debug.evaluate.memory.unavailable")) },
                )
                if (watchRequests.trySend(request).isFailure) request.unavailable()
            }
        }
    }

    // The debugger calls this while the stopped machine is still owned by its execution coroutine.
    suspend fun evaluateAtBreakpoint(expression: String, memory: MemoryState): Result<Scalar> {
        val evaluation = readAction { evaluateSource(expression.trim()) }
            ?: return invalidExpression()
        return evaluation.resolve(memory)
    }

    private fun evaluateSource(source: String): Evaluation? {
        if (source.length > 1024 || source.any { it == '\r' || it == '\n' }) return null
        val file = PsiFileFactory.getInstance(project).createFileFromText("watch.asm", MipsFileType, "")
        val builder = PsiSyntaxBuilderFactory.getInstance().createBuilder(file.node, MipsLexer(debuggerExpression = true), text = source)
        val runtime = createSyntaxGeneratedParserRuntime(MipsLanguage, builder.getSyntaxTreeBuilder(), DebuggerExpressionParsing)
        MipsSyntaxDefinition().parse(MipsTypes.syntax.getValue("DEBUGGER_EXPRESSION"), runtime)
        val root = builder.getTreeBuilt()
        if (SyntaxTraverser.astTraverser(root).filterTypes { it == TokenType.ERROR_ELEMENT }.firstOrNull() != null) return null
        return evaluateNode(root.firstChildNode)
    }

    private fun evaluateNode(node: ASTNode): Evaluation? = when (node.elementType) {
        literalType -> {
            val token = Tokenizer().tokenizeLine(1, node.text, ErrorList(), false)[0]
            when {
                TokenTypes.isIntegerTokenType(token.type) -> Immediate(Word(Binary.stringToInt(token.value)))
                TokenTypes.isFloatingTokenType(token.type) -> Immediate(Real(token.value.toDouble()))
                else -> null
            }
        }
        referenceType -> (registers[node.text] ?: (localSymbols[node.text] ?: globalSymbols[node.text])?.let { Word(it.address()) })
            ?.let(::Immediate)
        groupType -> evaluateNode(node.getChildren(expressionParts).single())
        unaryType -> {
            val (operator, operand) = node.getChildren(expressionParts)
            evaluateNode(operand)?.let { value ->
                when (operator.elementType) {
                    minusType -> value.map(Scalar::negated)
                    bitNotType -> value.map { (it as? Word)?.let { word -> Word(word.bits.inv()) } }
                    else -> value
                }
            }
        }
        in binaryTypes -> {
            val (left, operator, right) = node.getChildren(expressionParts)
            val leftValue = evaluateNode(left)
            val rightValue = evaluateNode(right)
            if (leftValue == null || rightValue == null) null
            else combine(leftValue, rightValue, binaryOperations.getValue(operator.text))
        }
        // The remaining operand form is the parser's address expression, such as word(buffer+4).
        else -> memoryExpression(node)
    }

    private fun memoryExpression(node: ASTNode): Evaluation? {
        val (operator, address) = node.getChildren(expressionParts)
        val width = WatchMemoryWidth.entries.firstOrNull { it.spelling == operator.text } ?: return null
        val value = evaluateNode(address) ?: return null
        return ReadMemory { memory ->
            val resolved = value.resolve(memory).getOrElse { return@ReadMemory Result.failure(it) }
            val location = resolved as? Word ?: return@ReadMemory invalidMemory()
            width.inspect(memory, location.bits)?.let { Result.success(Word(it)) } ?: invalidMemory()
        }
    }

    private sealed interface Evaluation {
        fun resolve(memory: MemoryState): Result<Scalar> = when (this) {
            is Immediate -> Result.success(value)
            is ReadMemory -> inspect(memory)
        }

        fun map(operation: (Scalar) -> Scalar?): Evaluation? = when (this) {
            is Immediate -> operation(value)?.let(::Immediate)
            is ReadMemory -> ReadMemory { memory ->
                val value = inspect(memory).getOrElse { return@ReadMemory Result.failure(it) }
                expressionValue(operation(value))
            }
        }
    }

    private data class Immediate(val value: Scalar) : Evaluation
    private class ReadMemory(val inspect: (MemoryState) -> Result<Scalar>) : Evaluation

    private fun combine(left: Evaluation, right: Evaluation, operation: (Scalar, Scalar) -> Scalar?): Evaluation? =
        if (left is Immediate && right is Immediate) operation(left.value, right.value)?.let(::Immediate)
        else ReadMemory { memory ->
            val a = left.resolve(memory).getOrElse { return@ReadMemory Result.failure(it) }
            val b = right.resolve(memory).getOrElse { return@ReadMemory Result.failure(it) }
            expressionValue(operation(a, b))
        }

    sealed interface Scalar {
        val number: Double
        fun negated(): Scalar
        fun display(): String

        operator fun plus(other: Scalar): Scalar =
            if (this is Word && other is Word) Word(bits + other.bits) else Real(number + other.number)
    }

    private data class Word(val bits: Int) : Scalar {
        override val number: Double get() = bits.toDouble()
        override fun negated() = Word(-bits)
        override fun display() = Binary.intToHexString(bits)
    }

    private data class Real(override val number: Double, val bits: Int? = null) : Scalar {
        override fun negated() = Real(-number)
        override fun display() = bits?.let { "${Float.fromBits(it)} (${Binary.intToHexString(it)})" } ?: number.toString()
    }

    private companion object {
        val literalType = MipsTypes.psi("LITERAL_EXPR")
        val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        val groupType = MipsTypes.psi("GROUP_EXPR")
        val unaryType = MipsTypes.psi("UNARY_EXPR")
        val minusType = MipsTypes.psi("MINUS")
        val bitNotType = MipsTypes.psi("BIT_NOT")
        val binaryTypes = tokenSet(
            "ADD_EXPR", "EQUALITY_EXPR", "RELATIONAL_EXPR", "BIT_OR_EXPR", "BIT_XOR_EXPR", "BIT_AND_EXPR", "SHIFT_EXPR",
        )
        val expressionParts = TokenSet.orSet(
            binaryTypes,
            tokenSet(
                "LITERAL_EXPR", "REFERENCE_EXPR", "GROUP_EXPR", "UNARY_EXPR", "ADDRESS_EXPR",
                "PLUS", "MINUS", "EQUALITY", "RELATION", "BIT_OR", "BIT_XOR", "BIT_AND", "BIT_NOT", "SHIFT",
            ),
        )
        // Every signed MIPS word is exactly representable as a Double. Mixed comparisons use
        // the decoded floating-point value, including IEEE NaN and signed-zero semantics.
        val binaryOperations = mapOf<String, (Scalar, Scalar) -> Scalar?>(
            "+" to { a, b -> a + b },
            "-" to { a, b -> a + b.negated() },
            "==" to { a, b -> truth(a.number == b.number) },
            "!=" to { a, b -> truth(a.number != b.number) },
            "<" to { a, b -> truth(a.number < b.number) },
            "<=" to { a, b -> truth(a.number <= b.number) },
            ">" to { a, b -> truth(a.number > b.number) },
            ">=" to { a, b -> truth(a.number >= b.number) },
            "|" to wordOperation(Int::or),
            "^" to wordOperation(Int::xor),
            "&" to wordOperation(Int::and),
            "<<" to wordOperation(Int::shl),
            ">>" to wordOperation(Int::shr),
            ">>>" to wordOperation(Int::ushr),
        )

        private fun tokenSet(vararg names: String) = TokenSet.create(*names.map(MipsTypes::psi).toTypedArray())

        private fun wordOperation(operation: (Int, Int) -> Int): (Scalar, Scalar) -> Scalar? = { a, b ->
            if (a is Word && b is Word) Word(operation(a.bits, b.bits)) else null
        }

        private fun expressionValue(value: Scalar?): Result<Scalar> =
            value?.let { Result.success(it) } ?: invalidExpression()

        private fun invalidExpression() = failure("debug.evaluate.invalid")

        private fun invalidMemory() = failure("debug.evaluate.memory.invalid")

        private fun failure(key: String): Result<Scalar> = Result.failure(IllegalArgumentException(runMessages.getMessage(key)))

        fun truth(value: Boolean) = Word(if (value) 1 else 0)
    }
}
