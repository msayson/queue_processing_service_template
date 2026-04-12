package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import io.mockk.CapturingSlot
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MetricsPublisherTest {

    companion object {
        private const val TEST_NAMESPACE = "TestNamespace"
        private const val TEST_METRIC_NAME = "TestMetric"
    }

    @Test
    fun `publishMetric sends correct CloudWatch request`() = runTest {
        val mockClient = mockk<CloudWatchClient>(relaxed = true)
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val requestSlot = slot<PutMetricDataRequest>()
        val metricValue = 1.0

        publisher.publishMetric(TEST_METRIC_NAME, metricValue)

        verifyMetricPublished(mockClient, requestSlot, TEST_NAMESPACE, TEST_METRIC_NAME, metricValue, StandardUnit.Count)
    }

    @Test
    fun `publishMetric handles custom unit`() = runTest {
        val mockClient = mockk<CloudWatchClient>(relaxed = true)
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val requestSlot = slot<PutMetricDataRequest>()
        val metricValue = 100.0
        val metricUnit = StandardUnit.Milliseconds

        publisher.publishMetric(TEST_METRIC_NAME, metricValue, "Milliseconds")

        verifyMetricPublished(mockClient, requestSlot, TEST_NAMESPACE, TEST_METRIC_NAME, metricValue, metricUnit)
    }

    @Test
    fun `publishMetric handles zero value`() = runTest {
        val mockClient = mockk<CloudWatchClient>(relaxed = true)
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val requestSlot = slot<PutMetricDataRequest>()
        val metricValue = 0.0

        publisher.publishMetric(TEST_METRIC_NAME, metricValue)

        verifyMetricPublished(mockClient, requestSlot, TEST_NAMESPACE, TEST_METRIC_NAME, metricValue, StandardUnit.Count)
    }

    private fun verifyMetricPublished(
        mockClient: CloudWatchClient,
        requestSlot: CapturingSlot<PutMetricDataRequest>,
        namespace: String,
        metricName: String,
        value: Double,
        unit: StandardUnit
    ) {
        coVerify { mockClient.putMetricData(capture(requestSlot)) }
        val request = requestSlot.captured
        val metricData = request.metricData?.first()

        assertEquals(namespace, request.namespace)
        assertEquals(1, request.metricData?.size)
        assertEquals(metricName, metricData?.metricName)
        assertEquals(value, metricData?.value)
        assertEquals(unit, metricData?.unit)
    }
}
