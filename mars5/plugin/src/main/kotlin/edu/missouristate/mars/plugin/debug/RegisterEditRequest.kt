package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

internal data class RegisterEditRequest(
    val register: Int,
    val expression: String,
    val callback: XModificationCallback,
) {
    fun unavailable() = callback.errorOccurred(runMessages.getMessage("debug.register.edit.unavailable"))
}

internal fun registerEditRequests(): Channel<RegisterEditRequest> =
    Channel(16, onUndeliveredElement = RegisterEditRequest::unavailable)

internal class RegisterModifier(
    private val register: Int,
    private val bits: Int,
    private val edits: SendChannel<RegisterEditRequest>,
    private val isCurrent: () -> Boolean,
) : XValueModifier() {
    override fun getInitialValueEditorText() = Binary.intToHexString(bits)

    override fun setValue(expression: XExpression, callback: XModificationCallback) {
        val request = RegisterEditRequest(register, expression.expression, callback)
        if (!isCurrent() || edits.trySend(request).isFailure) request.unavailable()
    }
}
