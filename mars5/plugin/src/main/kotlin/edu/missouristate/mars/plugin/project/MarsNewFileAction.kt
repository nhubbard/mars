package edu.missouristate.mars.plugin.project

import com.intellij.DynamicBundle
import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import edu.missouristate.mars.plugin.language.MipsFileType

private const val ASSEMBLY_TEMPLATE = "MIPS Assembly"
private const val SOURCE_TEMPLATE = "MIPS Source"
private val messages = DynamicBundle(MarsNewFileAction::class.java, "messages.MarsBundle")

internal class MarsNewFileAction : CreateFileFromTemplateAction(), DumbAware {
    // FileTemplateUtil owns the write command. Let the platform open the editor after that write.
    override fun startInWriteAction() = false

    override fun buildDialog(project: Project, directory: PsiDirectory, builder: CreateFileFromTemplateDialog.Builder) {
        builder.setTitle(messages.getMessage("project.file.title"))
            .addKind(messages.getMessage("project.file.asm"), MipsFileType.icon, ASSEMBLY_TEMPLATE)
            .addKind(messages.getMessage("project.file.s"), MipsFileType.icon, SOURCE_TEMPLATE)
    }

    override fun createFile(name: String, templateName: String, dir: PsiDirectory): PsiFile? {
        // A written MIPS suffix takes precedence over the kind selected in the popup.
        val template = when (name.substringAfterLast('.', "")) {
            "asm" -> ASSEMBLY_TEMPLATE
            "s" -> SOURCE_TEMPLATE
            else -> templateName
        }
        return super.createFile(name, template, dir)
    }

    public override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        messages.getMessage("project.file.command", newName)
}
