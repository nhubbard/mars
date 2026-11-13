package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_END
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_START
import com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_END
import com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_START
import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiFileRange
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsSourceEvent
import edu.missouristate.mars.plugin.analysis.macroArgumentCount
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import java.util.TreeMap

internal class MipsDocumentationTargetProvider : DocumentationTargetProvider, LookupElementDocumentationTargetProvider {
    override fun documentationTargets(file: PsiFile, offset: Int): List<DocumentationTarget> {
        if (file !is MipsFile) return emptyList()
        val adjusted = TargetElementUtil.adjustOffset(file, file.fileDocument, offset)
        val token = file.findElementAt(adjusted) ?: return emptyList()
        return documentationAt(file, token)
    }

    private fun documentationAt(file: MipsFile, token: PsiElement): List<DocumentationTarget> {
        val owner = token.parent
        val ownerType = PsiUtilCore.getElementType(owner)
        val candidate = operationOwner(token)
        if (candidate != null) {
            val analysis = file.project.service<MarsAnalysisService>()
            val site = analysis.operationSite(file, candidate.operationHead)?.takeIf { it.range.startOffset == token.textOffset }
            val definition = site?.macroDefinition
            val calls = analysis.assembledMacroDefinitionsAt(file, token, hasLiveDefinition = definition != null)
            if (calls.isNotEmpty()) {
                return calls.distinctBy { (call) -> Triple(call.definition(), call.name(), call.parameters()) }.map { (call, source, offset) ->
                    MacroDocumentation(token, source, offset, call.name(), call.parameters(), expanded = true)
                }.sortedWith(compareBy({ it.source.viewProvider.virtualFile.path }, { it.nameOffset }, { it.signature }))
            }
            if (site == null) return emptyList()
            if (site.type == TokenTypes.IDENTIFIER) {
                if (definition == null) return emptyList()
                val source = definition.header.containingFile.originalFile
                return listOf(MacroDocumentation(
                    token, source, definition.nameOffset,
                    definition.name, definition.parameters.map { it.value }, expanded = source !is MipsFile,
                ))
            }
            // Includes are expanded before equivalences, so a generated .include cannot open a file.
            val operation = site.name
            if (Directives.INCLUDE.name.equals(operation, ignoreCase = true)) return emptyList()
            return listOfNotNull(MipsDocumentationCatalog[operation])
        }
        val keyword = when (PsiUtilCore.getElementType(token)) {
            includeType -> ownerType in includeOwners
            macroEndType -> ownerType in macroEndOwners
            else -> ownerType in headOwners && owner.firstChild == token
        }
        return if (keyword) listOfNotNull(MipsDocumentationCatalog[token.text]) else emptyList()
    }

    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? =
        when (val item = element.`object`) {
            is MipsCatalogEntry -> item
            is MipsMacroLookup -> macroLookupDocumentation(psiFile, offset, item.name)
            else -> element.getUserData(mipsStatementAliasSite)?.let { statementAliasLookupDocumentation(it, element.lookupString) }
        }

    private fun macroLookupDocumentation(file: PsiFile?, offset: Int, name: String): DocumentationTarget? {
        if (file !is MipsFile) return null
        val definitions = file.project.service<MarsAnalysisService>().macroCompletionDefinitions(file, offset, name)
        if (definitions.isEmpty()) return null
        val site = SmartPointerManager.getInstance(file.project).createSmartPsiFileRangePointer(file, TextRange.from(offset, 0))
        return MacroLookupDocumentation(name, definitions, Pointer {
            val start = site.psiRange?.startOffset ?: return@Pointer null
            macroLookupDocumentation(site.element, start, name)
        })
    }

    private fun statementAliasLookupDocumentation(site: SmartPsiFileRange, name: String): DocumentationTarget? {
        val file = site.containingFile as? MipsFile ?: return null
        val range = site.psiRange ?: return null
        val pointer = Pointer<DocumentationTarget> { statementAliasLookupDocumentation(site, name) }
        val options = file.project.service<MarsAssemblySettings>().executionOptions
        return file.project.service<MarsAnalysisService>().resolveStatementAliasCompletion(file, TextRange.create(range), name) { tokens, end, visible ->
            val operation = tokens[0]
            if (operation.type == TokenTypes.IDENTIFIER) {
                val overloads = visible.macroOverloads(operation.value)
                if (!matchesMacroCall(tokens, end, overloads.keys)) return@resolveStatementAliasCompletion null
                val exact = overloads[tokens.macroArgumentCount()]
                val definitions = if (exact == null) overloads.filterKeys { matchesMacroCall(tokens, end, setOf(it)) }.values else listOf(exact)
                MacroLookupDocumentation(name, definitions, pointer)
            } else if (matchesCatalogAlias(tokens, end, options)) {
                CatalogAliasDocumentation(MipsDocumentationCatalog[operation.value], pointer)
            } else null
        }
    }

    private class CatalogAliasDocumentation(
        private val entry: MipsCatalogEntry,
        private val pointer: Pointer<DocumentationTarget>,
    ) : DocumentationTarget {
        override fun createPointer(): Pointer<DocumentationTarget> = pointer

        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(entry.name).presentation()

        override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(entry.documentationHtml())
    }

    private class MacroLookupDocumentation(
        private val name: String,
        private val definitions: Collection<MipsSourceEvent.Begin>,
        private val pointer: Pointer<DocumentationTarget>,
    ) : DocumentationTarget {
        override fun createPointer(): Pointer<DocumentationTarget> = pointer

        override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name).presentation()

        override val navigatable: OpenFileDescriptor?
            get() = definitions.singleOrNull()?.let {
                val source = it.header.containingFile.originalFile
                OpenFileDescriptor(source.project, source.viewProvider.virtualFile, it.nameOffset)
            }

        override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(buildString {
            for ((header, name, offset, _, parameters) in definitions) {
                val source = header.containingFile.originalFile
                val signature = parameters.joinToString(prefix = "$name(", postfix = ")") { it.value }
                appendMacroDocumentation(source, offset, signature)
            }
        })
    }

    private inner class MacroDocumentation(
        private val call: PsiElement,
        val source: PsiFile,
        val nameOffset: Int,
        private val name: String,
        parameters: List<String>,
        private val expanded: Boolean,
    ) : DocumentationTarget {
        val signature = parameters.joinToString(prefix = "$name(", postfix = ")")
        private val anchor get() = TextRange.from(nameOffset, 0)

        override fun createPointer(): Pointer<MacroDocumentation> {
            val pointer = SmartPointerManager.createPointer(call)
            val definition = if (expanded) {
                SmartPointerManager.getInstance(source.project).createSmartPsiFileRangePointer(source, anchor)
            } else null
            return Pointer {
                val token = pointer.element ?: return@Pointer null
                val candidates = documentationAt(token.containingFile as MipsFile, token).filterIsInstance<MacroDocumentation>()
                if (definition == null) candidates.singleOrNull()
                else candidates.firstOrNull {
                    it.source.viewProvider.virtualFile == definition.virtualFile && it.anchor == definition.range && it.signature == signature
                }
            }
        }

        override fun computePresentation(): TargetPresentation {
            val location = if (expanded) "${source.name}:${source.fileDocument.getLineNumber(nameOffset) + 1}" else source.name
            return TargetPresentation.builder(if (expanded) signature else name).locationText(location).presentation()
        }

        override val navigatable: OpenFileDescriptor
            get() = OpenFileDescriptor(source.project, source.viewProvider.virtualFile, nameOffset)

        override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(buildString {
            appendMacroDocumentation(source, nameOffset, signature)
        })
    }

    private companion object {
        fun StringBuilder.appendMacroDocumentation(source: PsiFile, nameOffset: Int, signature: String) {
            append(DEFINITION_START).append(StringUtil.escapeXmlEntities(signature)).append(DEFINITION_END)
            val document = source.fileDocument
            val end = document.getLineEndOffset(document.getLineNumber(nameOffset))
            val comment = document.getText(TextRange(nameOffset, end)).substringAfter('#', "").trim()
            if (comment.isNotEmpty()) {
                append(CONTENT_START).append(StringUtil.escapeXmlEntities(comment)).append(CONTENT_END)
            }
        }

        val includeType = MipsTypes.psi("INCLUDE")
        val macroEndType = MipsTypes.psi("END_MACRO")
        val includeOwners = TokenSet.create(MipsTypes.psi("INCLUDE_LINE"), MipsTypes.psi("DIRECTIVE_STATEMENT"))
        val macroEndOwners = TokenSet.create(MipsTypes.psi("MACRO_DEFINITION"), MipsTypes.psi("STATEMENT"))
        val headOwners = TokenSet.create(
            MipsTypes.psi("DIRECTIVE_STATEMENT"),
            MipsTypes.psi("EQUIVALENCE"), MipsTypes.psi("MACRO_DEFINITION"), MipsTypes.psi("MACRO_HEADER"),
        )
    }
}

internal data class MipsMacroLookup(val name: String)
internal val mipsStatementAliasSite = Key.create<SmartPsiFileRange>("mars.statement.alias.completion.site")

internal data class MipsCompletionTails(val symbolic: String, val numeric: String)

/** Catalog documentation is immutable and does not retain PSI or editor state. */
internal class MipsCatalogEntry(
    val name: String,
    private val completionTails: MipsCompletionTails,
    private val basicCompletionTails: MipsCompletionTails?,
    private val forms: List<Pair<String, String>>,
) : DocumentationTarget {
    override fun createPointer(): Pointer<MipsCatalogEntry> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name).presentation()

    val searchText: String = forms.joinToString(" ") { (example, description) -> "$example $description" }

    fun completionTail(allowPseudo: Boolean, bareMachine: Boolean): String? {
        val tails = (if (allowPseudo) completionTails else basicCompletionTails) ?: return null
        return if (bareMachine) tails.numeric else tails.symbolic
    }

    fun referenceHtml(): String = buildString {
        for ((example, description) in forms) {
            append(DEFINITION_START).append(StringUtil.escapeXmlEntities(example)).append(DEFINITION_END)
            append(CONTENT_START).append(StringUtil.escapeXmlEntities(description)).append(CONTENT_END)
        }
    }

    fun documentationHtml(): String = referenceHtml() + if (name == "syscall") MipsSyscallDocumentation.html else ""

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(documentationHtml())
}

internal object MipsDocumentationCatalog {
    private val byName by lazy {
        TreeMap<String, MipsCatalogEntry>(String.CASE_INSENSITIVE_ORDER).apply {
            for ((name, instructions) in InstructionDefaults.get().instructionList.groupBy { it.name }) {
                val forms = instructions.map { it.exampleFormat to it.description }.distinct()
                val tails = completionTails(instructions.first())
                val basicTails = instructions.firstOrNull { it is BasicInstruction }?.let(::completionTails)
                put(name, MipsCatalogEntry(name, tails, basicTails, forms))
            }
            for (directive in Directives.getDirectiveList()) {
                val name = directive.name
                val description = directive.description
                val tail = "  $description"
                val tails = MipsCompletionTails(tail, tail)
                put(name, MipsCatalogEntry(name, tails, tails, listOf(name to description)))
            }
        }
    }

    val entries: Collection<MipsCatalogEntry> get() = byName.values

    private fun completionTails(instruction: Instruction) = MipsCompletionTails(
        instruction.exampleFormat.removePrefix(instruction.name),
        renderInstructionExample(instruction, bareMachine = true).text.removePrefix(instruction.name),
    )

    operator fun get(name: String): MipsCatalogEntry = byName.getValue(name)
}
