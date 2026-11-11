package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.lang.HelpID
import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.lexer.DelegateLexer
import com.intellij.platform.syntax.psi.lexer.LexerAdapter
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.TokenSet
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal

internal class MipsFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): DefaultWordsScanner = object : DefaultWordsScanner(
        UsageLexer(), identifiers, comments, literals, TokenSet.EMPTY, wholeWords,
    ) {
        override fun getVersion() = 7
    }

    override fun canFindUsagesFor(psiElement: PsiElement) = when (psiElement) {
        is MipsMacroFormal -> psiElement.effectiveNames().isNotEmpty()
        else -> (psiElement as? MipsDeclaration)?.name != null
    }

    override fun getHelpId(psiElement: PsiElement) = HelpID.FIND_OTHER_USAGES

    override fun getType(element: PsiElement): String {
        val key = when ((element as? MipsDeclaration)?.kind) {
            SymbolKind.LABEL -> "usage.type.label"
            SymbolKind.MACRO -> "usage.type.macro"
            SymbolKind.CONSTANT -> "usage.type.constant"
            SymbolKind.EXTERNAL -> "usage.type.external"
            null -> if (element is MipsMacroFormal) "usage.type.macro.parameter" else return ""
        }
        return bundle.getMessage(key)
    }

    override fun getDescriptiveName(element: PsiElement) = (element as? PsiNamedElement)?.name ?: element.text

    override fun getNodeText(element: PsiElement, useFullName: Boolean) = getDescriptiveName(element)

    // IntelliJ's word search falls back to the whole spelling when it contains no identifier parts.
    private class UsageLexer : DelegateLexer(LexerAdapter(MipsLexer(), MipsTypes.converter)) {
        override fun getTokenType(): IElementType? {
            val type = super.getTokenType()
            if (type == parameter) {
                for (offset in tokenStart + 1 until tokenEnd) {
                    if (Character.isJavaIdentifierPart(bufferSequence[offset])) return type
                }
                return PunctuationIdentifier
            }
            if (type != word || bufferSequence[tokenStart] != '.') return type
            for (offset in tokenStart + 1 until tokenEnd) {
                if (bufferSequence[offset] != '.') return type
            }
            return PunctuationIdentifier
        }
    }

    private object PunctuationIdentifier : IElementType("MIPS_PUNCTUATION_IDENTIFIER", MipsLanguage)

    private companion object {
        val bundle = DynamicBundle(MipsFindUsagesProvider::class.java, "messages.MarsBundle")
        val word = MipsTypes.psi("WORD")
        val parameter = MipsTypes.psi("PARAMETER")
        val identifiers = TokenSet.create(word, parameter, MipsTypes.psi("DOLLAR_WORD"), MipsTypes.psi("MNEMONIC"))
        val wholeWords = TokenSet.create(PunctuationIdentifier)
        val comments = TokenSet.create(MipsTypes.psi("COMMENT"))
        val literals = TokenSet.create(
            MipsTypes.psi("STRING"), MipsTypes.psi("CHARACTER"),
            MipsTypes.psi("UNCLOSED_STRING"), MipsTypes.psi("UNCLOSED_CHARACTER"),
        )
    }
}
