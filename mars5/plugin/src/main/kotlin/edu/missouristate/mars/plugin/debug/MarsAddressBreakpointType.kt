package edu.missouristate.mars.plugin.debug

import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.Attribute
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.util.Binary
import com.intellij.openapi.util.io.FileUtil

internal typealias AddressBreakpoint = XBreakpoint<MarsAddressBreakpointProperties>

internal class MarsAddressBreakpointType : XBreakpointType<AddressBreakpoint, MarsAddressBreakpointProperties>(
    "MarsMipsAddress", runMessages.getMessage("debug.breakpoint.address.type"),
) {
    override fun createProperties() = MarsAddressBreakpointProperties()

    override fun getDisplayText(breakpoint: AddressBreakpoint): String {
        val properties = breakpoint.properties ?: return runMessages.getMessage("debug.breakpoint.address.missing")
        return runMessages.getMessage("debug.breakpoint.address.display", properties.wideAddress?.let(Binary::longToHexString) ?: Binary.intToHexString(properties.address), properties.entry)
    }

    override fun getEditorsProvider(breakpoint: AddressBreakpoint, project: Project) = MarsDebugEditors

    override fun createCustomConditionsPanel() = MarsBreakpointHitCountPanel<AddressBreakpoint>()
}

internal class MarsAddressBreakpointProperties(
    @get:Attribute var entry: String = "",
    @get:Attribute var address: Int = -1,
    @get:Attribute @Volatile override var hitInterval: Int = 1,
    @get:Attribute var wideAddress: Long? = null,
) : XBreakpointProperties<MarsAddressBreakpointProperties>(), MarsBreakpointHitCount {
    override fun getState() = this

    override fun loadState(state: MarsAddressBreakpointProperties) {
        entry = state.entry
        address = state.address
        wideAddress = state.wideAddress
        hitInterval = state.hitInterval
    }

    fun addressBitsIn(entry: String): Long? =
        (wideAddress ?: address.toUInt().toLong()).takeIf { FileUtil.pathsEqual(this.entry, entry) && it and 3L == 0L }
}
