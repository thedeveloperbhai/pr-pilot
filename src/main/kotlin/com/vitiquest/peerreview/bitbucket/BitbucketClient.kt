package com.vitiquest.peerreview.bitbucket

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class BitbucketClient(private val pat: String) {

    private val http = OkHttpClient()
    private val mapper = jacksonObjectMapper()
    private val base = "https://api.bitbucket.org/2.0"

    // -------------------------------------------------------------------------
    // Pull Requests
    // -------------------------------------------------------------------------

    fun getPullRequests(workspace: String, repoSlug: String, state: String = "OPEN"): List<PullRequest> {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests?state=$state&pagelen=20"
        val response = get(url)
        val parsed: PullRequestsResponse = mapper.readValue(response)
        return parsed.values
    }

    fun getPullRequestDetails(workspace: String, repoSlug: String, prId: Int): PullRequest {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId"
        val response = get(url)
        return mapper.readValue(response)
    }

    fun getPullRequestDiff(workspace: String, repoSlug: String, prId: Int): String {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diff"
        return get(url)
    }

    fun getDiffStat(workspace: String, repoSlug: String, prId: Int): List<DiffStatEntry> {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/diffstat?pagelen=50"
        val response = get(url)
        val parsed: DiffStatResponse = mapper.readValue(response)
        return parsed.values
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    fun approvePullRequest(workspace: String, repoSlug: String, prId: Int) {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/approve"
        post(url, "")
    }

    fun declinePullRequest(workspace: String, repoSlug: String, prId: Int) {
        val url = "$base/repositories/$workspace/$repoSlug/pullrequests/$prId/decline"
        post(url, "{}")
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

    private fun execute(request: Request): String {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Bitbucket API error ${response.code}: ${response.body?.string()}")
            }
            return response.body?.string() ?: ""
        }
    }
}

