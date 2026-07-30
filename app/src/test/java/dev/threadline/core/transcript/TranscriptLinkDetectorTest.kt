package dev.threadline.core.transcript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptLinkDetectorTest {
    @Test
    fun detectsHttpAndHttpsUrlsWithExactSourceRanges() {
        val text = "first https://example.com/a then HTTP://example.net:8080/b?q=1"

        val links = TranscriptLinkDetector.detect(text)

        assertEquals(
            listOf(
                TranscriptLink(
                    start = text.indexOf("https://"),
                    endExclusive = text.indexOf("https://") + "https://example.com/a".length,
                    url = "https://example.com/a",
                ),
                TranscriptLink(
                    start = text.indexOf("HTTP://"),
                    endExclusive = text.length,
                    url = "HTTP://example.net:8080/b?q=1",
                ),
            ),
            links,
        )
    }

    @Test
    fun ignoresNonWebSchemesAndBareDomains() {
        val text = "file:///tmp/report javascript:alert(1) example.com ssh://host"

        assertTrue(TranscriptLinkDetector.detect(text).isEmpty())
    }

    @Test
    fun removesSentencePunctuationAndUnmatchedClosingDelimiters() {
        val text = "See https://example.com/report?q=done). Then continue."

        assertEquals(
            listOf(
                TranscriptLink(
                    start = 4,
                    endExclusive = 37,
                    url = "https://example.com/report?q=done",
                ),
            ),
            TranscriptLinkDetector.detect(text),
        )
    }

    @Test
    fun preservesBalancedParenthesesInsideUrl() {
        val text = "Docs: https://example.com/function(arg)"

        assertEquals(
            "https://example.com/function(arg)",
            TranscriptLinkDetector.detect(text).single().url,
        )
    }

    @Test
    fun rejectsMalformedHostAndOversizedCandidates() {
        val oversized = "https://example.com/" + "a".repeat(4_096)
        val text =
            "https:///missing-host https://exa%mple.com " +
                "https://user@example.com $oversized"

        assertTrue(TranscriptLinkDetector.detect(text).isEmpty())
    }
}
