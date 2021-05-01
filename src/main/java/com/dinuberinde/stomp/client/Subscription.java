package com.dinuberinde.stomp.client;

import com.dinuberinde.stomp.client.internal.ResultHandler;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Subscription to a topic with its result handler.
 */
public class Subscription {
    private final static Logger LOGGER = Logger.getLogger(Subscription.class.getName());

    private final String topic;
    private final String subscriptionId;
    private final ResultHandler<?> resultHandler;
    private final Object LOCK = new Object();
    private boolean isSubscribed = false;

    public Subscription(String topic, String subscriptionId, ResultHandler<?> resultHandler) {
        this.topic = topic;
        this.subscriptionId = subscriptionId;
        this.resultHandler = resultHandler;
    }

    /**
     * Emits that the subscription is completed.
     */
    public void emitSubscription() {
        synchronized(LOCK) {
            LOCK.notify();
        }
    }

    /**
     * Awaits if necessary for the subscription to complete.
     */
    public void awaitSubscription() {
        synchronized(LOCK) {

            if (!isSubscribed)
                try {
                    LOCK.wait();
                    isSubscribed = true;
                }
                catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "Interrupted while waiting for subscription", e);
                }
        }
    }

    public ResultHandler<?> getResultHandler() {
        return resultHandler;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getTopic() {
        return topic;
    }
}
