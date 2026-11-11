package edu.missouristate.mars.plugin.compiler

import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.openapi.application.readAction
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileTask
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VfsUtilCore
import edu.missouristate.mars.plugin.build.MarsCompilerAssembly
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/** Loaded only when IntelliJ's Java compiler support is available. */
class MarsCompilerTask : CompileTask {
    override fun execute(context: CompileContext): Boolean {
        val scope = context.compileScope
        val assembly = MarsCompilerAssembly.select(context.project, scope.getUserData(CompilerManager.RUN_CONFIGURATION_KEY))
            ?: return true
        return runBlockingCancellable {
            if (!readAction { assembly.entries.any { scope.belongs(sourceUrl(it)) } }) return@runBlockingCancellable true
            context.getUserData(resultKey)?.let { return@runBlockingCancellable it }
            assembly.assemble(context) { messages ->
                for (message in messages) {
                    checkCanceled()
                    context.reportAssemblyMessage(message)
                }
            }.also { context.putUserData(resultKey, it) }
        }
    }
}

private val resultKey = Key.create<Boolean>("MARS assembly result for this compilation")

private fun sourceUrl(path: Path) = VfsUtilCore.pathToUrl(path.invariantSeparatorsPathString)

private fun CompileContext.reportAssemblyMessage(event: MessageEvent) {
    val category = if (event.kind == MessageEvent.Kind.WARNING) CompilerMessageCategory.WARNING else CompilerMessageCategory.ERROR
    if (event is FileMessageEvent) {
        val position = event.filePosition
        addMessage(category, event.message, sourceUrl(position.path!!), position.startLine + 1, position.startColumn + 1,
            event.getNavigatable(project))
    } else {
        val message = listOfNotNull(event.hint, event.message).joinToString("\n")
        addMessage(category, message, null, -1, -1)
    }
}
