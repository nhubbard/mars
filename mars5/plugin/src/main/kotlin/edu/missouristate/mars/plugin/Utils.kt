package edu.missouristate.mars.plugin

import com.intellij.DynamicBundle
import com.intellij.util.messages.Topic
import edu.missouristate.mars.util.boxedClassOf

internal inline fun <reified L : Any> topicOf(
    name: String,
    direction: Topic.BroadcastDirection = Topic.BroadcastDirection.NONE,
): Topic<L> = Topic(name, boxedClassOf<L>(), direction)

internal inline fun <reified T : Any> dynamicBundleOf(
    path: String = "messages.MarsBundle",
): DynamicBundle = DynamicBundle(boxedClassOf<T>(), path)
