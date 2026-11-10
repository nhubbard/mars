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
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiUtilCore
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
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
            val calls = analysis.assembledMacroCallsAt(file, token, hasLiveDefinition = definition != null)
            if (calls.isNotEmpty()) {
                return calls.distinctBy { Triple(it.definition(), it.name(), it.parameters()) }.mapNotNull { call ->
                    val (source, offset) = analysis.sourceTokenPosition(call.definition()) ?: return@mapNotNull null
                    MacroDocumentation(token, source, offset, call.name(), call.parameters(), expanded = true)
                }.sortedWith(compareBy({ it.source.viewProvider.virtualFile.path }, { it.nameOffset }, { it.signature }))
            }
            if (site == null) return emptyList()
            if (site.type == TokenTypes.IDENTIFIER) {
                if (definition == null) return emptyList()
                val source = definition.header.containingFile.originalFile
                return listOf(MacroDocumentation(
                    token, source, definition.nameOffset,
                    definition.name, definition.parameters.map { it.value }, expanded = source is PsiPlainTextFile,
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
        element.`object` as? MipsCatalogEntry

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
            append(DEFINITION_START).append(StringUtil.escapeXmlEntities(signature)).append(DEFINITION_END)
            val document = source.fileDocument
            val end = document.getLineEndOffset(document.getLineNumber(nameOffset))
            val comment = document.getText(TextRange(nameOffset, end)).substringAfter('#', "").trim()
            if (comment.isNotEmpty()) {
                append(CONTENT_START).append(StringUtil.escapeXmlEntities(comment)).append(CONTENT_END)
            }
        })
    }

    private companion object {
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

/** Catalog documentation is immutable and does not retain PSI or editor state. */
internal class MipsCatalogEntry(
    val name: String,
    val completionTail: String,
    private val forms: List<Pair<String, String>>,
) : DocumentationTarget {
    override fun createPointer(): Pointer<MipsCatalogEntry> = Pointer.hardPointer(this)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name).presentation()

    val searchText: String = forms.joinToString(" ") { (example, description) -> "$example $description" }

    fun referenceHtml(): String = buildString {
        for ((example, description) in forms) {
            append(DEFINITION_START).append(StringUtil.escapeXmlEntities(example)).append(DEFINITION_END)
            append(CONTENT_START).append(StringUtil.escapeXmlEntities(description)).append(CONTENT_END)
        }
    }

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(
        referenceHtml() + if (name == "syscall") MipsSyscallDocumentation.html else "",
    )
}

internal object MipsDocumentationCatalog {
    private val byName by lazy {
        TreeMap<String, MipsCatalogEntry>(String.CASE_INSENSITIVE_ORDER).apply {
            for ((name, instructions) in InstructionDefaults.get().instructionList.groupBy { it.name }) {
                val forms = instructions.map { it.exampleFormat to it.description }.distinct()
                put(name, MipsCatalogEntry(name, forms.first().first.removePrefix(name), forms))
            }
            for (directive in Directives.getDirectiveList()) {
                val name = directive.name
                val description = directive.description
                put(name, MipsCatalogEntry(name, "  $description", listOf(name to description)))
            }
        }
    }

    val entries: Collection<MipsCatalogEntry> get() = byName.values

    operator fun get(name: String): MipsCatalogEntry = byName.getValue(name)
}
