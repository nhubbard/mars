package edu.missouristate.mars.plugin.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorEventMulticaster
import edu.missouristate.mars.api.MarsListenerDsl

@MarsListenerDsl
internal class DocumentListenerBuilder internal constructor() {
  private var beforeDocumentChangeAction: DocumentListener.(DocumentEvent) -> Unit = { _ -> }
  private var documentChangedAction: DocumentListener.(DocumentEvent) -> Unit = { _ -> }
  private var bulkUpdateStartingAction: DocumentListener.(Document) -> Unit = { _ -> }
  private var bulkUpdateFinishedAction: DocumentListener.(Document) -> Unit = { _ -> }

  fun beforeDocumentChange(block: @MarsListenerDsl DocumentListener.(DocumentEvent) -> Unit) {
    beforeDocumentChangeAction = block
  }

  fun documentChanged(block: @MarsListenerDsl DocumentListener.(DocumentEvent) -> Unit) {
    documentChangedAction = block
  }

  fun bulkUpdateStarting(block: @MarsListenerDsl DocumentListener.(Document) -> Unit) {
    bulkUpdateStartingAction = block
  }

  fun bulkUpdateFinished(block: @MarsListenerDsl DocumentListener.(Document) -> Unit) {
    bulkUpdateFinishedAction = block
  }

  internal fun build(): DocumentListener {
    val beforeDocumentChangeCallback = beforeDocumentChangeAction
    val documentChangedCallback = documentChangedAction
    val bulkUpdateStartingCallback = bulkUpdateStartingAction
    val bulkUpdateFinishedCallback = bulkUpdateFinishedAction
    return object : DocumentListener {
      override fun beforeDocumentChange(event: DocumentEvent) =
          beforeDocumentChangeCallback(this, event)

      override fun documentChanged(event: DocumentEvent) = documentChangedCallback(this, event)

      override fun bulkUpdateStarting(document: Document) =
          bulkUpdateStartingCallback(this, document)

      override fun bulkUpdateFinished(document: Document) =
          bulkUpdateFinishedCallback(this, document)
    }
  }
}

internal fun documentListener(configure: DocumentListenerBuilder.() -> Unit): DocumentListener =
    DocumentListenerBuilder().apply(configure).build()

/** Register with the platform's disposable lifetime and return the exact registered listener. */
internal fun EditorEventMulticaster.onDocumentChanged(
    parentDisposable: Disposable,
    callback: @MarsListenerDsl DocumentListener.(DocumentEvent) -> Unit,
): DocumentListener = documentListener {
  documentChanged(callback)
}
    .also {
      addDocumentListener(it, parentDisposable)
    }
