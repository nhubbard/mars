package edu.missouristate.mars.plugin.platform

import com.intellij.util.containers.JBIterable
import edu.missouristate.mars.util.boxedClassOf

internal inline fun <reified T : Any> JBIterable<*>.filterIsInstance(): JBIterable<T> =
    filter(boxedClassOf<T>())
