package com.template.queue

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.Message
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals

class QueuePollerTest {

    companion object {
        private const val TEST_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue"
        private const val TEST_MESSAGE_ID = "test-message-123"
        private const val TEST_MESSAGE_BODY = """{"eventType":"TEST","data":"test data"}"""
        private const val TEST_RECEIPT_HANDLE = "test-receipt-handle"
    }

    private class TestProcessor : MessageProcessor {
        var processedMessages = mutableListOf<Pair<String, String>>()
        var shouldThrow = false

        override suspend fun process(messageBody: String, messageId: String) {
            if (shouldThrow) throw RuntimeException("Test error")
            processedMessages.add(messageBody to messageId)
        }
    }

    @Test
    fun `receiveMessage called with correct parameters`() = runTest {
        val mockSqsClient = mock<SqsClient>()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = TestProcessor()

        whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>())).thenReturn(
            ReceiveMessageResponse {
                messages = emptyList()
            }
        )

        val poller = QueuePoller(
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher,
            maxMessages = 10,
            waitTimeSeconds = 20
        )

        poller.pollAndProcess()

        val captor = argumentCaptor<ReceiveMessageRequest>()
        verify(mockSqsClient).receiveMessage(captor.capture())

        assertEquals(TEST_QUEUE_URL, captor.firstValue.queueUrl)
        assertEquals(10, captor.firstValue.maxNumberOfMessages)
        assertEquals(20, captor.firstValue.waitTimeSeconds)
    }

    @Test
    fun `processes message and deletes on success`() = runTest {
        val mockSqsClient = mock<SqsClient>()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = TestProcessor()

        whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>())).thenReturn(
            ReceiveMessageResponse {
                messages = listOf(
                    Message {
                        messageId = TEST_MESSAGE_ID
                        body = TEST_MESSAGE_BODY
                        receiptHandle = TEST_RECEIPT_HANDLE
                    }
                )
            }
        )

        val poller = QueuePoller(
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher
        )

        poller.pollAndProcess()

        val captor = argumentCaptor<DeleteMessageRequest>()
        verify(mockSqsClient).deleteMessage(captor.capture())

        assertEquals(TEST_QUEUE_URL, captor.firstValue.queueUrl)
        assertEquals(TEST_RECEIPT_HANDLE, captor.firstValue.receiptHandle)
        assertEquals(1, processor.processedMessages.size)
        assertEquals(TEST_MESSAGE_BODY to TEST_MESSAGE_ID, processor.processedMessages[0])
    }

    @Test
    fun `emits success metric after processing`() = runTest {
        val mockSqsClient = mock<SqsClient>()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = TestProcessor()

        whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>())).thenReturn(
            ReceiveMessageResponse {
                messages = listOf(
                    Message {
                        messageId = TEST_MESSAGE_ID
                        body = TEST_MESSAGE_BODY
                        receiptHandle = TEST_RECEIPT_HANDLE
                    }
                )
            }
        )

        val poller = QueuePoller(
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher
        )

        poller.pollAndProcess()

        verify(mockMetricsPublisher).publishMetric("MessagesProcessedSuccess", 1.0)
    }

    @Test
    fun `does not delete message on processing failure`() = runTest {
        val mockSqsClient = mock<SqsClient>()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = TestProcessor().apply { shouldThrow = true }

        whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>())).thenReturn(
            ReceiveMessageResponse {
                messages = listOf(
                    Message {
                        messageId = TEST_MESSAGE_ID
                        body = TEST_MESSAGE_BODY
                        receiptHandle = TEST_RECEIPT_HANDLE
                    }
                )
            }
        )

        val poller = QueuePoller(
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher
        )

        poller.pollAndProcess()

        verify(mockSqsClient, never()).deleteMessage(any())
        verify(mockMetricsPublisher).publishMetric("MessagesProcessedFailure", 1.0)
    }

    @Test
    fun `stop sets running flag to false`() = runTest {
        val mockSqsClient = mock<SqsClient>()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = TestProcessor()
        val poller = QueuePoller(
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher
        )

        poller.stop()
    }
}
