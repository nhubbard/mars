package edu.missouristate.mars.api

import java.nio.file.Path
import java.util.List.copyOf as copyList
import java.util.Map.copyOf as copyMap
import java.util.Objects.requireNonNull
import kotlin.jvm.JvmSuppressWildcards

/** Ordered assembly entries, decoded texts, and any include decisions captured by the frontend. */
@JvmSuppressWildcards
class AssemblySourceSnapshot @JvmOverloads constructor(
    entries: List<Path>,
    texts: Map<Path, String>,
    resolvedIncludes: Map<Path, Path>? = null,
    includeIdentities: Map<Path, Path> = emptyMap(),
) {
    private val entries = copyList(entries.map(::normalize))
    private val texts: Map<Path, String>
    private val resolvedIncludes: Map<Path, Path>?
    private val includeIdentities: Map<Path, Path>

    init {
        require(this.entries.isNotEmpty()) { "At least one source file is required" }

        val normalizedTexts = HashMap<Path, String>()
        texts.forEach { (path, text) ->
            val file = normalize(path)
            require(normalizedTexts.putIfAbsent(file, requireNonNull(text)) == null) {
                "Multiple source texts for $file"
            }
        }
        this.texts = copyMap(normalizedTexts)

        // A symlink can change how a following dot segment resolves. Keep request spellings intact.
        this.resolvedIncludes = resolvedIncludes?.let { requests ->
            val targets = HashMap<Path, Path>()
            requests.forEach { (request, target) -> targets[request.toAbsolutePath()] = normalize(target) }
            copyMap(targets)
        }

        val identities = HashMap<Path, Path>()
        includeIdentities.forEach { (path, identity) -> identities[normalize(path)] = normalize(identity) }
        this.includeIdentities = copyMap(identities)
    }

    fun entries(): List<Path> = entries

    fun texts(): Map<Path, String> = texts

    fun resolvedIncludes(): Map<Path, Path>? = resolvedIncludes

    fun includeIdentities(): Map<Path, Path> = includeIdentities

    /** Virtual snapshots without an include map use lexical resolution. */
    fun resolveInclude(request: Path): Path? =
        if (resolvedIncludes == null) normalize(request) else resolvedIncludes[request.toAbsolutePath()]

    /** Return the captured identity used to detect cycles, preserving the source path separately. */
    fun includeIdentity(file: Path): Path {
        val path = normalize(file)
        return includeIdentities[path] ?: path
    }

    override fun equals(other: Any?): Boolean =
        other is AssemblySourceSnapshot && entries == other.entries && texts == other.texts &&
            resolvedIncludes == other.resolvedIncludes && includeIdentities == other.includeIdentities

    override fun hashCode(): Int {
        var result = entries.hashCode()
        result = 31 * result + texts.hashCode()
        result = 31 * result + (resolvedIncludes?.hashCode() ?: 0)
        return 31 * result + includeIdentities.hashCode()
    }

    override fun toString(): String =
        "AssemblySourceSnapshot[entries=$entries, texts=$texts, resolvedIncludes=$resolvedIncludes, includeIdentities=$includeIdentities]"

    private companion object {
        fun normalize(path: Path): Path = path.toAbsolutePath().normalize()
    }
}
