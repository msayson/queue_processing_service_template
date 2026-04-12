package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.sqs.SqsClient
import com.template.queue.config.Configuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Queue Processing Service starting..." }

    try {
        val config = Configuration.fromEnvironment()
        logger.info { "Configuration loaded: queueUrl=${config.queueUrl}, region=${config.awsRegion}" }

        runBlocking {
            SqsClient { region = config.awsRegion }.use { sqsClient ->
                CloudWatchClient { region = config.awsRegion }.use { cloudWatchClient ->
                    val metricsPublisher = MetricsPublisher(cloudWatchClient)
                    val processor = TemplateMessageProcessor()
                    val poller = QueuePoller(
                        sqsClient = sqsClient,
                        queueUrl = config.queueUrl,
                        processor = processor,
                        metricsPublisher = metricsPublisher,
                        maxMessages = config.maxMessages,
                        waitTimeSeconds = config.waitTimeSeconds
                    )

                    Runtime.getRuntime().addShutdownHook(Thread {
                        logger.info { "Shutdown signal received" }
                        poller.stop()
                    })

                    poller.start()
                }
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "Fatal error in main" }
        System.exit(1)
    }
}
