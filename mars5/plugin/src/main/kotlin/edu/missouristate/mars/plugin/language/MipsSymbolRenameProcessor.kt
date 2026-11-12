package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenameInputValidator
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.MultiMap
import com.intellij.util.indexing.FileBasedIndex
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.EquivalenceOccurrence
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.AssemblySymbolMap.MacroCall
import edu.missouristate.mars.api.AssemblySymbolMap.MacroDefinition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.assembler.TokenTypes
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.EditorAssemblyCapture
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.collectBackgroundAssemblySources
import edu.missouristate.mars.plugin.analysis.collectEditorAssembly
import edu.missouristate.mars.plugin.analysis.mipsAssemblyRoots
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex.Companion.EXPANSION_SEED
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import java.nio.file.Path

internal class MipsSymbolRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement) = element is MipsDeclaration
    override fun isInplaceRenameSupported() = false
    override fun getElementToSearchInStringsAndComments(element: PsiElement): PsiElement? = null

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
        val target = element as MipsDeclaration
        val name = target.name ?: return emptyList()
        val file = target.containingFile as MipsFile
        val capture = collectEditorAssembly(file) ?: return emptyList()
        val documents = capture.committedDocuments(file) ?: return emptyList()
        val analysis = file.project.service<MarsAnalysisService>()
        // A refactoring can run before external highlighting has published anything for this revision.
        val symbols = analysis.backgroundAssembly.assemble(capture.sources, capture.structureStamp, capture.options).symbols
            ?: return emptyList()
        val declaration = writtenToken(target, capture.source, documents.getValue(capture.source))
        val subject = symbols.symbols.renameSubject(target.kind, declaration, name) ?: return emptyList()
        return symbols.symbols.referenceSpellings(subject, declaration).mapNotNull { site ->
            ProgressManager.checkCanceled()
            analysis.assembledReference(target, site, searchScope)
        }
    }

    override fun findCollisions(element: PsiElement, newName: String, allRenames: Map<out PsiElement, String>, result: MutableList<UsageInfo>) {
        element.putUserData(renameVerification, null)
        val verification = try {
            if (allRenames.size != 1) throw symbolRenameError("symbol.rename.scope")
            Result.success(verifyRename(element as MipsDeclaration, newName, usageEdits(result)))
        } catch (failure: IncorrectOperationException) {
            Result.failure(failure)
        }
        element.putUserData(renameVerification, verification)
    }

    override fun findExistingNameConflicts(element: PsiElement, newName: String, conflicts: MultiMap<PsiElement, String>) {
        element.getUserData(renameVerification)?.exceptionOrNull()?.let { conflicts.putValue(element, it.message!!) }
    }

    override fun renameElement(element: PsiElement, newName: String, usages: Array<UsageInfo>, listener: RefactoringElementListener?) {
        val plan = element.getUserData(renameVerification)?.getOrThrow() ?: throw symbolRenameError("symbol.rename.unverified")
        element.putUserData(renameVerification, null)
        if (newName != plan.newName || usageEdits(usages.asList()) != plan.usages) throw symbolRenameError("symbol.rename.unverified")
        val file = element.containingFile as MipsFile
        val documents = plan.capture.committedDocuments(file) ?: throw symbolRenameError("symbol.rename.stale")
        if (PsiModificationTracker.getInstance(element.project).modificationCount != plan.projectRevision) {
            throw symbolRenameError("symbol.rename.stale")
        }
        val manager = PsiDocumentManager.getInstance(element.project)
        for ((path, edits) in plan.edits) {
            val document = documents.getValue(path)
            for (range in edits.sortedByDescending(TextRange::getStartOffset)) {
                document.replaceString(range.startOffset, range.endOffset, newName)
            }
            manager.commitDocument(document)
        }
        val updated = plan.newDeclaration.offsetIn(documents.getValue(plan.capture.source))
        listOfNotNull(listener).forEach { it.elementRenamed(MipsDeclaration.findAt(file, updated, newName)!!) }
    }
}

internal class MipsSymbolRenameInputValidator : RenameInputValidator {
    override fun getPattern() = PlatformPatterns.psiElement(MipsDeclaration::class.java)

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext) =
        validSymbolName(newName, (element as MipsDeclaration).kind)
}

private data class SymbolEdit(val path: Path, val range: TextRange)
private data class LabelDefinition(val entry: Path?, val definition: Definition)
private data class SymbolRenamePlan(
    val capture: EditorAssemblyCapture, val newName: String, val usages: Set<SymbolEdit>,
    val edits: Map<Path, List<TextRange>>, val newDeclaration: SourceToken, val projectRevision: Long,
)

private data class RenameSubject(val kind: SymbolKind, val site: SourceToken, val name: String) {
    val macro get() = kind == SymbolKind.MACRO
    val equivalence get() = kind == SymbolKind.CONSTANT
    val address get() = kind in addressKinds
    fun matches(site: SourceToken, name: String) = this.site == site && this.name == name
}

private val addressKinds = setOf(SymbolKind.LABEL, SymbolKind.EXTERNAL)
private val renameVerification = Key.create<Result<SymbolRenamePlan>>("mars.symbol.rename.verification")
private val bundle = DynamicBundle(MipsSymbolRenameProcessor::class.java, "messages.MarsBundle")

private fun symbolRenameError(key: String) = IncorrectOperationException(bundle.getMessage(key))

private fun validSymbolName(name: String, kind: SymbolKind): Boolean = !reservedEntry(name, kind) && name.isNotEmpty() &&
    TokenTypes.isValidIdentifier(name) && TokenTypes.matchTokenType(name) == TokenTypes.IDENTIFIER

private fun reservedEntry(name: String, kind: SymbolKind) = name == SymbolTable.getStartLabel() && kind in addressKinds

private fun usageEdits(usages: List<UsageInfo>): Set<SymbolEdit> = usages.mapTo(HashSet()) { usage ->
    val file = usage.virtualFile ?: throw symbolRenameError("symbol.rename.unverified")
    val range = usage.segment ?: throw symbolRenameError("symbol.rename.unverified")
    SymbolEdit(file.toNioPath().toAbsolutePath().normalize(), TextRange(range.startOffset, range.endOffset))
}

private fun EditorAssemblyCapture.committedDocuments(file: MipsFile): Map<Path, Document>? = buildMap {
    if (!validate(file) { path, document, revision ->
            put(path, document)
            document.modificationStamp == revision && PsiDocumentManager.getInstance(file.project).isCommitted(document)
        }) return null
}

private fun verifyRename(target: MipsDeclaration, newName: String, usages: Set<SymbolEdit>): SymbolRenamePlan {
    if (!validSymbolName(newName, target.kind)) throw symbolRenameError("symbol.rename.invalid.name")
    val oldName = target.name ?: throw symbolRenameError("symbol.rename.scope")
    if (reservedEntry(oldName, target.kind)) throw symbolRenameError("symbol.rename.scope")
    val file = target.containingFile as MipsFile
    val project = file.project
    val capture = collectEditorAssembly(file) ?: throw symbolRenameError("symbol.rename.scope")
    val sources = capture.sources
    val documents = capture.committedDocuments(file) ?: throw symbolRenameError("symbol.rename.stale")
    val vfs = VirtualFileManager.getInstance()
    val psi = PsiManager.getInstance(project)
    val sourceFiles = sources.revisions.keys.associateWith { vfs.findFileByNioPath(it)!! }
    for (path in documents.keys) {
        val sourceFile = sourceFiles.getValue(path)
        val source = psi.findFile(sourceFile)
        if (source !is MipsFile && source !is PsiPlainTextFile) throw symbolRenameError("symbol.rename.scope")
    }
    val declaration = writtenToken(target, capture.source, documents.getValue(capture.source))

    val (before, beforeImage) = capture.assemble(sources)
    val subject = before.renameSubject(target.kind, declaration, oldName)
        ?: throw symbolRenameError(if (target.kind == SymbolKind.CONSTANT) "symbol.rename.equivalence" else "symbol.rename.external")
    val macroDefinitions = before.macros().values.flatten().filter { it.name() == oldName }
    val written = when (subject.kind) {
        SymbolKind.MACRO -> {
            if (macroDefinitions.count { it.site() == declaration } != 1) throw symbolRenameError("symbol.rename.scope")
            null
        }
        SymbolKind.CONSTANT -> {
            if (before.equivalences().values.any { occurrences ->
                    occurrences.any { subject.matches(it.definition(), it.name()) && it.spelling() == null }
                }) throw symbolRenameError("symbol.rename.equivalence")
            null
        }
        SymbolKind.LABEL, SymbolKind.EXTERNAL -> before.labelDefinitions(oldName)
            .filter { it.definition.writtenToken() == subject.site }.singleOrNull()
            ?: throw symbolRenameError("symbol.rename.scope")
    }
    val references = before.referenceSpellings(subject, declaration)
    val assemblerSpelling = target.usesAssemblerSpelling()
    val sites = (references + declaration).associateWith { it.editIn(documents, oldName, assemblerSpelling) }
    val expected = references.mapTo(HashSet(), sites::getValue)
    val edits = sites.values.distinct().groupBy(SymbolEdit::path, SymbolEdit::range)
    val editedFiles = edits.keys.map(sourceFiles::getValue)
    val editedPhysicalFiles = editedFiles.mapTo(HashSet()) { it.canonicalFile }
    sources.exceptionHandler?.let { handler ->
        // Capture the handler's include closure independently. An entry file may also be included by the handler.
        val handlerSources = collectBackgroundAssemblySources(project, MarsSourceSet(handler))
        if (handlerSources.revisions.keys.any { sourceFiles.getValue(it).canonicalFile in editedPhysicalFiles }) {
            throw symbolRenameError("symbol.rename.handler.shared")
        }
    }
    if (usages != expected) throw symbolRenameError("symbol.rename.usages")
    val entryFiles = sources.snapshot.entries().mapTo(HashSet()) { vfs.findFileByNioPath(it)!! }
    for (source in editedFiles) {
        // A plain-text parent has no reverse index entry. The forward check below covers those edges.
        if ((mipsAssemblyRoots(project, source) - source).any { it !in entryFiles }) throw symbolRenameError("symbol.rename.scope")
    }
    if (sourceFiles.values.count { it.canonicalFile in editedPhysicalFiles } != editedPhysicalFiles.size) {
        throw symbolRenameError("symbol.rename.scope")
    }
    // Follow the assembler's include prepass from other indexed entries, including through plain text.
    val seeds = FileBasedIndex.getInstance().getContainingFiles(MipsDeclarationIndex.NAME, EXPANSION_SEED,
        GlobalSearchScope.projectScope(project))
    for (seed in seeds) {
        ProgressManager.checkCanceled()
        if (seed.toNioPath() in documents) continue
        val other = collectBackgroundAssemblySources(project, MarsSourceSet(seed.toNioPath()))
        if (other.revisions.keys.any { vfs.findFileByNioPath(it)!!.canonicalFile in editedPhysicalFiles }) {
            throw symbolRenameError("symbol.rename.scope")
        }
    }
    val unrelated = if (assemblerSpelling) emptyMap() else buildSet {
        before.labelDefinitions(oldName).mapNotNullTo(this) { it.definition.writtenToken() }
        for ((site, bindings) in before.symbolUses()) {
            ProgressManager.checkCanceled()
            for (binding in bindings) if (binding.name() == oldName) add(binding.equivalenceSource() ?: site)
        }
        macroDefinitions.mapTo(this, MacroDefinition::site)
        for ((site, calls) in before.macroCalls()) {
            ProgressManager.checkCanceled()
            for (call in calls) if (call.name() == oldName) add(call.spelling() ?: site)
        }
        for (occurrences in before.equivalences().values) {
            for (occurrence in occurrences) {
                ProgressManager.checkCanceled()
                if (occurrence.name() == oldName) occurrence.spelling()?.let(::add)
            }
        }
        removeAll(sites.keys)
    }.map { it.editIn(documents, oldName, false) }.groupBy(SymbolEdit::path, SymbolEdit::range)
    val texts = sources.snapshot.texts().mapValues { (path, text) ->
        val ranges = edits[path].orEmpty()
        // The binding map selects numeric extern names. Matching all equal literals would also select data.
        verifySpellings(text, oldName.takeUnless { assemblerSpelling }, newName,
            if (assemblerSpelling) emptySet() else ranges.toSet(), unrelated[path].orEmpty().toSet())
        val changed = StringBuilder(text)
        for (range in ranges.sortedByDescending(TextRange::getStartOffset)) {
            changed.replace(range.startOffset, range.endOffset, newName)
        }
        changed.toString()
    }
    val snapshot = sources.snapshot
    val changed = AssemblySourceSnapshot(snapshot.entries(), texts, snapshot.resolvedIncludes(), snapshot.includeIdentities())
    val (after, afterImage) = capture.assemble(sources.copy(snapshot = changed))
    val byLine = sites.entries.groupBy { it.key.location() }
    fun shifted(site: SourceToken): SourceToken {
        val delta = byLine[site.location()].orEmpty().sumOf { (source, edit) ->
            if (source.column() < site.column()) newName.length - edit.range.length else 0
        }
        return SourceToken(site.location(), site.column() + delta)
    }
    val expectedUses = before.symbolUses().mapKeys { shifted(it.key) }.mapValues { (_, bindings) ->
        bindings.mapTo(HashSet()) {
            val name = if (subject.address && subject.matches(it.definition(), it.name())) newName else it.name()
            SymbolUse(shifted(it.definition()), name, it.address(), it.equivalenceSource()?.let(::shifted))
        }
    }
    val expectedLocals = before.locals().mapValues { (entry, definitions) ->
        if (entry == written?.entry) definitions.renamed(oldName, newName) else definitions.withoutColumns()
    }
    val expectedGlobals = if (written != null && written.entry == null) before.globals().renamed(oldName, newName)
    else before.globals().withoutColumns()
    val expectedSymbols = Triple(expectedUses, expectedLocals, expectedGlobals)
    val actualSymbols = Triple(after.symbolUses(), after.locals().mapValues { it.value.withoutColumns() }, after.globals().withoutColumns())
    val sameImage = beforeImage.hasSameMachineImage(afterImage, ProgressManager::checkCanceled)
    val sameMacros = before.hasRenamedMacros(after, subject, newName, ::shifted)
    val sameEquivalences = before.hasRenamedEquivalences(after, subject, newName, ::shifted)
    if (!(expectedSymbols == actualSymbols).and(sameImage).and(sameMacros).and(sameEquivalences)) {
        throw symbolRenameError("symbol.rename.changed")
    }
    return SymbolRenamePlan(capture, newName, usages, edits, shifted(declaration),
        PsiModificationTracker.getInstance(project).modificationCount)
}

private fun AssemblySymbolMap.renameSubject(kind: SymbolKind, declaration: SourceToken, name: String): RenameSubject? {
    if (kind == SymbolKind.CONSTANT) {
        val occurrence = equivalences().values.flatten().filter {
            it.declaration() && it.name() == name && it.spelling() == declaration
        }.singleOrNull() ?: return null
        return RenameSubject(kind, occurrence.definition(), name)
    }
    if (kind != SymbolKind.EXTERNAL) return RenameSubject(kind, declaration, name)
    val allocation = globals()[name]?.takeIf { it.kind() == Kind.EXTERNAL }?.writtenToken() ?: return null
    val subject = RenameSubject(kind, allocation, name)
    if (declaration != allocation && symbolUses()[declaration].orEmpty().none { subject.matches(it.definition(), it.name()) }) {
        return null
    }
    return subject
}

private fun AssemblySymbolMap.referenceSpellings(subject: RenameSubject, declaration: SourceToken): Set<SourceToken> = buildSet {
    when (subject.kind) {
        SymbolKind.MACRO -> for ((site, calls) in macroCalls()) {
            ProgressManager.checkCanceled()
            for (call in calls) if (subject.matches(call.definition(), call.name())) add(call.spelling() ?: site)
        }
        SymbolKind.CONSTANT -> for (occurrences in equivalences().values) {
            for (occurrence in occurrences) {
                ProgressManager.checkCanceled()
                if (subject.matches(occurrence.definition(), occurrence.name())) occurrence.spelling()?.let(::add)
            }
        }
        SymbolKind.LABEL, SymbolKind.EXTERNAL -> {
            for ((site, bindings) in symbolUses()) {
                ProgressManager.checkCanceled()
                for (binding in bindings) if (subject.matches(binding.definition(), binding.name())) add(binding.equivalenceSource() ?: site)
            }
            // A repeat can initiate .extern Rename, so the allocation belongs in its preview.
            add(subject.site)
        }
    }
    remove(declaration)
}

private fun AssemblySymbolMap.hasRenamedEquivalences(
    after: AssemblySymbolMap, subject: RenameSubject, newName: String, shift: (SourceToken) -> SourceToken,
): Boolean {
    val expected = equivalences().mapValues { (_, occurrences) ->
        occurrences.mapTo(HashSet()) {
            ProgressManager.checkCanceled()
            val name = if (subject.equivalence && subject.matches(it.definition(), it.name())) newName else it.name()
            EquivalenceOccurrence(shift(it.site()), it.spelling()?.let(shift), shift(it.definition()), name, it.declaration())
        }
    }
    return expected == after.equivalences()
}

private fun AssemblySymbolMap.hasRenamedMacros(
    after: AssemblySymbolMap, subject: RenameSubject, newName: String, shift: (SourceToken) -> SourceToken,
): Boolean {
    fun nameAt(site: SourceToken, name: String) = if (subject.macro && subject.matches(site, name)) newName else name
    val definitions = macros().mapValues { (_, definitions) ->
        definitions.map {
            ProgressManager.checkCanceled()
            MacroDefinition(shift(it.site()), nameAt(it.site(), it.name()), it.parameters())
        }
    }
    val calls = macroCalls().mapKeys { shift(it.key) }.mapValues { (site, calls) ->
        fun shiftedColumn(column: Int) = shift(SourceToken(site.location(), column)).column()
        calls.mapTo(HashSet()) {
            ProgressManager.checkCanceled()
            MacroCall(shift(it.definition()), nameAt(it.definition(), it.name()), it.parameters(),
                it.argumentColumns().map(::shiftedColumn), it.closingParenthesisColumn()?.let(::shiftedColumn), it.spelling()?.let(shift))
        }
    }
    return (definitions == after.macros()).and(calls == after.macroCalls())
}

private fun AssemblySymbolMap.labelDefinitions(name: String): List<LabelDefinition> = buildList {
    globals()[name]?.let { add(LabelDefinition(null, it)) }
    for ((entry, definitions) in locals()) definitions[name]?.let { add(LabelDefinition(entry, it)) }
}

private fun Definition.writtenToken(): SourceToken? {
    // Every definition in an assembled snapshot has a physical origin and written column.
    val origin = origin()!!
    return if (origin.caller() == null) SourceToken(origin.location(), column()!!) else null
}

private fun Map<String, Definition>.renamed(oldName: String, newName: String) = withoutColumns().toMutableMap().apply {
    put(newName, remove(oldName)!!)
}

private fun Map<String, Definition>.withoutColumns() = mapValues { (_, definition) ->
    Definition(definition.address(), definition.kind(), definition.origin(), definition.macroPrivate())
}

private fun writtenToken(target: MipsDeclaration, path: Path, document: Document): SourceToken {
    val offset = target.textOffset
    val line = document.getLineNumber(offset)
    return SourceToken(Location(path, line + 1), offset - document.getLineStartOffset(line) + 1)
}

private fun SourceToken.offsetIn(document: Document) = document.getLineStartOffset(location().line() - 1) + column() - 1

private fun SourceToken.editIn(documents: Map<Path, Document>, name: String, assemblerSpelling: Boolean): SymbolEdit {
    val path = location().file()
    val document = documents[path] ?: throw symbolRenameError("symbol.rename.scope")
    if (FileDocumentManager.getInstance().isPartialPreviewOfALargeFile(document)) throw symbolRenameError("symbol.rename.preview")
    val offset = offsetIn(document)
    val length = if (assemblerSpelling) {
        val token = assemblerTokensStartingAt(document.charsSequence, offset).firstOrNull { it.value == name }
            ?: throw symbolRenameError("symbol.rename.indirect")
        token.endPos - token.startPos
    } else name.length
    return SymbolEdit(path, TextRange.from(offset, length))
}

private fun EditorAssemblyCapture.assemble(sources: CapturedAssemblySources) =
    MarsSessionService.createMarsComponent().let { component ->
        component.context().use { session ->
            options.applyForAssembly(session)
            val program = sources.assembleIn(component, ProgressManager::checkCanceled).program()
                ?: throw symbolRenameError("symbol.rename.assembly")
            program.symbolMap() to session.captureAssemblyImage(ProgressManager::checkCanceled)
        }
    }

private fun verifySpellings(text: String, oldName: String?, newName: String, expected: Set<TextRange>, unrelated: Set<TextRange>) {
    val found = HashSet<TextRange>()
    var offset = 0
    while (offset < text.length) {
        ProgressManager.checkCanceled()
        val token = MipsScanner.next(text, offset)!!
        val spelling = text.substring(offset, token.end)
        if (spelling == newName) throw symbolRenameError("symbol.rename.name.conflict")
        if (spelling == oldName) {
            val range = TextRange(offset, token.end)
            if (range !in unrelated) found.add(range)
        }
        offset = token.end
    }
    if (found != expected) throw symbolRenameError("symbol.rename.indirect")
}
