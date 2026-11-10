package edu.missouristate.mars.plugin.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.ExtensionPointAdapter
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// The VCS and Problems lazy content helpers are internal to IntelliJ.
private val toolInitializer = Key.create<(Content) -> Unit>("MARS_TOOL_TAB_INITIALIZER")

internal fun Content.initializeMarsToolOnSelection(initializer: (Content) -> Unit) {
    putUserData(toolInitializer, initializer)
}

internal class MarsToolExtensions(private val project: Project, private val manager: ContentManager, parent: Disposable) {
    private val firstExtensionIndex = manager.contentCount
    private val tabs = IdentityHashMap<MarsToolProvider, Content>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    init {
        val selection = object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) = loadSelected()
        }
        manager.addContentManagerListener(selection)
        Disposer.register(parent) {
            scope.cancel()
            manager.removeContentManagerListener(selection)
            tabs.clear()
        }
        MarsToolProvider.EP.addExtensionPointListener(scope, object : ExtensionPointAdapter<MarsToolProvider>() {
            override fun extensionListChanged() = scheduleRefresh()
        })
        refresh()
    }

    private fun scheduleRefresh() {
        if (ApplicationManager.getApplication().isDispatchThread) refresh()
        else scope.launch { refresh() }
    }

    private fun refresh() {
        val ids = mutableSetOf<String>()
        val providers = MarsToolProvider.EP.extensionList.filter { provider ->
            val id = provider.id
            val unique = id.isNotBlank() && ids.add(id)
            if (!unique) thisLogger().warn("Ignoring an empty or duplicate MARS tool ID: $id")
            unique
        }
        val removed = tabs.keys.filter { existing -> providers.none { it === existing } }
            .map { tabs.remove(it)!! }
        removed.forEach { manager.removeContent(it, true) }
        providers.forEachIndexed { index, provider ->
            if (provider !in tabs) {
                val content = ContentFactory.getInstance().createContent(JBLabel(), provider.title, false)
                val lifetime = Disposer.newDisposable()
                content.setDisposer(lifetime)
                content.initializeMarsToolOnSelection { initializeProvider(it, provider, lifetime) }
                tabs[provider] = content
                manager.addContent(content, minOf(firstExtensionIndex + index, manager.contentCount))
            }
        }
        loadSelected()
    }

    private fun loadSelected() {
        val selected = manager.selectedContent ?: return
        val initialize = selected.getUserData(toolInitializer) ?: return
        selected.putUserData(toolInitializer, null)
        try {
            initialize(selected)
        } catch (error: CancellationException) {
            selected.initializeMarsToolOnSelection(initialize)
            throw error
        }
    }

    private fun initializeProvider(content: Content, provider: MarsToolProvider, parent: Disposable) {
        val lifetime = Disposer.newDisposable(parent)
        try {
            content.component = provider.createContent(MarsToolContext(project, lifetime))
        } catch (error: CancellationException) {
            Disposer.dispose(lifetime)
            throw error
        } catch (error: Exception) {
            Disposer.dispose(lifetime)
            thisLogger().warn("Could not open MARS tool ${provider.id}", error)
            content.component = JBLabel(toolsMessages.getMessage("tools.provider.failed", provider.title))
        }
    }
}
