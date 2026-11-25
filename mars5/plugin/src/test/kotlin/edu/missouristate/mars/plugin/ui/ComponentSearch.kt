package edu.missouristate.mars.plugin.ui

import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.util.javaClassOf
import javax.swing.JComponent

internal inline fun <reified T : JComponent> findComponentsOfType(parent: JComponent?): List<T> =
    UIUtil.findComponentsOfType(parent, javaClassOf<T>())

internal inline fun <reified T : JComponent> findComponentOfType(parent: JComponent?): T? =
    UIUtil.findComponentOfType(parent, javaClassOf<T>())
