package com.template.queue

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

class QueuePoller(
    private val sqsClient: SqsClient,
    private val queueUrl: String,
    private val processor: MessageProcessor,
    private val metricsPublisher: MetricsPublisher,
    private val maxMessages: Int = 10,
    private val waitTimeSeconds: Int = 20,
    /**
     * Controls whether this poller keeps running.  The caller flips this to `false`
     * to request a graceful stop after the current poll-and-process iteration.
     */
    val running: AtomicBoolean = AtomicBoolean(true)
) {
    suspend fun start() {
        logger.debug { "Starting queue poller: queueUrl=$queueUrl" }

        while (running.get()) {
            try {
                pollAndProcess()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Error in polling loop" }
            }
        }

        logger.debug { "Queue poller stopped" }
    }

    fun stop() {
        logger.debug { "Stopping queue poller" }
        running.set(false)
    }

    internal suspend fun pollAndProcess() = coroutineScope {
        val response = sqsClient.receiveMessage(ReceiveMessageRequest {
            this.queueUrl = this@QueuePoller.queueUrl
            this.maxNumberOfMessages = maxMessages
            this.waitTimeSeconds = this@QueuePoller.waitTimeSeconds
        })

        val messages = response.messages ?: emptyList()
        if (messages.isEmpty()) {
            logger.debug { "No messages received" }
            return@coroutineScope
        }

        messages.map { message ->
            async {
                val messageId = message.messageId ?: "unknown"
                val body = message.body ?: ""
                val receiptHandle = message.receiptHandle

                try {
                    metricsPublisher.measureCall {
                        processor.process(body, messageId)
                    }

                    if (receiptHandle != null) {
                        sqsClient.deleteMessage(DeleteMessageRequest {
                            this.queueUrl = this@QueuePoller.queueUrl
                            this.receiptHandle = receiptHandle
                        })
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process message: messageId=$messageId" }
                }
            }
        }.awaitAll()
    }
}
