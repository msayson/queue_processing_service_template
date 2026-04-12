package com.template.queue

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class QueuePoller(
    private val processor: MessageProcessor
) {
    @Volatile
    private var running = true

    suspend fun start() {
        logger.info { "Starting queue poller" }
        
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

    private suspend fun pollAndProcess() {
        // AWS SDK integration will be added in next commit
        logger.debug { "Polling for messages..." }
    }
}
