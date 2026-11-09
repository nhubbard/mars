package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_END
import com.intellij.lang.documentation.DocumentationMarkup.CONTENT_START
import com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_END
import com.intellij.lang.documentation.DocumentationMarkup.DEFINITION_START
import com.intellij.model.Pointer
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.documentation.LookupElementDocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
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
        val owner = token.parent
        val ownerType = PsiUtilCore.getElementType(owner)
        if (ownerType == operationCandidate && owner.firstChild == token) {
            val analysis = file.project.service<MarsAnalysisService>()
            val operation = analysis.expandedOperationAlias(file, token, token.text)?.get(0) ?: return emptyList()
            return when (operation.type) {
                TokenTypes.OPERATOR -> listOf(MipsDocumentationCatalog[operation.value])
                TokenTypes.DIRECTIVE ->
                    // Includes are expanded before equivalences, so a generated .include cannot open a file.
                    if (Directives.INCLUDE.name.equals(operation.value, ignoreCase = true)) emptyList()
                    else listOf(MipsDocumentationCatalog[operation.value])
                else -> emptyList()
            }
        }
        val keyword = when (PsiUtilCore.getElementType(token)) {
            includeType -> ownerType in includeOwners
            macroEndType -> ownerType in macroEndOwners
            else -> ownerType in headOwners && owner.firstChild == token
        }
        return if (keyword) listOf(MipsDocumentationCatalog[token.text]) else emptyList()
    }

    override fun documentationTarget(psiFile: PsiFile, element: LookupElement, offset: Int): DocumentationTarget? =
        element.`object` as? MipsCatalogEntry

    private companion object {
        val includeType = MipsTypes.psi("INCLUDE")
        val operationCandidate = MipsTypes.psi("OPERATION_CANDIDATE")
        val macroEndType = MipsTypes.psi("END_MACRO")
        val includeOwners = TokenSet.create(MipsTypes.psi("INCLUDE_LINE"), MipsTypes.psi("DIRECTIVE_STATEMENT"))
        val macroEndOwners = TokenSet.create(MipsTypes.psi("MACRO_DEFINITION"), MipsTypes.psi("STATEMENT"))
        val headOwners = TokenSet.create(
            MipsTypes.psi("OPERATION"), MipsTypes.psi("DIRECTIVE_STATEMENT"),
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

    override fun computeDocumentation(): DocumentationResult = DocumentationResult.documentation(buildString {
        for ((example, description) in forms) {
            append(DEFINITION_START).append(StringUtil.escapeXmlEntities(example)).append(DEFINITION_END)
            append(CONTENT_START).append(StringUtil.escapeXmlEntities(description)).append(CONTENT_END)
        }
    })
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
