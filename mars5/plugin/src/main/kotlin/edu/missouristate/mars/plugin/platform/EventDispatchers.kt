package edu.missouristate.mars.plugin.platform

import com.intellij.util.EventDispatcher
import edu.missouristate.mars.util.javaClassOf
import java.util.EventListener

/** The platform owns dispatch, registration identity, ordering, and disposal. */
internal inline fun <reified T : EventListener> eventDispatcherOf(): EventDispatcher<T> =
    EventDispatcher.create(javaClassOf<T>())
