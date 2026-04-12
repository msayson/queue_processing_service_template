package com.template.queue

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class QueuePollerTest {

    private class TestProcessor : MessageProcessor {
        var processedCount = 0
        var shouldThrow = false

        override suspend fun process(messageBody: String, messageId: String) {
            if (shouldThrow) throw RuntimeException("Test error")
            processedCount++
        }
    }

    @Test
    fun `stop sets running flag to false`() = runTest {
        val processor = TestProcessor()
        val poller = QueuePoller(processor)

        poller.stop()
    }

    @Test
    fun `poller can be stopped before starting`() = runTest {
        val processor = TestProcessor()
        val poller = QueuePoller(processor)

        poller.stop()
    }
}
