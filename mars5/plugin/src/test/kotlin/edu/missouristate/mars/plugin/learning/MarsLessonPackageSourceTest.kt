@file:Suppress("JetBrainsInternalApiUsage", "OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import com.intellij.util.net.ssl.CertificateManager
import com.intellij.util.net.ssl.ConfirmingTrustManager
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import edu.missouristate.mars.util.javaClassOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/** Actual TLS and native import dialogs, with temporary trust removed after every test. */
@TestApplication
class MarsLessonPackageSourceTest {
  private val fixture = codeInsightProjectFixture()
  @TempDir lateinit var temporary: Path
  private lateinit var server: HttpsServer
  private lateinit var trust: X509TrustManager
  private val requests = AtomicInteger()
  private val url
    get() = "https://127.0.0.1:${server.address.port}/package.zip"

  private val bytes: ByteArray =
      ByteArrayOutputStream()
          .also { buffer ->
            ZipOutputStream(buffer).use { zip ->
              val manifest =
                  """{"formatVersion":1,"id":"001-course","version":"1.2.3","title":"Course lab","entry":"main.asm","assignment":"assignment.md","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
              for ((name, text) in
                  mapOf(
                      "package.json" to manifest,
                      "main.asm" to ".text\nnop\n",
                      "assignment.md" to "Predict the result.",
                  )) {
                zip.putNextEntry(ZipEntry("001-course/$name"))
                zip.write(text.toByteArray())
                zip.closeEntry()
              }
            }
          }
          .toByteArray()

  @BeforeEach
  fun startCourseServer() {
    // Public test key for this loopback server only; never a production credential.
    val password = "mars-test-only".toCharArray()
    val keys =
        KeyStore.getInstance("PKCS12").apply {
          MarsLessonPackageSourceTest::class
              .java
              .getResourceAsStream("/learning/course-server.p12")!!
              .use { load(it, password) }
        }
    val managers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
          init(keys, password)
        }
    val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
    trust =
        ConfirmingTrustManager.createTrustManagerFromCertificates(
            listOf(keys.getCertificate("course-server") as X509Certificate),
        )
    CertificateManager.getInstance().trustManager.addSystemTrustManager(trust)
    server =
        HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 1).apply {
          httpsConfigurator = HttpsConfigurator(context)
          createContext("/package.zip") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(bytes) }
          }
          start()
        }
  }

  @AfterEach
  fun stopCourseServer() {
    server.stop(0)
    CertificateManager.getInstance().trustManager.removeSystemTrustManager(trust)
  }

  private fun downloads(): Set<Path> =
      Files.list(Path.of(System.getProperty("java.io.tmpdir"))).use { paths ->
        paths.filter { it.fileName.toString().startsWith("mars-lesson-download-") }.toList().toSet()
      }

  @Test
  fun `local sources retain preview and links require a complete HTTPS authority`() {
    val zip = Files.write(temporary.resolve("space in name.zip"), bytes)
    val local = MarsLessonPackageSource.parse("  $zip  ") as MarsLessonPackageSource.Local
    assertEquals(zip, local.archive)
    local.prepare().use { assertEquals("001-course", it.metadata.id) }
    val remote =
        MarsLessonPackageSource.parse("  $url?edition=1  ") as MarsLessonPackageSource.Remote
    assertEquals(URI.create("$url?edition=1"), remote.uri)
    assertTrue(
        MarsLessonPackageSource.parse("HTTPS://example.com/lab.zip")
            is MarsLessonPackageSource.Remote,
    )
    assertTrue(MarsLessonPackageSource.parse("~/course.zip") is MarsLessonPackageSource.Local)
    for (value in
        listOf(
            "",
            "relative.zip",
            "bad\u0000path",
            "http://example.com/a.zip",
            "file:///tmp/a.zip",
            "https:///a.zip",
            "https://user:password@example.com/a.zip",
            "https://example.com/a.zip#step",
            "https://example.com:0/a.zip",
            "https://example.com:65536/a.zip",
            "https://[bad/a.zip",
        )) {
      assertThrows<IllegalArgumentException>(value) { MarsLessonPackageSource.parse(value) }
    }
  }

  @Test
  fun `chunked download produces an immutable preview and removes the temporary transfer`() {
    val before = downloads()
    val source = MarsLessonPackageSource.parse(url)
    source.prepare(MarsLessonPackages.Limits(archiveBytes = bytes.size.toLong())).use { preview ->
      assertEquals("Course lab", preview.metadata.title)
      assertEquals(
          MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
          preview.sha256,
      )
      assertEquals(before, downloads())
      val installed = preview.install(temporary)
      assertEquals(".text\nnop\n", Files.readString(installed.entry))
      Files.writeString(installed.entry, "# student edits\n")
      source.prepare().use { again ->
        assertTrue(again.install(temporary).reused)
        assertEquals("# student edits\n", Files.readString(installed.entry))
      }
    }
    assertEquals(before, downloads())
  }

  @Test
  fun `actual bytes are bounded with missing size headers and gzip expansion`() {
    val before = downloads()
    val source = MarsLessonPackageSource.parse(url)
    assertThrows<IllegalArgumentException> {
      source.prepare(MarsLessonPackages.Limits(archiveBytes = bytes.size - 1L))
    }
    server.createContext("/gzip") { exchange ->
      val compressed =
          ByteArrayOutputStream()
              .also { buffer -> GZIPOutputStream(buffer).use { it.write(bytes) } }
              .toByteArray()
      exchange.responseHeaders.add("Content-Encoding", "gzip")
      exchange.sendResponseHeaders(200, compressed.size.toLong())
      exchange.responseBody.use { it.write(compressed) }
    }
    val gzip = MarsLessonPackageSource.parse(url.replace("package.zip", "gzip"))
    gzip.prepare(MarsLessonPackages.Limits(archiveBytes = bytes.size.toLong())).use {
      assertEquals("001-course", it.metadata.id)
    }
    assertThrows<IllegalArgumentException> {
      gzip.prepare(MarsLessonPackages.Limits(archiveBytes = bytes.size - 1L))
    }
    assertEquals(before, downloads())
  }

  @Test
  fun `redirects may retain HTTPS but cannot downgrade or import another scheme`() {
    val before = downloads()
    for ((path, target) in
        mapOf(
            "/again" to "/package.zip",
            "/downgrade" to url.replace("https:", "http:"),
            "/file" to temporary.resolve("not-a-package").toUri().toString(),
            "/loop" to "/loop",
        )) {
      server.createContext(path) { exchange ->
        exchange.responseHeaders.add("Location", target)
        exchange.sendResponseHeaders(302, -1)
        exchange.close()
      }
    }
    MarsLessonPackageSource.parse(url.replace("package.zip", "again")).prepare().use {
      assertEquals("1.2.3", it.metadata.version)
    }
    assertEquals(1, requests.get())
    assertThrows<IOException> {
      MarsLessonPackageSource.parse(url.replace("package.zip", "downgrade")).prepare()
    }
    assertThrows<IOException> {
      MarsLessonPackageSource.parse(url.replace("package.zip", "file")).prepare()
    }
    assertThrows<IOException> {
      MarsLessonPackageSource.parse(url.replace("package.zip", "loop")).prepare()
    }
    assertEquals(1, requests.get())
    assertEquals(before, downloads())
  }

  @Test
  fun `missing packages malformed responses and cancellation remove transfer files`() {
    val before = downloads()
    assertThrows<IOException> {
      MarsLessonPackageSource.parse(url.replace("package.zip", "missing")).prepare()
    }
    server.createContext("/broken") { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.use { it.write("not a ZIP".toByteArray()) }
    }
    assertThrows<IOException> {
      MarsLessonPackageSource.parse(url.replace("package.zip", "broken")).prepare()
    }
    val canceled = ProgressIndicatorBase().apply { cancel() }
    assertThrows<ProcessCanceledException> {
      ProgressManager.getInstance()
          .runProcess({ MarsLessonPackageSource.parse(url).prepare().close() }, canceled)
    }
    assertEquals(before, downloads())
  }

  @Test
  fun `canceling during a response closes the transfer before package validation`() {
    val before = downloads()
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    server.createContext("/slow") { exchange ->
      exchange.sendResponseHeaders(200, 0)
      exchange.responseBody.use { output ->
        output.write(ByteArray(8))
        output.flush()
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
        output.write(ByteArray(8))
      }
    }
    val indicator = ProgressIndicatorBase()
    Executors.newSingleThreadExecutor().use { executor ->
      val download = executor.submit {
        ProgressManager.getInstance()
            .runProcess(
                {
                  MarsLessonPackageSource.parse(url.replace("package.zip", "slow"))
                      .prepare()
                      .close()
                },
                indicator,
            )
      }
      try {
        assertTrue(started.await(10, TimeUnit.SECONDS))
        assertTrue(downloads().size > before.size)
        indicator.cancel()
        release.countDown()
        val failure = assertThrows<ExecutionException> { download.get(10, TimeUnit.SECONDS) }
        assertTrue(failure.cause is ProcessCanceledException)
      } finally {
        release.countDown()
      }
    }
    assertEquals(before, downloads())
  }

  @Test
  fun `native dialog previews a course download before importing and focuses the starter`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val dialog = MarsLessonPackageFileDialog(project)
          try {
            dialog.archiveField.text = url
            assertNull(dialog.doValidate())
            assertTrue(dialog.source is MarsLessonPackageSource.Remote)
            dialog.archiveField.text = "http://example.com/lab.zip"
            assertTrue(dialog.doValidate() != null)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
          val action = ActionManager.getInstance().getAction("Mars.Learning.ImportPackage")
          val event =
              AnActionEvent.createEvent(
                  action,
                  SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
                  null,
                  ActionPlaces.MAIN_MENU,
                  ActionUiKind.NONE,
                  null,
              )
          for (accept in listOf(false, true)) {
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLessonPackageFileDialog>(
                        javaClassOf<MarsLessonPackageFileDialog>(),
                    ) {
                  override fun doIntercept(dialog: MarsLessonPackageFileDialog) {
                    dialog.archiveField.text = url
                    dialog.close(DialogWrapper.OK_EXIT_CODE)
                  }
                },
            )
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLessonPackagePreviewDialog>(
                        javaClassOf<MarsLessonPackagePreviewDialog>(),
                    ) {
                  override fun doIntercept(dialog: MarsLessonPackagePreviewDialog) {
                    assertTrue(dialog.report.contains("Course lab"))
                    assertTrue(dialog.report.contains("Version: 1.2.3"))
                    dialog.close(
                        if (accept) DialogWrapper.OK_EXIT_CODE else DialogWrapper.CANCEL_EXIT_CODE,
                    )
                  }
                },
            )
            try {
              action.actionPerformed(event)
            } finally {
              UiInterceptors.clear()
            }
            assertEquals(
                accept,
                Files.exists(Path.of(project.basePath!!).resolve("mars-labs/packages")),
            )
          }
          assertEquals(
              "main.asm",
              FileEditorManager.getInstance(project).selectedEditor!!.file.name,
          )
          assertEquals(
              ".text\nnop\n",
              FileEditorManager.getInstance(project).selectedTextEditor!!.document.text,
          )
          var shown = false
          val previous = TestDialogManager.setTestDialog {
            shown = true
            Messages.OK
          }
          UiInterceptors.register(
              object :
                  UiInterceptors.UiInterceptor<MarsLessonPackageFileDialog>(
                      javaClassOf<MarsLessonPackageFileDialog>(),
                  ) {
                override fun doIntercept(dialog: MarsLessonPackageFileDialog) {
                  dialog.archiveField.text = url.replace("package.zip", "missing")
                  dialog.close(DialogWrapper.OK_EXIT_CODE)
                }
              },
          )
          try {
            action.actionPerformed(event)
          } finally {
            UiInterceptors.clear()
            TestDialogManager.setTestDialog(previous)
          }
          assertTrue(shown)
        }
      }
}
