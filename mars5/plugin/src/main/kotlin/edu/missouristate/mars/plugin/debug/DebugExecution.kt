package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.run.MarsExecutionSources

/** Capture the loaded image once; later suspensions use this program and its symbol context. */
internal class DebugExecution(val machine: MarsContext, sources: MarsExecutionSources) {
  val program = checkNotNull(machine.program) { "Load an assembled program before starting Debug" }
  val symbols = LoadedSymbols(program.symbolMap(), sources)
}
