package com.template.queue

import aws.sdk.kotlin.services.sqs.SqsClient
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Manages a pool of [numWorkers] concurrent SQS polling coroutines.
 *
 * Each worker owns its own [AtomicBoolean] running flag so that shutdown can be
 * signalled between poll-and-process iterations without interrupting a message
 * that is currently being processed.
 *
 * Lifecycle:
 * 1. [start] — launches all worker coroutines and returns immediately.
 * 2. [stop] — signals workers to stop and blocks the calling thread for up to
 *    [gracePeriodSeconds] while they finish. The caller is responsible for any
 *    post-shutdown cleanup (flushing metrics, closing the log manager, etc.).
 */
class WorkerManager(
    private val numWorkers: Int,
    private val gracePeriodSeconds: Int,
    private val sqsClient: SqsClient,
    private val queueUrl: String,
    private val processor: MessageProcessor,
    private val metricsPublisher: MetricsPublisher,
    private val maxMessages: Int = 10,
    private val waitTimeSeconds: Int = 20
) {
    // Each element is (running flag, coroutine Job) for one worker.
    private val workers: MutableList<Pair<AtomicBoolean, Job>> = mutableListOf()

    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Launches [numWorkers] polling coroutines. Returns immediately; the
     * coroutines run in the background on [Dispatchers.IO].
     */
    fun start() {
        logger.debug { "Starting $numWorkers worker(s)" }
        repeat(numWorkers) { index ->
            val running = AtomicBoolean(true)
            val poller = QueuePoller(
                sqsClient = sqsClient,
                queueUrl = queueUrl,
                processor = processor,
                metricsPublisher = metricsPublisher,
                maxMessages = maxMessages,
                waitTimeSeconds = waitTimeSeconds,
                running = running
            )
            val job = scope.launch {
                poller.start()
            }
            workers.add(running to job)
        }
    }

    /**
     * Signals workers to stop after their current iteration, then waits up to
     * [gracePeriodSeconds] for them to finish.
     */
    fun stop() {
        workers.forEach { (running, _) -> running.set(false) }

        logger.info { "Waiting up to ${gracePeriodSeconds}s for worker(s) to finish" }
        val deadlineMs = System.currentTimeMillis() + gracePeriodSeconds * 1_000L
        for ((workerIndex, runningFlagAndJob) in workers.withIndex()) {
            val (_, job) = runningFlagAndJob
            val remainingMs = deadlineMs - System.currentTimeMillis()
            if (remainingMs <= 0) {
                logger.warn { "Grace period expired; worker $workerIndex may still be processing" }
                break
            }
            // Job.join() is a suspend function; we need a blocking equivalent here
            // because stop() is called from the JVM shutdown hook (a plain Thread).
            val thread = Thread { runBlocking { job.join() } }
            thread.start()
            thread.join(remainingMs)
            if (thread.isAlive) {
                logger.warn { "Worker $workerIndex did not finish within the grace period" }
            }
        }
    }
}
