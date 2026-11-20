package tw.dafeiyu.mangatranslator.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TranslationQueueStoreTest {
    @Test fun queueItemsArePersistedInTheSameOrder() {
        val root = Files.createTempDirectory("yaoxin-queue-test-").toFile()
        try {
            val queue = TranslationQueueStore(root.resolve("translation_queue.json"))
            queue.save(listOf(QueueItem("a"), QueueItem("b", autoExport = false)))
            val loaded = queue.load()
            assertEquals(listOf("a", "b"), loaded.map { it.projectId })
            assertTrue(loaded.first().autoExport)
            assertEquals(false, loaded.last().autoExport)
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedTranslationCanBeResumed() {
        val root = Files.createTempDirectory("yaoxin-queue-test-").toFile()
        try {
            val queue = TranslationQueueStore(root.resolve("translation_queue.json"))
            queue.save(listOf(QueueItem("a", state = QueueItem.RUNNING)))
            assertEquals(QueueItem.PAUSED, queue.load().single().state)
        } finally { root.deleteRecursively() }
    }

    @Test fun outputUriAndErrorsSurviveReload() {
        val root = Files.createTempDirectory("yaoxin-queue-test-").toFile()
        try {
            val queue = TranslationQueueStore(root.resolve("translation_queue.json"))
            queue.save(listOf(QueueItem("a", state = QueueItem.DONE, outputUri = "content://example", message = "已匯出")))
            assertEquals("content://example", queue.load().single().outputUri)
            assertEquals("已匯出", queue.load().single().message)
        } finally { root.deleteRecursively() }
    }
}
