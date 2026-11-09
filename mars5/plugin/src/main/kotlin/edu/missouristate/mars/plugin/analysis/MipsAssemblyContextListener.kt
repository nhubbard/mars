package edu.missouristate.mars.plugin.analysis

import com.intellij.util.messages.Topic

internal fun interface MipsAssemblyContextListener {
    fun assemblyEntryChanged()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC = Topic("MARS assembly context", MipsAssemblyContextListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
