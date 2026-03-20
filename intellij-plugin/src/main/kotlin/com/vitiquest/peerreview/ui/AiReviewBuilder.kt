package com.vitiquest.peerreview.ui

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.vitiquest.peerreview.ai.AiReviewResult
import com.vitiquest.peerreview.ai.InlineComment
import com.vitiquest.peerreview.ai.OpenAIClient
import com.vitiquest.peerreview.analysis.CodeAnalyzer
import com.vitiquest.peerreview.bitbucket.PullRequest
import com.vitiquest.peerreview.bitbucket.PullRequestService
import com.vitiquest.peerreview.utils.RepoInfo
import java.io.File

/**
 * Builds AI review payloads and parses AI responses.
 * No UI code — pure data/business logic.
 */
class AiReviewBuilder(
    private val project: Project,
    private val service: PullRequestService,
    private val aiClient: OpenAIClient
) {

    /**
     * Parses raw AI response text into an [AiReviewResult].
     * Splits on the INLINE_COMMENTS delimiter tags; everything before is Markdown summary.
     */
    fun parseResponse(rawText: String): AiReviewResult {
        val startTag = "<!-- INLINE_COMMENTS_START -->"
        val endTag   = "<!-- INLINE_COMMENTS_END -->"
        val startIdx = rawText.indexOf(startTag)
        val endIdx   = rawText.indexOf(endTag)

        if (startIdx == -1 || endIdx == -1 || endIdx <= startIdx) {
            return AiReviewResult.ofSummaryOnly(rawText.trim())
        }

        val summary = rawText.substring(0, startIdx).trim()
        val rawJson = rawText.substring(startIdx + startTag.length, endIdx).trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

        val inlineComments: List<InlineComment> = try {
            jacksonObjectMapper().readValue(rawJson)
        } catch (e: Exception) {
            emptyList()
        }
        return AiReviewResult(summary, inlineComments)
    }

    /**
     * Fetches the diff, resolves imports, and calls the AI to generate a review.
     *
     * @param onProgress optional callback for status updates (called on the calling thread)
     */
    fun buildSummaryText(
        pr: PullRequest,
        info: RepoInfo,
        onProgress: (String) -> Unit = {}
    ): String {
        val diffStat     = service.getDiffStat(info.owner, info.repoSlug, pr.id)
        val rawDiff      = service.getPullRequestDiff(info.owner, info.repoSlug, pr.id)
        val changedFiles = diffStat.take(20).mapNotNull { it.newFile?.path ?: it.oldFile?.path }
        val basePath     = project.basePath ?: ""

        val patchMap = DiffParser.buildPatchMap(rawDiff)
        val analyses = changedFiles.map { path ->
            val content = ReadAction.compute<String, Exception> {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, path))
                vf?.let { runCatching { String(it.contentsToByteArray()) }.getOrElse { "" } } ?: ""
            }
            val patch = patchMap[path] ?: patchMap.entries
                .firstOrNull { it.key.endsWith(path) || path.endsWith(it.key) }?.value ?: ""
            CodeAnalyzer.analyze(path, content, patch)
        }

        val alreadyIncluded    = changedFiles.toMutableSet()
        val referencedAnalyses = mutableListOf<CodeAnalyzer.FileAnalysis>()

        analyses.forEach { analysis ->
            val localPaths = CodeAnalyzer.resolveLocalImports(
                imports         = analysis.imports,
                language        = analysis.language,
                projectRoot     = basePath,
                alreadyIncluded = alreadyIncluded
            )
            localPaths.forEach { refPath ->
                alreadyIncluded.add(refPath)
                val refContent = ReadAction.compute<String, Exception> {
                    val vf = LocalFileSystem.getInstance().findFileByIoFile(File(basePath, refPath))
                    vf?.let { runCatching { String(it.contentsToByteArray()) }.getOrElse { "" } } ?: ""
                }
                if (refContent.isNotBlank()) {
                    referencedAnalyses.add(
                        CodeAnalyzer.analyze(refPath, refContent, "", isReferenced = true)
                    )
                }
            }
        }

        onProgress(
            "Generating AI review for PR #${pr.id} " +
            "(${analyses.size} changed, ${referencedAnalyses.size} referenced)…"
        )

        val prContext = OpenAIClient.PrContext(
            id                = pr.id,
            title             = pr.title,
            author            = pr.author.displayName,
            sourceBranch      = pr.source.branch.name,
            destinationBranch = pr.destination.branch.name,
            fileCount         = analyses.size
        )
        return aiClient.generateSummary(buildPrompt(pr, analyses, referencedAnalyses), prContext)
    }

    // ── Internal prompt builder ───────────────────────────────────────────────

    private fun buildPrompt(
        pr: PullRequest,
        analyses: List<CodeAnalyzer.FileAnalysis>,
        referencedAnalyses: List<CodeAnalyzer.FileAnalysis> = emptyList()
    ): String = buildString {
        appendLine("## Directly Changed Files (${analyses.size})")
        appendLine()
        appendLine("These files were modified in PR #${pr.id}. Review them thoroughly.")
        appendLine()

        analyses.forEach { analysis ->
            appendLine(CodeAnalyzer.formatForPrompt(analysis))
            val fileContent = ReadAction.compute<String?, Exception> {
                val f = File(project.basePath ?: "", analysis.path)
                LocalFileSystem.getInstance().findFileByIoFile(f)?.let {
                    runCatching { String(it.contentsToByteArray()).take(3000) }.getOrNull()
                }
            }
            if (!fileContent.isNullOrBlank()) {
                appendLine("```${analysis.language}")
                appendLine(fileContent)
                appendLine("```")
            }
            appendLine("---")
        }

        if (referencedAnalyses.isNotEmpty()) {
            appendLine()
            appendLine("## Referenced Files (${referencedAnalyses.size})")
            appendLine()
            appendLine("These files are **imported by** the changed files above.")
            appendLine("They were NOT directly modified but are part of the blast radius.")
            appendLine("Use them to understand the full context — interfaces, base classes,")
            appendLine("shared utilities, or data models that the changed code depends on.")
            appendLine()

            referencedAnalyses.forEach { analysis ->
                appendLine(CodeAnalyzer.formatForPrompt(analysis))
                val refContent = ReadAction.compute<String?, Exception> {
                    val f = File(project.basePath ?: "", analysis.path)
                    LocalFileSystem.getInstance().findFileByIoFile(f)?.let {
                        runCatching { String(it.contentsToByteArray()).take(1500) }.getOrNull()
                    }
                }
                if (!refContent.isNullOrBlank()) {
                    appendLine("```${analysis.language}")
                    appendLine(refContent)
                    appendLine("```")
                }
                appendLine("---")
            }
        }
    }.trimIndent()
}
