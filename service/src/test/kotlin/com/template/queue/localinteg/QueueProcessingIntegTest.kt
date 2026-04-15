package com.template.queue.localinteg

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import com.template.queue.MetricsPublisher
import com.template.queue.QueuePoller
import com.template.queue.TemplateMessageProcessor
import com.template.queue.localinteg.testutil.SqsLocalStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.mock

@Tag("localIntegTest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueProcessingIntegTest {
    private val sqsLocalStack = SqsLocalStack()

    @BeforeAll
    fun setUp() = runBlocking {
        sqsLocalStack.start()
        sqsLocalStack.waitForSqs()
    }

    @AfterAll
    fun tearDown() {
        sqsLocalStack.stop()
    }

    @Test
    fun `message sent to queue is processed and deleted`() = runBlocking {
        val queueUrl = sqsLocalStack.createQueue("integ-test-queue")

        // MetricsPublisher.publishMetric swallows all exceptions internally, so a mock
        // CloudWatchClient with default (null-returning) behaviour is safe here.
        val metricsPublisher = MetricsPublisher(mock<CloudWatchClient>())

        val poller = QueuePoller(
            sqsClient = sqsLocalStack.sqsClient,
            queueUrl = queueUrl,
            processor = TemplateMessageProcessor(),
            metricsPublisher = metricsPublisher,
            waitTimeSeconds = 1,
        )

        sqsLocalStack.sqsClient.sendMessage(SendMessageRequest {
            this.queueUrl = queueUrl
            messageBody = """{"event": "integration-test"}"""
        })

        // Run the poller on a background thread so this coroutine can continue
        val pollerJob = launch(Dispatchers.Default) { poller.start() }

        try {
            waitForQueueEmpty(queueUrl)
        } finally {
            poller.stop()
            pollerJob.join()
        }
    }

    private suspend fun waitForQueueEmpty(queueUrl: String, maxAttempts: Int = 10) {
        repeat(maxAttempts) {
            if (sqsLocalStack.getApproximateMessageCount(queueUrl) == 0) return
            delay(1_000)
        }
        sqsLocalStack.printLogs()
        error("Queue did not drain within $maxAttempts seconds")
    }
}
