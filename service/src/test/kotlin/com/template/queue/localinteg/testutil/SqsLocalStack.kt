package com.template.queue.localinteg.testutil

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.CreateQueueRequest
import aws.sdk.kotlin.services.sqs.model.GetQueueAttributesRequest
import aws.sdk.kotlin.services.sqs.model.ListQueuesRequest
import aws.sdk.kotlin.services.sqs.model.QueueAttributeName
import aws.smithy.kotlin.runtime.net.url.Url
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName

private val logger = KotlinLogging.logger {}

class SqsLocalStack {
    private val container = LocalStackContainer(DockerImageName.parse("localstack/localstack:3"))
        .withServices(LocalStackContainer.Service.SQS)

    lateinit var sqsClient: SqsClient
        private set

    fun start() {
        logger.info { "Starting LocalStack SQS container" }
        container.start()
        sqsClient = SqsClient {
            region = "us-east-1"
            endpointUrl = Url.parse(container.getEndpointOverride(LocalStackContainer.Service.SQS).toString())
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = "test"
                secretAccessKey = "test"
            }
        }
    }

    fun stop() {
        logger.info { "Stopping LocalStack SQS container" }
        if (::sqsClient.isInitialized) sqsClient.close()
        container.stop()
    }

    /**
     * Waits for SQS to become available by periodically querying ListQueues.
     * The SQS service can take a few seconds to become available after the container starts.
     */
    suspend fun waitForSqs(maxAttempts: Int = 10, retryDelayMs: Long = 3_000) {
        logger.info { "Waiting for SQS to become available..." }
        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                sqsClient.listQueues(ListQueuesRequest {})
                logger.info { "SQS is ready" }
                return
            } catch (e: Exception) {
                lastException = e
                logger.warn { "SQS not yet available (attempt ${attempt + 1}/$maxAttempts): ${e.message}" }
                if (attempt < maxAttempts - 1) delay(retryDelayMs)
            }
        }
        error("SQS did not become available after $maxAttempts attempts. Last error: ${lastException?.message}")
    }

    suspend fun createQueue(name: String, visibilityTimeoutSeconds: Int = 30): String {
        val response = sqsClient.createQueue(CreateQueueRequest {
            queueName = name
            attributes = mapOf(QueueAttributeName.VisibilityTimeout to visibilityTimeoutSeconds.toString())
        })
        return checkNotNull(response.queueUrl) { "No queue URL returned for queue: $name" }
    }

    suspend fun getApproximateMessageCount(queueUrl: String): Int {
        val response = sqsClient.getQueueAttributes(GetQueueAttributesRequest {
            this.queueUrl = queueUrl
            attributeNames = listOf(QueueAttributeName.ApproximateNumberOfMessages)
        })
        return response.attributes
            ?.get(QueueAttributeName.ApproximateNumberOfMessages)
            ?.toInt()
            ?: 0
    }

    fun printLogs() {
        logger.info { "LocalStack container logs:\n${container.logs}" }
    }
}
