package com.template.queue.localinteg

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import com.template.queue.MessageProcessor
import com.template.queue.MetricsPublisher
import com.template.queue.TemplateMessageProcessor
import com.template.queue.WorkerManager
import com.template.queue.localinteg.testutil.SqsLocalStack
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local integration tests for queue processing.  All tests share a single
 * LocalStack container to avoid paying the ~40s Docker startup cost more than
 * once per test run.
 *
 * Run with: ./gradlew localIntegTest -DrunLocalIntegTests=true
 */
@Tag("localIntegTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueProcessingIntegTest {

    private val sqsLocalStack = SqsLocalStack()

    // Reused across all tests; each test creates its own queue so tests are isolated.
    private lateinit var metricsPublisher: MetricsPublisher

    @BeforeAll
    fun setUp() = runBlocking {
        sqsLocalStack.start()
        sqsLocalStack.waitForSqs()
        metricsPublisher = MetricsPublisher(mock<CloudWatchClient>())
    }

    @AfterAll
    fun tearDown() {
        runBlocking { metricsPublisher.flush() }
        sqsLocalStack.stop()
    }

    // -------------------------------------------------------------------------
    // Test 1: multiple workers drain a queue, each message processed exactly once
    //
    // Validates the core multi-worker correctness invariant: all messages are
    // processed, none are dropped, and none are processed more than once.
    // Also covers the single-worker baseline (the queue draining from any worker
    // exercises the same QueuePoller → process → deleteMessage path).
    // -------------------------------------------------------------------------
    @Test
    fun `multiple workers drain queue and each message is processed exactly once`() = runBlocking {
        val messageCount = 10
        val queueUrl = sqsLocalStack.createQueue(
            "multi-worker-drain-test",
            visibilityTimeoutSeconds = 30
        )

        val processedIds = ConcurrentHashMap.newKeySet<String>()
        val duplicateCount = AtomicInteger(0)

        val trackingProcessor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {
                val isNew = processedIds.add(messageId)
                if (!isNew) duplicateCount.incrementAndGet()
            }
        }

        val manager = WorkerManager(
            numWorkers = 4,
            gracePeriodSeconds = 15,
            sqsClient = sqsLocalStack.sqsClient,
            queueUrl = queueUrl,
            processor = trackingProcessor,
            metricsPublisher = metricsPublisher,
            waitTimeSeconds = 1
        )

        sqsLocalStack.sendMessages(queueUrl, messageCount)

        manager.start()
        try {
            waitForQueueEmpty(queueUrl)
        } finally {
            manager.stop()
        }

        assertEquals(messageCount, processedIds.size,
            "Expected $messageCount unique messages processed, got ${processedIds.size}")
        assertEquals(0, duplicateCount.get(),
            "Expected 0 duplicate processings, got ${duplicateCount.get()}")
        assertEquals(0, sqsLocalStack.getTotalMessageCount(queueUrl),
            "Expected queue to be fully drained (visible + in-flight = 0)")
    }

    // -------------------------------------------------------------------------
    // Test 2: graceful shutdown does not abandon a message mid-processing
    //
    // A slow processor holds a message. stop() is called while the message is
    // in-flight and must block until the worker completes the current iteration
    // (finishes processing and deletes the message) before returning.
    // -------------------------------------------------------------------------
    @Test
    fun `shutdown during processing completes the in-flight message before stopping`() = runBlocking {
        val queueUrl = sqsLocalStack.createQueue(
            "shutdown-in-flight-test",
            visibilityTimeoutSeconds = 30
        )

        val processingStarted = CountDownLatch(1)
        val processingCompleted = AtomicInteger(0)

        val slowProcessor = object : MessageProcessor {
            override suspend fun process(messageBody: String, messageId: String) {
                processingStarted.countDown()
                delay(500)
                processingCompleted.incrementAndGet()
            }
        }

        val manager = WorkerManager(
            numWorkers = 1,
            gracePeriodSeconds = 10,
            sqsClient = sqsLocalStack.sqsClient,
            queueUrl = queueUrl,
            processor = slowProcessor,
            metricsPublisher = metricsPublisher,
            waitTimeSeconds = 1
        )

        sqsLocalStack.sendMessages(queueUrl, 1)

        manager.start()

        assertTrue(
            processingStarted.await(10, TimeUnit.SECONDS),
            "Worker did not start processing within timeout"
        )
        manager.stop()

        assertEquals(1, processingCompleted.get(),
            "Expected in-flight message to be fully processed before shutdown completed")
        assertEquals(0, sqsLocalStack.getTotalMessageCount(queueUrl),
            "Expected queue to be empty: message should have been deleted before worker exited")
    }

    // -------------------------------------------------------------------------
    // Test 3: stop() returns promptly when workers are in a long poll
    //
    // With an empty queue, workers block inside receiveMessage for waitTimeSeconds.
    // The AtomicBoolean flag is only checked after that call returns, so
    // stop() must tolerate waiting for the in-progress poll to finish.
    // Verifies it completes in roughly waitTimeSeconds, not the full grace period.
    // -------------------------------------------------------------------------
    @Test
    fun `stop returns within grace period when workers are in a long poll`() = runBlocking {
        val queueUrl = sqsLocalStack.createQueue("long-poll-shutdown-test")

        val waitTimeSeconds = 1
        val gracePeriodSeconds = 15

        val manager = WorkerManager(
            numWorkers = 3,
            gracePeriodSeconds = gracePeriodSeconds,
            sqsClient = sqsLocalStack.sqsClient,
            queueUrl = queueUrl,
            processor = TemplateMessageProcessor(),
            metricsPublisher = metricsPublisher,
            waitTimeSeconds = waitTimeSeconds
        )

        manager.start()
        delay(100)  // let workers enter their first long poll

        val shutdownStartMs = System.currentTimeMillis()
        manager.stop()
        val elapsedMs = System.currentTimeMillis() - shutdownStartMs

        // Must complete within poll duration + a reasonable margin, not the full grace period.
        val maxExpectedMs = waitTimeSeconds * 1_000L + 2_000L
        assertTrue(elapsedMs < maxExpectedMs,
            "stop took ${elapsedMs}ms; expected < ${maxExpectedMs}ms (${waitTimeSeconds}s poll + 2s margin)")
        assertTrue(elapsedMs < gracePeriodSeconds * 1_000L,
            "stop must complete well within the grace period (${gracePeriodSeconds}s), took ${elapsedMs}ms")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private suspend fun waitForQueueEmpty(queueUrl: String, maxAttempts: Int = 20) {
        repeat(maxAttempts) {
            if (sqsLocalStack.getApproximateMessageCount(queueUrl) == 0) return
            delay(500)
        }
        sqsLocalStack.printLogs()
        error("Queue did not drain within ${maxAttempts / 2} seconds")
    }
}
