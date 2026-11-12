package edu.missouristate.mars.assembler

import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse
import kotlin.jvm.JvmSuppressWildcards

/** Collects the written positions of symbol references during both assembly passes. */
@JvmSuppressWildcards
internal class AssemblySymbolUses {
    private data class Binding(val target: Symbol, val equivalenceSource: SourceToken?)

    private val bindings = HashMap<SourceToken, MutableSet<Binding>>()

    fun bindings(checkCancellation: Runnable): Map<SourceToken, Set<SymbolUse>> {
        val result = HashMap<SourceToken, Set<SymbolUse>>()
        bindings.forEach { (site, uses) ->
            val detached = HashSet<SymbolUse>()
            for (use in uses) {
                checkCancellation.run()
                val target = use.target
                val definition = SourceToken(target.origin()!!.location, target.column()!!)
                detached.add(SymbolUse(definition, target.name, target.address, use.equivalenceSource))
            }
            result[site] = detached
        }
        return result
    }

    fun record(operand: Token, target: Symbol) {
        if (target.origin() == null || target.column() == null) return
        bindings.getOrPut(operand.diagnosticSite(), ::HashSet).add(Binding(target, operand.equivalenceSource()))
    }
}
