package com.template.queue

import aws.sdk.kotlin.services.cloudwatch.CloudWatchClient
import aws.sdk.kotlin.services.cloudwatch.model.MetricDatum
import aws.sdk.kotlin.services.cloudwatch.model.PutMetricDataRequest
import aws.sdk.kotlin.services.cloudwatch.model.StandardUnit
import aws.smithy.kotlin.runtime.time.Instant
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class MetricsPublisher(
    private val cloudWatchClient: CloudWatchClient,
    private val namespace: String = "QueueProcessingService"
) {
    suspend fun publishMetric(metricName: String, value: Double, unit: String = "Count") {
        try {
            val metricDatum = MetricDatum {
                this.metricName = metricName
                this.value = value
                this.unit = StandardUnit.fromValue(unit)
                this.timestamp = Instant.now()
            }

            cloudWatchClient.putMetricData(PutMetricDataRequest {
                this.namespace = this@MetricsPublisher.namespace
                this.metricData = listOf(metricDatum)
            })

            logger.debug { "Published metric: $metricName=$value $unit" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to publish metric: $metricName" }
        }
    }
}
