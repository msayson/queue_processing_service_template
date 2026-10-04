package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import aws.smithy.kotlin.runtime.ServiceException
import aws.smithy.kotlin.runtime.http.response.statusCode
import aws.smithy.kotlin.runtime.time.Instant
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private val logger = KotlinLogging.logger {}

class MetricsPublisher(
    private val cloudWatchClient: CloudWatchClient,
    private val namespace: String = "QueueProcessingService",
    bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
    private val flushTimeoutMillis: Long = FLUSH_TIMEOUT_SECONDS * 1_000
) {
    private val buffer = ArrayBlockingQueue<MetricDatum>(bufferCapacity)
    private val droppedDatumCount = AtomicLong()
    private val acceptingMetrics = AtomicBoolean(true)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "cloudwatch-metrics-drain").apply { isDaemon = true }
    }
    private val scheduledDrain: ScheduledFuture<*> = scheduler.scheduleWithFixedDelay(
        {
            // Any escaping Throwable would silently cancel all future drains.
            try {
                runBlocking { drainAll() }
            } catch (t: Throwable) {
                logger.error(t) { "CloudWatch metrics drain failed; retrying next interval" }
            }
        },
        DRAIN_DELAY_SECONDS,
        DRAIN_DELAY_SECONDS,
        TimeUnit.SECONDS
    )

    /**
     * Measures a processor call and queues its per-call observations without
     * blocking the caller on CloudWatch network requests.
     */
    suspend fun <T> measureCall(call: suspend () -> T): T {
        val startNanos = System.nanoTime()
        try {
            val result = call()
            val timestamp = Instant.now()
            bufferMetrics(
                metricDatum(FAILURE_METRIC_NAME, 0.0, StandardUnit.Count, timestamp),
                metricDatum(
                    LATENCY_METRIC_NAME,
                    (System.nanoTime() - startNanos) / NANOS_PER_MILLISECOND,
                    StandardUnit.Milliseconds,
                    timestamp
                )
            )
            return result
        } catch (e: CancellationException) {
            // Cancelled calls neither succeeded nor failed; record nothing.
            throw e
        } catch (e: Exception) {
            if (!e.isBadInputError()) {
                bufferMetrics(metricDatum(FAILURE_METRIC_NAME, 1.0, StandardUnit.Count, Instant.now()))
            }
            throw e
        }
    }

    /**
     * Stops scheduled draining and sends the remaining buffered datums, giving up
     * after [flushTimeoutMillis] so the shutdown hook can still flush logs.
     */
    suspend fun shutdownAndFlush() {
        acceptingMetrics.set(false)
        scheduledDrain.cancel(false)
        scheduler.shutdown()
        val deadlineMillis = System.currentTimeMillis() + flushTimeoutMillis
        try {
            if (!scheduler.awaitTermination(flushTimeoutMillis, TimeUnit.MILLISECONDS)) {
                logger.warn { "In-flight CloudWatch metrics drain did not finish within ${flushTimeoutMillis}ms" }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        }
        val remainingMillis = deadlineMillis - System.currentTimeMillis()
        val drained = remainingMillis > 0 && withTimeoutOrNull(remainingMillis) { drainAll() } != null
        if (!drained) {
            val lost = buffer.size + droppedDatumCount.getAndSet(0)
            logger.warn { "Gave up flushing CloudWatch metrics after ${flushTimeoutMillis}ms; $lost datum(s) lost" }
        }
    }

    private suspend fun drainAll() {
        while (true) {
            val batch = ArrayList<MetricDatum>(MAX_DATUMS_PER_REQUEST)
            if (buffer.drainTo(batch, MAX_DATUMS_PER_REQUEST) == 0) {
                break
            }

            try {
                cloudWatchClient.putMetricData(PutMetricDataRequest {
                    this.namespace = this@MetricsPublisher.namespace
                    metricData = batch
                })
            } catch (e: CancellationException) {
                // Let shutdownAndFlush()'s timeout stop the drain instead of discarding every batch.
                // The batch is already off the buffer, so count it as dropped.
                droppedDatumCount.addAndGet(batch.size.toLong())
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to publish batch of ${batch.size} CloudWatch metrics; discarding batch" }
            }
        }

        val dropped = droppedDatumCount.getAndSet(0)
        if (dropped == 0L) {
            logger.debug { "Dropped 0 CloudWatch metric datum(s) since the previous drain" }
        } else {
            logger.warn { "Dropped $dropped CloudWatch metric datum(s) because the buffer was full or shutting down" }
        }
    }

    private fun bufferMetrics(vararg datums: MetricDatum) {
        datums.forEach { datum ->
            if (!acceptingMetrics.get() || !buffer.offer(datum)) {
                droppedDatumCount.incrementAndGet()
            }
        }
    }

    private fun metricDatum(
        metricName: String,
        value: Double,
        unit: StandardUnit,
        timestamp: Instant
    ) = MetricDatum {
        this.metricName = metricName
        this.value = value
        this.unit = unit
        this.timestamp = timestamp
    }

    /**
     * True only for 4xx errors caused by bad request input. Other 4xx errors
     * (throttling, access denied, expired credentials) still count as failures.
     */
    private fun Exception.isBadInputError(): Boolean {
        val serviceException = this as? ServiceException ?: return false
        val metadata = serviceException.sdkErrorMetadata
        val statusCode = metadata.protocolResponse.statusCode()?.value
        return statusCode != null && statusCode in 400..499 && metadata.errorCode in BAD_INPUT_ERROR_CODES
    }

    companion object {
        internal const val FAILURE_METRIC_NAME = "Failure"
        internal const val LATENCY_METRIC_NAME = "Latency"
        private val BAD_INPUT_ERROR_CODES = setOf(
            "ValidationException",
            "ValidationError",
            "InvalidParameter",
            "InvalidParameterException",
            "InvalidParameterValue",
            "InvalidParameterValueException",
            "InvalidParameterCombination",
            "MissingParameter",
            "MissingRequiredParameter",
            "InvalidInput",
            "InvalidInputException",
            "InvalidArgument",
            "SerializationException",
            "MalformedQueryString",
        )

        const val DEFAULT_BUFFER_CAPACITY = 100_000
        const val DRAIN_DELAY_SECONDS = 15L
        // Fits within the 30 s left between the 90 s worker grace period and the 120 s stopTimeout.
        const val FLUSH_TIMEOUT_SECONDS = 20L
        const val MAX_DATUMS_PER_REQUEST = 1_000
        const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}
