package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.MarsRegisterFile
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.core.SimulationSettings
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.di.MarsComponent
import edu.missouristate.mars.mips.hardware.Coprocessor0State
import edu.missouristate.mars.mips.hardware.Coprocessor1State
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import java.util.prefs.AbstractPreferences
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

internal class SettingsReconciliationTest {
    @ParameterizedTest
    @ValueSource(
        ints =
            [
                Settings.DELAYED_BRANCHING_ENABLED,
                Settings.SELF_MODIFYING_CODE_ENABLED,
                Settings.WARNINGS_ARE_ERRORS,
                Settings.EXTENDED_ASSEMBLER_ENABLED,
                Settings.START_AT_MAIN,
                Settings.BARE_MACHINE_ENABLED,
            ],
    )
    fun booleanViewsSharePersistentAndTemporaryChanges(id: Int) {
        val preferences = MemoryPreferences()
        val settings = Settings(preferences)
        val simulation = settings.simulationSettings
        val notifications = AtomicInteger()
        val listener = Consumer { ignored: Settings -> notifications.incrementAndGet() }
        settings.subscribeChange(listener)

        settings.setBooleanSettingNonPersistent(id, false)
        settings.setBooleanSetting(id, true)
        Assertions.assertThat(readBoolean(simulation, id)).isTrue()
        Assertions.assertThat(notifications.get()).isEqualTo(1)
        Assertions.assertThat(readBoolean(Settings(preferences).simulationSettings, id)).isTrue()

        clearBoolean(simulation, id)
        Assertions.assertThat(settings.getBooleanSetting(id)).isFalse()
        Assertions.assertThat(notifications.get()).isEqualTo(1)
        // Session-only writes must not overwrite the saved GUI preference.
        Assertions.assertThat(readBoolean(Settings(preferences).simulationSettings, id)).isTrue()

        settings.reset()
        Assertions.assertThat<SimulationSettings?>(settings.simulationSettings).isSameAs(simulation)
        Assertions.assertThat(readBoolean(simulation, id)).isTrue()

        settings.unsubscribeChange(listener)
        settings.setBooleanSetting(id, false)
        Assertions.assertThat(readBoolean(simulation, id)).isFalse()
        Assertions.assertThat(notifications.get()).isEqualTo(1)
    }

    @Test
    fun memoryConfigurationAndErrorLimitShareTheOwnerAndResetCorrectly() {
        val preferences = MemoryPreferences()
        val settings = Settings(preferences)
        val simulation = settings.simulationSettings
        settings.exceptionHandler = "handler.asm"
        settings.memoryConfiguration = "CompactDataAtZero"
        Assertions.assertThat(simulation.memoryConfiguration).isEqualTo("CompactDataAtZero")

        simulation.setMemoryConfiguration("CompactTextAtZero").setAssemblerErrorLimit(17)
        Assertions.assertThat(settings.memoryConfiguration).isEqualTo("CompactTextAtZero")
        Assertions.assertThat(settings.assemblerErrorLimit).isEqualTo(17)
        Assertions.assertThat(Settings(preferences).memoryConfiguration)
            .isEqualTo("CompactDataAtZero")
        Assertions.assertThat(Settings(preferences).exceptionHandler).isEqualTo("handler.asm")
        Assertions.assertThat(Settings(preferences).assemblerErrorLimit)
            .isEqualTo(ApplicationInfo.maximumErrorMessages)

        settings.assemblerErrorLimit = 23
        Assertions.assertThat(simulation.assemblerErrorLimit).isEqualTo(23)
        settings.reset()
        Assertions.assertThat(simulation.memoryConfiguration).isEqualTo("CompactDataAtZero")
        Assertions.assertThat(simulation.assemblerErrorLimit)
            .isEqualTo(ApplicationInfo.maximumErrorMessages)
    }

    @Test
    fun daggerSharesOneSettingsOwnerPerGraph() {
        val first: MarsComponent = component(Settings(MemoryPreferences()))
        val second: MarsComponent = component(Settings(MemoryPreferences()))
        first.context().use { firstContext ->
            second.context().use { secondContext ->
                Assertions.assertThat<SimulationSettings?>(firstContext.settings)
                    .isSameAs(first.settings().simulationSettings)
                Assertions.assertThat<MarsSettings?>(first.simulationSettings())
                    .isSameAs(firstContext.settings)
                firstContext.settings.setDelayedBranchingEnabled(true)
                secondContext.settings.setDelayedBranchingEnabled(false)
                Assertions.assertThat(
                        first.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED),
                    )
                    .isTrue()
                Assertions.assertThat(
                        second.settings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED),
                    )
                    .isFalse()
            }
        }
    }

    @Test
    fun daggerOwnsIndependentRegistersWithSessionSpecificResetAddresses() {
        val firstSettings = Settings(MemoryPreferences())
        firstSettings.simulationSettings.setMemoryConfiguration("CompactDataAtZero")
        val secondSettings = Settings(MemoryPreferences())
        secondSettings.simulationSettings.setMemoryConfiguration("Default")
        val first: MarsComponent = component(firstSettings)
        val second: MarsComponent = component(secondSettings)
        first.context().use { firstContext ->
            second.context().use { secondContext ->
                Assertions.assertThat<MarsRegisterFile?>(first.registerFile())
                    .isSameAs(firstContext.registerFile)
                first.registerFile().setValue(8, 123)
                first.registerFile().setValue(33, 45)
                Assertions.assertThat(secondContext.registerFile.getValue(8)).isZero()
                Assertions.assertThat(secondContext.registerFile.getValue(33)).isZero()
                val configuration = MemoryConfigurations.createConfiguration("CompactDataAtZero")
                Assertions.assertThat(first.registerFile().getPC())
                    .isEqualTo(configuration.textBaseAddress)
                Assertions.assertThat(first.registerFile().getValue(28))
                    .isEqualTo(configuration.globalPointer)
                Assertions.assertThat(first.registerFile().getValue(29))
                    .isEqualTo(configuration.stackPointer)
                Assertions.assertThat(first.registerFile().getPC())
                    .isNotEqualTo(secondContext.registerFile.getPC())
                first.registerFile().reset()
                Assertions.assertThat(first.registerFile().getValue(8)).isZero()
            }
        }
    }

    @Test
    fun daggerOwnsIndependentCoprocessor0State() {
        val first: MarsComponent = component(Settings(MemoryPreferences()))
        val second: MarsComponent = component(Settings(MemoryPreferences()))
        first.context().use { firstContext ->
            second.context().use { secondContext ->
                Assertions.assertThat<Coprocessor0State?>(firstContext.coprocessor0)
                    .isNotSameAs(secondContext.coprocessor0)
                firstContext.coprocessor0.updateRegister(12, 42)
                Assertions.assertThat(secondContext.coprocessor0.getValue(12))
                    .isEqualTo(Coprocessor0State.DEFAULT_STATUS_VALUE)
                firstContext.coprocessor0.resetRegisters()
                Assertions.assertThat(firstContext.coprocessor0.getValue(12))
                    .isEqualTo(Coprocessor0State.DEFAULT_STATUS_VALUE)
            }
        }
    }

    @Test
    fun daggerOwnsIndependentCoprocessor1RegistersAndFlags() {
        val first: MarsComponent = component(Settings(MemoryPreferences()))
        val second: MarsComponent = component(Settings(MemoryPreferences()))
        first.context().use { firstContext ->
            second.context().use { secondContext ->
                Assertions.assertThat<Coprocessor1State?>(firstContext.coprocessor1)
                    .isNotSameAs(secondContext.coprocessor1)
                firstContext.coprocessor1.updateRegister(12, 42)
                firstContext.coprocessor1.setConditionFlag(7)
                Assertions.assertThat(secondContext.coprocessor1.getValue(12)).isZero()
                Assertions.assertThat(secondContext.coprocessor1.getConditionFlag(7)).isZero()
                secondContext.coprocessor1.updateRegister(12, 99)
                firstContext.coprocessor1.resetRegisters()
                Assertions.assertThat(firstContext.coprocessor1.getValue(12)).isZero()
                Assertions.assertThat(firstContext.coprocessor1.conditionFlags).isZero()
                Assertions.assertThat(secondContext.coprocessor1.getValue(12)).isEqualTo(99)
            }
        }
    }

    @Test
    fun settingsOwnerIsSharedWithTheGraphAndErrorList() {
        val settings = Settings(MemoryPreferences())

        val component: MarsComponent = component(settings)
        component.context().use { context ->
            context.settings.setAssemblerErrorLimit(3).setWarningsAreErrors(true)
            val errors = ErrorList(settings.simulationSettings)
            Assertions.assertThat(errors.errorLimit).isEqualTo(3)
            for (i in 0..5) {
                errors.add(ErrorMessage(null, i + 1, 1, "Invalid instruction"))
            }
            Assertions.assertThat(errors.errorLimitExceeded()).isTrue()
            Assertions.assertThat(errors.errorMessages).hasSize(4)
            Assertions.assertThat(errors.errorMessages[3].message)
                .isEqualTo("Error Limit of 3 exceeded.")
            Assertions.assertThat(settings.getBooleanSetting(Settings.WARNINGS_ARE_ERRORS)).isTrue()
            settings.setBooleanSettingNonPersistent(Settings.WARNINGS_ARE_ERRORS, false)
            Assertions.assertThat(context.settings.isWarningsAreErrors).isFalse()
        }
    }

    @Test
    fun standaloneSettingsKeepIndependentInMemoryDefaults() {
        val first = SimulationSettings()
        val second = SimulationSettings()
        Assertions.assertThat(second.memoryConfiguration).isEqualTo("Default")
        Assertions.assertThat(second.isExtendedAssemblerEnabled).isTrue()
        Assertions.assertThat(second.assemblerErrorLimit).isEqualTo(200)
        first.setDelayedBranchingEnabled(true).setMemoryConfiguration("CompactDataAtZero")
        Assertions.assertThat(second.isDelayedBranchingEnabled).isFalse()
        Assertions.assertThat(second.memoryConfiguration).isEqualTo("Default")
    }

    /** In-memory preferences keep these tests away from the user's saved settings. */
    private class MemoryPreferences : AbstractPreferences(null, "") {
        private val values: MutableMap<String?, String?> = HashMap()

        override fun putSpi(key: String?, value: String?) {
            values.put(key, value)
        }

        override fun getSpi(key: String?): String? {
            return values[key]
        }

        override fun removeSpi(key: String?) {
            values.remove(key)
        }

        override fun removeNodeSpi() {
            values.clear()
        }

        override fun keysSpi(): Array<String?>? {
            return values.keys.toTypedArray()
        }

        override fun childrenNamesSpi(): Array<String?> {
            return arrayOfNulls(0)
        }

        override fun childSpi(name: String?): AbstractPreferences? {
            throw UnsupportedOperationException()
        }

        override fun syncSpi() {}

        override fun flushSpi() {}
    }

    companion object {
        private fun readBoolean(settings: SimulationSettings, id: Int): Boolean {
            return when (id) {
                Settings.DELAYED_BRANCHING_ENABLED -> settings.isDelayedBranchingEnabled
                Settings.SELF_MODIFYING_CODE_ENABLED -> settings.isSelfModifyingCodeEnabled
                Settings.WARNINGS_ARE_ERRORS -> settings.isWarningsAreErrors
                Settings.EXTENDED_ASSEMBLER_ENABLED -> settings.isExtendedAssemblerEnabled
                Settings.START_AT_MAIN -> settings.isStartAtMainEnabled
                Settings.BARE_MACHINE_ENABLED -> settings.isBareMachineEnabled
                else -> throw IllegalArgumentException()
            }
        }

        private fun clearBoolean(settings: SimulationSettings, id: Int) {
            when (id) {
                Settings.DELAYED_BRANCHING_ENABLED -> settings.setDelayedBranchingEnabled(false)
                Settings.SELF_MODIFYING_CODE_ENABLED -> settings.setSelfModifyingCodeEnabled(false)
                Settings.WARNINGS_ARE_ERRORS -> settings.setWarningsAreErrors(false)
                Settings.EXTENDED_ASSEMBLER_ENABLED -> settings.setExtendedAssemblerEnabled(false)
                Settings.START_AT_MAIN -> settings.setStartAtMainEnabled(false)
                Settings.BARE_MACHINE_ENABLED -> settings.setBareMachineEnabled(false)
                else -> throw IllegalArgumentException()
            }
        }

        private fun component(settings: Settings): MarsComponent {
            return DaggerMarsComponent.builder()
                .coreModule(CoreModule(settings, target = MarsTarget.MARS32))
                .guiProxy(NO_GUI)
                .build()
        }

        private val NO_GUI: MarsGuiProxy =
            object : MarsGuiProxy {
                override val isGuiMode: Boolean
                    get() = false

                override fun postMessage(message: String) {}

                override fun postRunMessage(message: String) {}

                override fun readInput(maxLength: Int): String {
                    return ""
                }

                override fun readInputWithPrompt(prompt: String): String {
                    return ""
                }

                override fun clearRunConsole() {}
            }
    }
}
