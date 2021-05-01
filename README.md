# java-stomp-client

## Overview
A synchronous and asynchronous thread safe webSocket client which implements 
the STOMP protocol https://stomp.github.io/index.html. 

## Usage

#### Asynchronous

```java
// create an instance of the stomp client
StompClient stompClient = new StompClient(endpoint);

// connect to the endpoint
stompClient.connect();

// subscribe to a topic an suppose to receive a result of type Event.class
stompClient.subscribeToTopic("/topic/events", Event.class, (result, error) -> {

        if (error != null) {
          System.out.println("Got an error: " + error.getMessage());
        } else if (result != null) {
          System.out.println("Got result: " + result.getMessage());
        } else {
          System.out.println("Received an empty result");
        }
});

// close the stomp client
stompClient.close();
```


#### Synchronous

```java
// create an instance of the stomp client
StompClient stompClient = new StompClient(endpoint);

// connect to the endpoint
stompClient.connect();

// send a message and wait for the result from the destination topic 
EchoModel result = stompClient.send("/echo/message", EchoModel.class, new EchoModel("hello world"));

// close the stomp client
stompClient.close();
```

## Tests
In order to execute the tests, be sure to download and launch locally 
the webSocket server implementation https://github.com/DinuBerinde/SpringBootStompWebSocketsDemo