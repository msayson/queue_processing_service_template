package com.template.queue

import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

class QueuePoller(
    private val sqsClient: SqsClient,
    private val queueUrl: String,
    private val processor: MessageProcessor,
    private val metricsPublisher: MetricsPublisher,
    private val maxMessages: Int = 10,
    private val waitTimeSeconds: Int = 20
) {
    @Volatile
    private var running = true

    suspend fun start() {
        logger.info { "Starting queue poller: queueUrl=$queueUrl" }
        
        while (running) {
            try {
                pollAndProcess()
            } catch (e: Exception) {
                logger.error(e) { "Error in polling loop" }
            }
        }
        
        logger.info { "Queue poller stopped" }
    }

    fun stop() {
        logger.info { "Stopping queue poller" }
        running = false
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

        logger.info { "Received ${messages.size} messages" }

        messages.map { message ->
            async {
                val messageId = message.messageId ?: "unknown"
                val body = message.body ?: ""
                val receiptHandle = message.receiptHandle

                try {
                    processor.process(body, messageId)
                    
                    if (receiptHandle != null) {
                        sqsClient.deleteMessage(DeleteMessageRequest {
                            this.queueUrl = this@QueuePoller.queueUrl
                            this.receiptHandle = receiptHandle
                        })
                        logger.info { "Message processed successfully: messageId=$messageId" }
                        metricsPublisher.publishMetric("MessagesProcessedSuccess", 1.0)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to process message: messageId=$messageId" }
                    metricsPublisher.publishMetric("MessagesProcessedFailure", 1.0)
                }
            }
        }.awaitAll()
    }
}
