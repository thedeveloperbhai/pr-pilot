package com.vitiquest.peerreview.ui

/**
 * Pure diff-parsing utilities — no UI, no platform dependencies.
 */
object DiffParser {

    fun parseToEntries(rawDiff: String): List<FileDiffEntry> {
        val entries     = mutableListOf<FileDiffEntry>()
        val filePatches = rawDiff.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }

        for (patch in filePatches) {
            val lines = patch.lines()

            val oldPath = lines.firstOrNull { it.startsWith("--- ") }
                ?.removePrefix("--- ")?.removePrefix("a/")?.trim()
                ?.takeIf { it.isNotBlank() } ?: ""
            val newPath = lines.firstOrNull { it.startsWith("+++ ") }
                ?.removePrefix("+++ ")?.removePrefix("b/")?.trim()
                ?.takeIf { it.isNotBlank() } ?: ""

            val isAdded   = oldPath.endsWith("/dev/null") || oldPath == "/dev/null" || oldPath == "dev/null"
            val isDeleted = newPath.endsWith("/dev/null") || newPath == "/dev/null" || newPath == "dev/null"
            val isRenamed = !isAdded && !isDeleted && oldPath.isNotBlank() && newPath.isNotBlank() && oldPath != newPath

            val oldLines = mutableListOf<String>()
            val newLines = mutableListOf<String>()

            for (line in lines) {
                when {
                    line.startsWith("--- ")      ||
                    line.startsWith("+++ ")      ||
                    line.startsWith("diff ")     ||
                    line.startsWith("index ")    ||
                    line.startsWith("new file")  ||
                    line.startsWith("deleted file") ||
                    line.startsWith("@@")         -> Unit
                    line.startsWith("-")          -> oldLines.add(line.drop(1))
                    line.startsWith("+")          -> newLines.add(line.drop(1))
                    line.startsWith("\\")         -> Unit
                    else -> {
                        val ctx = if (line.startsWith(" ")) line.drop(1) else line
                        oldLines.add(ctx); newLines.add(ctx)
                    }
                }
            }

            val displayPath = when {
                isAdded              -> newPath.ifBlank { oldPath }
                isDeleted            -> oldPath.ifBlank { newPath }
                isRenamed            -> "$oldPath → $newPath"
                newPath.isNotBlank() -> newPath
                else                 -> oldPath.ifBlank { "(unknown)" }
            }
            val statusTag = when {
                isAdded   -> "ADDED"
                isDeleted -> "DELETED"
                isRenamed -> "RENAMED"
                else      -> "MODIFIED"
            }

            entries.add(
                FileDiffEntry(
                    displayLabel = displayPath,
                    statusTag    = statusTag,
                    oldPath      = oldPath,
                    newPath      = newPath,
                    oldText      = if (isAdded)   "" else oldLines.joinToString("\n"),
                    newText      = if (isDeleted) "" else newLines.joinToString("\n")
                )
            )
        }
        return entries
    }

    /** Splits a raw unified diff into a map of filePath → patchText. Keys use the b/ path. */
    fun buildPatchMap(rawDiff: String): Map<String, String> {
        val map      = mutableMapOf<String, String>()
        val sections = rawDiff.split(Regex("(?=diff --git )")).filter { it.isNotBlank() }
        for (section in sections) {
            val newPathLine = section.lines().firstOrNull { it.startsWith("+++ ") } ?: continue
            val path = newPathLine.removePrefix("+++ ").removePrefix("b/").trim()
            if (path.isNotBlank() && path != "/dev/null") map[path] = section
        }
        return map
    }
}
