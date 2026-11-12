package edu.missouristate.mars.plugin.language

import com.intellij.lang.Language

// IntelliJ registers the singleton by ID. Language inherits Serializable, but holds a non-serializable lock.
@Suppress("JavaIoSerializableObjectMustHaveReadResolve")
object MipsLanguage : Language("MARS_MIPS")
