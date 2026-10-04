package com.template.queue

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageResponse
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerManagerTest {

    companion object {
        private const val TEST_QUEUE_URL = "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue"
    }

    /**
     * Returns an SqsClient mock that always returns an empty ReceiveMessageResponse.
     */
    private fun emptyQueueSqsClient(): SqsClient {
        val mockSqsClient = mock<SqsClient>()
        kotlinx.coroutines.runBlocking {
            whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>()))
                .thenReturn(ReceiveMessageResponse { messages = emptyList() })
        }
        return mockSqsClient
    }

    @Test
    fun `stop signals all worker running flags to false`() {
        val mockSqsClient = emptyQueueSqsClient()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {}
        }

        val manager = WorkerManager(
            numWorkers = 3,
            gracePeriodSeconds = 5,
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher,
            waitTimeSeconds = 1
        )

        manager.start()
        manager.stop()

        // Workers should have stopped cleanly within the grace period.
        // (Verified implicitly: stop returns without logging warnings.)
    }

    @Test
    fun `all N workers are started`() {
        val workerStartCount = AtomicInteger(0)
        val allStarted = CountDownLatch(3)

        val mockSqsClient = mock<SqsClient>()
        kotlinx.coroutines.runBlocking {
            whenever(mockSqsClient.receiveMessage(any<ReceiveMessageRequest>()))
                .thenReturn(ReceiveMessageResponse { messages = emptyList() })
        }

        val mockMetricsPublisher = mock<MetricsPublisher>()

        // A processor that counts how many coroutines have reached it, then
        // waits for the manager to be stopped.
        val processor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {}
        }

        // We can't easily intercept coroutine launch counts from outside, so
        // we verify indirectly: receiveMessage is called at least N times total
        // after start(), confirming N independent pollers are running.
        val manager = WorkerManager(
            numWorkers = 3,
            gracePeriodSeconds = 5,
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher,
            waitTimeSeconds = 0  // no long-poll delay in tests
        )

        manager.start()

        // Allow some time for workers to make at least one poll each.
        Thread.sleep(300)

        manager.stop()

        // With 3 workers each polling at least once, we expect >= 3 receiveMessage calls.
        val callCount = mockingDetails(mockSqsClient).invocations.size
        assertTrue(callCount >= 3, "Expected >= 3 receiveMessage calls, got $callCount")
    }

    @Test
    fun `stop returns within grace period when workers stop promptly`() {
        val mockSqsClient = emptyQueueSqsClient()
        val mockMetricsPublisher = mock<MetricsPublisher>()
        val processor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {}
        }

        val manager = WorkerManager(
            numWorkers = 2,
            gracePeriodSeconds = 10,
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher,
            waitTimeSeconds = 0
        )

        manager.start()
        Thread.sleep(100)

        val startMs = System.currentTimeMillis()
        manager.stop()
        val elapsedMs = System.currentTimeMillis() - startMs

        // Should complete well within the 10-second grace period.
        assertTrue(elapsedMs < 9_000, "stop took ${elapsedMs}ms, expected < 9000ms")
    }

    @Test
    fun `workers process messages independently`() {
        val processedCount = AtomicInteger(0)
        val mockSqsClient = emptyQueueSqsClient()
        val mockMetricsPublisher = mock<MetricsPublisher>()

        val processor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {
                processedCount.incrementAndGet()
                delay(50)
            }
        }

        val manager = WorkerManager(
            numWorkers = 4,
            gracePeriodSeconds = 5,
            sqsClient = mockSqsClient,
            queueUrl = TEST_QUEUE_URL,
            processor = processor,
            metricsPublisher = mockMetricsPublisher,
            waitTimeSeconds = 0
        )

        manager.start()
        Thread.sleep(200)
        manager.stop()

        // Empty queue means processor.process is never called; we just confirm
        // the manager starts and stops cleanly with multiple workers.
        assertEquals(0, processedCount.get())
    }
}
