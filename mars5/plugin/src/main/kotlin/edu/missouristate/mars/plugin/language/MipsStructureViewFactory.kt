package edu.missouristate.mars.plugin.language

import com.intellij.ide.structureView.StructureViewBuilder
import com.intellij.ide.structureView.StructureViewModelBase
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.NavigatablePsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.elementType
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import javax.swing.Icon

internal class MipsStructureViewFactory : PsiStructureViewFactory {
    override fun getStructureViewBuilder(psiFile: PsiFile): StructureViewBuilder? =
        (psiFile as? MipsFile)?.let(::MipsStructureViewBuilder)
}

private class MipsStructureViewBuilder(private val file: MipsFile) : TreeBasedStructureViewBuilder() {
    override fun createStructureViewModel(editor: Editor?) =
        StructureViewModelBase(file, editor, MipsStructureNode(file)).withSuitableClasses(MipsDeclaration::class.java)
}

private class MipsStructureNode(item: NavigatablePsiElement) : StructureViewTreeElement, ItemPresentation {
    private val pointer = SmartPointerManager.createPointer(item)
    private val element get() = pointer.element

    override fun getValue() = element
    override fun getPresentation() = this
    override fun getPresentableText() = element?.name
    override fun navigationRequest() = element?.navigationRequest()

    override fun navigate(requestFocus: Boolean) {
        element?.navigate(requestFocus)
    }

    override fun canNavigate() = element?.canNavigate() ?: false
    override fun canNavigateToSource() = element?.canNavigateToSource() ?: false

    override fun getIcon(open: Boolean): Icon? = when (val item = element) {
        is MipsDeclaration -> item.presentation.getIcon(open)
        else -> item?.getIcon(0)
    }

    override fun getChildren(): Array<StructureViewTreeElement> {
        val scope = when (val item = element ?: return StructureViewTreeElement.EMPTY_ARRAY) {
            is MipsFile -> item
            else -> item.parent.takeIf { it.elementType == macroDefinition } ?: return StructureViewTreeElement.EMPTY_ARRAY
        }
        return buildList<StructureViewTreeElement> {
            for (statement in scope.children) {
                ProgressManager.checkCanceled()
                if (statement.elementType != statementType) continue
                for (part in statement.children) {
                    val declaration = when {
                        part is MipsDeclaration -> part
                        part.elementType in declarationContainers -> PsiTreeUtil.getChildOfType(part, MipsDeclaration::class.java)
                        else -> null
                    }
                    if (declaration?.name != null) add(MipsStructureNode(declaration))
                }
            }
        }.toTypedArray()
    }

    private companion object {
        val statementType = MipsTypes.psi("STATEMENT")
        val macroDefinition = MipsTypes.psi("MACRO_DEFINITION")
        val declarationContainers = TokenSet.create(
            macroDefinition, MipsTypes.psi("MACRO_HEADER"), MipsTypes.psi("DIRECTIVE_STATEMENT"),
        )
    }
}
