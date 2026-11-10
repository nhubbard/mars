package edu.missouristate.mars.plugin.language.psi

import com.intellij.DynamicBundle
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.assembler.Tokenizer
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots
import edu.missouristate.mars.plugin.analysis.mipsSourceTimeline
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsLexer
import edu.missouristate.mars.plugin.language.parser.MipsSyntaxTypes

private val bundle = DynamicBundle(MipsMacroFormal::class.java, "messages.MarsBundle")
private val formalTokens = setOf(MipsSyntaxTypes.PARAMETER, MipsSyntaxTypes.DOLLAR_WORD)

internal fun formalRenameError(key: String) = IncorrectOperationException(bundle.getMessage(key))

internal fun isMacroFormalName(name: String): Boolean {
    val lexer = MipsLexer()
    lexer.start(name, 0, name.length, 0)
    return lexer.getTokenType() in formalTokens && lexer.getTokenEnd() == name.length
}

internal fun macroFormalNameElement(project: Project, name: String): PsiElement {
    if (!isMacroFormalName(name)) throw formalRenameError("macro.rename.invalid.name")
    val file = PsiFileFactory.getInstance(project).createFileFromText("formal.asm", MipsFileType, ".macro emit ($name)\n.end_macro\n")
    return PsiTreeUtil.findChildOfType(file, MipsMacroFormal::class.java)!!.nameIdentifier
}

/** Confirms that a rename changes the spelling of one binding in the assembler's prepass tokens. */
internal fun MipsMacroFormal.checkedRenameSites(newName: String): Set<PsiElement> {
    if (!isMacroFormalName(newName)) throw formalRenameError("macro.rename.invalid.name")
    val file = containingFile as MipsFile
    val root = mipsAssemblyRoots(file).singleOrNull() ?: throw formalRenameError("macro.rename.incomplete.scope")
    val entry = PsiManager.getInstance(project).findFile(root) as MipsFile
    val timeline = mipsSourceTimeline(entry)
    val macro = timeline.localMacros(file)[parent.parent] ?: throw formalRenameError("macro.rename.incomplete.scope")
    if (timeline.hasIncompleteIncludes || !timeline.hasClosed(macro)) throw formalRenameError("macro.rename.incomplete.scope")
    val oldName = name
    if (macro.parametersByName[oldName]?.offset != textOffset ||
        macro.parameters.count { it.offset == textOffset || it.value == oldName } != 1
    ) throw formalRenameError("macro.rename.ambiguous.formal")
    if (newName != oldName && (newName in macro.parametersByName ||
            timeline.prefix(file, macro.directiveOffset).equivalence(newName) != null)
    ) throw formalRenameError("macro.rename.conflict")

    return buildSet {
        for (source in timeline.sourceFiles()) {
            val document = source.fileDocument
            val includeLines = mipsIncludeSites(source).paths.keys.mapTo(HashSet(), document::getLineNumber)
            val contents = document.immutableCharSequence
            val exclusive by lazy { mipsAssemblyRoots(source).singleOrNull() == root }
            for (line in 0 until document.lineCount) {
                ProgressManager.checkCanceled()
                val start = document.getLineStartOffset(line)
                if (line in includeLines || timeline.macroAt(source, start) !== macro) continue
                val written = contents.subSequence(start, document.getLineEndOffset(line)).toString()
                val prefix = timeline.prefix(source, start)
                val errors = ErrorList()
                val expanded = Tokenizer.tokenizeMappedLine(
                    written, errors, { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
                )
                if (errors.errorsOccurred()) throw formalRenameError("macro.rename.incomplete.scope")
                val tokens = expanded.tokens()
                val lineTokens = (0 until tokens.size()).map(tokens::get)
                if (newName != oldName && lineTokens.any { it.value == newName }) throw formalRenameError("macro.rename.conflict")
                val references = lineTokens.filter { it.value == oldName }
                if (references.isEmpty()) continue
                if (!exclusive) throw formalRenameError("macro.rename.incomplete.scope")
                if (expanded.expanded().definition() != null) throw formalRenameError("macro.rename.indirect.use")
                val positions = references.map { token ->
                    val column = token.originalStartPos - 1
                    val leaf = source.findElementAt(start + column)!!
                    if (leaf.text != oldName || leaf.references.none { it.isReferenceTo(this@checkedRenameSites) }) {
                        throw formalRenameError("macro.rename.indirect.use")
                    }
                    add(leaf)
                    column
                }
                val changed = StringBuilder(written)
                for (column in positions.asReversed()) changed.replace(column, column + oldName.length, newName)
                val afterErrors = ErrorList()
                val after = Tokenizer.tokenizeWithEquivalences(
                    changed.toString(), afterErrors, { prefix.equivalence(it)?.replacement }, ProgressManager::checkCanceled,
                )
                val expected = lineTokens.map { if (it.value == oldName) newName else it.value }
                if (afterErrors.errorsOccurred() || (0 until after.size()).map { after[it].value } != expected) {
                    throw formalRenameError("macro.rename.conflict")
                }
            }
        }
    }
}
