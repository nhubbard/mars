package edu.missouristate.mars.plugin.language

import com.intellij.lang.Language

object MipsLanguage : Language("MARS_MIPS") {
  fun readResolve(): Any = MipsLanguage
}
