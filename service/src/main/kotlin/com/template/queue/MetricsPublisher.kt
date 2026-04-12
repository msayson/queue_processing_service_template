package com.template.queue

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

class MetricsPublisher {
    suspend fun publishMetric(metricName: String, value: Double, unit: String = "Count") {
        // AWS SDK integration will be added in next commit
        logger.debug { "Publishing metric: $metricName=$value $unit" }
    }
}
