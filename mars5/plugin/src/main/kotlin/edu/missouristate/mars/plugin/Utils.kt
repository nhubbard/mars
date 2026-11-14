package edu.missouristate.mars.plugin

import com.intellij.DynamicBundle
import com.intellij.util.PathUtil
import com.intellij.util.messages.Topic

internal inline fun <reified L : Any> topicOf(name: String, direction: Topic.BroadcastDirection): Topic<L> =
    Topic(name, L::class.java, direction)

internal inline fun <reified T : Any> dynamicBundleOf(path: String): DynamicBundle =
    DynamicBundle(T::class.java, path)

internal inline fun <reified T : Any> jarPathForClass(): String =
    PathUtil.getJarPathForClass(T::class.java)
