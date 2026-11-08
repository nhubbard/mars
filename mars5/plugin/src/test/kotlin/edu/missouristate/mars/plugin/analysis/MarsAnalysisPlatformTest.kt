package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

@TestApplication
class MarsAnalysisPlatformTest {
    private val firstProject = projectFixture()
    private val secondProject = projectFixture()

    @Test
    fun `platform creates one analysis service per project`() {
        val first = firstProject.get().service<MarsAnalysisService>()
        val second = secondProject.get().service<MarsAnalysisService>()
        assertSame(first, firstProject.get().service<MarsAnalysisService>())
        assertNotSame(first, second)
        assertNotSame(first.snapshot.index, second.snapshot.index)
    }
}
