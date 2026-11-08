package edu.missouristate.mars.plugin.language

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.openapi.project.Project
import com.intellij.platform.syntax.psi.lexer.LexerAdapter
import com.intellij.psi.FileViewProvider
import com.intellij.psi.tree.TokenSet

class MipsParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?) = LexerAdapter(MipsLexer(), MipsTypes.converter)
    // The native file element owns parsing, matching the platform's YAML syntax integration.
    override fun createParser(project: Project?): PsiParser =
        throw UnsupportedOperationException("Use the native syntax file element")
    override fun getFileNodeType() = MipsTypes.file
    override fun getCommentTokens() = TokenSet.create(MipsTypes.psi("COMMENT"))
    override fun getStringLiteralElements() = TokenSet.create(MipsTypes.psi("STRING"), MipsTypes.psi("CHARACTER"))
    override fun createElement(node: ASTNode) = ASTWrapperPsiElement(node)
    override fun createFile(viewProvider: FileViewProvider) = MipsFile(viewProvider)
}

class MipsFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, MipsLanguage) {
    override fun getFileType() = MipsFileType
}
