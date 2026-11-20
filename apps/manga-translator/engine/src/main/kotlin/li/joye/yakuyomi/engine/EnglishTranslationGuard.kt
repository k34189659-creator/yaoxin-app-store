package li.joye.yakuyomi.engine

/**
 * English-only response contract. This checks structure and obvious generation failures,
 * not translation meaning. It is also applied to old cache/manual text before painting.
 * No accepted translation is silently shortened to satisfy a limit.
 */
object EnglishTranslationGuard {
    data class CheckedBatch(
        /** Same indices as the queries; null means that region must keep its source image. */
        val translations: List<String?>,
        val errors: Map<Int, String>,
    )

    /**
     * A bounded completion allowance for one page, including reasoning where the provider
     * counts it. Eight short bubbles must not inherit a model's 32k+ default output limit.
     */
    fun completionBudget(queries: List<String>): Int {
        val sourceChars = queries.sumOf { it.length.toLong().coerceAtMost(20_000L) }
        return (128L + sourceChars * 2L + queries.size.toLong() * 48L)
            .coerceIn(512L, 4096L).toInt()
    }

    /** null = usable; a reason = retain the original region and expose the failure. */
    fun validate(source: String, translated: String): String? {
        val text = translated.trim()
        if (text.isEmpty()) return "空譯文"
        if (text.equals(source.trim(), ignoreCase = true) && source.any { it.isLetter() }) {
            return "譯文與原文相同，未取得有效翻譯"
        }
        if (source.any { it.isLetter() } && text.none { it.isLetter() }) {
            return "譯文只有符號或數字，未取得有效翻譯"
        }
        if (text.contains("<|") || text.contains("<think", ignoreCase = true) ||
            text.contains("</think", ignoreCase = true)) {
            return "譯文混入區域標記或分析內容"
        }
        if (text.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
            return "譯文含無效控制字元"
        }
        val sourceLength = source.count { !it.isWhitespace() }
        val targetLength = text.count { !it.isWhitespace() }
        val maxLength = (sourceLength.toLong() * 2L + 24L).coerceIn(80L, 1600L)
        if (targetLength > maxLength) return "譯文長度異常（原文 ${sourceLength} 字、譯文 ${targetLength} 字）"
        if (hasRunawayRepetition(text)) return "譯文出現異常重複，已拒絕嵌字"
        return null
    }

    /**
     * Parse canonical IDs without relying on response line order. Missing regions can fail
     * independently; duplicate/unknown IDs invalidate the batch because its mapping is ambiguous.
     * A token-limited response is never treated as a completed translation.
     */
    fun check(queries: List<String>, raw: String, finishReason: String? = null): CheckedBatch {
        fun rejectAll(reason: String) = CheckedBatch(
            List(queries.size) { null }, queries.indices.associateWith { reason },
        )
        if (queries.isEmpty()) return CheckedBatch(emptyList(), emptyMap())
        if (!finishReason.isNullOrBlank() && finishReason != "stop") {
            val reason = if (finishReason == "length") "翻譯回應達到輸出上限，未採用可能截斷的譯文"
                         else "翻譯回應未正常完成（${finishReason.take(40)}）"
            return rejectAll(reason)
        }
        if (raw.length > 65_536) return rejectAll("翻譯回應過長，已拒絕異常輸出")
        var cleaned = THINK_RE.replace(raw, "").trim()
        if (cleaned.contains("<think", ignoreCase = true) || cleaned.contains("</think", ignoreCase = true)) {
            return rejectAll("翻譯回應的分析區段未完整結束")
        }
        // A single surrounding code fence is harmless; embedded fences are not translation.
        if (cleaned.startsWith("```")) {
            val newline = cleaned.indexOf('\n')
            if (newline < 0 || !cleaned.endsWith("```")) return rejectAll("翻譯回應格式不完整")
            cleaned = cleaned.substring(newline + 1, cleaned.length - 3).trim()
        }
        if (cleaned.contains("```")) return rejectAll("翻譯回應混入非譯文內容")
        val markers = ID_RE.findAll(cleaned).toList()
        if (markers.isEmpty() || cleaned.substring(0, markers.first().range.first).isNotBlank()) {
            return rejectAll("翻譯回應缺少有效區域 ID 或混入說明文字")
        }
        val ids = ArrayList<Int>(markers.size)
        for (marker in markers) {
            val id = marker.groupValues[1].toIntOrNull()
            if (id == null || id !in 1..queries.size || marker.value != "<|$id|>") {
                return rejectAll("翻譯回應含未知或無效區域 ID")
            }
            if (id in ids) return rejectAll("翻譯回應含重複區域 ID")
            ids.add(id)
        }
        val translations = MutableList<String?>(queries.size) { null }
        val errors = LinkedHashMap<Int, String>()
        for (m in markers.indices) {
            val start = markers[m].range.last + 1
            val end = markers.getOrNull(m + 1)?.range?.first ?: cleaned.length
            val text = cleaned.substring(start, end).trim()
            val index = ids[m] - 1
            val error = if (text.lineSequence().count { it.isNotBlank() } > 1) {
                "翻譯回應混入未標記的續行或說明文字"
            } else validate(queries[index], text)
            if (error == null) translations[index] = text else errors[index] = error
        }
        for (index in queries.indices) {
            if (translations[index] == null && index !in errors) {
                errors[index] = "翻譯回應缺少區域 ${index + 1}"
            }
        }
        return CheckedBatch(translations, errors)
    }

    /** Ignore whitespace/punctuation so '甲乙。甲乙。...' and spaced loops are caught too. */
    private fun hasRunawayRepetition(text: String): Boolean {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.length < 24) return false
        // Bounded by validate's 1,600-character region cap. Require both a long span and
        // many repetitions: ordinary stutters, short laughs and repeated emphasis pass.
        for (period in 1..minOf(32, compact.length / 6)) {
            var run = 0
            for (i in period until compact.length) {
                run = if (compact[i] == compact[i - period]) run + 1 else 0
                val span = run + period
                if (span >= 24 && span >= period * 6) return true
            }
        }
        return false
    }

    private val ID_RE = Regex("""<\|(\d+)\|>""")
    private val THINK_RE = Regex("""<think>.*?</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
}

