package com.panepilot.remote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Test

class AnsiTerminalParserTest {
    @Test
    fun `preserves text while removing non-style terminal controls`() {
        val styled = AnsiTerminalParser.parse(
            "plain\u001b]0;window title\u0007 text\u001b[2K!"
        )

        assertEquals("plain text!", styled.text)
    }

    @Test
    fun `renders basic foreground colors and reset`() {
        val styled = AnsiTerminalParser.parse(
            "default \u001b[31mred\u001b[0m done"
        )

        assertEquals("default red done", styled.text)
        assertEquals(AnsiTerminalPalette.color(1), styled.styleAt(8).color)
        assertEquals(AnsiTerminalPalette.Foreground, styled.styleAt(12).color)
    }

    @Test
    fun `renders 256 color true color background and bold text`() {
        val styled = AnsiTerminalParser.parse(
            "\u001b[1;38;5;214;48;2;1;2;3mhot\u001b[0m"
        )
        val style = styled.styleAt(0)

        assertEquals("hot", styled.text)
        assertEquals(AnsiTerminalPalette.color(214), style.color)
        assertEquals(Color(1, 2, 3), style.background)
        assertEquals(FontWeight.Bold, style.fontWeight)
    }

    @Test
    fun `renders inverse italic and underline attributes`() {
        val styled = AnsiTerminalParser.parse(
            "\u001b[3;4;7mstyled\u001b[0m"
        )
        val style = styled.styleAt(0)

        assertEquals(AnsiTerminalPalette.Background, style.color)
        assertEquals(AnsiTerminalPalette.Foreground, style.background)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(TextDecoration.Underline, style.textDecoration)
    }

    private fun AnnotatedString.styleAt(index: Int): SpanStyle =
        spanStyles.last { index >= it.start && index < it.end }.item
}
