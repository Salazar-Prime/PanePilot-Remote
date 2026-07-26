package com.panepilot.remote.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration

internal data class TerminalLink(
    val tag: String,
    val target: String,
    val start: Int,
    val end: Int
)

object TerminalLinkifier {
    const val URL_TAG = "panepilot-url"
    const val PATH_TAG = "panepilot-path"

    private val tokenPattern = Regex("\\S+")
    private val fileNamePattern =
        Regex("^[A-Za-z0-9_@+.-]+\\.[A-Za-z][A-Za-z0-9]{0,11}$")
    private val lineSuffixPattern = Regex(":(\\d+)(?::\\d+)?$")
    private val leadingWrappers = setOf('(', '[', '{', '<', '"', '\'', '`')
    private val trailingPunctuation =
        setOf('.', ',', ';', '!', '?', '"', '\'', '`', '>', ')', ']', '}')

    fun annotate(styledText: AnnotatedString): AnnotatedString {
        val links = find(styledText.text)
        if (links.isEmpty()) return styledText
        return AnnotatedString.Builder().apply {
            append(styledText)
            links.forEach { link ->
                addStringAnnotation(
                    tag = link.tag,
                    annotation = link.target,
                    start = link.start,
                    end = link.end
                )
                addStyle(
                    SpanStyle(
                        color = Sky,
                        textDecoration = TextDecoration.Underline
                    ),
                    start = link.start,
                    end = link.end
                )
            }
        }.toAnnotatedString()
    }

    internal fun find(text: String): List<TerminalLink> =
        tokenPattern.findAll(text).mapNotNull { match ->
            val token = match.value
            val leadingCount = token.indexOfFirst { it !in leadingWrappers }
                .let { if (it < 0) token.length else it }
            if (leadingCount >= token.length) return@mapNotNull null

            var candidate = token.substring(leadingCount)
            candidate = trimTrailingPunctuation(candidate)
            if (candidate.isBlank()) return@mapNotNull null

            val start = match.range.first + leadingCount
            val end = start + candidate.length
            when {
                isWebUrl(candidate) -> TerminalLink(
                    tag = URL_TAG,
                    target = candidate,
                    start = start,
                    end = end
                )

                isProjectPath(candidate) -> TerminalLink(
                    tag = PATH_TAG,
                    target = stripLineSuffix(candidate),
                    start = start,
                    end = end
                )

                else -> null
            }
        }.toList()

    private fun trimTrailingPunctuation(value: String): String {
        var end = value.length
        while (end > 0 && value[end - 1] in trailingPunctuation) {
            val character = value[end - 1]
            if (
                character == ')' &&
                value.substring(0, end).count { it == '(' } >=
                value.substring(0, end).count { it == ')' }
            ) {
                break
            }
            end -= 1
        }
        return value.substring(0, end)
    }

    private fun isWebUrl(value: String): Boolean {
        val scheme = value.substringBefore("://", "").lowercase()
        return scheme in setOf("http", "https") &&
            value.length <= MAX_LINK_LENGTH &&
            value.substringAfter("://", "").isNotBlank()
    }

    private fun isProjectPath(value: String): Boolean {
        if (
            value.length > MAX_LINK_LENGTH ||
            "://" in value ||
            value.any { it == '\u0000' || (it.code in 0..31 && it != '\t') }
        ) {
            return false
        }
        val path = stripLineSuffix(value).trimEnd('/')
        if (path.isBlank() || path == "." || path == "..") return false
        return when {
            path.startsWith("/") -> path.length > 1 && !path.startsWith("//")
            path.startsWith("./") -> path.length > 2
            '/' in path -> path.split('/').all {
                it.isNotBlank() && it != "." && it != ".."
            }

            else -> fileNamePattern.matches(path)
        }
    }

    internal fun stripLineSuffix(value: String): String =
        value.replace(lineSuffixPattern, "").trimEnd('/')

    private const val MAX_LINK_LENGTH = 4_096
}
