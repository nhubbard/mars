package edu.missouristate.mars.plugin.platform

import com.intellij.util.containers.FilteredTraverserBase
import com.intellij.util.containers.JBIterable
import edu.missouristate.mars.util.boxedClassOf

/** Retain the native lazy traversal and boxed object matching. */
internal inline fun <reified T : Any> FilteredTraverserBase<*, *>.filterIsInstance():
    JBIterable<T> = filter(boxedClassOf<T>())
