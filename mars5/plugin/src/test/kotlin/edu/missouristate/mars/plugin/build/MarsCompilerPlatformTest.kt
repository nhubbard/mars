package edu.missouristate.mars.plugin.build

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileScope
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessage
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.pom.Navigatable
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.execution.configurations.ConfigurationTypeUtil
import edu.missouristate.mars.plugin.run.MarsRunType
import javax.swing.JCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsCompilerPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()

    @Test
    fun `optional compiler task assembles an in-scope ordered unit once and reports native diagnostics`() = timeoutRunBlocking<Unit> {
        val compiler = CompilerManager.getInstance(project)
        val task = compiler.beforeTasks.filterIsInstance<MarsCompilerTask>().single()
        val settings = project.service<MarsAssemblySettings>()
        val (entry, library, unrelated) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("app/main.asm", "la \$t0, fromLibrary\n").virtualFile
            val library = fixture.addFileToProject("lib/words.s", "nop\n").virtualFile
            val unrelated = fixture.addFileToProject("other/Unrelated.java", "class Unrelated {}").virtualFile
            Triple(entry, library, unrelated)
        }
        fun context(file: VirtualFile) = RecordingContext(project, compiler.createFilesCompileScope(arrayOf(file)))
        fun execute(context: RecordingContext): Boolean =
            ProgressManager.getInstance().runProcess(Computable { task.execute(context) }, context.progressIndicator)
        val disabled = context(entry)
        assertTrue(execute(disabled))
        assertTrue(disabled.messages.isEmpty())
        withContext(Dispatchers.EDT) {
            val page = MarsAssemblyConfigurable(project)
            try {
                val form = page.createComponent()
                page.reset()
                val checkbox = UIUtil.findComponentsOfType(form, JCheckBox::class.java)
                    .single { it.text == "Assemble project sources before IntelliJ JVM compilation" }
                assertFalse(checkbox.isSelected)
                checkbox.isSelected = true
                page.apply()
                val persisted = XmlSerializer.serialize(settings.getState())
                settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                assertTrue(settings.assembleBeforeJvmCompile)
            } finally {
                page.disposeUIResources()
            }
        }
        assertTrue(execute(context(entry)))
        withContext(Dispatchers.EDT) {
            settings.changeSources(listOf(entry.path, library.path))
            edit(library, ".foreign_directive\n.globl fromLibrary\nfromLibrary: nop\n")
        }
        val outside = context(unrelated)
        assertTrue(execute(outside))
        assertTrue(outside.messages.isEmpty())
        val mipsLaunch = context(library)
        mipsLaunch.compileScope.putUserData(CompilerManager.RUN_CONFIGURATION_KEY,
            ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project))
        assertTrue(execute(mipsLaunch))
        assertTrue(mipsLaunch.messages.isEmpty())

        val success = context(library)
        assertTrue(execute(success))
        assertTrue(execute(success))
        val warning = success.messages.single()
        assertEquals(CompilerMessageCategory.WARNING, warning.category)
        assertEquals(library.url, warning.url)
        assertEquals(1, warning.line)
        assertEquals(1, warning.column)
        assertNotNull(warning.navigatable)

        withContext(Dispatchers.EDT) { settings.changeAssemblerOptions(true, true) }
        val strict = context(library)
        assertFalse(execute(strict))
        assertEquals(warning.copy(category = CompilerMessageCategory.ERROR, navigatable = null),
            strict.messages.single().copy(navigatable = null))

        withContext(Dispatchers.EDT) { settings.changeAssemblerOptions(true, false) }
        withContext(Dispatchers.EDT) { edit(entry, "nop\nla \$t0, missing_label\n") }
        val failed = context(entry)
        assertFalse(execute(failed))
        val messages = failed.messages.toList()
        assertFalse(execute(failed))
        assertEquals(messages, failed.messages)
        val error = messages.single { it.category == CompilerMessageCategory.ERROR }
        assertTrue(error.text.contains("missing_label"))
        assertEquals(entry.url, error.url)
        assertEquals(2, error.line)
        assertEquals(9, error.column)
        assertNotNull(error.navigatable)

        val cancelled = context(entry)
        cancelled.onMessage = { throw ProcessCanceledException() }
        assertThrows<ProcessCanceledException> { execute(cancelled) }
        cancelled.onMessage = {}
        assertFalse(execute(cancelled))
        assertTrue(cancelled.messages.any { it.category == CompilerMessageCategory.ERROR })

        val missingHandler = entry.toNioPath().resolveSibling("missing-handler.s")
        withContext(Dispatchers.EDT) { settings.changeExceptionHandler(missingHandler.toString()) }
        val missing = context(entry)
        assertFalse(execute(missing))
        val diagnostic = missing.messages.single()
        assertTrue(diagnostic.text.contains(missingHandler.toString()))
        assertNull(diagnostic.url)
        assertEquals(-1, diagnostic.line)
        assertNull(diagnostic.navigatable)
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
    }

    private data class Diagnostic(
        val category: CompilerMessageCategory, val text: String, val url: String?,
        val line: Int, val column: Int, val navigatable: Navigatable?,
    )

    private class RecordingContext(private val project: Project, private val scope: CompileScope) :
        UserDataHolderBase(), CompileContext {
        val messages = mutableListOf<Diagnostic>()
        var onMessage: () -> Unit = {}
        private val indicator = EmptyProgressIndicator()
        override fun getProject() = project
        override fun getCompileScope() = scope
        override fun getProjectCompileScope() = scope
        override fun getProgressIndicator() = indicator
        override fun addMessage(
            category: CompilerMessageCategory, message: String, url: String?, lineNum: Int, columnNum: Int,
            navigatable: Navigatable?, moduleNames: Collection<String>,
        ) {
            onMessage()
            messages.add(Diagnostic(category, message, url, lineNum, columnNum, navigatable))
        }
        override fun getMessages(category: CompilerMessageCategory): Array<CompilerMessage> = error("Unused")
        override fun getMessageCount(category: CompilerMessageCategory?) = error("Unused")
        override fun getModuleByFile(file: VirtualFile): Module = error("Unused")
        override fun getModuleOutputDirectory(module: Module): VirtualFile? = error("Unused")
        override fun getModuleOutputDirectoryForTests(module: Module): VirtualFile? = error("Unused")
        override fun isMake() = error("Unused")
        override fun isAutomake() = error("Unused")
        override fun isRebuild() = error("Unused")
        override fun isAnnotationProcessorsEnabled() = error("Unused")
    }
}
