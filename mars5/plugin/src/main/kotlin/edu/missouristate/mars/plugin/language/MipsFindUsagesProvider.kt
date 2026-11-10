package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.DelegateLexer
import com.intellij.platform.syntax.psi.lexer.LexerAdapter
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration

internal class MipsFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): DefaultWordsScanner = object : DefaultWordsScanner(
        UsageLexer(), identifiers, comments, literals, TokenSet.EMPTY, wholeWords,
    ) {
        override fun getVersion() = 5
    }

    override fun canFindUsagesFor(psiElement: PsiElement) = (psiElement as? MipsDeclaration)?.name != null

    override fun getHelpId(psiElement: PsiElement) = HelpID.FIND_OTHER_USAGES

    override fun getType(element: PsiElement): String {
        val key = when ((element as? MipsDeclaration)?.kind) {
            SymbolKind.LABEL -> "usage.type.label"
            SymbolKind.MACRO -> "usage.type.macro"
            SymbolKind.CONSTANT -> "usage.type.constant"
            SymbolKind.EXTERNAL -> "usage.type.external"
            null -> return ""
        }
        return bundle.getMessage(key)
    }

    override fun getDescriptiveName(element: PsiElement) = (element as? MipsDeclaration)?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)

    // IntelliJ's normal word splitting drops punctuation. Preserve valid MARS names like ".".
    private class UsageLexer : DelegateLexer(LexerAdapter(MipsLexer(), MipsTypes.converter)) {
        override fun getTokenType(): IElementType? {
            val type = super.getTokenType()
            if (type != word || bufferSequence[tokenStart] != '.') return type
            for (offset in tokenStart + 1 until tokenEnd) {
                if (bufferSequence[offset] != '.') return type
            }
            return DotIdentifier
        }
    }

    private object DotIdentifier : IElementType("MIPS_DOT_IDENTIFIER", MipsLanguage)

    private companion object {
        val bundle = DynamicBundle(MipsFindUsagesProvider::class.java, "messages.MarsBundle")
        val word = MipsTypes.psi("WORD")
        val identifiers = TokenSet.create(word, MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"))
        val wholeWords = TokenSet.create(DotIdentifier)
        val comments = TokenSet.create(MipsTypes.psi("COMMENT"))
        val literals = TokenSet.create(
            MipsTypes.psi("STRING"), MipsTypes.psi("CHARACTER"),
            MipsTypes.psi("UNCLOSED_STRING"), MipsTypes.psi("UNCLOSED_CHARACTER"),
        )
    }
}
