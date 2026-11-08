package edu.missouristate.mars.plugin.language

import com.intellij.platform.syntax.SyntaxElementType
import com.intellij.platform.syntax.parser.SyntaxTreeBuilder
import com.intellij.platform.syntax.psi.createSyntaxGeneratedParserRuntime
import com.intellij.platform.syntax.syntaxElementTypeSetOf
import com.intellij.platform.syntax.util.runtime.GrammarKitLanguageDefinition
import com.intellij.platform.syntax.util.runtime.SyntaxGeneratedParserRuntime
import edu.missouristate.mars.plugin.language.parser.MipsParser

class MipsSyntaxDefinition : GrammarKitLanguageDefinition {
    override val comments = syntaxElementTypeSetOf(MipsTypes.syntax.getValue("COMMENT"))
    override fun createLexer() = MipsLexer()
    override fun parse(elementType: SyntaxElementType, runtime: SyntaxGeneratedParserRuntime) =
        MipsParser.parse(elementType, runtime)
    override fun parse(builder: SyntaxTreeBuilder) =
        parse(MipsTypes.fileSyntax, createSyntaxGeneratedParserRuntime(MipsLanguage, builder))
}
