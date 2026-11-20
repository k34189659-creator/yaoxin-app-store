package tw.dafeiyu.mangatranslator.net

import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.EnglishTranslationGuard
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.TranslatorConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class EnglishLlmTranslatorTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun teardown() { server.shutdown() }

    private fun translator(model: String = "test-model") = LlmTranslator("local-test-key", TranslatorConfig(
        provider = "venice", model = model,
        apiBase = server.url("/chat/completions").toString(),
        fromLangName = "English", sampleSource = "", sampleTarget = "",
    ))

    private fun enqueue(content: String, finishReason: String = "stop") {
        server.enqueue(MockResponse().setResponseCode(200).setBody(JSONObject()
            .put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", content)).put("finish_reason", finishReason)))
            .put("usage", JSONObject().put("prompt_tokens", 123).put("completion_tokens", 456))
            .put("cost", JSONObject().put("usd", 0.001))
            .toString()))
    }

    @Test fun englishRequestCarriesBoundedBudgetAndConciseContract() = runBlocking {
        val queries = listOf("Hello.", "Wait.")
        enqueue("<|1|>你好。\n<|2|>等一下。")
        val result = translator().translateDetailed(queries, englishGuard = true)
        assertEquals(listOf("你好。", "等一下。"), result.translations)
        assertTrue(result.regionErrors.isEmpty())
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(EnglishTranslationGuard.completionBudget(queries), body.getInt("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertTrue(body.getJSONObject("venice_parameters").getBoolean("disable_thinking"))
        assertTrue(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("OUTPUT CONTRACT"))
        assertEquals(1, server.requestCount)
    }

    @Test fun truncatedResponseKeepsSourcesAndReportsUsageWithoutAutomaticRetry() = runBlocking {
        val queries = listOf("Hello.", "Wait for me.")
        enqueue("<|1|>你好。\n<|2|>等", "length")
        val result = translator().translateDetailed(queries, englishGuard = true)
        assertEquals(queries, result.translations)
        assertEquals(setOf(0, 1), result.regionErrors.keys)
        assertEquals("length", result.finishReason)
        assertEquals(456, result.usage!!.completionTokens)
        assertEquals(0.001, result.usage!!.costUsd!!, 0.000001)
        assertEquals(1, server.requestCount)
    }

    @Test fun runawayRegionIsRejectedBeforeItCanBeCachedOrPainted() = runBlocking {
        val first = "Everything is fine. We will meet at the station tomorrow."
        enqueue("<|1|>" + "沒問題。".repeat(18) + "\n<|2|>等一下。")
        val result = translator().translateDetailed(listOf(first, "Wait."), englishGuard = true)
        assertEquals(listOf(first, "等一下。"), result.translations)
        assertEquals(setOf(0), result.regionErrors.keys)
        assertTrue(result.error!!.contains("重複"))
        assertEquals(1, server.requestCount)
    }

    @Test fun japaneseDefaultRequestDoesNotEnableEnglishPolicyOrCap() = runBlocking {
        enqueue("<|1|>你好。")
        val result = translator().translateDetailed(listOf("こんにちは。"))
        assertEquals(listOf("你好。"), result.translations)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("max_completion_tokens"))
        assertFalse(body.has("max_tokens"))
        assertFalse(body.has("venice_parameters"))
        assertFalse(body.getJSONArray("messages").getJSONObject(0).getString("content").contains("OUTPUT CONTRACT"))
    }

    @Test fun budgetIsNeverDroppedWhenServerRejectsItsParameter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":"unsupported parameter max_completion_tokens"}"""))
        val result = translator().translateDetailed(listOf("Hello."), englishGuard = true)
        assertEquals(listOf("Hello."), result.translations)
        assertNotNull(result.regionErrors[0])
        assertEquals(1, server.requestCount)
    }

    @Test fun optionalThinkingNegotiationRetainsTheHardBudget() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":"disable_thinking is not supported"}"""))
        enqueue("<|1|>你好。")
        // Unique model avoids contaminating other tests via the existing process-wide cache.
        val result = translator("test-thinking-negotiation").translateDetailed(listOf("Hello."), englishGuard = true)
        assertEquals(listOf("你好。"), result.translations)
        val first = JSONObject(server.takeRequest().body.readUtf8())
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(512, first.getInt("max_completion_tokens"))
        assertEquals(512, second.getInt("max_completion_tokens"))
        assertTrue(first.has("venice_parameters"))
        assertFalse(second.has("venice_parameters"))
        assertEquals(2, server.requestCount)
    }
}

