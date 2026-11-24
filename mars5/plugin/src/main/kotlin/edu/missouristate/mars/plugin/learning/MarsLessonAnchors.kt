package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.language.operationHead
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.awt.Rectangle
import javax.swing.JComponent

/** Resolve the edited PSI afresh; an ambiguous or missing anchor never selects another block. */
internal object MarsLessonAnchors {
  fun range(editor: Editor, anchor: MarsLessonAnchor): TextRange? = runReadActionBlocking {
    val manager =
        PsiDocumentManager.getInstance(editor.project ?: return@runReadActionBlocking null)
    if (!manager.isCommitted(editor.document)) return@runReadActionBlocking null
    val file = manager.getPsiFile(editor.document) ?: return@runReadActionBlocking null
    val labels = mutableListOf<MipsDeclaration>()
    val instructions = mutableListOf<PsiElement>()
    file.accept(
        object : PsiRecursiveElementWalkingVisitor() {
          override fun visitElement(element: PsiElement) {
            if (element is MipsDeclaration && element.kind == SymbolKind.LABEL) labels += element
            if (element.node.elementType == MipsTypes.psi("INSTRUCTION")) instructions += element
            super.visitElement(element)
          }
        },
    )
    val matches = labels.filter { it.name == anchor.label }
    if (matches.size != 1) return@runReadActionBlocking null
    val label = matches.single()
    // The PSI visitor collects labels in source order.
    val next =
        labels.firstOrNull { it.textOffset > label.textOffset }?.textOffset ?: file.textLength
    instructions
        .filter {
          it.textRange.startOffset >= label.textRange.endOffset &&
              it.textRange.endOffset <= next &&
              it.operationHead.text.equals(anchor.mnemonic, ignoreCase = true)
        }
        .getOrNull(anchor.occurrence)
        ?.textRange
  }

  /** The native Swing timer invokes this outside any task read context. */
  fun highlight(editor: Editor, anchor: MarsLessonAnchor): (JComponent) -> Rectangle? = {
    runReadActionBlocking {
      val range = range(editor, anchor) ?: return@runReadActionBlocking null
      val start = editor.offsetToXY(range.startOffset)
      val end = editor.offsetToXY(range.endOffset)
      Rectangle(start.x, start.y, maxOf(1, end.x - start.x), editor.lineHeight)
    }
  }
}
