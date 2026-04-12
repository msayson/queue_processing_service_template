package com.template.queue

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class TemplateMessageProcessor : MessageProcessor {
    override suspend fun process(messageBody: String, messageId: String) {
        logger.info { "Processing message: messageId=$messageId" }
        logger.debug { "Message body: $messageBody" }
        
        // Template implementation - replace with actual business logic
        
        logger.info { "Message processed successfully: messageId=$messageId" }
    }
}
