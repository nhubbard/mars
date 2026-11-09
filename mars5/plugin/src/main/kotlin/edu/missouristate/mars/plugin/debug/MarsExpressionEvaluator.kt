package edu.missouristate.mars.plugin.debug

import com.intellij.lang.ASTNode
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.HI_REGISTER
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile.LO_REGISTER
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary

/** An evaluator belongs to a single suspension and never reads live simulator state. */
internal class MarsExpressionEvaluator(
    private val project: Project,
    machine: MarsContext,
    private val localSymbols: Map<String, Definition>,
    private val globalSymbols: Map<String, Definition>,
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
        if (result == null) {
            callback.invalidExpression(runMessages.getMessage("debug.evaluate.invalid"))
        } else {
            callback.evaluated(RegisterValue(source, result::display))
        }
    }

    private fun evaluateSource(source: String): Scalar? {
        if (source.length > 1024 || source.any { it == '\r' || it == '\n' }) return null
        // An assembly operand uses the same expression syntax. Require the entire input to be
        // exactly one expression, excluding directive lists, repeats and comments.
        val file = PsiFileFactory.getInstance(project).createFileFromText("watch.asm", MipsFileType, ".word $source")
        val operand = SyntaxTraverser.psiTraverser(file).filterTypes { it == operandType }.firstOrNull() ?: return null
        val expression = operand.node.firstChildNode
        if (expression.text != source || PsiTreeUtil.hasErrorElements(file)) return null
        return evaluateNode(expression)
    }

    private fun evaluateNode(node: ASTNode): Scalar? = when (node.elementType) {
        literalType -> {
            val token = Tokenizer().tokenizeLine(1, node.text, ErrorList(), false)[0]
            when {
                TokenTypes.isIntegerTokenType(token.type) -> Word(Binary.stringToInt(token.value))
                TokenTypes.isFloatingTokenType(token.type) -> Real(token.value.toDouble())
                else -> null
            }
        }
        referenceType -> registers[node.text] ?: (localSymbols[node.text] ?: globalSymbols[node.text])?.let { Word(it.address()) }
        groupType -> evaluateNode(node.getChildren(expressionParts).single())
        unaryType -> {
            val (operator, operand) = node.getChildren(expressionParts)
            evaluateNode(operand)?.let { if (operator.elementType == minusType) it.negated() else it }
        }
        addType -> {
            val (left, operator, right) = node.getChildren(expressionParts)
            val leftValue = evaluateNode(left)
            val rightValue = evaluateNode(right)
            if (leftValue == null || rightValue == null) null
            else leftValue + if (operator.elementType == minusType) rightValue.negated() else rightValue
        }
        else -> null
    }

    private sealed interface Scalar {
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
        val operandType = MipsTypes.psi("OPERAND")
        val literalType = MipsTypes.psi("LITERAL_EXPR")
        val referenceType = MipsTypes.psi("REFERENCE_EXPR")
        val groupType = MipsTypes.psi("GROUP_EXPR")
        val unaryType = MipsTypes.psi("UNARY_EXPR")
        val addType = MipsTypes.psi("ADD_EXPR")
        val minusType = MipsTypes.psi("MINUS")
        val expressionParts = TokenSet.create(
            literalType, referenceType, groupType, unaryType, addType, MipsTypes.psi("ADDRESS_EXPR"),
            MipsTypes.psi("PLUS"), minusType,
        )
    }
}
