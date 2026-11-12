package edu.missouristate.mars.plugin

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.Settings
import edu.missouristate.mars.api.MarsGuiProxy
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.di.CoreModule
import edu.missouristate.mars.di.DaggerMarsComponent
import edu.missouristate.mars.di.MarsComponent

/** Owns the project's core session, shared by future language, console, and debugger integrations. */
@Service(Service.Level.PROJECT)
class MarsSessionService : Disposable {
    private val lock = Any()
    private var ownedContext: MarsContext? = null
    private var disposed = false

    /** Builds the core graph on first use, without simulation work during service construction. */
    val context: MarsContext
        get() = synchronized(lock) {
            check(!disposed) { "MARS project session is disposed" }
            ownedContext ?: createMarsComponent().context().also { ownedContext = it }
        }

    override fun dispose() {
        val owned = synchronized(lock) {
            if (disposed) return
            disposed = true
            ownedContext.also { ownedContext = null }
        }
        owned?.close()
    }

    companion object {
        internal fun createMarsComponent(backstepLimit: Int = ApplicationInfo.maximumBacksteps): MarsComponent = DaggerMarsComponent.builder()
            .coreModule(CoreModule(Settings(), backstepLimit))
            .guiProxy(ProjectFrontend())
            .build()
    }

    /** Routes core messages before a project console is attached. */
    private class ProjectFrontend : MarsGuiProxy {
        override val isGuiMode = false

        override fun postMessage(message: String) {
            Logger.getInstance(MarsSessionService::class.java).info(message)
        }

        override fun postRunMessage(message: String) = postMessage(message)

        override fun readInput(maxLength: Int): String =
            throw UnsupportedOperationException("Interactive input requires a project console")

        override fun readInputWithPrompt(prompt: String): String =
            throw UnsupportedOperationException("Interactive input requires a project console")

        override fun clearRunConsole() = Unit
    }
}
