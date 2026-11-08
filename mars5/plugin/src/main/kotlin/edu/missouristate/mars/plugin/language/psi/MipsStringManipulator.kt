package edu.missouristate.mars.plugin.language.psi

import com.intellij.DynamicBundle
import com.intellij.openapi.util.TextRange
import com.intellij.psi.AbstractElementManipulator
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.language.MipsLexer
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes.STRING

class MipsStringManipulator : AbstractElementManipulator<MipsStringLiteral>() {
    private val bundle = DynamicBundle(MipsStringManipulator::class.java, "messages.MarsBundle")

    override fun getRangeInElement(element: MipsStringLiteral): TextRange = TextRange(1, element.textLength - 1)

    override fun handleContentChange(element: MipsStringLiteral, range: TextRange, newContent: String): MipsStringLiteral {
        val updated = range.replace(element.text, newContent)
        val lexer = MipsLexer()
        lexer.start(updated, 0, updated.length, 0)
        if (lexer.getTokenType() != STRING || lexer.getTokenEnd() != updated.length) {
            throw IncorrectOperationException(bundle.getMessage("include.path.invalid.literal"))
        }
        return element.replaceWithText(updated) as MipsStringLiteral
    }
}
