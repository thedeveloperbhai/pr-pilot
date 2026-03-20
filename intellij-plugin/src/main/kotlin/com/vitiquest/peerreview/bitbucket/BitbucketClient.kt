package com.vitiquest.peerreview.bitbucket

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.vitiquest.peerreview.ai.InlineComment
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * @param pat  Bitbucket Repository / Project / Workspace Access Token
 *             (generated under Repository settings → Access tokens  OR
 *              Workspace settings → Access tokens).
 *
 * These tokens use HTTP Bearer auth:
 *   Authorization: Bearer <token>
 *
 * NOTE: Bitbucket *App Passwords* (Personal settings → App passwords) use
 * Basic auth and are NOT supported here.  Use an Access Token instead.
 */
class BitbucketClient(internal val pat: String) {

    private val mapper = jacksonObjectMapper()
    private val base   = "https://api.bitbucket.org/2.0"

    companion object {
        val http: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }
    }

    // -------------------------------------------------------------------------
    // Pull Requests
    // -------------------------------------------------------------------------

    fun getPullRequests(workspace: String, repoSlug: String, state: String = "OPEN"): List<PullRequest> {
        val stateParams = when (state.uppercase()) {
            "ALL"  -> "state=OPEN&state=MERGED&state=DECLINED"
            else   -> "state=${state.uppercase()}"
        }
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests?$stateParams&pagelen=20"
        val response = get(url)
        val parsed: PullRequestsResponse = mapper.readValue(response)
        return parsed.values
    }

    fun getPullRequestDetails(workspace: String, repoSlug: String, prId: Int): PullRequest {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId"
        return mapper.readValue(get(url))
    }

    fun getPullRequestDiff(workspace: String, repoSlug: String, prId: Int): String {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diff"
        return get(url)
    }

    fun getDiffStat(workspace: String, repoSlug: String, prId: Int): List<DiffStatEntry> {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diffstat?pagelen=50"
        val parsed: DiffStatResponse = mapper.readValue(get(url))
        return parsed.values
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    fun approvePullRequest(workspace: String, repoSlug: String, prId: Int) {
        post("$base/repositories/$workspace/$repoSlug/pullrequests/$prId/approve", "{}")
    }

    fun declinePullRequest(workspace: String, repoSlug: String, prId: Int) {
        post("$base/repositories/$workspace/$repoSlug/pullrequests/$prId/decline", "{}")
    }

    fun mergePullRequest(workspace: String, repoSlug: String, prId: Int) {
        val body = """{"type":"pullrequest","merge_strategy":"merge_commit","close_source_branch":false}"""
        post("$base/repositories/$workspace/$repoSlug/pullrequests/$prId/merge", body)
    }

    /**
     * Posts a text comment on a pull request.
     * Bitbucket API: POST /2.0/repositories/{workspace}/{repoSlug}/pullrequests/{prId}/comments
     *
     * Uses Jackson to serialise the body so newlines, backticks, quotes and other
     * special characters in the AI summary are always properly escaped.
     */
    fun postComment(workspace: String, repoSlug: String, prId: Int, commentBody: String) {
        val jsonBody = mapper.writeValueAsString(
            mapOf("content" to mapOf("raw" to commentBody))
        )
        post("$base/repositories/$workspace/$repoSlug/pullrequests/$prId/comments", jsonBody)
    }

    /**
     * Posts each [InlineComment] as an individual inline comment anchored to the
     * specified file and line number.
     *
     * Bitbucket Cloud API:
     * POST /2.0/repositories/{workspace}/{repoSlug}/pullrequests/{prId}/comments
     * {
     *   "content": { "raw": "<text>" },
     *   "inline":  { "to": <line>, "path": "<file>" }
     * }
     */
    fun postInlineComments(workspace: String, repoSlug: String, prId: Int, comments: List<InlineComment>) {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/comments"

        for ((index, ic) in comments.withIndex()) {
            if (ic.file.isBlank() || ic.line <= 0) continue

            // Bitbucket Cloud inline comment payload.
            // "type" is a *response* discriminator — do NOT send it in the request body.
            // "inline.from" and "inline.to" must both be present.
            val payload = mapper.writeValueAsString(
                mapOf(
                    "content" to mapOf("raw" to ic.comment),
                    "inline"  to mapOf(
                        "from" to ic.line,
                        "to"   to ic.line,
                        "path" to ic.file
                    )
                )
            )

            postRaw(url, payload)
        }
    }

    /**
     * Posts inline comments and then posts a top-level "Changes Requested" comment
     * summarising the request.  Bitbucket Cloud does not have a first-class
     * "request-changes" API in the free tier, so we approximate it with a comment.
     *
     * @param summaryBody  The overall AI review text to post as a top-level comment.
     * @param comments     Inline per-line comments to attach.
     */
    fun requestChanges(
        workspace: String,
        repoSlug: String,
        prId: Int,
        summaryBody: String,
        comments: List<InlineComment>
    ) {
        postInlineComments(workspace, repoSlug, prId, comments)
        val header = "⚠️ **Changes Requested**\n\n"
        postComment(workspace, repoSlug, prId, header + summaryBody)
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/json")
            .get()
            .build()
        return execute(request)
    }

    private fun post(url: String, jsonBody: String): String {
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $pat")
            .header("Accept", "application/json")
            .post(body)
            .build()
        return execute(request)
    }

    /** Same as [post] but also used directly where we want to capture the raw response. */
    private fun postRaw(url: String, jsonBody: String): String = post(url, jsonBody)

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            val bodyText = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                val hint = when (response.code) {
                    401 -> " — Invalid or expired Access Token. " +
                           "Regenerate it under Bitbucket → Repo settings → Access tokens and update Settings."
                    403 -> " — Token does not have sufficient permissions (needs at least read scope on pull-requests)."
                    404 -> " — Repository not found. Check that Workspace and Repo Slug exactly match the Bitbucket URL."
                    else -> ""
                }
                throw IOException(
                    "Bitbucket API ${response.code}$hint\n" +
                    "[${request.method} ${request.url}]: ${bodyText.take(400)}"
                )
            }
            return bodyText
        }
    }
}
