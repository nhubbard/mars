package edu.missouristate.mars.plugin.language

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import java.util.function.Supplier

internal class MipsEditorLanguageLifecycle : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        updateOpenEditors(pluginDescriptor, loaded = false)
    }

    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        updateOpenEditors(pluginDescriptor, loaded = true)
    }

    private fun updateOpenEditors(descriptor: IdeaPluginDescriptor, loaded: Boolean) {
        if (descriptor.pluginId.idString != "edu.missouristate.mars") return
        WriteIntentReadAction.run {
            val documents = FileDocumentManager.getInstance()
            for (editor in EditorFactory.getInstance().allEditors) {
                val file = documents.getFile(editor.document) ?: continue
                if (file.fileType !== MipsFileType) continue
                // The editor caches the language separately from its highlighter, even while
                // native input handling retains the focused component during plugin disable.
                editor.settings.setLanguageSupplier(
                    if (loaded) Supplier { LanguageUtil.getFileTypeLanguage(file.fileType) } else null,
                )
            }
        }
    }
}
