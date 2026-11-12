package edu.missouristate.mars.plugin.analysis

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.time.Duration.Companion.milliseconds

class AnalysisCoordinatorTest {
    @Test
    fun `new request cancels old computation and publishes only new input`() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var calls = 0
            val coordinator = AnalysisCoordinator(scope) { facts, checkCancelled ->
                if (++calls == 1) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                IndexSnapshot.build(facts, checkCancelled)
            }
            try {
                val old = coordinator.submit(emptyList())
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val source = SourceId("file:///new.s")
                val facts = mutableListOf<IndexContribution>(
                    IndexContribution.Declarations(source, 2, emptyList()),
                )
                val latest = coordinator.submit(facts)
                facts.clear()
                release.countDown()
                assertTrue(withTimeout(10_000.milliseconds) { latest.await() })
                assertTrue(old.isCancelled)
                assertEquals(2, coordinator.snapshot.generation)
                assertTrue(coordinator.snapshot.index.inScope(setOf(source))
                    .lookup(SymbolKey("x", SymbolKind.LABEL)).declarations.complete)
            } finally {
                release.countDown()
                coordinator.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `failed analysis preserves published result and propagates failure`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var fail = false
        val expected = IllegalArgumentException("bad producer")
        val coordinator = AnalysisCoordinator(scope) { facts, checkCancelled ->
            if (fail) throw expected
            IndexSnapshot.build(facts, checkCancelled)
        }
        try {
            assertTrue(coordinator.submit(emptyList()).await())
            val before = coordinator.snapshot
            fail = true
            val error = assertThrows<IllegalArgumentException> { coordinator.submit(emptyList()).await() }
            assertEquals(expected.message, error.message)
            assertSame(before, coordinator.snapshot)
        } finally {
            coordinator.close()
            scope.cancel()
        }
    }

    @Test
    fun `closing during computation prevents publication even when builder ignores cancellation`() = runBlocking<Unit> {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val coordinator = AnalysisCoordinator(scope) { _, _ ->
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                IndexSnapshot.build(emptyList())
            }
            try {
                val task = coordinator.submit(emptyList())
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                coordinator.close()
                release.countDown()
                withTimeout(10_000.milliseconds) { task.join() }
                assertTrue(task.isCancelled)
                assertEquals(1, coordinator.snapshot.generation)
                assertThrows<IllegalStateException> {
                    @Suppress("DeferredResultUnused")
                    coordinator.submit(emptyList())
                }
            } finally {
                release.countDown()
                coordinator.close()
                scope.cancel()
            }
        }
    }
}
