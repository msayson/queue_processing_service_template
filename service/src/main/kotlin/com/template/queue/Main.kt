package com.template.queue

import com.template.queue.config.Configuration
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "Queue Processing Service starting..." }

    try {
        val config = Configuration.fromEnvironment()
        logger.info { "Configuration loaded: queueUrl=${config.queueUrl}, region=${config.awsRegion}" }

        val processor = TemplateMessageProcessor()
        val poller = QueuePoller(processor)

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info { "Shutdown signal received" }
            poller.stop()
        })

        runBlocking {
            poller.start()
        }
    } catch (e: Exception) {
        logger.error(e) { "Fatal error in main" }
        System.exit(1)
    }
}
