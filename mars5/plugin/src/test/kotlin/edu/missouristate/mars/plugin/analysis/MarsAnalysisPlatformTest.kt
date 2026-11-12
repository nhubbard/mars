package edu.missouristate.mars.plugin.analysis

import com.intellij.openapi.components.service
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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

    @Test
    fun `service disposal closes publication and preserves the supplied parent scope`() = timeoutRunBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val service = MarsAnalysisService(firstProject.get(), scope)
        try {
            assertTrue(service.submit(emptyList()).await())
            assertEquals(1, service.snapshot.generation)
            service.dispose()
            service.dispose()
            assertTrue(scope.isActive)
            assertThrows<IllegalStateException> {
                @Suppress("DeferredResultUnused")
                service.submit(emptyList())
            }
        } finally {
            service.dispose()
            scope.cancel()
        }
    }

    @Test
    fun `native query rejects a scope from a different project`() = timeoutRunBlocking<Unit> {
        val service = firstProject.get().service<MarsAnalysisService>()
        val scope = GlobalSearchScope.projectScope(secondProject.get())
        assertThrows<IllegalArgumentException> {
            service.lookupCandidates(SymbolKey("main", SymbolKind.LABEL), scope)
        }
    }
}
