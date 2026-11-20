package li.joye.yakuyomi.engine

/**
 * 「保留原圖」策略的保底規則。
 *
 * 0.3.0 的區域分類會把藝術字/不確定區標成不可擦（保留原日文），這是刻意的安全設計。
 * 但如果一整頁**每個**區域都不可擦，頁面就會完全沒有變更 —— 這正是使用者回報的
 * 「整頁保留原圖」症狀。此規則在原則是「全部保留」時，讓足夠長的文字區改用保守擦字，
 * 保證頁面仍有進度；短小的裝飾/音效區仍保持原樣。
 */
object PreservePolicy {

    /** 少於此字數的區域不強制處理（多半是裝飾字、單字音效）。 */
    const val MIN_LENGTH_TO_FORCE = 6

    data class Result(
        val inspections: Map<Int, RegionInspection>,
        /** 是否觸發保底（有區域被改成可擦）。 */
        val forced: Boolean,
        val forcedIndices: List<Int>,
    )

    /**
     * @param regions 與 [inspections] 索引一致的「有原文」文字區。
     * @param inspections 目前的檢查結果（索引 → 檢查）。
     */
    fun ensureProgress(
        regions: List<TextRegion>,
        inspections: Map<Int, RegionInspection>,
    ): Result {
        if (inspections.isEmpty()) return Result(inspections, false, emptyList())
        if (inspections.values.any { it.eraseSafe }) return Result(inspections, false, emptyList())

        val out = LinkedHashMap<Int, RegionInspection>(inspections.size)
        val forced = ArrayList<Int>()
        for ((idx, insp) in inspections) {
            val text = regions.getOrNull(idx)?.sourceText?.trim().orEmpty()
            if (text.length >= MIN_LENGTH_TO_FORCE &&
                insp.kind == TextKind.UNKNOWN && !insp.style.saturated && !insp.style.bold) {
                out[idx] = insp.copy(
                    eraseSafe = true,
                    note = "整頁未有安全區，僅對非彩色、非粗體的長文字採保守擦字；藝術字不處理",
                )
                forced.add(idx)
            } else {
                out[idx] = insp
            }
        }
        // 連一個長文字區都沒有（例如整頁只有裝飾字）：維持全部保留，交由頁面標記 SKIPPED。
        if (forced.isEmpty()) return Result(inspections, false, emptyList())
        return Result(out, true, forced)
    }
}
