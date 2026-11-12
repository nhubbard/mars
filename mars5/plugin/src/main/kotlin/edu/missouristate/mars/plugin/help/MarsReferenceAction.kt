package edu.missouristate.mars.plugin.help

import com.intellij.CommonBundle
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil.escapeXmlEntities
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBSplitter
import com.intellij.ui.ScrollingUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.BrowserLink
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.speedSearch.FilteringListModel
import com.intellij.util.ui.JBUI
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent

internal class MarsReferenceAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = MarsReferenceDialog(e.project, MarsReferenceCatalog.topics.first()).show()
}

internal class MarsAboutAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) = MarsReferenceDialog(e.project, MarsReferenceCatalog.about).show()
}

internal class MarsReferenceDialog(project: Project?, initialTopic: MarsReferenceTopic) :
    DialogWrapper(project, false, IdeModalityType.MODELESS) {
    private val search = SearchTextField(false)
    private val model = FilteringListModel(CollectionListModel(MarsReferenceCatalog.topics))
    private val topics = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = textListCellRenderer("", MarsReferenceTopic::title)
        emptyText.text = referenceMessages.getMessage("reference.empty")
        accessibleContext.accessibleName = referenceMessages.getMessage("reference.topics")
    }
    private val details = JBHtmlPane().apply {
        accessibleContext.accessibleName = referenceMessages.getMessage("reference.details")
        border = JBUI.Borders.empty(8)
    }
    val contents = panel {
        row(referenceMessages.getMessage("reference.search")) {
            cell(search).align(AlignX.FILL)
        }
        row {
            val split = JBSplitter(false, 0.33f).apply {
                firstComponent = JBScrollPane(topics)
                secondComponent = JBScrollPane(details)
                preferredSize = JBUI.size(820, 490)
            }
            cell(split).align(Align.FILL)
        }.resizableRow()
        row {
            cell(BrowserLink(
                referenceMessages.getMessage("reference.card"),
                "https://github.com/dpetersanderson/MARS/blob/main/help/mipsref.pdf",
            ))
        }
    }

    init {
        title = referenceMessages.getMessage("reference.title")
        setCancelButtonText(CommonBundle.getCloseButtonText())
        Disposer.register(disposable, details)
        Disposer.register(disposable) { model.dispose() }
        ScrollingUtil.installActions(topics, search.textEditor)
        topics.addListSelectionListener {
            details.text = topics.selectedValue?.let { topic -> "<h2>${escapeXmlEntities(topic.title)}</h2>${topic.html()}" }.orEmpty()
            details.caretPosition = 0
        }
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = filterTopics()
        })
        filterTopics()
        topics.setSelectedValue(initialTopic, true)
        init()
    }

    private fun filterTopics() {
        val words = search.text.split(Regex("\\s+")).filter(String::isNotEmpty)
        topics.clearSelection()
        model.setFilter { it.matches(words) }
        topics.selectedIndex = 0
    }

    override fun createCenterPanel() = contents
    override fun createActions() = arrayOf(cancelAction)
    override fun getPreferredFocusedComponent(): JBTextField? = search.textEditor
    override fun getDimensionServiceKey() = "Mars.Reference"
}
