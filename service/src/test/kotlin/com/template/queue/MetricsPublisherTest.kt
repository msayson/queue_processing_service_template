package com.template.queue

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MetricsPublisherTest {

    @Test
    fun `publishMetric handles valid metric`() = runTest {
        val publisher = MetricsPublisher()

        publisher.publishMetric("TestMetric", 1.0)
    }

    @Test
    fun `publishMetric handles custom unit`() = runTest {
        val publisher = MetricsPublisher()

        publisher.publishMetric("TestMetric", 100.0, "Milliseconds")
    }

    @Test
    fun `publishMetric handles zero value`() = runTest {
        val publisher = MetricsPublisher()

        publisher.publishMetric("TestMetric", 0.0)
    }
}
