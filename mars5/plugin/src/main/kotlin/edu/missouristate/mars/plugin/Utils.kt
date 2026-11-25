package edu.missouristate.mars.plugin

import com.intellij.DynamicBundle
import com.intellij.util.messages.Topic

internal inline fun <reified L : Any> topicOf(
    name: String,
    direction: Topic.BroadcastDirection = Topic.BroadcastDirection.NONE,
): Topic<L> = Topic(name, L::class.java, direction)

internal inline fun <reified T : Any> dynamicBundleOf(path: String): DynamicBundle =
    DynamicBundle(T::class.java, path)
