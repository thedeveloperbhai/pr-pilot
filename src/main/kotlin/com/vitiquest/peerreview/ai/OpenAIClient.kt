package com.vitiquest.peerreview.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.vitiquest.peerreview.settings.PluginSettings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAIClient {

    private val http = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .build()
    private val mapper = jacksonObjectMapper()

    fun generateSummary(prompt: String): String {
        val settings = PluginSettings.instance
        val apiKey = settings.getOpenAiKey()
        require(apiKey.isNotBlank()) { "OpenAI API key is not configured. Go to Settings → Tools → PR Review Assistant." }

        val baseUrl = settings.openAiBaseUrl.trimEnd('/')
        val model = settings.openAiModel.ifBlank { "gpt-4o" }

        val requestBody = mapper.writeValueAsString(
            mapOf(
                "model" to model,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to 1500,
                "temperature" to 0.3
            )
        )

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IOException("OpenAI API error ${response.code}: $body")
            }
            val parsed: ChatCompletionResponse = mapper.readValue(body, ChatCompletionResponse::class.java)
            return parsed.choices.firstOrNull()?.message?.content
                ?: throw IOException("Empty response from AI")
        }
    }
}

// ---- DTOs ----

@JsonIgnoreProperties(ignoreUnknown = true)
data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Choice(
    val message: Message = Message()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Message(
    val content: String = ""
)

