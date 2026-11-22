package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonReader
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.Decompressor
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/** Import existing public distributions as files; no downloaded checks or lesson code is executed. */
internal object MarsLessonPackages {
    data class Limits(
        val archiveBytes: Long = 32L * 1024 * 1024,
        val fileBytes: Long = 16L * 1024 * 1024,
        val expandedBytes: Long = 128L * 1024 * 1024,
        val entries: Int = 4096,
    ) {
        init { require(listOf(archiveBytes, fileBytes, expandedBytes, entries.toLong()).all { it > 0 }) }
    }

    data class Metadata(
        val id: String, val version: String, val title: String, val isa: String,
        val entry: String, val assignment: String, val nativeLesson: String?,
    )
    data class Installed(val metadata: Metadata, val sha256: String, val directory: Path, val reused: Boolean) {
        val entry: Path get() = directory.resolve(metadata.entry)
        val assignment: Path get() = directory.resolve(metadata.assignment)
    }
    internal data class Asset(val size: Long, val sha256: String)

    /** The bounded private snapshot binds preview and installation even if the selected ZIP changes. */
    class Prepared internal constructor(
        val metadata: Metadata, val sha256: String, private val snapshot: Path,
        private val assets: Map<String, Asset>, val nativeLesson: MarsNativeLessonPlan?,
    ) : AutoCloseable {
        private var closed = false

        /** Blocking I/O, invoked outside the UI thread. Existing student files are never replaced. */
        @Synchronized
        fun install(projectRoot: Path): Installed {
            check(!closed) { "The package preview has been closed" }
            var parent = projectRoot.toRealPath()
            for (part in listOf("mars-labs", "packages", metadata.id, metadata.version)) parent = directory(parent, part)
            val destination = parent.resolve(sha256)
            if (Files.exists(destination, NOFOLLOW_LINKS)) return existing(destination)
            val staging = Files.createTempDirectory(parent, ".mars-import-")
            try {
                // Platform extraction handles ZIP attributes. Preflight already bounded actual bytes;
                // the extended ZIP reader additionally rejects symlink entries before publishing.
                Decompressor.Zip(snapshot).withZipExtensions().entryFilter { entry ->
                    ProgressManager.checkCanceled()
                    require(entry.type == Decompressor.Entry.Type.FILE) { "Only regular package files are supported" }
                    require(entry.name in assets) { "Archive entry changed after preview" }
                    true
                }.extract(staging)
                val content = staging.resolve(metadata.id)
                for ((name, asset) in assets) {
                    require(digest(Files.newInputStream(staging.resolve(name)), asset.size).second == asset.sha256) { "Extracted package content differs from preview" }
                }
                Files.writeString(content.resolve(RECEIPT), sha256 + "\n", CREATE_NEW)
                Files.copy(snapshot, content.resolve(ARCHIVE))
                ProgressManager.checkCanceled()
                Files.move(content, destination)
                return Installed(metadata, sha256, destination, false)
            } finally { FileUtil.delete(staging.toFile()) }
        }

        private fun existing(destination: Path): Installed {
            require(Files.isDirectory(destination, NOFOLLOW_LINKS)) { "Installed package path is not a regular directory" }
            val receipt = regular(destination, RECEIPT)
            require(Files.size(receipt) == 65L && Files.readString(receipt) == sha256 + "\n") { "Existing folder is not this installed package" }
            val archive = regular(destination, ARCHIVE)
            require(digest(Files.newInputStream(archive), Files.size(snapshot)).second == sha256) { "The retained package archive has changed" }
            regular(destination, metadata.entry)
            regular(destination, metadata.assignment)
            return Installed(metadata, sha256, destination, true)
        }

        @Synchronized
        override fun close() {
            if (!closed) { closed = true; FileUtil.delete(snapshot.parent.toFile()) }
        }
    }

    /** Blocking I/O. Limits apply to actual bytes, not merely untrusted ZIP size fields. */
    fun prepare(archive: Path, limits: Limits = Limits()): Prepared {
        val temporary = Files.createTempDirectory("mars-lesson-preview-")
        try {
            val snapshot = temporary.resolve("package.zip")
            val sha256 = Files.newOutputStream(snapshot, CREATE_NEW).use { output ->
                digest(Files.newInputStream(archive), limits.archiveBytes, output).second
            }
            val assets = linkedMapOf<String, Asset>()
            val names = hashSetOf<String>()
            var total = 0L
            var manifest: ByteArray? = null
            var root: String? = null
            ZipFile(snapshot.toFile()).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    ProgressManager.checkCanceled()
                    val entry = entries.nextElement()
                    require(assets.size < limits.entries) { "Too many package files" }
                    val name = entry.name
                    require(!entry.isDirectory) { "Package archives contain files, not directory entries" }
                    path(name)
                    require(names.add(name.lowercase(Locale.ROOT))) { "Duplicate or case-conflicting archive path" }
                    val prefix = name.substringBefore('/')
                    require(name.contains('/')) { "A package archive needs one package root" }
                    if (root == null) root = prefix
                    require(root == prefix) { "A package archive needs one package root" }
                    val bytes = if (name == "$root/package.json") ByteArrayOutputStream() else null
                    val (size, hash) = digest(zip.getInputStream(entry), minOf(limits.fileBytes, limits.expandedBytes - total), bytes)
                    require(entry.size == size) { "Incorrect archive size metadata" }
                    total += size
                    assets[name] = Asset(size, hash)
                    if (bytes != null) manifest = bytes.toByteArray()
                }
            }
            val data = metadata(requireNotNull(manifest) { "Missing package.json" })
            require(data.id == root) { "Package ID does not match its archive root" }
            require("${data.id}/${data.entry}" in assets && "${data.id}/${data.assignment}" in assets) { "Missing starter or assignment" }
            val lesson = data.nativeLesson?.let { relative ->
                val name = "${data.id}/$relative"
                val asset = requireNotNull(assets[name]) { "Missing native lesson content" }
                require(asset.size <= MarsNativeLessonPlan.MAX_BYTES) { "Native lesson content is too large" }
                ZipFile(snapshot.toFile()).use { zip ->
                    MarsNativeLessonPlan.parse(zip.getInputStream(zip.getEntry(name)).use { it.readAllBytes() })
                }
            }
            return Prepared(data, sha256, snapshot, assets.toMap(), lesson)
        } catch (error: Throwable) {
            FileUtil.delete(temporary.toFile())
            throw error
        }
    }

    private fun metadata(bytes: ByteArray): Metadata {
        val text = UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        val json = JsonObject()
        JsonReader(StringReader(text)).use { reader ->
            reader.strictness = Strictness.STRICT
            reader.nestingLimit = 64
            require(reader.peek() == JsonToken.BEGIN_OBJECT) { "The package manifest must be an object" }
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                require(!json.has(name)) { "Duplicate manifest field" }
                json.add(name, JsonParser.parseReader(reader))
            }
            reader.endObject()
            reader.peek() // STRICT rejects trailing top-level content when the next token is requested.
        }
        require(json.get("formatVersion")?.toString() == "1") { "Unsupported student package format" }
        val id = json.string("id")
        require(Regex("[0-9]{3}-[a-z][a-z0-9-]{0,90}").matches(id)) { "Invalid package ID" }
        val version = json.string("version")
        require(Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})").matches(version)) { "Invalid package version" }
        val title = json.string("title")
        require(title.isNotBlank() && title.length <= 200) { "Invalid package title" }
        val environment = json.get("environment")
        require(environment is JsonObject) { "Missing package environment" }
        val isa = environment.string("isa")
        require(isa in setOf("MIPS64 Release 6", "MIPS64 Release 6 with MSA")) { "Unsupported package ISA" }
        require(environment.string("syscallAbi") == "MARS educational") { "Unsupported package syscall ABI" }
        val nativeLesson = if (json.has("nativeLesson")) path(json.string("nativeLesson")) else null
        return Metadata(id, version, title, isa, path(json.string("entry")), path(json.string("assignment")), nativeLesson)
    }

    private fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Missing or invalid $name" }
        return value.asString
    }

    private fun path(value: String): String {
        require(value.length <= 240 && value.split('/').all { part ->
            Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(part) && !part.endsWith('.') &&
                part.lowercase(Locale.ROOT) !in setOf("instructor", "reference", "solutions", "private") &&
                !Regex("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])").matches(part.substringBefore('.'))
        }) { "Unsafe or instructor-only package path" }
        return value
    }

    private fun directory(parent: Path, name: String): Path = parent.resolve(name).also {
        try { Files.createDirectory(it) }
        catch (_: FileAlreadyExistsException) { require(Files.isDirectory(it, NOFOLLOW_LINKS)) { "Package parent is not a regular directory" } }
    }

    private fun regular(root: Path, relative: String): Path {
        val parts = relative.split('/')
        var parent = root
        for (part in parts.dropLast(1)) {
            parent = parent.resolve(part)
            require(Files.isDirectory(parent, NOFOLLOW_LINKS)) { "Package source parent is not a regular directory" }
        }
        return parent.resolve(parts.last()).also {
            require(Files.isRegularFile(it, NOFOLLOW_LINKS)) { "Package source is not a regular file" }
        }
    }

    private fun digest(input: InputStream, limit: Long, output: OutputStream? = null): Pair<Long, String> = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var count = 0L
        while (true) {
            ProgressManager.checkCanceled()
            val read = it.read(buffer)
            if (read < 0) break
            require(read.toLong() <= limit - count) { "Package exceeds its size limit" }
            count += read
            digest.update(buffer, 0, read)
            output?.write(buffer, 0, read)
        }
        count to digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val RECEIPT = ".mars-package.sha256"
    private const val ARCHIVE = ".mars-package.zip"
}
