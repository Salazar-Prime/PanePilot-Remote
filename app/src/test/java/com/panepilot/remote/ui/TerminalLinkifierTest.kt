package com.panepilot.remote.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinkifierTest {
    @Test
    fun `finds http links without sentence punctuation`() {
        val links = TerminalLinkifier.find(
            "Open https://example.com/report?id=4, then continue."
        )

        assertEquals(1, links.size)
        assertEquals(TerminalLinkifier.URL_TAG, links.single().tag)
        assertEquals("https://example.com/report?id=4", links.single().target)
    }

    @Test
    fun `finds absolute relative and standalone file paths`() {
        val links = TerminalLinkifier.find(
            "See /work/paper/main.tex:42:7 and src/model/train.py plus README.md."
        )

        assertEquals(
            listOf(
                "/work/paper/main.tex",
                "src/model/train.py",
                "README.md"
            ),
            links.map { it.target }
        )
        assertTrue(links.all { it.tag == TerminalLinkifier.PATH_TAG })
    }

    @Test
    fun `does not treat ordinary terminal tokens as paths`() {
        val links = TerminalLinkifier.find(
            "Context 100% left · gpt-5.6-sol · ready"
        )

        assertTrue(links.isEmpty())
    }

    @Test
    fun `annotations preserve text and add link styling`() {
        val annotated = TerminalLinkifier.annotate(
            AnnotatedString("Download ./results/model.pt")
        )
        val start = annotated.text.indexOf("./results/model.pt")

        assertEquals("Download ./results/model.pt", annotated.text)
        assertEquals(
            "./results/model.pt",
            annotated.getStringAnnotations(
                TerminalLinkifier.PATH_TAG,
                start,
                start
            ).single().item
        )
        assertEquals(
            TextDecoration.Underline,
            annotated.spanStyles.last { start in it.start until it.end }.item.textDecoration
        )
    }
}
