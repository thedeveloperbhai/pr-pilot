package com.vitiquest.peerreview.bitbucket

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class PullRequest(
    val id: Int = 0,
    val title: String = "",
    val state: String = "",
    val author: Author = Author(),
    val description: String = "",
    val source: RefHolder = RefHolder(),
    val destination: RefHolder = RefHolder(),
    val links: Links = Links(),
    @param:JsonProperty("created_on") val createdOn: String = "",
    @param:JsonProperty("updated_on") val updatedOn: String = "",
    @param:JsonProperty("comment_count") val commentCount: Int = 0,
    @param:JsonProperty("task_count") val taskCount: Int = 0
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Author(
    @param:JsonProperty("display_name") val displayName: String = "",
    val nickname: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RefHolder(
    val branch: Branch = Branch(),
    val repository: RepositoryRef = RepositoryRef()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Branch(
    val name: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RepositoryRef(
    @param:JsonProperty("full_name") val fullName: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Links(
    val html: HtmlLink = HtmlLink()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class HtmlLink(
    val href: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PullRequestsResponse(
    val values: List<PullRequest> = emptyList(),
    val next: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiffStatEntry(
    val status: String = "",
    @param:JsonProperty("new") val newFile: FileRef? = null,
    @param:JsonProperty("old") val oldFile: FileRef? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class FileRef(
    val path: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DiffStatResponse(
    val values: List<DiffStatEntry> = emptyList()
)




