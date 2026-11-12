package edu.missouristate.mars.api

import java.nio.file.Path
import kotlin.jvm.JvmRecord

/** An assembler error or warning and its source position when known. */
@JvmRecord
data class AssemblyError(val file: Path?, val line: Int, val column: Int, val message: String?, val isWarning: Boolean) {
    override fun toString(): String {
        val severity = if (isWarning) "warning" else "error"
        return "$severity at $file:$line: $message"
    }
}
