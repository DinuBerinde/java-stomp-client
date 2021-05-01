package com.dinuberinde.stomp.client;

import com.dinuberinde.stomp.client.exceptions.InternalFailureException;
import com.dinuberinde.stomp.client.exceptions.NetworkExceptionResponse;
import com.dinuberinde.stomp.client.internal.*;
import com.dinuberinde.stomp.client.internal.stomp.StompCommand;
import com.dinuberinde.stomp.client.internal.stomp.StompMessageHelper;
import com.neovisionaries.ws.client.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StompClient implements AutoCloseable {
    private final static Logger LOGGER = Logger.getLogger(StompClient.class.getName());

    /**
     * The websockets end-point.
     */
    private final String url;

    /**
     * The unique identifier of this client. This allows more clients to connect to the same server.
     */
    private final String clientKey;

    /**
     * The websockets subscriptions open so far with this client, per topic.
     */
    private final ConcurrentHashMap<String, Subscription> internalSubscriptions;

    /**
     * The websockets queues where the results are published and consumed, per topic.
     */
    private final ConcurrentHashMap<String, BlockingQueue<Object>> queues;

    /**
     * Lock to synchronize the initial connection of the websocket client.
     */
    private final Object CONNECTION_LOCK = new Object();

    /**
     * Boolean to track whether the client is connected.
     */
    private boolean isClientConnected = false;

    /**
     * The websocket instance.
     */
    private WebSocket webSocket;


    /**
     * It construct an instance of the StompClient.
     *
     * @param url the url of the webSocket endpoint, e.g ws://localhost:8080
     */
    public StompClient(String url) {
        this.url = url;
        this.clientKey = generateClientKey();
        this.internalSubscriptions = new ConcurrentHashMap<>();
        this.queues = new ConcurrentHashMap<>();
    }


    /**
     * It opens a webSocket connection and connects to the STOMP endpoint.
     */
    public void connect() {
        connect(null, null, null);
    }

    /**
     * It opens a webSocket connection and connects to the STOMP endpoint.
     *
     * @param onStompConnectionOpened handler for a successful STOMP endpoint connection
     */
    public void connect(Callback onStompConnectionOpened) {
        connect(onStompConnectionOpened, null, null);
    }

    /**
     * It opens a webSocket connection and connects to the STOMP endpoint.
     *
     * @param onStompConnectionOpened handler for a successful STOMP endpoint connection
     * @param onWebSocketFailure      handler for the webSocket connection failure due to an error reading from or writing to the network
     */
    public void connect(Callback onStompConnectionOpened, Callback onWebSocketFailure) {
        connect(onStompConnectionOpened, onWebSocketFailure, null);
    }

    /**
     * It opens a webSocket connection and connects to the STOMP endpoint.
     *
     * @param onStompConnectionOpened handler for a successful STOMP endpoint connection
     * @param onWebSocketFailure      handler for the webSocket connection failure due to an error reading from or writing to the network
     * @param onWebSocketClosed       handler for the webSocket connection when both peers have indicated that no more messages
     *                                will be transmitted and the connection has been successfully released.
     */
    public void connect(Callback onStompConnectionOpened, Callback onWebSocketFailure, Callback onWebSocketClosed) {
        LOGGER.info("[Stomp client] Connecting to " + url);

        try {
            webSocket = new WebSocketFactory()
                    .setConnectionTimeout(30 * 1000)
                    .createSocket(url)
                    .addHeader("uuid", clientKey)
                    .addListener(new WebSocketAdapter() {
                        @Override
                        public void onConnected(WebSocket websocket, Map<String, List<String>> headers) throws Exception {
                            LOGGER.info("[Stomp client] Connected to server");

                            // we open the stomp session
                            websocket.sendText(StompMessageHelper.buildConnectMessage());
                        }

                        @Override
                        public void onDisconnected(WebSocket websocket, WebSocketFrame serverCloseFrame, WebSocketFrame clientCloseFrame, boolean closedByServer) throws Exception {
                            LOGGER.info("[Stomp client] WebSocket session closed");
                            if (onWebSocketClosed != null) {
                                onWebSocketClosed.invoke();
                            }
                        }

                        public void onTextMessage(WebSocket websocket, String messageTxt) {
                            LOGGER.info("[Stomp client] Received message");

                            try {
                                Message message = StompMessageHelper.parseStompMessage(messageTxt);
                                String payload = message.getPayload();
                                StompCommand command = message.getCommand();

                                switch (command) {

                                    case CONNECTED:
                                        LOGGER.info("[Stomp client] Connected to stomp session");
                                        if (onStompConnectionOpened != null) {
                                            onStompConnectionOpened.invoke();
                                        }
                                        emitClientConnected();
                                        break;

                                    case RECEIPT:
                                        String destination = message.getStompHeaders().getDestination();
                                        LOGGER.info("[Stomp client] Subscribed to topic " + destination);

                                        Subscription subscription = internalSubscriptions.get(destination);
                                        if (subscription == null) {
                                            throw new NoSuchElementException("Topic not found");
                                        }

                                        subscription.emitSubscription();
                                        break;

                                    case ERROR:
                                        LOGGER.info("[Stomp client] STOMP Session Error: " + payload);

                                        // clean-up client resources because the server closed the connection
                                        close();
                                        if (onWebSocketFailure != null) {
                                            onWebSocketFailure.invoke();
                                        }
                                        break;

                                    case MESSAGE:
                                        destination = message.getStompHeaders().getDestination();
                                        LOGGER.info("[Stomp client] Received message from topic " + destination);
                                        handleStompDestinationResult(payload, destination);
                                        break;

                                    default:
                                        LOGGER.info("unexpected stomp message " + command);
                                        break;
                                }
                            }
                            catch (Exception e) {
                                LOGGER.info("[Stomp client] Got an exception while handling message");
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onError(WebSocket websocket, WebSocketException cause) throws Exception {
                            LOGGER.info("[Stomp client] WebSocket Session error");
                            cause.printStackTrace();

                            close();
                            if (onWebSocketFailure != null) {
                                onStompConnectionOpened.invoke();
                            }
                        }
                    })
                    .connect();
        }
        catch (Exception e) {
           LOGGER.log(Level.SEVERE, "[Stomp client] got an exception", e);
        }

        awaitClientConnection();
    }

    /**
     * Emits that the client is connected.
     */
    private void emitClientConnected() {
        synchronized(CONNECTION_LOCK) {
            CONNECTION_LOCK.notify();
        }
    }

    /**
     * Awaits if necessary until the websocket client is connected.
     */
    private void awaitClientConnection() {
        synchronized(CONNECTION_LOCK) {
            if (!isClientConnected)
                try {
                    CONNECTION_LOCK.wait();
                    isClientConnected = true;
                }
                catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "[Stomp client] got an unexpected exception", e);
                    throw new InternalFailureException("unexpected exception " + e.getMessage());
                }
        }
    }

    /**
     * It handles a STOMP result message of a destination.
     *
     * @param result      the result
     * @param destination the destination
     */
    private void handleStompDestinationResult(String result, String destination) {
        Subscription subscription = internalSubscriptions.get(destination);
        if (subscription != null) {
            ResultHandler<?> resultHandler = subscription.getResultHandler();

            if (resultHandler.getResultTypeClass() == Void.class || result == null || result.equals("null"))
                resultHandler.deliverError(new ErrorModel("Received a null result", InternalFailureException.class.getName()));
            else
                resultHandler.deliverResult(result);
        }
    }

    /**
     * It sends a payload to the "user" topic by performing an initial subscription
     * and waits for a result. The subscription is recycled.
     *
     * @param <T>        the type of the expected result
     * @param topic      the topic
     * @param resultType the result type
     */
    public <T> T send(String topic, Class<T> resultType) {
        return send(topic, resultType);
    }

    /**
     * It sends a payload to the "user" topic by performing an initial subscription
     * and waits for a result. The subscription is recycled.
     *
     * @param <T>        the type of the expected result
     * @param <P>        the type of the payload
     * @param topic      the topic
     * @param resultType the result type
     * @param payload    the payload
     */
    @SuppressWarnings("unchecked")
    public <T, P> T send(String topic, Class<T> resultType, P payload) throws InterruptedException, NetworkExceptionResponse {
        LOGGER.info("[Stomp client] Subscribing to  " + topic);

        String resultTopic = "/user/" + clientKey + topic;
        Object result;

        BlockingQueue<Object> queue = queues.computeIfAbsent(topic, _key -> new LinkedBlockingQueue<>(1));
        synchronized (queue) {
            subscribe(resultTopic, resultType, queue);

            LOGGER.info("[Stomp client] Sending payload to  " + topic);
            webSocket.sendText(StompMessageHelper.buildSendMessage(topic, payload));
            result = queue.take();
        }

        if (result instanceof Nothing)
            return null;
        else if (result instanceof ErrorModel)
            throw new NetworkExceptionResponse((ErrorModel) result);
        else
            return (T) result;
    }


    /**
     * Subscribes to a topic providing a {@link BiConsumer} handler to handle the result published by the topic.
     *
     * @param topic      the topic destination
     * @param resultType the result type
     * @param handler    handler of the result
     * @param <T>        the result type
     */
    public <T> void subscribeToTopic(String topic, Class<T> resultType, BiConsumer<T, ErrorModel> handler) {
        Subscription subscription = internalSubscriptions.computeIfAbsent(topic, _topic -> {

            ResultHandler<T> resultHandler = new ResultHandler<>(resultType) {
                @Override
                public void deliverResult(String payload) {
                    try {
                        handler.accept(this.toModel(payload), null);
                    }
                    catch (InternalFailureException e) {
                        deliverError(new ErrorModel(e.getMessage() != null ? e.getMessage() : "Got a deserialization error", InternalFailureException.class.getName()));
                    }
                }

                @Override
                public void deliverError(ErrorModel errorModel) {
                    handler.accept(null, errorModel);
                }

                @Override
                public void deliverNothing() {
                    handler.accept(null, null);
                }
            };

            return subscribeInternal(topic, resultHandler);
        });
        subscription.awaitSubscription();
    }

    /**
     * It sends a payload to a previous subscribed topic. The method {@link #subscribeToTopic(String, Class, BiConsumer)}}
     * is used to subscribe to a topic.
     *
     * @param topic   the topic
     * @param payload the payload
     */
    public <T> void sendToTopic(String topic, T payload) {
        LOGGER.info("[Stomp client] Sending to " + topic);
        webSocket.sendText(StompMessageHelper.buildSendMessage(topic, payload));
    }

    /**
     * Subscribes to a topic and register its queue where to deliver the result.
     *
     * @param topic      the topic
     * @param resultType the result type
     * @param queue      the queue
     * @param <T>        the result type
     */
    private <T> void subscribe(String topic, Class<T> resultType, BlockingQueue<Object> queue) {
        Subscription subscription = internalSubscriptions.computeIfAbsent(topic, _topic -> subscribeInternal(topic, new ResultHandler<>(resultType) {
            @Override
            public void deliverResult(String payload) {
                try {
                    deliverInternal(this.toModel(payload));
                }
                catch (Exception e) {
                    deliverError(new ErrorModel(e.getMessage() != null ? e.getMessage() : "Got a deserialization error", InternalFailureException.class.getName()));
                }
            }

            @Override
            public void deliverError(ErrorModel errorModel) {
                deliverInternal(errorModel);
            }

            @Override
            public void deliverNothing() {
                deliverInternal(Nothing.INSTANCE);
            }

            private void deliverInternal(Object result) {
                try {
                    queue.put(result);
                }
                catch (Exception e) {
                    LOGGER.log(Level.SEVERE, "[WsClient] Queue put error: " + e);
                }
            }
        }));
        subscription.awaitSubscription();
    }

    /**
     * Internal method to subscribe to a topic. The subscription is recycled.
     *
     * @param topic   the topic
     * @param handler the result handler of the topic
     * @return the subscription
     */
    private Subscription subscribeInternal(String topic, ResultHandler<?> handler) {
        String subscriptionId = "" + (internalSubscriptions.size() + 1);
        Subscription subscription = new Subscription(topic, subscriptionId, handler);
        webSocket.sendText(StompMessageHelper.buildSubscribeMessage(subscription.getTopic(), subscription.getSubscriptionId()));

        return subscription;
    }

    /**
     * It unsubscribes from a topic.
     *
     * @param subscription the subscription
     */
    private void unsubscribeFrom(Subscription subscription) {
        LOGGER.info("[Stomp client] Unsubscribing from " + subscription.getTopic());
        webSocket.sendText(StompMessageHelper.buildUnsubscribeMessage(subscription.getSubscriptionId()));
    }

    /**
     * Generates an UUID for this webSocket client.
     */
    private static String generateClientKey() {
        try {
            MessageDigest salt = MessageDigest.getInstance("SHA-256");
            salt.update(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
            return bytesToHex(salt.digest());
        }
        catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }

    private static String bytesToHex(byte[] bytes) {
        byte[] HEX_ARRAY = "0123456789abcdef".getBytes();
        byte[] hexChars = new byte[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars, StandardCharsets.UTF_8);
    }

    public String getClientKey() {
        return clientKey;
    }

    @Override
    public void close() {
        LOGGER.info("[Stomp client] Closing webSocket session");
        internalSubscriptions.values().forEach(this::unsubscribeFrom);
        internalSubscriptions.clear();

        // indicates a normal closure
        webSocket.disconnect(1000, null);
    }

    /**
     * Special object to wrap a NOP.
     */
    private static class Nothing {
        private final static Nothing INSTANCE = new Nothing();

        private Nothing() {
        }
    }
}
