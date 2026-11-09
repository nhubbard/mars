package edu.missouristate.mars.plugin.build

import java.nio.file.Path

internal enum class MarsAssemblyMode { SINGLE, DIRECTORY, PROJECT }

/** Directory mode discovers siblings during capture. Project mode already has an ordered list. */
internal data class MarsSourceSet(val entries: List<Path>, val mode: MarsAssemblyMode) {
    constructor(entry: Path, mode: MarsAssemblyMode = MarsAssemblyMode.SINGLE) :
        this(listOf(entry.toAbsolutePath().normalize()), mode)

    val entry: Path get() = entries.first()
}
