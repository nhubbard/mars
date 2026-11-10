package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.util.messages.Topic
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.NumberDisplayBase.formatNumber
import edu.missouristate.mars.util.NumberDisplayBase.formatUnsignedInteger
import edu.missouristate.mars.util.NumberDisplayBase.getBase

@Service(Service.Level.APP)
@State(name = "MarsDebuggerDisplaySettings", storages = [Storage("marsDebugger.xml")])
internal class MarsDebuggerDisplaySettings :
    SerializablePersistentStateComponent<MarsDebuggerDisplaySettings.Settings>(Settings()) {

    data class Settings(
        @JvmField var hexAddresses: Boolean = true,
        @JvmField var hexValues: Boolean = true,
    )

    private val bus get() = ApplicationManager.getApplication().messageBus
    val hexAddresses get() = getState().hexAddresses
    val hexValues get() = getState().hexValues

    fun formatAddress(address: Int) = formatUnsignedInteger(address, getBase(hexAddresses))
    fun formatValue(bits: Int) = formatNumber(bits, getBase(hexValues))
    fun formatUnsignedValue(bits: Int) = formatUnsignedInteger(bits, getBase(hexValues))

    fun setHexAddresses(selected: Boolean) {
        updateState { it.copy(hexAddresses = selected) }
        notifyViews()
    }

    fun setHexValues(selected: Boolean) {
        updateState { it.copy(hexValues = selected) }
        notifyViews()
    }

    override fun loadState(state: Settings) {
        super.loadState(state)
        notifyViews()
    }

    fun addListener(lifetime: Disposable, update: () -> Unit) {
        bus.connect(lifetime).subscribe(DisplayListener.TOPIC, DisplayListener {
            UIUtil.invokeLaterIfNeeded(update)
        })
    }

    fun addValueListener(lifetime: Disposable, update: () -> Unit) {
        var previous = hexValues
        addListener(lifetime) {
            val current = hexValues
            if (previous != current) {
                previous = current
                update()
            }
        }
    }

    private fun notifyViews() = bus.syncPublisher(DisplayListener.TOPIC).displayChanged()

    internal fun interface DisplayListener {
        fun displayChanged()

        companion object {
            @Topic.AppLevel
            @JvmField
            val TOPIC = Topic("MARS debugger display settings", DisplayListener::class.java, Topic.BroadcastDirection.NONE)
        }
    }
}

internal class MarsHexAddressesAction(private val display: MarsDebuggerDisplaySettings) :
    DumbAwareToggleAction(runMessages.getMessage("debug.settings.hex.addresses")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = display.hexAddresses
    override fun setSelected(e: AnActionEvent, state: Boolean) = display.setHexAddresses(state)
}

internal class MarsHexValuesAction(private val display: MarsDebuggerDisplaySettings) :
    DumbAwareToggleAction(runMessages.getMessage("debug.settings.hex.values")) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = display.hexValues
    override fun setSelected(e: AnActionEvent, state: Boolean) = display.setHexValues(state)
}
