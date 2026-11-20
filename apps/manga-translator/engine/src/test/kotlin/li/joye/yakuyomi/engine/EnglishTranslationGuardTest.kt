package li.joye.yakuyomi.engine

import org.junit.Assert.*
import org.junit.Test

class EnglishTranslationGuardTest {
    @Test fun reorderedIdsKeepTheirOriginalRegions() {
        val result = EnglishTranslationGuard.check(
            listOf("Good morning.", "See you tonight."),
            "<|2|>今晚見。\n<|1|>早安。", "stop",
        )
        assertEquals(listOf("早安。", "今晚見。"), result.translations)
        assertTrue(result.errors.isEmpty())
    }

    @Test fun missingIdFailsOnlyThatRegion() {
        val result = EnglishTranslationGuard.check(listOf("Hello.", "Wait."), "<|2|>等一下。", "stop")
        assertEquals(listOf(null, "等一下。"), result.translations)
        assertEquals(setOf(0), result.errors.keys)
    }

    @Test fun duplicateIdsCannotMisassignText() {
        val result = EnglishTranslationGuard.check(
            listOf("Hello.", "Wait."), "<|1|>你好。\n<|1|>等一下。", "stop",
        )
        assertEquals(listOf(null, null), result.translations)
        assertTrue(result.errors.values.all { it.contains("重複") })
    }

    @Test fun extraIdsRejectAmbiguousBatch() {
        val result = EnglishTranslationGuard.check(listOf("Hello."), "<|1|>你好。\n<|2|>多出來。", "stop")
        assertNull(result.translations[0])
        assertTrue(result.errors[0]!!.contains("ID"))
    }

    @Test fun hugeOrMalformedIdFailsWithoutThrowing() {
        for (raw in listOf("<|99999999999999999999999999|>你好。", "<|01|>你好。", "<|x|>你好。")) {
            val result = EnglishTranslationGuard.check(listOf("Hello."), raw, "stop")
            assertNull(result.translations[0])
            assertNotNull(result.errors[0])
        }
    }

    @Test fun inlineCanonicalIdsAreStillSeparated() {
        val result = EnglishTranslationGuard.check(listOf("Hello.", "Wait."), "<|1|>你好。<|2|>等一下。", "stop")
        assertEquals(listOf("你好。", "等一下。"), result.translations)
    }

    @Test fun repeatedRunawayTranslationIsNeverPassedToRenderer() {
        val result = EnglishTranslationGuard.check(
            listOf("Everything is fine. We will meet at the station tomorrow.", "Wait."),
            "<|1|>" + "沒問題。".repeat(18) + "\n<|2|>等一下。", "stop",
        )
        assertEquals(listOf(null, "等一下。"), result.translations)
        assertTrue(result.errors[0]!!.contains("重複"))
    }

    @Test fun overlongUnrepeatedTranslationIsNotSilentlyTruncated() {
        val output = (0 until 140).map { (0x4e00 + it).toChar() }.joinToString("")
        val result = EnglishTranslationGuard.check(listOf("Hello."), "<|1|>$output", "stop")
        assertNull(result.translations[0])
        assertTrue(result.errors[0]!!.contains("長度"))
    }

    @Test fun shortSoundEffectsAndNormalChineseRemainValid() {
        assertNull(EnglishTranslationGuard.validate("Ha ha ha ha!", "哈哈哈哈哈哈！"))
        assertNull(EnglishTranslationGuard.validate("No!!", "不要！！"))
        assertNull(EnglishTranslationGuard.validate("Okay.", "好。"))
        assertNull(EnglishTranslationGuard.validate("We will meet at 24:00.", "我們午夜十二點見。"))
    }

    @Test fun noOpAndEmptyTranslationsAreRejected() {
        assertNotNull(EnglishTranslationGuard.validate("NO!!", "NO!!"))
        assertNotNull(EnglishTranslationGuard.validate("Hello.", " "))
        assertNotNull(EnglishTranslationGuard.validate("Hello.", "123456"))
        assertNotNull(EnglishTranslationGuard.validate("Hello.", "......"))
    }

    @Test fun tokenLimitedResponseCannotLookSuccessfulEvenWithAllIds() {
        val result = EnglishTranslationGuard.check(
            listOf("Hello.", "Wait for me at the gate."),
            "<|1|>你好。\n<|2|>在門口等", "length",
        )
        assertEquals(listOf(null, null), result.translations)
        assertTrue(result.errors.values.all { it.contains("輸出上限") })
    }

    @Test fun unexpectedFinishReasonIsRejected() {
        val result = EnglishTranslationGuard.check(listOf("Hello."), "<|1|>你好。", "content_filter")
        assertNull(result.translations[0])
    }

    @Test fun leadingExplanationAndIncompleteThinkingAreRejected() {
        for (raw in listOf("Here is the translation:\n<|1|>你好。", "<think>Working\n<|1|>你好。")) {
            val result = EnglishTranslationGuard.check(listOf("Hello."), raw, "stop")
            assertNull(result.translations[0])
        }
    }

    @Test fun untaggedContinuationDoesNotBecomeDialogue() {
        val result = EnglishTranslationGuard.check(
            listOf("Hello.", "Wait."), "<|1|>你好。\nTranslation notes: ...\n<|2|>等一下。", "stop",
        )
        assertEquals(listOf(null, "等一下。"), result.translations)
        assertNotNull(result.errors[0])
    }

    @Test fun singleCodeFenceAndCompletedThinkingAreNotPrinted() {
        val result = EnglishTranslationGuard.check(
            listOf("Hello."), "<think>internal text</think>\n```text\n<|1|>你好。\n```", "stop",
        )
        assertEquals(listOf("你好。"), result.translations)
    }

    @Test fun budgetGrowsWithInputButHasHardBounds() {
        assertEquals(512, EnglishTranslationGuard.completionBudget(listOf("No!!")))
        assertTrue(EnglishTranslationGuard.completionBudget(List(8) { "We will meet at the station tomorrow." }) > 512)
        assertEquals(4096, EnglishTranslationGuard.completionBudget(List(100) { "A".repeat(20_000) }))
        assertEquals(512, EnglishTranslationGuard.completionBudget(emptyList()))
    }
}

