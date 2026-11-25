package edu.missouristate.mars.plugin.language.psi

import com.intellij.patterns.PlatformPatterns
import com.intellij.patterns.PsiElementPattern
import com.intellij.psi.PsiElement
import edu.missouristate.mars.util.javaClassOf

internal inline fun <reified T : PsiElement> psiElement(): PsiElementPattern.Capture<T?> =
    PlatformPatterns.psiElement(javaClassOf<T>())
