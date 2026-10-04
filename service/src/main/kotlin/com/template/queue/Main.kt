package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.sqs.SqsClient
import com.template.queue.config.Configuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.LogManager

private val logger = KotlinLogging.logger {}

fun main() {
    try {
        val config = Configuration.fromEnvironment()
        logger.info {
            "Configuration loaded: queueUrl=${config.queueUrl}, " +
                "region=${config.awsRegion}, " +
                "numWorkerThreads=${config.numWorkerThreads}, " +
                "workerShutdownGracePeriodSeconds=${config.workerShutdownGracePeriodSeconds}"
        }

        runBlocking {
            SqsClient { region = config.awsRegion }.use { sqsClient ->
                CloudWatchClient { region = config.awsRegion }.use { cloudWatchClient ->
                    val metricsPublisher = MetricsPublisher(cloudWatchClient)
                    val processor = TemplateMessageProcessor()

                    val workerManager = WorkerManager(
                        numWorkers = config.numWorkerThreads,
                        gracePeriodSeconds = config.workerShutdownGracePeriodSeconds,
                        sqsClient = sqsClient,
                        queueUrl = config.queueUrl,
                        processor = processor,
                        metricsPublisher = metricsPublisher,
                        maxMessages = config.maxMessages,
                        waitTimeSeconds = config.waitTimeSeconds
                    )

                    registerShutdownHook(workerManager, metricsPublisher)

                    workerManager.start()

                    // Block the main coroutine until the JVM shuts down.
                    Thread.currentThread().join()
                }
            }
        }
    } catch (e: Exception) {
        logger.error(e) { "Fatal error in main" }
        exitProcess(1)
    }
}

private fun registerShutdownHook(
    workerManager: WorkerManager,
    metricsPublisher: MetricsPublisher
) {
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received" }

        // Stop workers after their current iteration and wait for them to finish.
        workerManager.stop()

        // Flush remaining CloudWatch metrics buffered by the publisher.
        try {
            runBlocking {
                metricsPublisher.flush()
            }
        } finally {
            // Shut down the log manager to flush buffered log events.
            LogManager.shutdown()
        }
    })
}
