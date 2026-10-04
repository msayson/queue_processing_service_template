package com.template.queue

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class TemplateMessageProcessor : MessageProcessor {
    override suspend fun process(messageBody: String, messageId: String) {
        logger.debug { "Processing message: messageId=$messageId, body=$messageBody" }

        // Template implementation - replace with actual business logic

        logger.debug { "Message processed successfully: messageId=$messageId" }
    }
}
