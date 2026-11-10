package edu.missouristate.mars.plugin.debug

import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Coprocessor1State.CONDITION_FLAG_MASK
import edu.missouristate.mars.plugin.run.runMessages
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel

internal enum class RegisterBank { CPU, COPROCESSOR_0, FPU_SINGLE, FPU_DOUBLE, FPU_FLAGS }

internal interface MachineEditRequest {
    val callback: XModificationCallback
    suspend fun apply(machine: MarsContext, evaluator: MarsExpressionEvaluator): Result<Boolean>
    fun unavailable()
}

internal data class MemoryWordEditRequest(
    val address: Int,
    val expression: String,
    override val callback: XModificationCallback,
) : MachineEditRequest {
    override fun unavailable() = callback.errorOccurred(runMessages.getMessage("debug.memory.edit.unavailable"))

    override suspend fun apply(machine: MarsContext, evaluator: MarsExpressionEvaluator): Result<Boolean> {
        val value = evaluator.evaluateStopped(expression, machine.memory).getOrElse { return Result.failure(it) }
        val bits = value.wordBits ?: return Result.failure(IllegalArgumentException(runMessages.getMessage("debug.memory.edit.word")))
        return try {
            Result.success(machine.editMemoryWord(address, bits))
        } catch (error: AddressErrorException) {
            Result.failure(error)
        }
    }
}

internal data class RegisterEditRequest(
    val bank: RegisterBank,
    val register: Int,
    val expression: String,
    override val callback: XModificationCallback,
) : MachineEditRequest {
    override fun unavailable() = callback.errorOccurred(runMessages.getMessage("debug.register.edit.unavailable"))

    override suspend fun apply(machine: MarsContext, evaluator: MarsExpressionEvaluator): Result<Boolean> {
        val bits = resolveBits(machine, evaluator).getOrElse { return Result.failure(it) }
        val changed = when (bank) {
            RegisterBank.CPU -> machine.editIntegerRegister(register, bits.toInt())
            RegisterBank.COPROCESSOR_0 -> machine.editCoprocessor0Register(register, bits.toInt())
            RegisterBank.FPU_SINGLE -> machine.editCoprocessor1Register(register, bits.toInt())
            RegisterBank.FPU_DOUBLE -> machine.editCoprocessor1Pair(register, bits)
            RegisterBank.FPU_FLAGS -> machine.editCoprocessor1ConditionFlags(bits.toInt())
        }
        return Result.success(changed)
    }

    private suspend fun resolveBits(machine: MarsContext, evaluator: MarsExpressionEvaluator): Result<Long> {
        if (bank == RegisterBank.FPU_SINGLE || bank == RegisterBank.FPU_DOUBLE) return floatingBits(machine, evaluator)
        val value = evaluator.evaluateStopped(expression, machine.memory).getOrElse { return Result.failure(it) }
        val bits = value.wordBits
        return when {
            bits == null -> invalid("debug.register.edit.word")
            bank == RegisterBank.FPU_FLAGS && bits !in 0..CONDITION_FLAG_MASK -> invalid("debug.register.edit.flags")
            else -> Result.success(bits.toLong())
        }
    }

    private suspend fun floatingBits(machine: MarsContext, evaluator: MarsExpressionEvaluator): Result<Long> {
        val input = expression.trim()
        val raw = rawHex.matches(input)
        val single = bank == RegisterBank.FPU_SINGLE
        // A whole hexadecimal literal denotes raw bits. Other inputs are numeric values.
        val bits = when {
            raw && single -> input.substring(2).toUIntOrNull(16)?.toLong()
            raw -> input.substring(2).toULongOrNull(16)?.toLong()
            single -> input.toFloatOrNull()?.toRawBits()?.toLong()
            else -> input.toDoubleOrNull()?.toRawBits()
        }
        if (bits != null) return Result.success(bits)
        if (raw) return invalid("debug.register.edit.float.width", if (single) 32 else 64)
        return evaluator.evaluateStopped(input, machine.memory).map {
            if (single) it.singleBits.toLong() else it.number.toRawBits()
        }
    }

    private fun invalid(key: String, vararg params: Any): Result<Long> =
        Result.failure(IllegalArgumentException(runMessages.getMessage(key, *params)))

    private companion object {
        val rawHex = Regex("0[xX][0-9a-fA-F]+")
    }
}

internal fun machineEditRequests(): Channel<MachineEditRequest> =
    Channel(16, onUndeliveredElement = MachineEditRequest::unavailable)

internal fun SendChannel<MachineEditRequest>.submitEdit(request: MachineEditRequest, isCurrent: Boolean = true) {
    if (!isCurrent || trySend(request).isFailure) request.unavailable()
}

internal class RegisterModifier(
    private val bank: RegisterBank,
    private val register: Int,
    private val initialText: () -> String,
    private val edits: SendChannel<MachineEditRequest>,
    private val isCurrent: () -> Boolean,
) : XValueModifier() {
    override fun getInitialValueEditorText() = initialText()

    override fun setValue(expression: XExpression, callback: XModificationCallback) {
        val request = RegisterEditRequest(bank, register, expression.expression, callback)
        edits.submitEdit(request, isCurrent())
    }
}
