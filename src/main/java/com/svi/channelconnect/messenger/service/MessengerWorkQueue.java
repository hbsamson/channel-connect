package com.svi.channelconnect.messenger.service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

import com.svi.channelconnect.messenger.config.MessengerProperties;
import com.svi.channelconnect.messenger.dto.IncomingMessageDTO;
import com.svi.channelconnect.messenger.exception.QueueUnavailableException;

@Component
public class MessengerWorkQueue {
    private final MessengerWorkflow workflow;
    private final ThreadPoolExecutor executor;

    public MessengerWorkQueue(MessengerWorkflow workflow, MessengerProperties properties) {
        this.workflow = workflow;
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
        executor.shutdown();
    }
}
