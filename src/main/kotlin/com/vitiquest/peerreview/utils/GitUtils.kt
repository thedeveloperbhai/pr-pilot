package com.vitiquest.peerreview.utils

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

data class BitbucketRepo(val workspace: String, val repoSlug: String)

object GitUtils {

    /**
     * Returns the Bitbucket workspace + repo slug from the first git remote that
     * points to bitbucket.org, or null if none found.
     */
    fun detectBitbucketRepo(project: Project): BitbucketRepo? {
        val manager = GitRepositoryManager.getInstance(project)
        val repo = manager.repositories.firstOrNull() ?: return null

        val remoteUrl = repo.remotes
            .flatMap { remote -> remote.urls }
            .firstOrNull { it.contains("bitbucket.org", ignoreCase = true) }
            ?: return null

        return parseRemoteUrl(remoteUrl)
    }

    /**
     * Supports both:
     *   https://bitbucket.org/workspace/repo.git
     *   git@bitbucket.org:workspace/repo.git
     */
    fun parseRemoteUrl(url: String): BitbucketRepo? {
        // SSH: git@bitbucket.org:workspace/repo.git
        val sshPattern = Regex("""git@bitbucket\.org[:/]([^/]+)/([^/]+?)(?:\.git)?$""")
        sshPattern.find(url)?.let { m ->
            return BitbucketRepo(m.groupValues[1], m.groupValues[2])
        }

        // HTTPS: https://bitbucket.org/workspace/repo.git
        val httpsPattern = Regex("""https?://(?:[^@]+@)?bitbucket\.org/([^/]+)/([^/]+?)(?:\.git)?$""")
        httpsPattern.find(url)?.let { m ->
            return BitbucketRepo(m.groupValues[1], m.groupValues[2])
        }

        return null
    }

    /**
     * Returns the name of the currently checked-out branch for the first repo.
     */
    fun currentBranch(project: Project): String? {
        val manager = GitRepositoryManager.getInstance(project)
        return manager.repositories.firstOrNull()?.currentBranchName
    }
}

