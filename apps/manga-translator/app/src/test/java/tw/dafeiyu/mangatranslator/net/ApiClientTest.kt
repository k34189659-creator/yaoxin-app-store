package tw.dafeiyu.mangatranslator.net

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

class ApiClientTest {
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

    private fun config(key: String = "test-key-123456"): ApiConfig = ApiConfig(
        providerId = "custom",
        baseUrl = server.url("/v1").toString().trimEnd('/'),
        apiKey = key,
        modelId = "test-model",
    )

    @Test
    fun `venice urls are fixed and custom urls are derived`() {
        val v = ApiConfig("venice", "https://ignored.example", "k", "m")
        assertEquals("https://api.venice.ai/api/v1/chat/completions", v.chatUrl)
        assertEquals("https://api.venice.ai/api/v1/models", v.modelsUrl)
        assertEquals("https://host/v1/chat/completions", ApiConfig.deriveChat("https://host/v1"))
        assertEquals("https://host/v1/chat/completions", ApiConfig.deriveChat("https://host"))
        assertEquals("https://host/v1/chat/completions", ApiConfig.deriveChat("https://host/v1/chat/completions"))
        assertEquals("https://host/v1/models", ApiConfig.deriveModels("https://host/v1"))
        assertEquals("https://host/v1/models", ApiConfig.deriveModels("https://host/v1/chat/completions"))
    }

    @Test
    fun `model list parses venice pricing and puts uncensored first`() {
        val body = JSONObject().put(
            "data",
            JSONArray()
                .put(
                    JSONObject().put("id", "z-regular").put(
                        "model_spec", JSONObject().put(
                            "pricing", JSONObject()
                                .put("input", JSONObject().put("usd", 1.5))
                                .put("output", JSONObject().put("usd", 3.0))
                        )
                    )
                )
                .put(
                    JSONObject().put("id", "venice-uncensored-1-2").put(
                        "model_spec", JSONObject().put(
                            "pricing", JSONObject()
                                .put("input", JSONObject().put("usd", 0.2))
                                .put("output", JSONObject().put("usd", 0.9))
                        )
                    )
                )
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
        val models = ApiClient.listModels(config())
        assertEquals(2, models.size)
        assertEquals("venice-uncensored-1-2", models[0].id)
        assertTrue(models[0].isUncensored)
        assertEquals(0.2, models[0].inputUsdPerMillion!!, 0.0001)
        assertEquals(0.9, models[0].outputUsdPerMillion!!, 0.0001)
        assertTrue(models[0].priceLabel().contains("0.20"))
        val req = server.takeRequest()
        assertEquals("/v1/models", req.path)
        assertEquals("Bearer test-key-123456", req.getHeader("Authorization"))
    }

    @Test
    fun `model list error redacts key`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":"invalid key test-key-123456"}""")
        )
        try {
            ApiClient.listModels(config())
            throw AssertionError("should fail")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("HTTP 401"))
            assertFalse("key 不應出現在錯誤訊息", e.message!!.contains("test-key-123456"))
        }
    }

    @Test
    fun `connection test parses reply and cost`() {
        val body = JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "OK"))))
            .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 2))
            .put("cost", JSONObject().put("usd", 0.00042))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body.toString()))
        val result = ApiClient.testConnection(config())
        assertTrue(result.ok)
        assertEquals(200, result.httpCode)
        assertEquals("OK", result.reply)
        assertNotNull(result.costUsd)
        assertEquals(0.00042, result.costUsd!!, 1e-9)
        val req = server.takeRequest()
        val sent = JSONObject(req.body.readUtf8())
        assertEquals("test-model", sent.getString("model"))
        assertEquals("Bearer test-key-123456", req.getHeader("Authorization"))
    }

    @Test
    fun `connection test failure is reported without leaking key`() {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("""{"error":"bad key test-key-123456"}""")
        )
        val result = ApiClient.testConnection(config())
        assertFalse(result.ok)
        assertEquals(401, result.httpCode)
        assertTrue(result.message.contains("HTTP 401"))
        assertFalse(result.message.contains("test-key-123456"))
    }

    @Test
    fun `connection test without key fails before network`() {
        val result = ApiClient.testConnection(config(key = ""))
        assertFalse(result.ok)
        assertTrue(result.message.contains("API Key"))
        assertEquals(0, server.requestCount)
    }
}
