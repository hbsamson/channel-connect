package com.svi.channelconnect.messenger.service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.QueueUnavailableException;

@Component
public class MessengerWorkQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(MessengerWorkQueue.class);
    private final MessengerWorkflow workflow;
    private final ThreadPoolExecutor executor;
    private final Duration shutdownTimeout;

    public MessengerWorkQueue(MessengerWorkflow workflow, MessengerProperties properties,
                              @Value("${messenger.shutdown-timeout:10s}") Duration shutdownTimeout) {
        if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
            throw new IllegalArgumentException("messenger.shutdown-timeout must be positive");
        }
        this.workflow = workflow;
        this.shutdownTimeout = shutdownTimeout;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.queueCapacity()), task -> {
                    Thread thread = new Thread(task, "messenger-worker");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public synchronized void accept(List<IncomingMessageDTO> messages) {
        if (messages.size() > executor.getQueue().remainingCapacity() || executor.isShutdown()) {
            throw new QueueUnavailableException();
        }
        for (IncomingMessageDTO message : messages) {
            executor.execute(() -> workflow.process(message));
        }
    }

    @PreDestroy
    public void shutdown() {
        synchronized (this) {
            executor.shutdown();
        }
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Messenger shutdown timed out; queued messages discarded={}", executor.shutdownNow().size());
            }
        } catch (InterruptedException exception) {
            LOGGER.warn("Messenger shutdown interrupted; queued messages discarded={}", executor.shutdownNow().size());
            Thread.currentThread().interrupt();
        }
    }
}
