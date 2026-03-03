package com.vitiquest.peerreview.bitbucket

import com.vitiquest.peerreview.settings.PluginSettings

/**
 * Thin service that combines BitbucketClient with the resolved workspace/repo from settings.
 * Keeps the UI layer free from credential management details.
 */
class PullRequestService {

    private fun client(): BitbucketClient {
        val pat = PluginSettings.instance.getBitbucketPat()
        require(pat.isNotBlank()) { "Bitbucket PAT is not configured. Go to Settings → Tools → PR Review Assistant." }
        return BitbucketClient(pat)
    }

    fun getPullRequests(workspace: String, repoSlug: String, state: String = "OPEN"): List<PullRequest> =
        client().getPullRequests(workspace, repoSlug, state)

    fun getPullRequestDetails(workspace: String, repoSlug: String, prId: Int): PullRequest =
        client().getPullRequestDetails(workspace, repoSlug, prId)

    fun getPullRequestDiff(workspace: String, repoSlug: String, prId: Int): String =
        client().getPullRequestDiff(workspace, repoSlug, prId)

    fun getDiffStat(workspace: String, repoSlug: String, prId: Int): List<DiffStatEntry> =
        client().getDiffStat(workspace, repoSlug, prId)

    fun approvePullRequest(workspace: String, repoSlug: String, prId: Int) =
        client().approvePullRequest(workspace, repoSlug, prId)

    fun declinePullRequest(workspace: String, repoSlug: String, prId: Int) =
        client().declinePullRequest(workspace, repoSlug, prId)
}

