package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import com.intellij.util.text.CharArrayUtil
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.analysis.sourceLineStart

internal class MipsStatementTemplateContext : TemplateContextType(bundle.getMessage("template.context.statement")) {
    override fun isInContext(context: TemplateActionContext): Boolean = context.isMipsStatement()
}

internal class MipsTopLevelTemplateContext : TemplateContextType(bundle.getMessage("template.context.topLevel")) {
    override fun isInContext(context: TemplateActionContext): Boolean {
        if (!context.isMipsStatement()) return false
        val atCaret = context.file.findElementAt(context.startOffset) ?: context.file.findElementAt(context.startOffset - 1)
        return PsiTreeUtil.findFirstParent(atCaret, true) { it.elementType == macroDefinition } == null
    }
}

private val bundle = DynamicBundle(MipsStatementTemplateContext::class.java, "messages.MarsBundle")
private val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")

private fun TemplateActionContext.isMipsStatement(): Boolean {
    if (file !is MipsFile || isSurrounding) return false
    val source = file.viewProvider.contents
    val start = sourceLineStart(source, startOffset)
    val first = CharArrayUtil.shiftForward(source, start, " \t")
    val end = CharArrayUtil.shiftForwardUntil(source, first, "\r\n")
    val statement = source.subSequence(first, end).toString().trimEnd(' ', '\t')
    // IntelliJ checks both at the abbreviation start and at the caret after typing it.
    return statement.isEmpty() || TokenTypes.isValidIdentifier(statement)
}
