package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.editor.Document
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.assembler.Tokenizer

/** Names from a build can survive editing one ordinary instruction line. */
internal fun preservesBuiltLabelNames(previous: String, document: Document, offset: Int): Boolean {
    val line = document.getLineNumber(offset)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    val current = document.immutableCharSequence
    // Adding the first newline after a file without a final newline is part of typing the new line.
    val original = if (start > previous.length) "$previous\n" else previous
    if (start > original.length || !current.regionMatches(0, original, 0, start)) return false
    val oldEnd = original.indexOf('\n', start).let { if (it == -1) original.length else it }
    val tailLength = original.length - oldEnd
    if (current.length - end != tailLength || !current.regionMatches(end, original, oldEnd, tailLength)) return false
    val tokenizer = Tokenizer()
    return tokenizer.isInstructionOrBlank(original.substring(start, oldEnd)) &&
        tokenizer.isInstructionOrBlank(current.subSequence(start, end).toString())
}

private fun Tokenizer.isInstructionOrBlank(line: String): Boolean {
    val errors = ErrorList()
    val tokens = tokenizeLine(1, line, errors, false)
    if (errors.errorsOccurred()) return false
    if (tokens.isEmpty || tokens[0].type == TokenTypes.COMMENT) return true
    // An opcode spelling can also be a label, and the include prepass looks anywhere on the line.
    return tokens[0].type == TokenTypes.OPERATOR && (1 until tokens.size()).none {
        tokens[it].type == TokenTypes.COLON || tokens[it].type == TokenTypes.DIRECTIVE
    }
}
