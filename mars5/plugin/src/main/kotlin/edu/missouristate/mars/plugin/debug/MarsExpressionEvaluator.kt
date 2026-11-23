package edu.missouristate.mars.plugin.debug

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory
import com.intellij.platform.syntax.psi.createSyntaxGeneratedParserRuntime
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.elementType
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.ExpressionInfo
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.SourceOperand
import edu.missouristate.mars.assembler.RegisterNames
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.Coprocessor1State.Companion.CONDITION_FLAG_MASK
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.Companion.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.Companion.LO_REGISTER
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.VectorRegister
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLanguage
import edu.missouristate.mars.plugin.language.MipsLexer
import edu.missouristate.mars.plugin.language.MipsSyntaxDefinition
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.coprocessor0Operand
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.operationOnLine
import edu.missouristate.mars.plugin.language.syntax.DebuggerExpressionParsing
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.api.MarsTarget
import kotlinx.coroutines.channels.SendChannel

/** An evaluator captures register values and delegates memory reads to the owner of its suspension. */
internal class MarsExpressionEvaluator(
    private val project: Project,
    machine: MarsContext,
    private val localSymbols: Map<String, Definition>,
    private val globalSymbols: Map<String, Definition>,
    private val loadedSymbols: LoadedSymbols,
    private val watchRequests: SendChannel<WatchMemoryRequest>,
) : XDebuggerEvaluator() {
    private val wide = machine.target == MarsTarget.MARS64
    /** Immutable assembly metadata for this stopped snapshot; no live machine access is needed by consumers. */
    val instructionSources: InstructionSourceMap = machine.program!!.sourceMap()
    val instructionWord: Int?
    private val expandedOperands: List<SourceOperand>
    init {
        val statement = machine.memory.sourceStatement(machine.registerFile.programCounterBits)
        instructionWord = statement?.binaryStatement
        expandedOperands = statement?.sourceOperands.orEmpty()
    }
    private val registers = buildMap<String, Scalar> {
        val cpu = machine.registerFile
        for (register in cpu.registers) {
            put(register.name, if (wide) Doubleword(cpu.getLongValueNoNotify(register.number)) else Word(register.valueNoNotify))
        }
        put("pc", addressValue(cpu.programCounterBits))
        put("hi", Word(cpu.getValueNoNotify(HI_REGISTER)))
        put("lo", Word(cpu.getValueNoNotify(LO_REGISTER)))
        for (register in machine.coprocessor0.registers) {
            put($$"cp0.$$${register.number}", Word(register.valueNoNotify))
        }
        val fpu = machine.coprocessor1
        for (register in fpu.registers) {
            val bits = register.valueNoNotify
            put(register.name, Real(Float.fromBits(bits).toDouble(), bits))
        }
        put("FCC", Word(fpu.conditionFlagsNoNotify and CONDITION_FLAG_MASK))
    }
    private val vectors = if (wide) List(32) { machine.coprocessor1.getVectorNoNotify(it) } else emptyList()

    /** Reads the same stopped snapshot used by the native debugger, without evaluating source or memory. */
    fun integerRegisterBits(name: String): Long = registers.getValue(name).integerBits
        ?: error("Not an integer register: $name")

    /** The same immutable 128-bit value displayed by this frame; absent on a scalar-only target. */
    fun vectorRegisterBits(name: String): VectorRegister? = RegisterNames.vector(name)?.let(vectors::getOrNull)

    /** Native Variables enumerates the captured bank's known indices on a vector-capable target. */
    fun vectorRegisterBits(number: Int): VectorRegister = vectors[number]

    override fun isCodeFragmentEvaluationSupported() = false

    override fun getExpressionInfoAtOffsetAsync(project: Project, document: Document, offset: Int, sideEffectsAllowed: Boolean) =
        ReadAction.nonBlocking<ExpressionInfo?> { expressionAt(project, document, offset) }
            .withDocumentsCommitted(project)
            .submit(AppExecutorUtil.getAppExecutorService())

    private fun expressionAt(project: Project, document: Document, offset: Int): ExpressionInfo? {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) as? MipsFile ?: return null
        val leaf = file.findElementAt(offset) ?: return null
        val operands = expandedOperandsAt(file, document, leaf.textOffset)
        if (operands.isNotEmpty()) {
            val expression = operands.groupBy { it.written() }.values
                .map { it.singleOrNull()?.let(::expandedExpression) }.distinct().singleOrNull() ?: return null
            return ExpressionInfo(leaf.textRange, expression, leaf.text)
        }
        val parent = leaf.parent
        return when (parent.elementType) {
            literalType -> literalHint(parent)
            unaryType -> parent.lastChild.takeIf { it.elementType == literalType }?.let(::literalHint)
            referenceType -> if (leaf.elementType == registerType) registerHint(leaf) else symbolHint(file, document, leaf)
            labelType -> if (leaf == parent.firstChild) symbolHint(file, document, leaf) else null
            externalNameType -> symbolHint(file, document, leaf, spelling = null)
            externalSignedNameType -> symbolHint(file, document, parent, spelling = null)
            else -> null
        }
    }

    private fun expandedOperandsAt(file: MipsFile, document: Document, offset: Int): List<SourceOperand> {
        if (expandedOperands.isEmpty()) return emptyList()
        val site = sourceSite(file, document, offset) ?: return emptyList()
        return expandedOperands.filter { it.written() == site || site in it.arguments() }
    }

    private fun sourceSite(file: MipsFile, document: Document, offset: Int): SourceToken? {
        val line = document.getLineNumber(offset)
        val location = loadedSymbols.sources.location(file.viewProvider.virtualFile, line) ?: return null
        return SourceToken(location, offset - document.getLineStartOffset(line) + 1)
    }

    private fun expandedExpression(operand: SourceOperand): String? {
        val value = operand.value()
        val type = operand.type()
        return when {
            type in registerTypes -> registerExpression(value, operand.coprocessor0())
            type == TokenTypes.IDENTIFIER -> addressExpression(symbolAddress(value))
            TokenTypes.isIntegerTokenType(type) -> Binary.intToHexString(Binary.stringToInt(value))
            else -> null
        }
    }

    private fun literalHint(literal: PsiElement): ExpressionInfo? {
        val expression = literal.parent.takeIf { it.elementType == unaryType } ?: literal
        return if (evaluateSource(expression.text) is Immediate) ExpressionInfo(expression.textRange) else null
    }

    private fun registerHint(leaf: PsiElement): ExpressionInfo? {
        val name = leaf.text
        val systemOffset = operationOnLine(leaf)?.operationHead?.let(::coprocessor0Operand)
        val expression = registerExpression(name, systemOffset == leaf.textOffset) ?: return null
        return ExpressionInfo(leaf.textRange, expression, name)
    }

    private fun registerExpression(name: String, coprocessor0: Boolean): String? {
        val expression = if (coprocessor0) {
            val register = RegisterNames.integer(name) ?: return null
            $$"cp0.$$${register.number}"
        } else name
        return expression.takeIf { resolveReference(it) != null }
    }

    private fun symbolHint(file: MipsFile, document: Document, element: PsiElement, spelling: String? = element.text): ExpressionInfo? {
        val site = sourceSite(file, document, element.textOffset) ?: return null
        val expression = addressExpression(loadedSymbols.addressAt(site, spelling)) ?: return null
        return ExpressionInfo(element.textRange, expression, element.text)
    }

    private fun addressExpression(address: Long?): String? = address?.let { addressValue(it).display() }

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) {
        val source = expression.trim()
        val result = runReadActionBlocking { evaluateSource(source) }
        when (result) {
            null -> callback.invalidExpression(runMessages.getMessage("debug.evaluate.invalid"))
            is Immediate -> callback.evaluated(RegisterValue(source, format = result.value::display))
            is ReadMemory -> {
                val request = WatchMemoryRequest(
                    inspect = { memory ->
                        result.inspect(memory).fold(
                            { value -> callback.evaluated(RegisterValue(source, format = value::display)) },
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
    suspend fun evaluateStopped(expression: String, memory: MemoryState): Result<Scalar> {
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
        literalType -> if (node.firstChildNode.elementType == characterType) {
            Immediate(Word(Tokenizer.decodeCharacterLiteral(node.text)))
        } else {
            // The expression lexer already determined the numeric boundary, including hexadecimal exponents.
            val value = node.text
            val type = TokenTypes.matchTokenType(value, wordCatalog)
            val bits = if (wide) runCatching { Binary.stringToLong(value) }.getOrNull() else null
            when {
                bits != null -> Immediate(Doubleword(bits))
                TokenTypes.isIntegerTokenType(type) -> Immediate(Word(Binary.stringToInt(value)))
                TokenTypes.isFloatingTokenType(type) -> Immediate(Real(value.toDouble()))
                else -> null
            }
        }
        referenceType -> resolveReference(node.text)?.let(::Immediate)
        groupType -> evaluateNode(node.getChildren(expressionParts).single())
        unaryType -> {
            val (operator, operand) = node.getChildren(expressionParts)
            val value = evaluateNode(operand)
            when {
                value == null -> signedWord(operator, operand)
                operator.elementType == minusType -> value.map(Scalar::negated)
                operator.elementType == bitNotType -> value.map {
                    when (it) {
                        is Word -> Word(it.bits.inv())
                        is Doubleword -> Doubleword(it.bits.inv())
                        is Real -> null
                    }
                }
                else -> value
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

    private fun resolveReference(name: String): Scalar? =
        registers[name]
            ?: RegisterNames.integer(name)?.let { registers.getValue(it.name) }
            ?: symbolAddress(name)?.let(::addressValue)

    private fun symbolAddress(name: String): Long? = (localSymbols[name] ?: globalSymbols[name])?.addressBits

    private fun addressValue(bits: Long): Scalar =
        if (bits == bits.toInt().toLong()) Word(bits.toInt()) else Doubleword(bits)

    private fun signedWord(operator: ASTNode, operand: ASTNode): Immediate? {
        if (operator.elementType != minusType || operand.elementType != literalType ||
            operator.startOffset + operator.textLength != operand.startOffset) return null
        // A zero-padded hexadecimal magnitude can exceed the assembler's unsigned spelling
        // rules while the complete signed word still fits.
        val spelling = operator.text + operand.text
        if (!TokenTypes.isIntegerTokenType(TokenTypes.matchTokenType(spelling, wordCatalog))) return null
        return Immediate(Word(Binary.stringToInt(spelling)))
    }

    private fun memoryExpression(node: ASTNode): Evaluation? {
        val (operator, address) = node.getChildren(expressionParts)
        val width = WatchMemoryWidth.entries.firstOrNull { it.spelling == operator.text } ?: return null
        val value = evaluateNode(address) ?: return null
        return ReadMemory { memory ->
            val resolved = value.resolve(memory).getOrElse { return@ReadMemory Result.failure(it) }
            val bits = resolved.integerBits ?: return@ReadMemory invalidMemory()
            val location = memoryAddress(bits)
            if (location !in 0..memory.highestInspectableAddress) return@ReadMemory invalidMemory()
            width.inspect(memory, location)?.let { Result.success(Word(it)) } ?: invalidMemory()
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
        val integerBits: Long? get() = when (this) {
            is Word -> bits.toLong()
            is Doubleword -> bits
            is Real -> null
        }
        val wordBits: Int? get() = integerBits?.let { bits ->
            bits.toInt().takeIf { bits in Int.MIN_VALUE.toLong()..UInt.MAX_VALUE.toLong() }
        }
        val singleBits: Int get() = (this as? Real)?.bits ?: number.toFloat().toRawBits()
        fun negated(): Scalar
        fun display(): String

        operator fun plus(other: Scalar): Scalar {
            val left = integerBits
            val right = other.integerBits
            return when {
                this is Word && other is Word -> Word(bits + other.bits)
                left != null && right != null -> Doubleword(left + right)
                else -> Real(number + other.number)
            }
        }
    }

    private data class Word(val bits: Int) : Scalar {
        override val number: Double get() = bits.toDouble()
        override fun negated() = Word(-bits)
        override fun display() = Binary.intToHexString(bits)
    }

    private data class Doubleword(val bits: Long) : Scalar {
        override val number: Double get() = bits.toDouble()
        override fun negated() = Doubleword(-bits)
        override fun display() = Binary.longToHexString(bits)
    }

    private data class Real(override val number: Double, val bits: Int? = null) : Scalar {
        override fun negated() = Real(-number)
        override fun display() = bits?.let { "${Float.fromBits(it)} (${Binary.intToHexString(it)})" } ?: number.toString()
    }

    private companion object {
        val wordCatalog = InstructionDefaults.forTarget(MarsTarget.MARS32)
        val literalType = MipsTypes.psi("LITERAL_EXPR")
        val characterType = MipsTypes.psi("CHARACTER")
        val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        val registerType = MipsTypes.psi("REGISTER")
        val registerTypes = setOf(TokenTypes.REGISTER_NAME, TokenTypes.REGISTER_NUMBER, TokenTypes.FP_REGISTER_NAME)
        val labelType = MipsTypes.psi("LABEL")
        val externalNameType = MipsTypes.psi("EXTERNAL_NAME")
        val externalSignedNameType = MipsTypes.psi("EXTERNAL_SIGNED_NAME")
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
        val binaryOperations = mapOf(
            "+" to { a, b -> a + b },
            "-" to { a, b -> a + b.negated() },
            "==" to comparison({ a, b -> a == b }, { a, b -> a == b }),
            "!=" to comparison({ a, b -> a != b }, { a, b -> a != b }),
            "<" to comparison({ a, b -> a < b }, { a, b -> a < b }),
            "<=" to comparison({ a, b -> a <= b }, { a, b -> a <= b }),
            ">" to comparison({ a, b -> a > b }, { a, b -> a > b }),
            ">=" to comparison({ a, b -> a >= b }, { a, b -> a >= b }),
            "|" to integerOperation(Int::or, Long::or),
            "^" to integerOperation(Int::xor, Long::xor),
            "&" to integerOperation(Int::and, Long::and),
            "<<" to integerOperation(Int::shl) { a, b -> a shl b.toInt() },
            ">>" to integerOperation(Int::shr) { a, b -> a shr b.toInt() },
            ">>>" to integerOperation(Int::ushr) { a, b -> a ushr b.toInt() },
        )

        private fun tokenSet(vararg names: String) = TokenSet.create(*names.map(MipsTypes::psi).toTypedArray())

        private fun integerOperation(word: (Int, Int) -> Int, doubleword: (Long, Long) -> Long): (Scalar, Scalar) -> Scalar? = { a, b ->
            val left = a.integerBits
            val right = b.integerBits
            when {
                a is Word && b is Word -> Word(word(a.bits, b.bits))
                left != null && right != null -> Doubleword(doubleword(left, right))
                else -> null
            }
        }

        private fun comparison(integer: (Long, Long) -> Boolean, real: (Double, Double) -> Boolean): (Scalar, Scalar) -> Scalar = { a, b ->
            val left = a.integerBits
            val right = b.integerBits
            truth(if (left != null && right != null) integer(left, right) else real(a.number, b.number))
        }

        private fun expressionValue(value: Scalar?): Result<Scalar> =
            value?.let { Result.success(it) } ?: invalidExpression()

        private fun invalidExpression() = failure("debug.evaluate.invalid")

        private fun invalidMemory() = failure("debug.evaluate.memory.invalid")

        private fun failure(key: String): Result<Scalar> = Result.failure(IllegalArgumentException(runMessages.getMessage(key)))

        fun truth(value: Boolean) = Word(if (value) 1 else 0)
    }
}
