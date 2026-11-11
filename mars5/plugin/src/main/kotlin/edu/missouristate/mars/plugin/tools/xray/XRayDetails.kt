package edu.missouristate.mars.plugin.tools.xray

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer

internal fun xRayDetailsContent(description: State<String>): @Composable () -> Unit = {
    VerticallyScrollableContainer(modifier = Modifier.fillMaxSize()) {
        SelectionContainer(Modifier.padding(8.dp)) {
            val details = description.value
            val headingLength = details.substringBefore('\n').length
            val text = buildAnnotatedString {
                append(details)
                addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, headingLength)
                addStyle(SpanStyle(fontFamily = FontFamily.Monospace), headingLength, details.length)
            }
            Text(text)
        }
    }
}
