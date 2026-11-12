package edu.missouristate.mars.mips.hardware

import java.lang.ScopedValue
import java.lang.ScopedValue.CallableOp

/** Provenance carried by each memory or register notice. */
enum class AccessOrigin {
    APPLICATION,
    EXECUTION,
    ;

    companion object {
        private val currentOrigin = ScopedValue.newInstance<AccessOrigin>()

        @JvmStatic
        fun current(): AccessOrigin = currentOrigin.orElse(APPLICATION)

        /** Bind execution for this lexical scope, including nested calls and exceptional exits. */
        @JvmStatic
        fun <T, X : Throwable> duringExecution(operation: CallableOp<T, X>): T =
            if (currentOrigin.isBound) operation.call() else ScopedValue.where(currentOrigin, EXECUTION).call(operation)
    }
}
