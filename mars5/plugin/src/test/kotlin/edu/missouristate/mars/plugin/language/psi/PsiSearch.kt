package edu.missouristate.mars.plugin.language.psi

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import edu.missouristate.mars.util.javaClassOf

/** Keep native recursive order, root exclusion, null handling, and the collection contract. */
internal inline fun <reified T : PsiElement> findChildrenOfType(
    element: PsiElement?,
): Collection<T> = PsiTreeUtil.findChildrenOfType(element, javaClassOf<T>())
