package com.template.queue

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class TemplateMessageProcessorTest {

    @Test
    fun `process handles valid message`() = runTest {
        val processor = TemplateMessageProcessor()
        val messageBody = """{"eventType":"TEST","data":"test data"}"""
        val messageId = "test-message-123"

        processor.process(messageBody, messageId)
    }

    @Test
    fun `process handles empty message body`() = runTest {
        val processor = TemplateMessageProcessor()

        processor.process("", "test-message-456")
    }

    @Test
    fun `process handles large message body`() = runTest {
        val processor = TemplateMessageProcessor()
        val largeBody = "x".repeat(10000)

        processor.process(largeBody, "test-message-789")
    }
}
