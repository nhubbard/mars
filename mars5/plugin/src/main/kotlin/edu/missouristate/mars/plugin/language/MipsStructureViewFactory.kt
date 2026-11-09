package edu.missouristate.mars.plugin.language

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.psi.MipsSymbolToken
import javax.swing.Icon

internal class MipsStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? =
        (psiFile as? MipsFile)?.let(::MipsStructureViewBuilder)
}

private class MipsStructureViewBuilder(private val file: MipsFile) : TreeBasedStructureViewBuilder() {
    override fun createStructureViewModel(editor: Editor?) = MipsStructureModel(file, editor)
}

private class MipsStructureModel(private val file: MipsFile, editor: Editor?) :
    StructureViewModelBase(file, editor, MipsStructureNode(file)) {
    init {
        file.project.messageBus.connect(this)
            .subscribe(MipsAssemblyContextListener.TOPIC, MipsAssemblyContextListener(::fireModelUpdate))
    }

    override fun isSuitable(element: PsiElement?): Boolean {
        if (element is MipsDeclaration) return true
        if (element !is MipsSymbolToken || DumbService.isDumb(file.project)) return false
        return file.project.service<MarsAnalysisService>().editorTimeline(file)
            .isGeneratedMacroName(file, element.textOffset)
    }
}

private class MipsStructureNode(
    item: PsiElement,
    private val expandedName: String? = null,
    private val effectiveChildren: List<StructureViewTreeElement>? = null,
) : StructureViewTreeElement, ItemPresentation {
    private val pointer = SmartPointerManager.createPointer(item)
    private val element get() = pointer.element
    private val navigatable get() = element as NavigationItem?

    override fun getValue() = element
    override fun getPresentation() = this
    override fun getPresentableText() = element?.let { expandedName ?: (it as NavigationItem).name }

    override fun navigationRequest() = element?.let { item ->
        if (item is MipsSymbolToken) {
            OpenFileDescriptor(item.project, item.containingFile.viewProvider.virtualFile, item.textOffset).navigationRequest()
        } else {
            (item as NavigationItem).navigationRequest()
        }
    }

    override fun navigate(requestFocus: Boolean) {
        navigatable?.navigate(requestFocus)
    }

    override fun canNavigate() = navigatable?.canNavigate() ?: false
    override fun canNavigateToSource() = navigatable?.canNavigateToSource() ?: false

    override fun getIcon(open: Boolean): Icon? {
        val item = element ?: return null
        return when {
            item is MipsDeclaration -> item.presentation.getIcon(open)
            expandedName != null -> AllIcons.Nodes.Function
            else -> item.getIcon(0)
        }
    }

    override fun getChildren(): Array<StructureViewTreeElement> {
        val item = element ?: return StructureViewTreeElement.EMPTY_ARRAY
        val children = effectiveChildren ?: if (item is MipsFile && !DumbService.isDumb(item.project)) {
            effectiveOutline(item)
        } else {
            writtenChildren(item).map { MipsStructureNode(it) }
        }
        return children.toTypedArray()
    }

    private fun effectiveOutline(file: MipsFile): List<StructureViewTreeElement> {
        val timeline = file.project.service<MarsAnalysisService>().editorTimeline(file)
        val macros = timeline.localMacros(file)
        val written = writtenChildren(file).flatMap { listOf(it) + writtenChildren(it) }
        val groups = written.groupBy { declaration ->
            timeline.macroAt(file, declaration.textOffset)?.header?.takeIf { it in macros }
        }
        fun children(header: PsiElement) = groups[header].orEmpty().map { MipsStructureNode(it, effectiveChildren = emptyList()) }
        val writtenNodes = groups[null].orEmpty().map { declaration ->
            declaration.textOffset to MipsStructureNode(declaration, macros[declaration]?.name, children(declaration))
        }
        val expandedNodes = macros.values.filter { it.header !is MipsDeclaration }.map { macro ->
            val name = file.findElementAt(macro.nameOffset)!!
            macro.nameOffset to MipsStructureNode(name, macro.name, children(macro.header))
        }
        return (writtenNodes + expandedNodes).sortedBy { it.first }.map { it.second }
    }

    private fun writtenChildren(item: PsiElement): List<MipsDeclaration> {
        val scope = when (item) {
            is MipsFile -> item
            else -> item.parent.takeIf { it.elementType == macroDefinition } ?: return emptyList()
        }
        return buildList {
            for (statement in scope.children) {
                ProgressManager.checkCanceled()
                if (statement.elementType != statementType) continue
                for (part in statement.children) {
                    val declaration = when {
                        part is MipsDeclaration -> part
                        part.elementType in declarationContainers -> PsiTreeUtil.getChildOfType(part, MipsDeclaration::class.java)
                        else -> null
                    }
                    if (declaration?.name != null) add(declaration)
                }
            }
        }
    }

    private companion object {
        val statementType = MipsTypes.psi("STATEMENT")
        val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")
        val declarationContainers = TokenSet.create(
            macroDefinition, MipsTypes.psi("MACRO_HEADER"), MipsTypes.psi("DIRECTIVE_STATEMENT"),
        )
    }
}
