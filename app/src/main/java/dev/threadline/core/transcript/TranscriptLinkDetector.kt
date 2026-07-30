package dev.threadline.core.transcript

import java.net.URI
import java.util.Locale

internal data class TranscriptLink(
    val start: Int,
    val endExclusive: Int,
    val url: String,
)

internal object TranscriptLinkDetector {
    private const val MAXIMUM_URL_CHARACTERS = 4_096
    private val candidatePattern = Regex(
        pattern = """\bhttps?://[^\s<>"']+""",
        option = RegexOption.IGNORE_CASE,
    )
    private val sentencePunctuation = setOf('.', ',', ';', ':', '!', '?')
    private val closingDelimiters = mapOf(
        ')' to '(',
        ']' to '[',
        '}' to '{',
    )

    fun detect(text: String): List<TranscriptLink> = buildList {
        candidatePattern.findAll(text).forEach { match ->
            if (match.value.length > MAXIMUM_URL_CHARACTERS) return@forEach
            val url = trimTrailingPunctuation(match.value)
            if (url.isSupportedWebUrl()) {
                add(
                    TranscriptLink(
                        start = match.range.first,
                        endExclusive = match.range.first + url.length,
                        url = url,
                    ),
                )
            }
        }
    }

    private fun trimTrailingPunctuation(candidate: String): String {
        var endExclusive = candidate.length
        val unmatchedClosingCounts = closingDelimiters.mapValues { (closing, opening) ->
            candidate.count { it == closing } - candidate.count { it == opening }
        }.toMutableMap()
        while (endExclusive > 0) {
            val trailing = candidate[endExclusive - 1]
            when {
                trailing in sentencePunctuation -> endExclusive--
                unmatchedClosingCounts.getOrDefault(trailing, 0) > 0 -> {
                    unmatchedClosingCounts[trailing] =
                        unmatchedClosingCounts.getValue(trailing) - 1
                    endExclusive--
                }

                else -> break
            }
        }
        return candidate.substring(0, endExclusive)
    }

    private fun String.isSupportedWebUrl(): Boolean {
        val uri = runCatching { URI(this) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        return (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null
    }
}
