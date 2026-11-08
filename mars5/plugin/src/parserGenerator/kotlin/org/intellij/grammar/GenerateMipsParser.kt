package org.intellij.grammar

import com.intellij.lang.LanguageASTFactory
import com.intellij.lang.LanguageBraceMatching
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.intellij.grammar.generator.KotlinParserGenerator
import org.intellij.grammar.generator.OutputOpener
import org.intellij.grammar.psi.BnfFile
import java.io.File

/**
 * Build-only entry point: Grammar-Kit 2023.3.4's CLI always selects its Java generator.
 * This package is required to access its package-private AST bootstrap factory.
 */
fun main(arguments: Array<String>) {
    LightPsi.init()
    LightPsi.Init.addKeyedExtension(LanguageASTFactory.INSTANCE, BnfLanguage.INSTANCE, BnfASTFactory(), null)
    LightPsi.Init.addKeyedExtension(LanguageBraceMatching.INSTANCE, BnfLanguage.INSTANCE, BnfBraceMatcher(), null)
    val source = File(arguments[1])
    val grammar = LightPsi.parseFile(source, BnfParserDefinition()) as BnfFile
    val errors = PsiTreeUtil.findChildrenOfType(grammar, PsiErrorElement::class.java)
    check(errors.isEmpty()) { errors.joinToString { it.errorDescription + " at " + it.textOffset } }
    KotlinParserGenerator(grammar, source.parent, arguments[0], "", "", OutputOpener.DEFAULT).generate()
}
