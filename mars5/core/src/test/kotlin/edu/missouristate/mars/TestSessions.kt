package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.mips.hardware.MemoryConfiguration
import java.util.prefs.AbstractPreferences

/** Fresh, deterministic composition for characterization tests. */
internal object TestSessions {
    @JvmStatic
    fun create(): MarsContext {
        val settings = Settings(MemoryPreferences())
        settings.simulationSettings
            .setMemoryConfiguration("Default")
            .setDelayedBranchingEnabled(false)
            .setBareMachineEnabled(false)
            .setStartAtMainEnabled(false)
            .setExtendedAssemblerEnabled(true)
            .setWarningsAreErrors(false)
        return DaggerMarsComponent.builder()
            .coreModule(CoreModule(settings, target = MarsTarget.MARS32))
            .guiProxy(NO_GUI)
            .build()
            .context()
    }

    @JvmStatic
    fun execution(session: MarsContext) = InstructionContext(session, session.syscallEnvironment)

    @JvmStatic
    fun select(session: MarsContext, configuration: MemoryConfiguration) {
        session.setMemoryConfiguration(configuration.configurationIdentifier())
    }

    @JvmField
    val NO_GUI: MarsGuiProxy =
        object : MarsGuiProxy {
            override val isGuiMode = false

            override fun postMessage(message: String) = Unit

            override fun postRunMessage(message: String) = Unit

            override fun readInput(maxLength: Int) = ""

            override fun readInputWithPrompt(prompt: String) = ""

            override fun clearRunConsole() = Unit
        }

    private class MemoryPreferences : AbstractPreferences(null, "") {
        private val values = mutableMapOf<String, String>()

        override fun putSpi(key: String, value: String) {
            values[key] = value
        }

        override fun getSpi(key: String): String? = values[key]

        override fun removeSpi(key: String) {
            values.remove(key)
        }

        override fun removeNodeSpi() = values.clear()

        override fun keysSpi(): Array<String> = values.keys.toTypedArray()

        override fun childrenNamesSpi(): Array<String> = emptyArray()

        override fun childSpi(name: String): AbstractPreferences =
            throw UnsupportedOperationException()

        override fun syncSpi() = Unit

        override fun flushSpi() = Unit
    }
}
