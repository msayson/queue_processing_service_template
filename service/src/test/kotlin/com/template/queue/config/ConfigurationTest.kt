package com.template.queue.config

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigurationTest {

    @BeforeEach
    fun setUp() {
        System.clearProperty("QUEUE_URL")
        System.clearProperty("AWS_REGION")
        System.clearProperty("MAX_MESSAGES")
        System.clearProperty("WAIT_TIME_SECONDS")
        System.clearProperty("VISIBILITY_TIMEOUT")
    }

    @Test
    fun `fromEnvironment loads configuration from environment variables`() {
        System.setProperty("QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue")
        System.setProperty("AWS_REGION", "us-east-1")

        val config = Configuration.fromEnvironment()

        assertEquals("https://sqs.us-east-1.amazonaws.com/123456789012/test-queue", config.queueUrl)
        assertEquals("us-east-1", config.awsRegion)
        assertEquals(10, config.maxMessages)
        assertEquals(20, config.waitTimeSeconds)
        assertEquals(300, config.visibilityTimeout)
    }

    @Test
    fun `fromEnvironment uses custom values when provided`() {
        System.setProperty("QUEUE_URL", "https://sqs.us-west-2.amazonaws.com/123456789012/test-queue")
        System.setProperty("AWS_REGION", "us-west-2")
        System.setProperty("MAX_MESSAGES", "5")
        System.setProperty("WAIT_TIME_SECONDS", "10")
        System.setProperty("VISIBILITY_TIMEOUT", "600")

        val config = Configuration.fromEnvironment()

        assertEquals(5, config.maxMessages)
        assertEquals(10, config.waitTimeSeconds)
        assertEquals(600, config.visibilityTimeout)
    }

    @Test
    fun `fromEnvironment throws when QUEUE_URL is missing`() {
        System.clearProperty("QUEUE_URL")
        System.setProperty("AWS_REGION", "us-east-1")

        assertFailsWith<IllegalStateException> {
            Configuration.fromEnvironment()
        }
    }

    @Test
    fun `fromEnvironment throws when AWS_REGION is missing`() {
        System.setProperty("QUEUE_URL", "https://sqs.us-east-1.amazonaws.com/123456789012/test-queue")
        System.clearProperty("AWS_REGION")

        assertFailsWith<IllegalStateException> {
            Configuration.fromEnvironment()
        }
    }
}
