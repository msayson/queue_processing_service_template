package com.template.queue.config

data class Configuration(
    val queueUrl: String,
    val awsRegion: String,
    val maxMessages: Int = 10,
    val waitTimeSeconds: Int = 20,
    val visibilityTimeout: Int = 300
) {
    companion object {
        fun fromEnvironment(): Configuration {
            val queueUrl = System.getenv("QUEUE_URL")
                ?: throw IllegalStateException("QUEUE_URL environment variable is required")
            val awsRegion = System.getenv("AWS_REGION")
                ?: throw IllegalStateException("AWS_REGION environment variable is required")
            val maxMessages = System.getenv("MAX_MESSAGES")?.toIntOrNull() ?: 10
            val waitTimeSeconds = System.getenv("WAIT_TIME_SECONDS")?.toIntOrNull() ?: 20
            val visibilityTimeout = System.getenv("VISIBILITY_TIMEOUT")?.toIntOrNull() ?: 300

            return Configuration(
                queueUrl = queueUrl,
                awsRegion = awsRegion,
                maxMessages = maxMessages,
                waitTimeSeconds = waitTimeSeconds,
                visibilityTimeout = visibilityTimeout
            )
        }
    }
}
