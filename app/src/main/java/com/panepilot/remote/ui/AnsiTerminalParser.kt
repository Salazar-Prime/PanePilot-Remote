package com.panepilot.remote.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

object AnsiTerminalParser {
    private const val ESC = '\u001b'
    private const val CSI = '\u009b'

    fun parse(input: String): AnnotatedString {
        val output = AnnotatedString.Builder()
        val style = TerminalStyle()
        var index = 0
        var textStart = 0

        fun appendPending(end: Int) {
            if (end <= textStart) return
            val start = output.length
            output.append(input.substring(textStart, end))
            output.addStyle(style.toSpanStyle(), start, output.length)
        }

        while (index < input.length) {
            val character = input[index]
            when {
                character == ESC -> {
                    appendPending(index)
                    index = consumeEscape(input, index, style)
                    textStart = index
                }

                character == CSI -> {
                    appendPending(index)
                    index = consumeCsi(input, index + 1, style)
                    textStart = index
                }

                character.code < 32 && character != '\n' && character != '\t' -> {
                    appendPending(index)
                    index += 1
                    textStart = index
                }

                else -> index += 1
            }
        }

        appendPending(input.length)
        return output.toAnnotatedString()
    }

    private fun consumeEscape(
        input: String,
        escapeIndex: Int,
        style: TerminalStyle
    ): Int {
        if (escapeIndex + 1 >= input.length) return input.length
        return when (input[escapeIndex + 1]) {
            '[' -> consumeCsi(input, escapeIndex + 2, style)
            ']', 'P', 'X', '^', '_' -> consumeControlString(input, escapeIndex + 2)
            else -> (escapeIndex + 2).coerceAtMost(input.length)
        }
    }

    private fun consumeCsi(
        input: String,
        parameterStart: Int,
        style: TerminalStyle
    ): Int {
        var cursor = parameterStart
        while (cursor < input.length) {
            val character = input[cursor]
            if (character.code in 0x40..0x7e) {
                if (character == 'm') {
                    applySgr(input.substring(parameterStart, cursor), style)
                }
                return cursor + 1
            }
            cursor += 1
        }
        return input.length
    }

    private fun consumeControlString(input: String, contentStart: Int): Int {
        var cursor = contentStart
        while (cursor < input.length) {
            when {
                input[cursor] == '\u0007' -> return cursor + 1
                input[cursor] == ESC &&
                    cursor + 1 < input.length &&
                    input[cursor + 1] == '\\' -> return cursor + 2
            }
            cursor += 1
        }
        return input.length
    }

    private fun applySgr(parameters: String, style: TerminalStyle) {
        val codes = if (parameters.isBlank()) {
            listOf(0)
        } else {
            parameters.replace(':', ';').split(';').map { token ->
                token.toIntOrNull() ?: 0
            }
        }

        var index = 0
        while (index < codes.size) {
            when (val code = codes[index]) {
                0 -> style.reset()
                1 -> style.bold = true
                2 -> style.dim = true
                3 -> style.italic = true
                4, 21 -> style.underline = true
                7 -> style.inverse = true
                8 -> style.conceal = true
                9 -> style.strikethrough = true
                22 -> {
                    style.bold = false
                    style.dim = false
                }

                23 -> style.italic = false
                24 -> style.underline = false
                27 -> style.inverse = false
                28 -> style.conceal = false
                29 -> style.strikethrough = false
                in 30..37 -> style.foreground = AnsiTerminalPalette.color(code - 30)
                38 -> index += applyExtendedColor(codes, index, foreground = true, style)
                39 -> style.foreground = null
                in 40..47 -> style.background = AnsiTerminalPalette.color(code - 40)
                48 -> index += applyExtendedColor(codes, index, foreground = false, style)
                49 -> style.background = null
                in 90..97 -> style.foreground = AnsiTerminalPalette.color(code - 90 + 8)
                in 100..107 -> style.background = AnsiTerminalPalette.color(code - 100 + 8)
            }
            index += 1
        }
    }

    private fun applyExtendedColor(
        codes: List<Int>,
        start: Int,
        foreground: Boolean,
        style: TerminalStyle
    ): Int {
        val mode = codes.getOrNull(start + 1) ?: return 0
        val color: Color
        val consumed: Int
        when (mode) {
            5 -> {
                val paletteIndex = codes.getOrNull(start + 2) ?: return 0
                if (paletteIndex !in 0..255) return 2
                color = AnsiTerminalPalette.color(paletteIndex)
                consumed = 2
            }

            2 -> {
                val red = codes.getOrNull(start + 2) ?: return 0
                val green = codes.getOrNull(start + 3) ?: return 0
                val blue = codes.getOrNull(start + 4) ?: return 0
                if (red !in 0..255 || green !in 0..255 || blue !in 0..255) return 4
                color = Color(red, green, blue)
                consumed = 4
            }

            else -> return 0
        }

        if (foreground) style.foreground = color else style.background = color
        return consumed
    }

    private class TerminalStyle {
        var foreground: Color? = null
        var background: Color? = null
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        var inverse = false
        var conceal = false
        var strikethrough = false

        fun reset() {
            foreground = null
            background = null
            bold = false
            dim = false
            italic = false
            underline = false
            inverse = false
            conceal = false
            strikethrough = false
        }

        fun toSpanStyle(): SpanStyle {
            val resolvedForeground = foreground ?: AnsiTerminalPalette.Foreground
            val resolvedBackground = background ?: AnsiTerminalPalette.Background
            var drawnForeground = resolvedForeground
            var drawnBackground = background ?: Color.Unspecified

            if (inverse) {
                drawnForeground = resolvedBackground
                drawnBackground = resolvedForeground
            }
            if (conceal) {
                drawnForeground = if (drawnBackground == Color.Unspecified) {
                    AnsiTerminalPalette.Background
                } else {
                    drawnBackground
                }
            }
            if (dim) {
                drawnForeground = drawnForeground.copy(alpha = 0.66f)
            }

            val decorations = buildList {
                if (underline) add(TextDecoration.Underline)
                if (strikethrough) add(TextDecoration.LineThrough)
            }

            return SpanStyle(
                color = drawnForeground,
                background = drawnBackground,
                fontWeight = if (bold) FontWeight.Bold else null,
                fontStyle = if (italic) FontStyle.Italic else null,
                textDecoration = when (decorations.size) {
                    0 -> null
                    1 -> decorations.single()
                    else -> TextDecoration.combine(decorations)
                }
            )
        }
    }
}

object AnsiTerminalPalette {
    val Foreground = Color(0xFFDCE5F5)
    val Background = Color(0xFF080E19)

    private val base = listOf(
        Color(0xFF101827),
        Color(0xFFFF6B7A),
        Color(0xFF66D4A4),
        Color(0xFFF5C451),
        Color(0xFF6EA0FF),
        Color(0xFFC792EA),
        Color(0xFF63D7E6),
        Color(0xFFDCE5F5),
        Color(0xFF596579),
        Color(0xFFFF8793),
        Color(0xFF83E3B7),
        Color(0xFFFFD978),
        Color(0xFF8DB4FF),
        Color(0xFFD7A7F5),
        Color(0xFF8AE8F0),
        Color(0xFFFFFFFF)
    )

    fun color(index: Int): Color {
        require(index in 0..255) { "ANSI color index must be between 0 and 255." }
        if (index < base.size) return base[index]
        if (index < 232) {
            val cubeIndex = index - 16
            val red = cubeIndex / 36
            val green = (cubeIndex % 36) / 6
            val blue = cubeIndex % 6
            return Color(
                red = cubeLevel(red),
                green = cubeLevel(green),
                blue = cubeLevel(blue)
            )
        }
        val gray = 8 + (index - 232) * 10
        return Color(gray, gray, gray)
    }

    private fun cubeLevel(value: Int): Int = if (value == 0) 0 else 55 + value * 40
}
