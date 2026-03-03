package com.vitiquest.peerreview.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class SettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val bitbucketPatField = JBPasswordField()
    private val openAiKeyField = JBPasswordField()
    private val openAiBaseUrlField = JBTextField()
    private val openAiModelField = JBTextField()

    override fun getDisplayName(): String = "PR Review Assistant"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(JBLabel("Bitbucket Cloud"))
            .addLabeledComponent("Personal Access Token:", bitbucketPatField, true)
            .addSeparator()
            .addComponent(JBLabel("OpenAI-Compatible API"))
            .addLabeledComponent("API Key:", openAiKeyField, true)
            .addLabeledComponent("Base URL:", openAiBaseUrlField, true)
            .addLabeledComponent("Model:", openAiModelField, true)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset() // populate with current values
        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.instance
        return String(bitbucketPatField.password) != settings.getBitbucketPat() ||
                String(openAiKeyField.password) != settings.getOpenAiKey() ||
                openAiBaseUrlField.text != settings.openAiBaseUrl ||
                openAiModelField.text != settings.openAiModel
    }

    override fun apply() {
        val settings = PluginSettings.instance
        val newPat = String(bitbucketPatField.password)
        if (newPat.isNotBlank()) settings.setBitbucketPat(newPat)
        val newKey = String(openAiKeyField.password)
        if (newKey.isNotBlank()) settings.setOpenAiKey(newKey)
        settings.openAiBaseUrl = openAiBaseUrlField.text.trim().ifBlank { "https://api.openai.com" }
        settings.openAiModel = openAiModelField.text.trim().ifBlank { "gpt-4o" }
    }

    override fun reset() {
        val settings = PluginSettings.instance
        bitbucketPatField.text = settings.getBitbucketPat()
        openAiKeyField.text = settings.getOpenAiKey()
        openAiBaseUrlField.text = settings.openAiBaseUrl
        openAiModelField.text = settings.openAiModel
    }

    override fun disposeUIResources() {
        panel = null
    }
}

