package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.junit5.TestApplication
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.zip.UnicodePathExtraField
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsLessonPackagesTest {
    @TempDir lateinit var temporary: Path
    private val id = "001-console-calculation"
    private val manifest = """{"formatVersion":1,"id":"001-console-calculation","version":"0.2.0","title":"Console calculation","entry":"starter/main.asm","assignment":"assignment.md","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"},"steps":[],"publicChecks":["checks/public.kts"]}"""
    private val source = ".text\nli \$v0, 10\nsyscall\n"
    private fun files(json: String = manifest) = linkedMapOf(
        "$id/package.json" to json.toByteArray(), "$id/starter/main.asm" to source.toByteArray(),
        "$id/assignment.md" to "Explain where the result is held.".toByteArray(),
        "$id/checks/public.kts" to "error(\"MUST NEVER EXECUTE DURING IMPORT\")".toByteArray(),
    )
    private fun archive(files: Map<String, ByteArray> = files(), directory: Boolean = false): Path {
        val path = Files.createTempFile(temporary, "lesson-", ".zip")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            if (directory) { zip.putNextEntry(ZipEntry("$id/")); zip.closeEntry() }
            for ((name, bytes) in files) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
        }
        return path
    }
    private fun changed(field: String, value: String): String = JsonParser.parseString(manifest).asJsonObject.apply {
        add(field, JsonParser.parseString(value))
    }.toString()
    private fun project() = Files.createTempDirectory(temporary, "project-")

    @Test
    fun `a private preview installs files and an identical import preserves student edits`() {
        val zip = archive()
        val project = project()
        val first = MarsLessonPackages.prepare(zip).use { prepared ->
            assertEquals("Console calculation", prepared.metadata.title)
            Files.writeString(zip, "changed after preview")
            prepared.install(project)
        }
        assertFalse(first.reused)
        assertEquals(source, Files.readString(first.entry))
        assertEquals("Explain where the result is held.", Files.readString(first.assignment))
        assertTrue(first.directory.startsWith(project.toRealPath().resolve("mars-labs/packages/$id/0.2.0")))
        assertTrue(Files.readString(first.directory.resolve("checks/public.kts")).contains("MUST NEVER EXECUTE"))
        Files.writeString(first.entry, "# student work\n")
        Files.writeString(first.assignment, "Student notes")
        MarsLessonPackages.prepare(first.directory.resolve(".mars-package.zip")).use { prepared ->
            val second = prepared.install(project)
            assertTrue(second.reused)
            assertEquals(first.directory, second.directory)
            assertEquals("# student work\n", Files.readString(second.entry))
            assertEquals("Student notes", Files.readString(second.assignment))
        }
    }

    @Test
    fun `different versions and different bytes at the same version get independent working directories`() {
        val project = project()
        val installs = listOf(manifest, changed("version", "\"0.3.0\""), changed("title", "\"Changed content\""))
            .map { json -> MarsLessonPackages.prepare(archive(files(json))).use { it.install(project) } }
        assertEquals(3, installs.map { it.directory }.toSet().size)
        assertEquals(3, installs.map { it.sha256 }.toSet().size)
        for (installed in installs) assertEquals(source, Files.readString(installed.entry))
        assertNotEquals(installs[0].directory, installs[1].directory)
    }

    @Test
    fun `preview close is idempotent and prevents further installation`() {
        val prepared = MarsLessonPackages.prepare(archive())
        prepared.close()
        prepared.close()
        assertThrows<IllegalStateException> { prepared.install(project()) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["../escape", "/absolute", "C:/windows", "a\\b", "a//b", "a/./b", "a/../b", "a/.hidden", "a/trailing.", "a/private/key", "a/Instructor/key", "a/solutions/key", "a/reference/key", "a/CON.txt", "a/com1", "a/LPT9.asm", "a/space name"])
    fun `unsafe and instructor paths are rejected before installing anything`(name: String) {
        val contents = files().apply { put(name, byteArrayOf(1)) }
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(contents)) }
    }

    @Test
    fun `oversized paths case conflicts directories roots and missing assets are rejected`() {
        for (contents in listOf(
            files().apply { put("$id/" + "a".repeat(250), byteArrayOf()) },
            files().apply { put("$id/STARTER/main.asm", byteArrayOf()) },
            files().apply { put("another/file", byteArrayOf()) },
            files().apply { put("loose-file", byteArrayOf()) },
            files().apply { remove("$id/package.json") },
            files().apply { remove("$id/starter/main.asm") },
            files().apply { remove("$id/assignment.md") },
            files(changed("id", "\"002-other\"")),
        )) assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(contents)) }
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(directory = true)) }
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(emptyMap())) }
    }

    @Test
    fun `strict bounded metadata rejects malformed types unsupported versions and incompatible targets`() {
        val invalid = listOf(
            "[]", "null", "{", manifest + "true", manifest.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"),
            changed("formatVersion", "2"), changed("formatVersion", "\"1\""), changed("formatVersion", "1.0"),
            changed("id", "\"../bad\""), changed("version", "\"01.0.0\""), changed("version", "\"1.0\""),
            changed("title", "\"\""), changed("title", "\"${"a".repeat(201)}\""),
            changed("title", "null"), changed("title", "42"), changed("title", "[]"),
            changed("environment", "null"), changed("environment", "{\"isa\":\"MIPS32\",\"syscallAbi\":\"MARS educational\"}"),
            changed("environment", "{\"isa\":\"MIPS64 Release 6\",\"syscallAbi\":\"other\"}"),
            changed("entry", "\"../escape.asm\""),
        )
        for (json in invalid) assertThrows<Exception>(json.take(80)) { MarsLessonPackages.prepare(archive(files(json))) }
        for (field in listOf("title", "formatVersion")) {
            val missing = JsonParser.parseString(manifest).asJsonObject.apply { remove(field) }.toString()
            assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(archive(files(missing))) }
        }
        val bytes = files().apply { put("$id/package.json", byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows<Exception> { MarsLessonPackages.prepare(archive(bytes)) }
        MarsLessonPackages.prepare(archive(files(manifest.replace("MIPS64 Release 6", "MIPS64 Release 6 with MSA")))).close()
    }

    @Test
    fun `limits apply to compressed actual per-file and cumulative expansion and file count`() {
        val zip = archive()
        val size = Files.size(zip)
        MarsLessonPackages.prepare(zip, MarsLessonPackages.Limits(archiveBytes = size)).close()
        for (limits in listOf(
            MarsLessonPackages.Limits(archiveBytes = size - 1), MarsLessonPackages.Limits(fileBytes = 10),
            MarsLessonPackages.Limits(expandedBytes = manifest.toByteArray().size.toLong()), MarsLessonPackages.Limits(entries = 1),
        )) assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(zip, limits) }
        for (limits in listOf<() -> Unit>(
            { MarsLessonPackages.Limits(archiveBytes = 0) }, { MarsLessonPackages.Limits(fileBytes = -1) },
            { MarsLessonPackages.Limits(expandedBytes = 0) }, { MarsLessonPackages.Limits(entries = 0) },
        )) assertThrows<IllegalArgumentException> { limits() }
    }

    @Test
    fun `symlink entries are rejected by the native extended extractor without publishing files`() {
        val path = Files.createTempFile(temporary, "symlink-", ".zip")
        ZipArchiveOutputStream(path).use { zip ->
            for ((name, bytes) in files()) {
                val entry = ZipArchiveEntry(name)
                if (name.endsWith("main.asm")) entry.unixMode = 0xA1FF
                zip.putArchiveEntry(entry); zip.write(bytes); zip.closeArchiveEntry()
            }
        }
        val project = project()
        MarsLessonPackages.prepare(path).use { prepared ->
            assertThrows<IllegalArgumentException> { prepared.install(project) }
            val version = project.resolve("mars-labs/packages/$id/0.2.0")
            Files.list(version).use { assertEquals(0, it.count()) }
        }
    }

    @Test
    fun `ZIP Unicode aliases cannot change extracted paths or bytes after preview`() {
        for (swap in listOf(false, true)) {
            val path = Files.createTempFile(temporary, "alias-", ".zip")
            ZipArchiveOutputStream(path).use { zip ->
                zip.setUseLanguageEncodingFlag(false)
                val contents = files()
                if (swap) contents["$id/assignment.md"] = ByteArray(source.toByteArray().size) { 'x'.code.toByte() }
                for ((name, bytes) in contents) {
                    val entry = ZipArchiveEntry(name)
                    val alias = when {
                        name.endsWith("main.asm") -> if (swap) "$id/assignment.md" else "$id/unknown.asm"
                        swap && name.endsWith("assignment.md") -> "$id/starter/main.asm"
                        else -> null
                    }
                    if (alias != null) entry.addExtraField(UnicodePathExtraField(alias, name.toByteArray()))
                    zip.putArchiveEntry(entry); zip.write(bytes); zip.closeArchiveEntry()
                }
            }
            MarsLessonPackages.prepare(path).use { prepared ->
                assertThrows<IllegalArgumentException> { prepared.install(project()) }
            }
        }
    }

    @Test
    fun `existing unrelated directories receipts modified caches and symlink sources are never overwritten`() {
        val zip = archive()
        for (change in listOf("destination-file", "missing-receipt", "receipt-size", "receipt-text", "receipt-link", "cache", "cache-large", "source-link", "parent-link", "assignment-missing")) {
            val project = project()
            MarsLessonPackages.prepare(zip).use { prepared ->
                val installed = prepared.install(project)
                val root = installed.directory
                when (change) {
                    "destination-file" -> { FileUtil.delete(root.toFile()); Files.writeString(root, "unrelated") }
                    "missing-receipt" -> Files.delete(root.resolve(".mars-package.sha256"))
                    "receipt-size" -> Files.writeString(root.resolve(".mars-package.sha256"), "bad")
                    "receipt-text" -> Files.writeString(root.resolve(".mars-package.sha256"), "0".repeat(64) + "\n")
                    "receipt-link" -> { Files.delete(root.resolve(".mars-package.sha256")); Files.createSymbolicLink(root.resolve(".mars-package.sha256"), zip) }
                    "cache" -> Files.writeString(root.resolve(".mars-package.zip"), "bad")
                    "cache-large" -> Files.write(root.resolve(".mars-package.zip"), ByteArray(Files.size(zip).toInt() + 1))
                    "source-link" -> { Files.delete(installed.entry); Files.createSymbolicLink(installed.entry, zip) }
                    "parent-link" -> { FileUtil.delete(root.resolve("starter").toFile()); Files.createSymbolicLink(root.resolve("starter"), temporary) }
                    else -> Files.delete(installed.assignment)
                }
                assertThrows<IllegalArgumentException>(change) { prepared.install(project) }
            }
        }
        val project = project()
        Files.createSymbolicLink(project.resolve("mars-labs"), temporary)
        MarsLessonPackages.prepare(zip).use { prepared -> assertThrows<IllegalArgumentException> { prepared.install(project) } }
    }
    @Test
    fun `actual decompressed sizes must agree with the archive metadata`() {
        val zip = archive()
        val bytes = Files.readAllBytes(zip)
        val index = (0 until bytes.size - 4).first { bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 1.toByte() && bytes[it + 3] == 2.toByte() }
        val view = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        view.putInt(index + 24, view.getInt(index + 24) + 1)
        Files.write(zip, bytes)
        assertThrows<IllegalArgumentException> { MarsLessonPackages.prepare(zip) }
    }

}
