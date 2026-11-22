@file:Suppress("JetBrainsInternalApiUsage", "OverrideOnly")

package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import com.intellij.mock.MockProject
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.replaceService
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import edu.missouristate.mars.plugin.debug.MarsDebugRunner
import edu.missouristate.mars.plugin.debug.MarsLineBreakpointType
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.MarsRunService
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.Disposable
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.impl.LaterInvocator
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.UiInterceptors
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.xmlb.XmlSerializer
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.Timer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import training.dsl.LearningBalloonConfig
import training.dsl.LessonContext
import training.dsl.TaskContext
import training.dsl.TaskRuntimeContext
import training.dsl.PreviousTaskInfo
import training.dsl.RuntimeTextContext
import training.lang.LangSupport
import training.lang.LangSupportBean
import training.learn.CourseManager
import training.statistic.LessonStartingWay
import training.ui.LearningUiManager

@TestApplication
class MarsDownloadedLessonsTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    @TempDir lateinit var temporary: Path
    private val prediction = MarsPrediction("value", "A < B & C?", listOf(
        MarsPredictionChoice("a", "<wrong>", "A < B"), MarsPredictionChoice("b", "correct", "B & C")), "b")
    private val hints = listOf(MarsLessonHint("one", "First <hint>"), MarsLessonHint("two", "Second & hint"))
    private val identity = MarsLessonIdentity("course", "byte", "1.0.0", "a".repeat(64))
    private fun callbacks(text: String): List<() -> Unit> = Regex("<callback id=\"([0-9]+)\">").findAll(text).map {
        requireNotNull(LearningUiManager.getAndClearCallback(it.groupValues[1]))
    }.toList()
    private fun text(project: Project, identity: MarsLessonIdentity = this.identity, active: () -> Boolean = { true },
                     feedback: (String) -> Unit = {}, complete: () -> Unit = {}) =
        MarsInlinePrediction.text(project, identity, prediction, "Explain < & >", hints, active, feedback, complete)

    @Test
    fun `scoped callbacks escape text and keep hints separate from answer credit`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val project = projects.get(); val progress = project.service<MarsLearningProgress>()
            val policy = project.service<MarsLearningPolicy>(); val feedback = mutableListOf<String>(); var completed = 0
            assertFalse(MarsInlinePrediction.answered(project, identity, prediction))
            progress.open(identity, identity.contentSha256)
            progress.recordChoice(identity, "other-question", "a")
            val rendered = text(project, feedback = feedback::add, complete = { completed++ })
            assertTrue(rendered.contains("Explain &lt; &amp; &gt;")); assertFalse(rendered.contains("<wrong>"))
            val controls = callbacks(rendered); assertEquals(3, controls.size)
            controls[0](); assertEquals("A &lt; B", feedback.last()); assertEquals(0, completed)
            policy.update(releasedHints = 0); controls[2](); assertTrue(progress.snapshot(identity)!!.revealedHints.isEmpty())
            policy.update(releasedHints = 1); controls[2](); assertEquals("First &lt;hint&gt;", feedback.last())
            controls[2](); assertTrue(feedback.last().contains("No further hints"))
            policy.update(releasedHints = 2); controls[2](); assertEquals("Second &amp; hint", feedback.last())
            assertEquals(setOf("hints/value/one", "hints/value/two"), progress.snapshot(identity)!!.revealedHints)
            assertFalse(MarsInlinePrediction.answered(project, identity, prediction))
            controls[1](); assertEquals(1, completed); assertTrue(MarsInlinePrediction.answered(project, identity, prediction))
            controls.forEach { it() }; assertEquals(1, completed)
            val resumed = text(project, feedback = { error("Already completed") }, complete = { error("Already completed") })
            assertTrue(resumed.contains("First &lt;hint&gt;")); callbacks(resumed).forEach { it() }
            assertEquals(3, progress.snapshot(identity)!!.quizAttempts.size)
            val next = identity.copy(contentSha256 = "b".repeat(64)); callbacks(text(project, next))
            assertFalse(MarsInlinePrediction.answered(project, next, prediction))
            assertNull(progress.snapshot(MarsPredictions.identity))
        }
    }

    @Test
    fun `inactive and closed-project hint controls cannot change saved history`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val project = projects.get(); val progress = project.service<MarsLearningProgress>()
            val controls = callbacks(text(project, active = { false }, feedback = { error("Inactive") }, complete = { error("Inactive") }))
            controls.forEach { it() }; assertTrue(progress.snapshot(identity)!!.quizAttempts.isEmpty())
            assertTrue(progress.snapshot(identity)!!.revealedHints.isEmpty())
            val closed = ProjectManagerEx.getInstanceEx().newProject(temporary.resolve("closed"), OpenProjectTask {
                isNewProject = true; useDefaultProjectAsTemplate = false
            })!!
            val stale = callbacks(text(closed, feedback = { error("Closed") }, complete = { error("Closed") }))
            edtWriteAction { Disposer.dispose(closed) }; stale.forEach { it() }
        }
    }

    @Test
    fun `anchors follow label blocks and occurrences across edited source`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val source = ".eqv constant, 1\n.text\nmain:\nandi \$t0, \$v0, 1\ninterpret:\nANDI \$t0, \$v0, 255\nandi \$t1, \$v0, 15\nreport:\nmove \$a0, \$t0\nlast:\nnop\n"
            fixture.configureByText("anchors.asm", source)
            val first = MarsLessonAnchor("interpret", "andi", 0)
            val second = first.copy(occurrence = 1)
            val a = MarsLessonAnchors.range(fixture.editor, first)!!; val b = MarsLessonAnchors.range(fixture.editor, second)!!
            assertTrue(source.substring(a.startOffset, a.endOffset).startsWith("ANDI")); assertTrue(b.startOffset > a.startOffset)
            assertNull(MarsLessonAnchors.range(fixture.editor, first.copy(occurrence = 2)))
            assertNull(MarsLessonAnchors.range(fixture.editor, first.copy(label = "missing")))
            assertNull(MarsLessonAnchors.range(fixture.editor, first.copy(mnemonic = "move")))
            assertNotNull(MarsLessonAnchors.range(fixture.editor, MarsLessonAnchor("report", "move", 0)))
            assertNotNull(MarsLessonAnchors.range(fixture.editor, MarsLessonAnchor("last", "nop", 0)))
            val geometry = MarsLessonAnchors.highlight(fixture.editor, first)(fixture.editor.contentComponent)!!
            assertTrue(geometry.width > 0); assertEquals(fixture.editor.lineHeight, geometry.height)
            WriteCommandAction.runWriteCommandAction(projects.get()) { fixture.editor.document.insertString(0, "# a new comment\n") }
            assertNull(MarsLessonAnchors.range(fixture.editor, first))
            PsiDocumentManager.getInstance(projects.get()).commitAllDocuments()
            assertTrue(MarsLessonAnchors.range(fixture.editor, first)!!.startOffset > a.startOffset)
            WriteCommandAction.runWriteCommandAction(projects.get()) { fixture.editor.document.insertString(0, "interpret:\nnop\n") }
            PsiDocumentManager.getInstance(projects.get()).commitAllDocuments()
            assertNull(MarsLessonAnchors.range(fixture.editor, first))
            assertNull(MarsLessonAnchors.highlight(fixture.editor, first)(fixture.editor.contentComponent))
        }
    }

    @Test
    fun `anchor geometry acquires its own read access and tolerates unattached documents`() = timeoutRunBlocking {
        val observed = CompletableDeferred<Rectangle?>()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("timer.asm", ".text\ninterpret:\nandi \$t0, \$v0, 255\n")
            val editor = fixture.editor; val anchor = MarsLessonAnchor("interpret", "andi", 0)
            Timer(1) {
                try {
                    assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                    observed.complete(MarsLessonAnchors.highlight(editor, anchor)(editor.contentComponent))
                } catch (error: Throwable) { observed.completeExceptionally(error) }
            }.apply { isRepeats = false; start() }
            val factory = EditorFactory.getInstance()
            val detached = factory.createEditor(factory.createDocument("nop"))
            try { assertNull(MarsLessonAnchors.range(detached, anchor)) } finally { factory.releaseEditor(detached) }
            val noPsi = factory.createEditor(factory.createDocument("nop"), projects.get())
            try { assertNull(MarsLessonAnchors.range(noPsi, anchor)) } finally { factory.releaseEditor(noPsi) }
        }
        assertNotNull(observed.await())
    }

    private val plan = """{"formatVersion":1,"introduction":"Start here.","conclusion":"Explain the result.","steps":[{"id":"value","explanation":"Follow the byte.","question":"Which value?","choices":[{"id":"a","text":"one","feedback":"One."},{"id":"b","text":"two","feedback":"Two."}],"correctChoice":"b"}]}"""
    private fun install(project: Project, native: Boolean = true, version: String = "1.0.0"): MarsDownloadedLessonContent {
        val file = Files.createTempFile(temporary, "package-", ".zip")
        val optional = if (native) "\"nativeLesson\":\"lesson.json\"," else ""
        val json = """{"formatVersion":1,"id":"004-byte-inspector","version":"$version","title":"Byte <lesson>","entry":"main.asm","assignment":"assignment.md",$optional"environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
        ZipOutputStream(Files.newOutputStream(file)).use { zip ->
            for ((name, text) in mapOf("package.json" to json, "main.asm" to ".text\nmain:\nnop\nli \$a0, 1\nli \$v0, 1\nsyscall\nli \$v0, 10\nsyscall\n", "assignment.md" to "Explain.", "lesson.json" to plan)) {
                zip.putNextEntry(ZipEntry("004-byte-inspector/$name")); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return MarsLessonPackages.prepare(file).use { MarsDownloadedLessonContent(it.install(Path.of(project.basePath!!)),
            it.nativeLesson ?: MarsNativeLessonPlan.parse(plan.toByteArray())) }
    }

    @Test
    fun `selection revalidates retained definitions while preserving student edits across state reload`() {
        val project = projects.get(); val content = install(project)
        val selection = project.service<MarsDownloadedLessonSelection>(); assertFalse(selection.hasSelection)
        selection.remember(content.installed); assertTrue(selection.hasSelection)
        Files.writeString(content.installed.entry, "# student edit\n")
        val saved = XmlSerializer.serialize(selection.getState())
        val reopened = MarsDownloadedLessonSelection(project)
        reopened.loadState(XmlSerializer.deserialize(saved, MarsDownloadedLessonSelection.Selection::class.java))
        val loaded = reopened.load(); assertEquals(content.identity, loaded.identity)
        assertEquals("# student edit\n", Files.readString(loaded.installed.entry))
        val module = MarsDownloadedLessons.module(loaded); val lesson = module.lessons.single()
        assertEquals("Byte <lesson>", module.name)
        assertEquals("Byte <lesson>", lesson.name)
        assertEquals("mars.downloaded", module.id); assertNull(module.sampleFilePath)
        assertEquals("left", module.preferredLearnWindowAnchor(project).toString())
        assertTrue(lesson.alwaysRestart); assertFalse(lesson.properties.openFileAtStart)
        lesson.prepare(project); project.service<MarsLearningProgress>().recordChoice(content.identity, "value", "b")
        lesson.prepare(project); assertEquals(1, project.service<MarsLearningProgress>().snapshot(content.identity)!!.quizAttempts.size)
        val another = install(project, version = "1.1.0"); assertFalse(another.identity == loaded.identity)
        assertEquals("# student edit\n", Files.readString(loaded.installed.entry))
    }

    @Test
    fun `selection rejects missing changed moved and nonnative editions without recreating source`() {
        val project = projects.get(); val selection = project.service<MarsDownloadedLessonSelection>()
        for (bad in listOf("", ".", "..", "/outside", "x/../y", "bad\u0000path")) {
            selection.loadState(MarsDownloadedLessonSelection.Selection(bad))
            assertThrows<IllegalArgumentException> { selection.load() }
        }
        val content = install(project); selection.remember(content.installed)
        Files.delete(content.installed.entry)
        assertThrows<IllegalArgumentException> { selection.load() }; assertFalse(Files.exists(content.installed.entry))
        val other = install(project, version = "1.1.0"); selection.remember(other.installed)
        val archive = other.installed.directory.resolve(".mars-package.zip")
        val moved = Files.createDirectory(Path.of(project.basePath!!).resolve("moved"))
        Files.copy(archive, moved.resolve(".mars-package.zip"))
        selection.loadState(MarsDownloadedLessonSelection.Selection("moved"))
        assertThrows<IllegalArgumentException> { selection.load() }
        val legacy = install(project, native = false, version = "1.2.0"); selection.remember(legacy.installed)
        assertThrows<IllegalArgumentException> { selection.load() }
        selection.remember(other.installed); Files.writeString(archive, "not the retained archive")
        assertThrows<Exception> { selection.load() }
    }

    @ParameterizedTest
    @ValueSource(strings = ["prediction", "run", "debug", "assessment", "independent", "source-run", "source-debug"])
    fun `downloaded DSL opens its entry and binds only its own answers and source anchors`(mode: String) = timeoutRunBlocking {
        val withRun = mode == "run" || mode == "source-run"
        val withDebug = mode == "debug" || mode == "source-debug"
        val source = if (mode.startsWith("source-")) "range.asm" else null
        val independent = mode == "independent"
        val withAssessment = mode == "assessment" || independent
        withContext(Dispatchers.EDT) {
            val project = projects.get()
            val installed = install(project)
            val anchored = (MarsNativeLessonPlan.parse(plan.toByteArray()).steps.single() as MarsNativeLessonStep).copy(
                anchor = MarsLessonAnchor("main", "nop", 0), hints = hints)
            val run = MarsNativeRunStep("run-one", "Run <this> program.", emptyList(), "1", "One printed.", null, hints, source)
            val debug = MarsNativeDebugStep("debug-one", "Debug <this> program.", emptyList(), listOf(
                MarsNativeDebugStop("before-load", "Inspect <the first load>.", MarsLessonAnchor("main", "li", 0),
                    mapOf("\$zero" to 0L), "First boundary.", hints),
                MarsNativeDebugStop("after-load", "Inspect <the next load>.", MarsLessonAnchor("main", "li", 1),
                    mapOf("\$a0" to 1L), "One is retained.", hints),
            ), "Explain the changed argument.", hints, source)
            val assessment = MarsNativeAssessmentStep("independent", "Check <this> program.", "main.asm", "cases.json",
                listOf(ConsoleCheckCase("one", "", "1", 20_000)), "Explain the result.", hints)
            val steps = when { withAssessment -> listOf(anchored, assessment); withRun -> listOf(anchored, run); withDebug -> listOf(anchored, debug); else -> listOf(anchored) }
            val content = installed.copy(plan = MarsNativeLessonPlan("Intro < & >", "End < & >", steps))
            val virtual = withContext(Dispatchers.IO) {
                val path = source?.let { installed.installed.directory.resolve(it).also { target ->
                    Files.copy(installed.installed.entry, target)
                } } ?: installed.installed.entry
                VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!
            }
            fixture.configureFromExistingVirtualFile(virtual)
            val file = fixture.file
            val lessons = MarsDownloadedLessons.module(content).lessons
            val lesson = if (independent) lessons.last() else lessons.first()
            lesson.prepare(project)
            // The native registrar is internal. Reflection is confined to tests;
            // the packaged implementation only calls the public task DSL.
            val loader = LessonContext::class.java.classLoader
            val executorClass = Class.forName("training.dsl.impl.LessonExecutor", true, loader)
            val executor = executorClass.constructors.single().newInstance(lesson, project, fixture.editor, file.virtualFile)
            try {
                val contextClass = Class.forName("training.dsl.impl.LessonContextImpl", true, loader)
                lesson.fullLessonContent(contextClass.constructors.single().newInstance(executor) as LessonContext)
                val tasks = executorClass.getDeclaredField("taskActions").apply { isAccessible = true }.get(executor) as List<*>
                val recorderClass = Class.forName("training.learn.ActionsRecorder", true, loader)
                val recorder = recorderClass.constructors.single().newInstance(project, fixture.editor.document, executor)
                val previous: () -> PreviousTaskInfo = { error("No restore state") }
                val runtime = TaskRuntimeContext::class.java.constructors.single { it.parameterCount == 4 }
                    .newInstance(executor, recorder, {}, previous) as TaskRuntimeContext
                val texts = mutableListOf<RuntimeTextContext.() -> String?>()
                val eagerTexts = mutableListOf<String>()
                val checks = mutableListOf<TaskRuntimeContext.() -> Boolean>()
                val timers = mutableListOf<TaskRuntimeContext.() -> Boolean>()
                val preparations = mutableListOf<CompletableFuture<Boolean>>()
                val taskContext = object : TaskContext() {
                    override val project: Project get() = projects.get()
                    override fun before(preparation: TaskRuntimeContext.() -> Unit) { preparation(runtime) }
                    override fun runtimeText(callback: RuntimeTextContext.() -> String?) { texts += callback }
                    override fun text(text: String, useBalloon: LearningBalloonConfig?) { eagerTexts += text }
                    override fun stateCheck(checkState: TaskRuntimeContext.() -> Boolean) =
                        super.stateCheck(checkState).also { checks += checkState }
                    override fun timerCheck(delayMillis: Int, checkState: TaskRuntimeContext.() -> Boolean): CompletableFuture<Boolean> {
                        timers += checkState
                        return super.timerCheck(delayMillis, checkState)
                    }
                    override fun addStep(step: CompletableFuture<Boolean>) { preparations += step }
                }
                for (task in tasks) {
                    val field = task!!.javaClass.getDeclaredField("taskContent").apply { isAccessible = true }
                    @Suppress("UNCHECKED_CAST")
                    (field.get(task) as? (TaskContext.() -> Unit))?.invoke(taskContext)
                    // Match native task ordering: finish a source-opening future
                    // before the following task binds its execution observer.
                    for (future in preparations) assertTrue(future.await())
                    preparations.clear()
                }
                assertEquals(virtual, runtime.virtualFile)
                assertEquals(if (independent) 0 else 1, checks.size)
                if (!independent) assertFalse(checks.single()(runtime))
                val rendered = eagerTexts + texts.mapNotNull { it(RuntimeTextContext(runtime)) }
                if (independent) {
                    assertTrue(rendered.any { it.contains("Write and investigate this program") })
                    assertFalse(rendered.any { it.contains("Which value?") })
                } else {
                    assertTrue(rendered.any { it.contains("Intro &lt; &amp; &gt;") }, "Introduction must be escaped")
                    assertTrue(rendered.any { it.contains("End &lt; &amp; &gt;") }, "Conclusion must be escaped")
                }
                val controls = if (independent) emptyList() else callbacks(rendered.single { it.contains("Which value?") })
                val runControls = if (withRun) callbacks(rendered.single { it.contains("Run &lt;this&gt; program.") }) else emptyList()
                val assessmentControls = if (withAssessment) callbacks(rendered.single { it.contains("Check &lt;this&gt; program.") }) else emptyList()
                val debugControls = if (withDebug) callbacks(rendered.single { it.contains("Debug &lt;this&gt; program.") }) else emptyList()
                rendered.filter { it.contains("<callback") && !it.contains("Which value?") && !it.contains("Run &lt;this&gt; program.") &&
                    !it.contains("Debug &lt;this&gt; program.") && !it.contains("Check &lt;this&gt; program.") }.forEach { callbacks(it) }
                if (!independent) {
                    controls[0](); assertFalse(checks.single()(runtime))
                    controls[1](); assertTrue(checks.single()(runtime))
                }
                if (withDebug) {
                    assertEquals(3, timers.size)
                    assertTrue(rendered.single { it.contains("Debug &lt;this&gt; program.") }
                        .contains("</callback>&#10;Inspect &lt;the first load&gt;."))
                    assertTrue(timers.none { it(runtime) })
                    debugControls.forEach { it() }
                    val history = project.service<MarsLearningProgress>()
                    assertEquals(setOf("hints/debug-one/one", "hints/debug-one/before-load/one"),
                        history.snapshot(content.debugIdentity(debug))!!.revealedHints)
                    val debugLife = Disposer.newDisposable()
                    var session: XDebugSession? = null
                    val started = CompletableDeferred<XDebugSession>()
                    val stopped = CompletableDeferred<Unit>()
                    val paused = Channel<Unit>(Channel.UNLIMITED)
                    try {
                        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
                        WriteCommandAction.runWriteCommandAction(project) {
                            XDebuggerManager.getInstance(project).breakpointManager.addLineBreakpoint(type, file.virtualFile.url, 2, null)
                        }
                        val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
                        config.options.sourcePath = file.virtualFile.path
                        val env = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
                        project.messageBus.connect(debugLife).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                            override fun processStarting(executorId: String, environment: ExecutionEnvironment, handler: ProcessHandler) {
                                if (environment !== env) return
                                val current = XDebuggerManager.getInstance(project).debugSessions.single { it.debugProcess.processHandler === handler }
                                current.addSessionListener(object : XDebugSessionListener {
                                    override fun sessionPaused() { paused.trySend(Unit) }
                                    override fun sessionStopped() { stopped.complete(Unit) }
                                }, debugLife)
                                started.complete(current)
                            }
                        })
                        assertTrue(env.runner is MarsDebugRunner)
                        env.runner.execute(env)
                        val current = started.await()
                        session = current
                        paused.receive(); assertTrue(timers.none { it(runtime) })
                        UIUtil.dispatchAllInvocationEvents() // Deliver boundary feedback through the native lesson executor.
                        current.stepOver(false); paused.receive()
                        assertTrue(timers[0](runtime)); assertFalse(timers[1](runtime))
                        current.stepOver(false); paused.receive()
                        assertTrue(timers.all { it(runtime) })
                        debugControls.forEach { it() }
                        assertEquals(2, history.snapshot(content.debugIdentity(debug))!!.revealedHints.size)
                        val gate = texts.first { it(RuntimeTextContext(runtime)) == null }
                        assertNull(gate(RuntimeTextContext(runtime)))
                        WriteCommandAction.runWriteCommandAction(project) { fixture.editor.document.insertString(0, "# changed\n") }
                        assertTrue(timers.none { it(runtime) })
                        assertTrue(gate(RuntimeTextContext(runtime))!!.contains("An earlier execution observation"))
                    } finally {
                        session?.stop()
                        if (session != null) stopped.await()
                        Disposer.dispose(debugLife)
                        paused.close()
                    }
                    Disposer.dispose(recorder as Disposable)
                    debugControls.forEach { it() } // Retired task controls cannot reveal another hint after an edit.
                }
                if (withRun) {
                    assertEquals(2, timers.size)
                    assertTrue(timers.none { it(runtime) })
                    assertTrue(rendered.any { it.contains("Run &lt;this&gt; program.") })
                    assertTrue(rendered.any { it.contains("An earlier execution observation") })
                    runControls.single()()
                    assertTrue(project.service<MarsLearningProgress>().snapshot(content.runIdentity(run))!!.revealedHints.contains("hints/run-one/one"))
                    var observed: MarsDownloadedRunCheckpoint? = null
                    taskContext.downloadedRun(content, run.copy(stdout = "2")) { observed = it }
                    val service = project.service<MarsLearningExecution>()
                    val scope = MarsLearningExecution::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(service) as CoroutineScope
                    suspend fun launch(path: Path = content.source(run)) {
                        val process = project.service<MarsRunService>().createProcess(path, memoryDumps = emptyList())
                        val stopped = CompletableDeferred<Unit>()
                        process.addProcessListener(object : ProcessListener {
                            override fun processTerminated(event: ProcessEvent) { stopped.complete(Unit) }
                        })
                        observed!!.starting(DefaultRunExecutor.EXECUTOR_ID, process)
                        process.startNotify(); stopped.await()
                        scope.coroutineContext[Job]!!.children.toList().joinAll()
                    }
                    launch(); assertFalse(observed!!.passed())
                    taskContext.downloadedRun(content, run) { observed = it }
                    if (source != null) { launch(content.installed.entry); assertFalse(observed!!.passed()) }
                    launch(); assertTrue(requireNotNull(observed).passed())
                    runControls.single()() // Completed execution cannot reveal another hint.
                    assertEquals(1, project.service<MarsLearningProgress>().snapshot(content.runIdentity(run))!!.revealedHints.size)
                    assertTrue(timers.all { it(runtime) })
                    val gate = texts.first { it(RuntimeTextContext(runtime)) == null }
                    assertNull(gate(RuntimeTextContext(runtime)))
                    WriteCommandAction.runWriteCommandAction(project) { fixture.editor.document.insertString(0, "# changed\n") }
                    assertTrue(timers.none { it(runtime) })
                    assertTrue(gate(RuntimeTextContext(runtime))!!.contains("An earlier execution observation"))
                    taskContext.downloadedRun(content, run.copy(stdout = "2")) { observed = it }
                    val modal = Any()
                    LaterInvocator.enterModal(modal)
                    try {
                        withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { launch() }
                        Disposer.dispose(recorder as Disposable)
                    } finally { LaterInvocator.leaveModal(modal) }
                    UIUtil.dispatchAllInvocationEvents()
                    runControls.single()() // Retired controls cannot reveal another hint.
                    assertEquals(1, project.service<MarsLearningProgress>().snapshot(content.runIdentity(run))!!.revealedHints.size)
                }
                if (withAssessment) {
                    val history = project.service<MarsLearningProgress>()
                    val assessmentId = content.assessmentIdentity(assessment)
                    val scope = MarsLearningExecution::class.java.getDeclaredField("scope").apply { isAccessible = true }
                        .get(project.service<MarsLearningExecution>()) as CoroutineScope
                    suspend fun waitChecks() { scope.coroutineContext[Job]!!.children.toList().joinAll() }
                    assertEquals(if (independent) 1 else 2, timers.size)
                    assertTrue(rendered.single { it.contains("Check &lt;this&gt; program.") }
                        .contains("Check this program&#10;</callback>"))
                    assertTrue(timers.none { it(runtime) })
                    assessmentControls[1]()
                    assertEquals(setOf("hints/independent/one"), history.snapshot(assessmentId)!!.revealedHints)
                    val other = fixture.addFileToProject("other.asm", ".text\nmain: nop\n").virtualFile
                    project.service<MarsLearningExecution>().openLessonFile(other.toNioPath()).await()
                    assessmentControls[0](); waitChecks()
                    assertEquals(0, history.snapshot(assessmentId)!!.completedAssessments)
                    FileEditorManager.getInstance(project).let { manager -> manager.openFiles.forEach(manager::closeFile) }
                    assessmentControls[0](); waitChecks()
                    assertEquals(0, history.snapshot(assessmentId)!!.completedAssessments)
                    project.service<MarsLearningExecution>().openLessonFile(content.installed.entry).await()
                    val policy = project.service<MarsLearningPolicy>()
                    policy.update(requirePredictions = true)
                    history.reset(content.identity)
                    assessmentControls[0](); waitChecks()
                    assertEquals(0, history.snapshot(assessmentId)!!.completedAssessments)
                    history.open(content.identity, content.identity.contentSha256)
                    history.recordChoice(content.identity, anchored.prediction.id, "b")
                    val original = fixture.editor.document.text
                    WriteCommandAction.runWriteCommandAction(project) {
                        fixture.editor.document.setText(original.replace("li \$a0, 1", "li \$a0, 2"))
                    }
                    assessmentControls[0](); assessmentControls[0](); waitChecks()
                    assertEquals(1, history.snapshot(assessmentId)!!.completedAssessments)
                    assertTrue(timers.none { it(runtime) })
                    WriteCommandAction.runWriteCommandAction(project) { fixture.editor.document.setText(original) }
                    assessmentControls[0](); waitChecks()
                    assertEquals(2, history.snapshot(assessmentId)!!.completedAssessments)
                    assertTrue(timers.all { it(runtime) })
                    assessmentControls[1]()
                    assertEquals(1, history.snapshot(assessmentId)!!.revealedHints.size)
                    WriteCommandAction.runWriteCommandAction(project) { fixture.editor.document.insertString(0, "# changed\n") }
                    assertTrue(timers.none { it(runtime) })
                    Disposer.dispose(recorder as Disposable)
                    assessmentControls.forEach { it() }; waitChecks()
                    assertEquals(2, history.snapshot(assessmentId)!!.completedAssessments)
                }
                if (!withRun && !withDebug && !withAssessment) {
                    val missing = anchored.copy(anchor = MarsLessonAnchor("missing", "nop", 0))
                    taskContext.downloadedPrediction(content.identity, missing, content.installed.entry)
                    callbacks(texts.last()(RuntimeTextContext(runtime))!!)
                    taskContext.downloadedPrediction(content.identity, anchored.copy(anchor = null), content.installed.entry)
                    val retired = callbacks(texts.last()(RuntimeTextContext(runtime))!!)
                    Disposer.dispose(recorder as Disposable)
                    retired.forEach { it() }
                }
            } finally { Disposer.dispose(executor as Disposable) }
        }
    }

    @Test
    fun `missing MARS native language support cannot select another language`() {
        val content = install(projects.get())
        val lifetime = Disposer.newDisposable()
        try {
            ExtensionTestUtil.maskExtensions(ExtensionPointName<LangSupportBean>(LangSupport.EP_NAME), emptyList(), lifetime)
            assertThrows<IllegalStateException> { MarsDownloadedLessons.module(content) }
        } finally { Disposer.dispose(lifetime) }
    }

    @Test
    fun `native import preview announces predictions and selects the content edition`() = timeoutRunBlocking {
        val project = projects.get(); val content = install(project)
        val archive = content.installed.directory.resolve(".mars-package.zip")
        withContext(Dispatchers.EDT) {
            val action = ActionManager.getInstance().getAction("Mars.Learning.ImportPackage")
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsLessonPackageFileDialog>(MarsLessonPackageFileDialog::class.java) {
                override fun doIntercept(dialog: MarsLessonPackageFileDialog) {
                    dialog.archiveField.text = archive.toString(); dialog.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsLessonPackagePreviewDialog>(MarsLessonPackagePreviewDialog::class.java) {
                override fun doIntercept(dialog: MarsLessonPackagePreviewDialog) {
                    assertTrue(dialog.report.contains("1 guided activities"))
                    dialog.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            try {
                action.actionPerformed(AnActionEvent.createEvent(action,
                    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
                    null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null))
            } finally { UiInterceptors.clear() }
            assertEquals(content.identity, project.service<MarsDownloadedLessonSelection>().load().identity)
        }
    }

    @Test
    fun `continue action is project aware reports invalid selection and prepares a native lesson`() = timeoutRunBlocking {
        val project = projects.get(); val action = ActionManager.getInstance().getAction("Mars.Learning.ContinueDownloaded")
        fun event(data: DataContext) = AnActionEvent.createEvent(action, data, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
        withContext(Dispatchers.EDT) {
            val absent = event(DataContext.EMPTY_CONTEXT); action.update(absent); assertFalse(absent.presentation.isEnabled); action.actionPerformed(absent)
            val normal = event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
            action.update(normal); assertFalse(normal.presentation.isEnabled)
            var shown = false; val previous = TestDialogManager.setTestDialog { shown = true; Messages.OK }
            try { action.actionPerformed(normal) } finally { TestDialogManager.setTestDialog(previous) }
            assertTrue(shown)
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            val missing = install(project); project.service<MarsDownloadedLessonSelection>().remember(missing.installed)
            action.update(normal); assertTrue(normal.presentation.isEnabled)
            val retained = missing.installed.directory.resolve(".mars-package.zip")
            for (corrupt in listOf(null, "{bad", """{"formatVersion":1,"other":[}""")) {
                if (corrupt == null) Files.delete(retained)
                else ZipOutputStream(Files.newOutputStream(retained)).use { zip ->
                    zip.putNextEntry(ZipEntry("004-byte-inspector/package.json"))
                    zip.write(corrupt.toByteArray()); zip.closeEntry()
                }
                shown = false
                val errorDialog = TestDialogManager.setTestDialog { shown = true; Messages.OK }
                try { action.actionPerformed(normal) } finally { TestDialogManager.setTestDialog(errorDialog) }
                assertTrue(shown)
            }
        }
        val content = install(project, version = "2.0.0")
        withContext(Dispatchers.EDT) {
            // The headless manager normally drops queued callbacks. Capture and dispatch
            // the actual callback so this test also exercises the production queue route.
            val lifetime = Disposer.newDisposable()
            val queued = mutableListOf<Runnable>()
            try {
                project.replaceService(ToolWindowManager::class.java, object : ToolWindowHeadlessManagerImpl(project) {
                    override fun invokeLater(runnable: Runnable) { queued += runnable }
                }, lifetime)
                project.service<MarsDownloadedLessonSelection>().remember(content.installed)
                action.actionPerformed(event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()))
                queued.single().run()
            } finally { Disposer.dispose(lifetime) }
        }
        withTimeout(10_000) { while (project.service<MarsLearningProgress>().snapshot(content.identity) == null) delay(50) }
        withContext(Dispatchers.EDT) {
            CourseManager.instance.openLesson(project, null, LessonStartingWay.LEARN_TAB)
            val lifetime = Disposer.newDisposable()
            val closed = MockProject(null, lifetime)
            assertThrows<IllegalArgumentException> { MarsDownloadedLessonSelection(closed).load() }
            Disposer.dispose(lifetime)
            MarsDownloadedLessons.openNow(closed, content)
        }
    }
}
