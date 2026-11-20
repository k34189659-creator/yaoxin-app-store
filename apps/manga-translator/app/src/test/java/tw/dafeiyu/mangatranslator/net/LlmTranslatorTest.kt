package tw.dafeiyu.mangatranslator.net

import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.TranslatorConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmTranslatorTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config(extra: String = "TEST_RULE_ADULT") = TranslatorConfig(
        provider = "venice",
        model = "venice-uncensored-1-2",
        apiBase = server.url("/chat/completions").toString(),
        temperature = 0.3,
        thinking = false,
        extraInstructions = extra,
    )

    private fun enqueue(content: String, cost: Double? = null) {
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))))
            .put("usage", JSONObject().put("prompt_tokens", 100).put("completion_tokens", 20))
        if (cost != null) body.put("cost", JSONObject().put("usd", cost).put("diem", cost))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
    }

    @Test
    fun `translates by id and parses cost`() = runBlocking {
        enqueue("<|1|>你好\n<|2|>世界", cost = 0.00042)
        val t = LlmTranslator("test-key-123456", config())
        val r = t.translateDetailed(listOf("こんにちは", "世界"))
        assertEquals(listOf("你好", "世界"), r.translations)
        assertEquals(null, r.error)
        assertNotNull(r.usage)
        assertEquals(100, r.usage!!.promptTokens)
        assertEquals(0.00042, r.usage!!.costUsd!!, 1e-9)

        val req = server.takeRequest()
        assertEquals("Bearer test-key-123456", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertTrue(messages.getJSONObject(0).getString("content").contains("TEST_RULE_ADULT"))
        assertEquals("<|1|>こんにちは\n<|2|>世界", messages.getJSONObject(messages.length() - 1).getString("content"))
        assertTrue(body.has("temperature"))
        assertEquals("venice-uncensored-1-2", body.getString("model"))
    }

    @Test
    fun `missing id keeps original and reports error`() = runBlocking {
        enqueue("<|1|>你好")
        val t = LlmTranslator("test-key", config())
        val r = t.translateDetailed(listOf("こんにちは", "さようなら"))
        assertEquals("你好", r.translations[0])
        assertEquals("さようなら", r.translations[1])
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("缺少 2"))
    }

    @Test
    fun `duplicate id is rejected instead of misaligning`() = runBlocking {
        enqueue("<|1|>甲\n<|1|>乙")
        val t = LlmTranslator("test-key", config())
        val r = t.translateDetailed(listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.translations)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("重複"))
    }

    @Test
    fun `extra id is reported`() = runBlocking {
        enqueue("<|1|>甲\n<|2|>乙\n<|3|>丙")
        val t = LlmTranslator("test-key", config())
        val r = t.translateDetailed(listOf("a", "b"))
        assertEquals(listOf("甲", "乙"), r.translations)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("多出"))
    }

    @Test
    fun `http error keeps original text and reports status without key`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":"unauthorized test-key"}""")
        )
        val t = LlmTranslator("test-key", config())
        val r = t.translateDetailed(listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.translations)
        assertNotNull(r.error)
        assertTrue(r.error!!.contains("HTTP 401"))
        assertFalse(r.error!!.contains("test-key"))
    }

    @Test
    fun `multi line numbers with markdown fence are parsed`() = runBlocking {
        enqueue("```\n<|1|>甲\n<|2|>乙\n```")
        val t = LlmTranslator("test-key", config())
        val r = t.translateDetailed(listOf("a", "b"))
        assertEquals(listOf("甲", "乙"), r.translations)
        assertEquals(null, r.error)
    }
}
