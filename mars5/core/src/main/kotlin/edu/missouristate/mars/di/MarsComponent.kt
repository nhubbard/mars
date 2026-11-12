package edu.missouristate.mars.di

import dagger.BindsInstance
import dagger.Component
import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.MarsAssembler
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.api.MarsMemory
import edu.missouristate.mars.api.MarsRegisterFile
import edu.missouristate.mars.api.MarsSettings
import edu.missouristate.mars.api.MarsSimulator
import edu.missouristate.mars.core.MarsContext
import javax.inject.Singleton

/** Wires one independent session using the caller's I/O and optional settings owner. */
@Singleton
@Component(modules = [CoreModule::class])
interface MarsComponent {
    fun context(): MarsContext

    fun assembler(): MarsAssembler

    fun simulator(): MarsSimulator

    fun settings(): Settings

    fun simulationSettings(): MarsSettings

    fun registerFile(): MarsRegisterFile

    fun memory(): MarsMemory

    @Component.Builder
    interface Builder {
        fun coreModule(module: CoreModule): Builder

        @BindsInstance
        fun guiProxy(proxy: MarsGuiProxy): Builder

        fun build(): MarsComponent
    }
}
