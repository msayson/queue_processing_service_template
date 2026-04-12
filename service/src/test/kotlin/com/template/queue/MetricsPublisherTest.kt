package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import kotlin.test.assertEquals

class MetricsPublisherTest {

    companion object {
        private const val TEST_NAMESPACE = "TestNamespace"
        private const val TEST_METRIC_NAME = "TestMetric"
    }

    @Test
    fun `publishMetric sends correct CloudWatch request`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val metricValue = 1.0

        publisher.publishMetric(TEST_METRIC_NAME, metricValue)

        val captor = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(captor.capture())
        
        val request = captor.firstValue
        val metricData = request.metricData?.first()
        
        assertEquals(TEST_NAMESPACE, request.namespace)
        assertEquals(1, request.metricData?.size)
        assertEquals(TEST_METRIC_NAME, metricData?.metricName)
        assertEquals(metricValue, metricData?.value)
        assertEquals(StandardUnit.Count, metricData?.unit)
    }

    @Test
    fun `publishMetric handles custom unit`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val metricValue = 100.0
        val metricUnit = "Milliseconds"

        publisher.publishMetric(TEST_METRIC_NAME, metricValue, metricUnit)

        val captor = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(captor.capture())
        
        val metricData = captor.firstValue.metricData?.first()
        
        assertEquals(metricValue, metricData?.value)
        assertEquals(StandardUnit.Milliseconds, metricData?.unit)
    }

    @Test
    fun `publishMetric handles zero value`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val metricValue = 0.0

        publisher.publishMetric(TEST_METRIC_NAME, metricValue)

        val captor = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(captor.capture())
        
        val metricData = captor.firstValue.metricData?.first()
        
        assertEquals(metricValue, metricData?.value)
    }
}
