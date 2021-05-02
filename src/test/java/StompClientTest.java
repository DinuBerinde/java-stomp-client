import com.dinuberinde.stomp.client.exceptions.NetworkExceptionResponse;
import com.dinuberinde.stomp.client.StompClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class StompClientTest {
    private final static String endpoint = "ws://localhost:8080/";

    @Test
    public void stompClientTopicSubcription() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();

        try (StompClient stompClient = new StompClient(endpoint)) {
            stompClient.connect(() -> {
                        CompletableFuture.runAsync(() -> {

                            // subscribe to topic
                            stompClient.subscribeToTopic("/topic/events", Event.class, (result, error) -> {

                                if (error != null) {
                                    Assertions.fail("unexpected error");
                                }
                                else if (result != null) {
                                    completableFuture.complete("testing stomp client".equals(result.name));
                                }
                                else {
                                    Assertions.fail("unexpected payload");
                                }
                            });

                            // send event
                            stompClient.sendToTopic("/topic/events", new Event("testing stomp client"));
                        });
                    },
                    () -> Assertions.fail("Connection failed")
            );

            Assertions.assertEquals(true, completableFuture.get(4L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void stompClientEchoMessage() throws ExecutionException, InterruptedException, TimeoutException {
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();

        try (StompClient stompClient = new StompClient(endpoint)) {
            stompClient.connect(() -> {

                        CompletableFuture.runAsync(() -> {

                            try {

                                // subscribe and send payload
                                EchoModel result = stompClient.send("/echo/message", EchoModel.class, new EchoModel("hello world"));
                                EchoModel result2 = stompClient.send("/echo/message", EchoModel.class, new EchoModel("hello world class"));

                                completableFuture.complete("hello world".equals(result.message) && "hello world class".equals(result2.message));
                            }
                            catch (InterruptedException e) {
                                e.printStackTrace();
                                Assertions.fail("unexpected error");
                            }
                            catch (NetworkExceptionResponse networkExceptionResponse) {
                                Assertions.fail("unexpected error");
                            }
                        });
                    },
                    () -> Assertions.fail("Connection failed")
            );

            Assertions.assertEquals(true, completableFuture.get(4L, TimeUnit.SECONDS));
        }
    }


    @Test
    public void concurrentlySendEchoMessages() throws ExecutionException, InterruptedException, TimeoutException {
        int numOfThreads = 40;
        ExecutorService pool = Executors.newCachedThreadPool();
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        Map<String, Boolean> results = new HashMap<>(numOfThreads);

        try (StompClient stompClient = new StompClient(endpoint)) {
            stompClient.connect(() -> {

                        Executor delayedTask = CompletableFuture.delayedExecutor(3, TimeUnit.SECONDS);
                        CompletableFuture.runAsync(() -> {

                            // build workers
                            List<StompClientSendWorker> workers = new ArrayList<>();
                            for (int i = 0; i < numOfThreads; i++) {
                                workers.add(new StompClientSendWorker(new EchoModel("hello world " + i), pool, stompClient, results));
                                results.put("hello world " + i, false);
                            }

                            // concurrent requests
                            workers.parallelStream().forEach(StompClientSendWorker::call);
                            pool.shutdown();

                        }).thenRunAsync(
                                () -> completableFuture.complete(results.values().parallelStream().allMatch(p -> p)),
                                delayedTask
                        );

                    },
                    () -> Assertions.fail("Connection failed")
            );

            Assertions.assertEquals(true, completableFuture.get(4L, TimeUnit.SECONDS));
        }
    }

    /**
     * Class model used for testing.
     */
    private static class Event {
        public final String name;

        Event(String name) {
            this.name = name;
        }
    }

    /**
     * Class model used for testing.
     */
    private static class EchoModel {
        public final String message;

        EchoModel(String message) {
            this.message = message;
        }
    }


    /**
     * STOMP client send worker to send messages to a destination.
     */
    private static class StompClientSendWorker {
        public final EchoModel echoModel;
        private final ExecutorService pool;
        private final StompClient asyncStompClient;
        private final Map<String, Boolean> results;

        private StompClientSendWorker(EchoModel echoModel, ExecutorService pool, StompClient asyncStompClient, Map<String, Boolean> results) {
            this.echoModel = echoModel;
            this.pool = pool;
            this.asyncStompClient = asyncStompClient;
            this.results = results;
        }

        public void call() {
            CompletableFuture.runAsync(() -> {
                try {

                    // subscribe and send payload
                    EchoModel result = asyncStompClient.send("/echo/message", EchoModel.class, echoModel);

                    // fill the map results
                    results.put(result.message, true);
                }
                catch (InterruptedException | NetworkExceptionResponse e) {
                    e.printStackTrace();
                }
            }, pool);
        }
    }
}
