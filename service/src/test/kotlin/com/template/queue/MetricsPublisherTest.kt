package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import aws.smithy.kotlin.runtime.ServiceErrorMetadata
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.HttpStatusCode
import aws.smithy.kotlin.runtime.http.response.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsPublisherTest {

    companion object {
        private const val TEST_NAMESPACE = "TestNamespace"
    }

    @Test
    fun `successful call queues failure zero and latency with a shared timestamp`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        publisher.measureCall { "result" }
        publisher.flush()

        val requests = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(requests.capture())
        val request = requests.firstValue
        val data = request.metricData.orEmpty()

        assertEquals(TEST_NAMESPACE, request.namespace)
        assertEquals(
            listOf(MetricsPublisher.FAILURE_METRIC_NAME, MetricsPublisher.LATENCY_METRIC_NAME),
            data.map { it.metricName }
        )
        assertEquals(0.0, data[0].value)
        assertTrue(data[1].value!! >= 0.0)
        assertEquals(StandardUnit.Count, data[0].unit)
        assertEquals(StandardUnit.Milliseconds, data[1].unit)
        assertEquals(data[0].timestamp, data[1].timestamp)
        assertNull(data[0].statisticValues)
        assertNull(data[1].statisticValues)
    }

    @Test
    fun `failed call queues failure one without latency and rethrows`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        assertFailsWith<IllegalStateException> {
            publisher.measureCall<Unit> { throw IllegalStateException("processing failed") }
        }
        publisher.flush()

        val requests = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(requests.capture())
        val data = requests.firstValue.metricData.orEmpty()
        assertEquals(1, data.size)
        assertEquals(MetricsPublisher.FAILURE_METRIC_NAME, data.single().metricName)
        assertEquals(1.0, data.single().value)
        assertEquals(StandardUnit.Count, data.single().unit)
    }

    @Test
    fun `cancellation emits no metrics and is rethrown`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        assertFailsWith<CancellationException> {
            publisher.measureCall<Unit> { throw CancellationException("cancelled") }
        }
        publisher.flush()

        verify(mockClient, never()).putMetricData(any())
    }

    @Test
    fun `bad input 4xx errors emit no metrics`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val clientError = serviceException(HttpStatusCode.BadRequest, "ValidationException")

        assertFailsWith<ServiceException> {
            publisher.measureCall<Unit> { throw clientError }
        }
        publisher.flush()

        verify(mockClient, never()).putMetricData(any())
    }

    @Test
    fun `throttling and access denied 4xx errors queue failure one`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)
        val errors = listOf(
            serviceException(HttpStatusCode.BadRequest, "ThrottlingException"),
            serviceException(HttpStatusCode.Forbidden, "AccessDeniedException"),
        )

        errors.forEach { error ->
            assertFailsWith<ServiceException> {
                publisher.measureCall<Unit> { throw error }
            }
        }
        publisher.flush()

        val requests = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient).putMetricData(requests.capture())
        val data = requests.firstValue.metricData.orEmpty()
        assertEquals(2, data.size)
        assertTrue(data.all { it.metricName == MetricsPublisher.FAILURE_METRIC_NAME && it.value == 1.0 })
    }

    @Test
    fun `flush gives up when CloudWatch hangs`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        whenever(mockClient.putMetricData(any())).doSuspendableAnswer { awaitCancellation() }
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE, flushTimeoutMillis = 1_000)

        publisher.measureCall { "ok" }
        publisher.flush()

        verify(mockClient, times(1)).putMetricData(any())
    }

    private fun serviceException(status: HttpStatusCode, errorCode: String): ServiceException {
        val metadata = mock<ServiceErrorMetadata>()
        val exception = mock<ServiceException>()
        whenever(exception.sdkErrorMetadata).thenReturn(metadata)
        whenever(metadata.protocolResponse).thenReturn(HttpResponse(status))
        whenever(metadata.errorCode).thenReturn(errorCode)
        return exception
    }

    @Test
    fun `CloudWatch failure discards batch without retry`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        whenever(mockClient.putMetricData(any())).thenThrow(IllegalStateException("CloudWatch unavailable"))
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        publisher.measureCall { Unit }
        publisher.flush()

        verify(mockClient, times(1)).putMetricData(any())
    }

    @Test
    fun `flush batches observations into requests of at most one thousand`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        repeat(501) {
            publisher.measureCall { Unit }
        }
        publisher.flush()

        val requests = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient, times(2)).putMetricData(requests.capture())
        assertEquals(listOf(1_000, 2), requests.allValues.map { it.metricData.orEmpty().size })
        assertTrue(requests.allValues.all { it.metricData.orEmpty().size <= 1_000 })
    }

    @Test
    fun `full buffer drops excess observations without blocking`() = runTest {
        val mockClient = mock<CloudWatchClient>()
        val publisher = MetricsPublisher(mockClient, TEST_NAMESPACE)

        repeat(25_001) {
            publisher.measureCall { Unit }
        }
        publisher.flush()

        val requests = argumentCaptor<PutMetricDataRequest>()
        verify(mockClient, times(50)).putMetricData(requests.capture())
        assertEquals(50_000, requests.allValues.sumOf { it.metricData.orEmpty().size })
    }
}
