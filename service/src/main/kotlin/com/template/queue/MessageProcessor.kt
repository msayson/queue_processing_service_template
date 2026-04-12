package com.template.queue

interface MessageProcessor {
    suspend fun process(messageBody: String, messageId: String)
}
