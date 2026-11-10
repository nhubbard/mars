package edu.missouristate.mars.plugin.language

import com.intellij.DynamicBundle
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.patterns.PatternCondition
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
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
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
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex.Companion.EXPANSION_SEED
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import edu.missouristate.mars.plugin.language.syntax.MipsScanner
import java.nio.file.Path

internal class MipsLabelRenameProcessor : RenamePsiElementProcessor() {
    override fun canProcessElement(element: PsiElement) = element is MipsDeclaration && element.kind == SymbolKind.LABEL
    override fun isInplaceRenameSupported() = false
    override fun getElementToSearchInStringsAndComments(element: PsiElement): PsiElement? = null

    override fun findReferences(element: PsiElement, searchScope: SearchScope, searchInCommentsAndStrings: Boolean): Collection<PsiReference> {
        val target = element as MipsDeclaration
        val file = target.containingFile as MipsFile
        val capture = collectEditorAssembly(file) ?: return emptyList()
        val documents = capture.committedDocuments(file) ?: return emptyList()
        val analysis = file.project.service<MarsAnalysisService>()
        // A refactoring can run before external highlighting has published anything for this revision.
        val symbols = analysis.backgroundAssembly.assemble(capture.sources, capture.structureStamp, capture.options).symbols
            ?: return emptyList()
        val declaration = writtenLabelToken(target, capture.source, documents.getValue(capture.source))
        return symbols.assembledLabelUsages[declaration].orEmpty().mapNotNull { site ->
            ProgressManager.checkCanceled()
            analysis.assembledReference(target, site, searchScope)
        }
    }

    override fun findCollisions(element: PsiElement, newName: String, allRenames: Map<out PsiElement, String>, result: MutableList<UsageInfo>) {
        element.putUserData(renameVerification, null)
        val verification = try {
            if (allRenames.size != 1) throw labelRenameError("label.rename.scope")
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
        val plan = element.getUserData(renameVerification)?.getOrThrow() ?: throw labelRenameError("label.rename.unverified")
        element.putUserData(renameVerification, null)
        if (newName != plan.newName || usageEdits(usages.asList()) != plan.usages) throw labelRenameError("label.rename.unverified")
        val file = element.containingFile as MipsFile
        val documents = plan.capture.committedDocuments(file) ?: throw labelRenameError("label.rename.stale")
        if (PsiModificationTracker.getInstance(element.project).modificationCount != plan.projectRevision) {
            throw labelRenameError("label.rename.stale")
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

internal class MipsLabelRenameInputValidator : RenameInputValidator {
    override fun getPattern() = PlatformPatterns.psiElement(MipsDeclaration::class.java)
        .with(object : PatternCondition<MipsDeclaration>("label") {
            override fun accepts(element: MipsDeclaration, context: ProcessingContext) = element.kind == SymbolKind.LABEL
        })

    override fun isInputValid(newName: String, element: PsiElement, context: ProcessingContext) = validLabelName(newName)
}

private data class LabelEdit(val path: Path, val range: TextRange)
private data class LabelRenamePlan(
    val capture: EditorAssemblyCapture, val newName: String, val usages: Set<LabelEdit>,
    val edits: Map<Path, List<TextRange>>, val newDeclaration: SourceToken, val projectRevision: Long,
)

private val renameVerification = Key.create<Result<LabelRenamePlan>>("mars.label.rename.verification")
private val bundle = DynamicBundle(MipsLabelRenameProcessor::class.java, "messages.MarsBundle")

private fun labelRenameError(key: String) = IncorrectOperationException(bundle.getMessage(key))

private fun validLabelName(name: String): Boolean = name != SymbolTable.getStartLabel() && name.isNotEmpty() &&
    TokenTypes.isValidIdentifier(name) && TokenTypes.matchTokenType(name) == TokenTypes.IDENTIFIER

private fun usageEdits(usages: List<UsageInfo>): Set<LabelEdit> = usages.mapTo(HashSet()) { usage ->
    val file = usage.virtualFile ?: throw labelRenameError("label.rename.unverified")
    val range = usage.segment ?: throw labelRenameError("label.rename.unverified")
    LabelEdit(file.toNioPath().toAbsolutePath().normalize(), TextRange(range.startOffset, range.endOffset))
}

private fun EditorAssemblyCapture.committedDocuments(file: MipsFile): Map<Path, Document>? = buildMap {
    if (!validate(file) { path, document, revision ->
            put(path, document)
            document.modificationStamp == revision && PsiDocumentManager.getInstance(file.project).isCommitted(document)
        }) return null
}

private fun verifyRename(target: MipsDeclaration, newName: String, usages: Set<LabelEdit>): LabelRenamePlan {
    if (!validLabelName(newName)) throw labelRenameError("label.rename.invalid.name")
    val oldName = target.name ?: throw labelRenameError("label.rename.scope")
    if (oldName == SymbolTable.getStartLabel()) throw labelRenameError("label.rename.scope")
    val file = target.containingFile as MipsFile
    val project = file.project
    val capture = collectEditorAssembly(file) ?: throw labelRenameError("label.rename.scope")
    val sources = capture.sources
    val entry = sources.sourceSet.entry
    if (sources.sourceSet.mode != MarsAssemblyMode.SINGLE || sources.exceptionHandler != null) throw labelRenameError("label.rename.scope")
    val documents = capture.committedDocuments(file) ?: throw labelRenameError("label.rename.stale")
    val vfs = VirtualFileManager.getInstance()
    val psi = PsiManager.getInstance(project)
    for (path in documents.keys) {
        val source = psi.findFile(vfs.findFileByNioPath(path)!!)
        if (source !is MipsFile && source !is PsiPlainTextFile) throw labelRenameError("label.rename.scope")
    }
    val declaration = writtenLabelToken(target, capture.source, documents.getValue(capture.source))

    val (before, beforeImage) = capture.assemble(sources)
    val definition = before.locals()[entry]?.get(oldName) ?: throw labelRenameError("label.rename.scope")
    val written = Definition(definition.address(), definition.kind(), Origin(declaration.location(), null), false, declaration.column())
    if (definition != written) {
        throw labelRenameError("label.rename.scope")
    }
    val binding = SymbolUse(declaration, oldName)
    val references = before.symbolUses().filterValues { binding in it }.keys
    val expected = references.mapTo(HashSet()) { it.editIn(documents, oldName.length) }
    if (usages != expected) throw labelRenameError("label.rename.usages")
    val sites = references + declaration
    val edits = (expected + declaration.editIn(documents, oldName.length)).groupBy(LabelEdit::path, LabelEdit::range)
    val entryFile = vfs.findFileByNioPath(entry)!!
    val editedFiles = edits.keys.map { vfs.findFileByNioPath(it)!! }
    for (source in editedFiles) {
        // A plain-text parent has no reverse index entry. The forward check below covers those edges.
        if ((mipsAssemblyRoots(project, source) - source).any { it != entryFile }) throw labelRenameError("label.rename.scope")
    }
    val editedPhysicalFiles = editedFiles.mapTo(HashSet()) { it.canonicalFile }
    // Follow the assembler's include prepass from other indexed entries, including through plain text.
    val seeds = FileBasedIndex.getInstance().getContainingFiles(MipsDeclarationIndex.NAME, EXPANSION_SEED,
        GlobalSearchScope.projectScope(project))
    for (seed in seeds) {
        ProgressManager.checkCanceled()
        if (seed.toNioPath() in documents) continue
        val other = collectBackgroundAssemblySources(project, MarsSourceSet(seed.toNioPath()))
        if (other.revisions.keys.any { vfs.findFileByNioPath(it)!!.canonicalFile in editedPhysicalFiles }) {
            throw labelRenameError("label.rename.scope")
        }
    }
    val texts = sources.snapshot.texts().mapValues { (path, text) ->
        val ranges = edits[path].orEmpty()
        verifySpellings(text, oldName, newName, ranges.toSet())
        val changed = StringBuilder(text)
        for (range in ranges.sortedByDescending(TextRange::getStartOffset)) {
            changed.replace(range.startOffset, range.endOffset, newName)
        }
        changed.toString()
    }
    val snapshot = sources.snapshot
    val changed = AssemblySourceSnapshot(snapshot.entries(), texts, snapshot.resolvedIncludes(), snapshot.includeIdentities())
    val (after, afterImage) = capture.assemble(sources.copy(snapshot = changed))
    val byLine = sites.groupBy(SourceToken::location)
    fun shifted(site: SourceToken): SourceToken {
        val earlier = byLine[site.location()].orEmpty().count { it.column() < site.column() }
        return SourceToken(site.location(), site.column() + earlier * (newName.length - oldName.length))
    }
    val expectedUses = before.symbolUses().mapKeys { shifted(it.key) }.mapValues { (_, bindings) ->
        bindings.mapTo(HashSet()) { SymbolUse(shifted(it.definition()), if (it == binding) newName else it.name()) }
    }
    val expectedLocals = before.locals().getValue(entry).toMutableMap().apply {
        remove(oldName)
        put(newName, definition)
    }
    val expectedSymbols = Triple(expectedUses, expectedLocals.withoutColumns(), before.globals().withoutColumns())
    val actualSymbols = Triple(after.symbolUses(), after.locals().getValue(entry).withoutColumns(), after.globals().withoutColumns())
    val sameImage = beforeImage.hasSameMachineImage(afterImage, ProgressManager::checkCanceled)
    if (!(expectedSymbols == actualSymbols).and(sameImage)) throw labelRenameError("label.rename.changed")
    return LabelRenamePlan(capture, newName, usages, edits, shifted(declaration),
        PsiModificationTracker.getInstance(project).modificationCount)
}

private fun Map<String, Definition>.withoutColumns() = mapValues { (_, definition) ->
    Definition(definition.address(), definition.kind(), definition.origin(), definition.macroPrivate())
}

private fun writtenLabelToken(target: MipsDeclaration, path: Path, document: Document): SourceToken {
    val offset = target.textOffset
    val line = document.getLineNumber(offset)
    return SourceToken(Location(path, line + 1), offset - document.getLineStartOffset(line) + 1)
}

private fun SourceToken.offsetIn(document: Document) = document.getLineStartOffset(location().line() - 1) + column() - 1

private fun SourceToken.editIn(documents: Map<Path, Document>, length: Int) =
    LabelEdit(location().file(), TextRange.from(offsetIn(documents.getValue(location().file())), length))

private fun EditorAssemblyCapture.assemble(sources: CapturedAssemblySources) =
    MarsSessionService.createMarsComponent().let { component ->
        component.context().use { session ->
            options.applyForAssembly(session)
            val program = sources.assembleIn(component, ProgressManager::checkCanceled).program()
                ?: throw labelRenameError("label.rename.assembly")
            program.symbolMap() to session.captureAssemblyImage(ProgressManager::checkCanceled)
        }
    }

private fun verifySpellings(text: String, oldName: String, newName: String, expected: Set<TextRange>) {
    val found = HashSet<TextRange>()
    var offset = 0
    while (offset < text.length) {
        ProgressManager.checkCanceled()
        val token = MipsScanner.next(text, offset)!!
        val spelling = text.substring(offset, token.end)
        if (spelling == newName) throw labelRenameError("label.rename.name.conflict")
        if (spelling == oldName) found.add(TextRange(offset, token.end))
        offset = token.end
    }
    if (found != expected) throw labelRenameError("label.rename.indirect")
}
