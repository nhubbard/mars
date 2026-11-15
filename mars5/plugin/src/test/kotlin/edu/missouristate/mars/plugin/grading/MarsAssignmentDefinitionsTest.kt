package edu.missouristate.mars.plugin.grading

import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.io.File
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.intellij.ScriptDefinitionsProvider
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinitionProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

@TestApplication
class MarsAssignmentDefinitionsTest {
    private val projectFixture = codeInsightProjectFixture()

    @Test
    fun `Kotlin registers MARS grading scripts without a project dependency`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val extension = ScriptDefinitionsProvider.EP_NAME.getExtensions(project)
            .single { it.id == "MARS assignment" }
        assertEquals("MARS assignment", extension.id)
        val definitions = project.service<ScriptDefinitionProvider>()
        fun definitionName(filename: String) = definitions.findDefinition(FileScriptSource(File(filename)))?.name
        assertEquals("MARS assignment", definitionName("worksheets/linked-list checks.kts"))
        assertEquals("MARS assignment", definitionName("review.kts"))
        assertNotEquals("MARS assignment", definitionName("build.gradle.kts"))
        assertNotEquals("MARS assignment", definitionName("settings.gradle.kts"))
        assertNotEquals("MARS assignment", definitionName("setup.main.kts"))
    }
}
