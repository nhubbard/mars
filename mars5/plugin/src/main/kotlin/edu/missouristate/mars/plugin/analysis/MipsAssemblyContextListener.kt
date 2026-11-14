package edu.missouristate.mars.plugin.analysis

import com.intellij.util.messages.Topic
import edu.missouristate.mars.plugin.topicOf

internal fun interface MipsAssemblyContextListener {
    fun assemblyEntryChanged()

    companion object {
        @Topic.ProjectLevel
        @JvmField
        val TOPIC = topicOf<MipsAssemblyContextListener>("MARS assembly context", Topic.BroadcastDirection.NONE)
    }
}
