/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.earth

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.ID
import edu.missouristate.mars.earth.lang.MipsFileType
import edu.missouristate.mars.earth.lang.psi.MipsNamedElement
import edu.missouristate.mars.earth.lang.psi.MipsFile

object MipsUtil {
    /**
     * Find name elements that belong to the given project.
     *
     * @param project The [Project] that the elements belong to.
     * @param cls Class type.
     * @param E [MipsNamedElement] type
     * @return A list of elements
     */
    @JvmStatic
    fun <E : MipsNamedElement> findNamedElements(project: Project, cls: Class<E>): List<E> {
        val result = arrayListOf<E>()
        val virtualFiles = FileBasedIndex.getInstance().getContainingFiles(
            ID.create<FileType, Unit>("filetypes"),
            MipsFileType.INSTANCE,
            GlobalSearchScope.allScope(project)
        )
        for (virtualFile in virtualFiles) {
            (PsiManager.getInstance(project).findFile(virtualFile) as MipsFile).let { file ->
                PsiTreeUtil.getChildrenOfType(file, cls)?.let {
                    result.addAll(it)
                }
            }
        }
        return result
    }

    /**
     * Find named elements that belong to the given project matching a key.
     *
     * @param project The [Project] that elements belong to.
     * @param key The key to match.
     * @param cls The class type.
     * @param E [MipsNamedElement] type
     * @return List of elements
     */
    @JvmStatic
    fun <E : MipsNamedElement> findNamedElements(project: Project, key: String, cls: Class<E>): List<E> {
        var result: ArrayList<E>? = null
        val virtualFiles = FileBasedIndex.getInstance().getContainingFiles(
            ID.create<FileType, Unit>("filetypes"),
            MipsFileType.INSTANCE,
            GlobalSearchScope.allScope(project)
        )

        for (virtualFile in virtualFiles) {
            (PsiManager.getInstance(project).findFile(virtualFile) as MipsFile).let { file ->
                PsiTreeUtil.getChildrenOfType(file, cls)?.let {
                    for (item in it) {
                        if (item.name == key) {
                            if (result == null) result = arrayListOf()
                            result!!.add(item)
                        }
                    }
                }
            }
        }
        return if (result == null) emptyList() else result!!
    }
}