package com.vitiquest.peerreview.ui

/**
 * Lightweight Markdown → HTML converter.
 * Handles headings, tables, fenced code, lists, blockquotes, HR, and inline markup.
 */
object MarkdownUtils {

    fun toHtml(md: String): String {
        val lines   = md.lines()
        val sb      = StringBuilder()
        var inPre   = false
        var inUl    = false
        var inOl    = false
        var inTable = false

        fun closeLists() {
            if (inUl) { sb.append("</ul>\n");    inUl   = false }
            if (inOl) { sb.append("</ol>\n");    inOl   = false }
        }
        fun closeTable() {
            if (inTable) { sb.append("</table>\n"); inTable = false }
        }

        for (raw in lines) {
            val line = raw.trimEnd()

            // Fenced code block
            if (line.startsWith("```")) {
                closeLists(); closeTable()
                if (!inPre) { sb.append("<pre>"); inPre = true }
                else        { sb.append("</pre>\n"); inPre = false }
                continue
            }
            if (inPre) { sb.append(line.escapeHtml()).append("\n"); continue }

            // Headings
            val h3 = Regex("^### (.+)").find(line)
            val h2 = Regex("^## (.+)").find(line)
            val h1 = Regex("^# (.+)").find(line)
            when {
                h1 != null -> { closeLists(); closeTable(); sb.append("<h1>${h1.groupValues[1].inlineMarkdown()}</h1>\n"); continue }
                h2 != null -> { closeLists(); closeTable(); sb.append("<h2>${h2.groupValues[1].inlineMarkdown()}</h2>\n"); continue }
                h3 != null -> { closeLists(); closeTable(); sb.append("<h3>${h3.groupValues[1].inlineMarkdown()}</h3>\n"); continue }
            }

            // HR
            if (line.matches(Regex("^-{3,}|\\*{3,}|_{3,}$"))) {
                closeLists(); closeTable(); sb.append("<hr/>\n"); continue
            }

            // Table row
            if (line.startsWith("|")) {
                closeLists()
                val cells = line.split("|").drop(1).dropLast(1)
                if (cells.all { it.trim().matches(Regex("^:?-+:?$")) }) continue
                if (!inTable) {
                    sb.append("<table>\n"); inTable = true
                    sb.append("<tr>"); cells.forEach { sb.append("<th>${it.trim().inlineMarkdown()}</th>") }; sb.append("</tr>\n")
                } else {
                    sb.append("<tr>"); cells.forEach { sb.append("<td>${it.trim().inlineMarkdown()}</td>") }; sb.append("</tr>\n")
                }
                continue
            } else {
                closeTable()
            }

            // Unordered list
            val ulMatch = Regex("^([-*+]) (.+)").find(line)
            if (ulMatch != null) {
                if (inOl) { sb.append("</ol>\n"); inOl = false }
                if (!inUl) { sb.append("<ul>\n"); inUl = true }
                sb.append("<li>${ulMatch.groupValues[2].inlineMarkdown()}</li>\n"); continue
            }

            // Ordered list
            val olMatch = Regex("^\\d+\\. (.+)").find(line)
            if (olMatch != null) {
                if (inUl) { sb.append("</ul>\n"); inUl = false }
                if (!inOl) { sb.append("<ol>\n"); inOl = true }
                sb.append("<li>${olMatch.groupValues[1].inlineMarkdown()}</li>\n"); continue
            }

            // Blockquote
            val bqMatch = Regex("^> (.+)").find(line)
            if (bqMatch != null) {
                closeLists(); closeTable()
                sb.append("<blockquote>${bqMatch.groupValues[1].inlineMarkdown()}</blockquote>\n"); continue
            }

            // Blank line
            if (line.isBlank()) {
                closeLists(); closeTable(); sb.append("<br/>\n"); continue
            }

            // Plain paragraph
            closeLists(); closeTable()
            sb.append("<p>${line.inlineMarkdown()}</p>\n")
        }

        closeLists(); closeTable()
        if (inPre) sb.append("</pre>\n")
        return sb.toString()
    }
}

// ── File-private helpers ──────────────────────────────────────────────────────

private fun String.inlineMarkdown(): String = this
    .escapeHtml()
    .replace(Regex("`([^`]+)`"),                 "<code>$1</code>")
    .replace(Regex("\\*\\*([^*]+)\\*\\*"),       "<strong>$1</strong>")
    .replace(Regex("__([^_]+)__"),               "<strong>$1</strong>")
    .replace(Regex("\\*([^*]+)\\*"),             "<em>$1</em>")
    .replace(Regex("_([^_]+)_"),                 "<em>$1</em>")
    .replace(Regex("\\[([^]]+)]\\(([^)]+)\\)"), "<a href=\"$2\">$1</a>")

internal fun String.escapeHtml(): String = this
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
