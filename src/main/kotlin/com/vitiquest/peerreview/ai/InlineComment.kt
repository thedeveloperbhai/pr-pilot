package com.vitiquest.peerreview.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * A single AI-generated inline comment targeting a specific line in a file.
 *
 * These are parsed from the structured JSON block the AI appends after the
 * Markdown summary (delimited by <!-- INLINE_COMMENTS_START/END --> markers).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class InlineComment(
    val file: String = "",
    val line: Int = 0,
    val severity: String = "suggestion",   // "critical" | "warning" | "suggestion"
    val comment: String = ""
)

/**
 * Full result of one AI review pass — holds both the human-readable Markdown
 * summary and the structured list of per-line inline comments.
 */
data class AiReviewResult(
    val summary: String,
    val inlineComments: List<InlineComment>
) {
    companion object {
        /** Wraps a plain summary string with no inline comments (e.g. for cache migration). */
        fun ofSummaryOnly(summary: String) = AiReviewResult(summary, emptyList())
    }
}
