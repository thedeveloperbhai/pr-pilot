package com.vitiquest.peerreview.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Non-secret settings (model name, base URL) are stored via PersistentStateComponent.
 * Secrets (PAT, OpenAI key) are stored via PasswordSafe.
 */
@State(
    name = "PRReviewAssistantSettings",
    storages = [Storage("PRReviewAssistant.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    companion object {
        val instance: PluginSettings
            get() = ApplicationManager.getApplication().getService(PluginSettings::class.java)

        private const val SERVICE_BITBUCKET = "PRReviewAssistant.Bitbucket"
        private const val SERVICE_OPENAI = "PRReviewAssistant.OpenAI"
        private const val USERNAME = "token"
    }

    data class State(
        var openAiBaseUrl: String = "https://api.openai.com",
        var openAiModel: String = "gpt-4o"
    )

    private var myState = State()

    override fun getState(): State = myState
    override fun loadState(state: State) {
        myState = state
    }

    // ---- Non-secret accessors ----

    var openAiBaseUrl: String
        get() = myState.openAiBaseUrl
        set(v) { myState.openAiBaseUrl = v }

    var openAiModel: String
        get() = myState.openAiModel
        set(v) { myState.openAiModel = v }

    // ---- Secret accessors via PasswordSafe ----

    fun getBitbucketPat(): String =
        PasswordSafe.instance.getPassword(credentialAttributes(SERVICE_BITBUCKET)) ?: ""

    fun setBitbucketPat(pat: String) {
        PasswordSafe.instance.set(
            credentialAttributes(SERVICE_BITBUCKET),
            Credentials(USERNAME, pat)
        )
    }

    fun getOpenAiKey(): String =
        PasswordSafe.instance.getPassword(credentialAttributes(SERVICE_OPENAI)) ?: ""

    fun setOpenAiKey(key: String) {
        PasswordSafe.instance.set(
            credentialAttributes(SERVICE_OPENAI),
            Credentials(USERNAME, key)
        )
    }

    private fun credentialAttributes(service: String) =
        CredentialAttributes(service, USERNAME)
}

