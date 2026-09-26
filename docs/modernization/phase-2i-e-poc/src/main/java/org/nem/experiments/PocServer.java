package org.nem.experiments;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.ee8.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.stereotype.Controller;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.sockjs.frame.AbstractSockJsMessageCodec;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONValue;

public final class PocServer {
    public static void main(String[] args) throws Exception {
        var context = new ServletContextHandler();
        context.setContextPath("/");
        JavaxWebSocketServletContainerInitializer.configure(context, null);
        var webContext = new org.springframework.web.context.support.AnnotationConfigWebApplicationContext();
        webContext.register(AppConfig.class);
        context.addServlet(new ServletHolder(new DispatcherServlet(webContext)), "/");
        var server = new Server(0);
        server.setHandler(context);
        server.start();
        int port = server.getURI().getPort();
        try {
            verifyInfo(port);
            verifyStompSockJs(port);
            System.out.println("POC_PASS port=" + port + " spring=5.3.39 jetty=12.1.13 namespace=javax");
        } finally {
            server.stop();
            server.join();
        }
    }

    private static void verifyInfo(int port) throws Exception {
        var response = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/info")).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("SockJS info status=" + response.statusCode());
        System.out.println("SOCKJS_INFO_PASS " + response.body());
    }

    private static void verifyStompSockJs(int port) throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        var listener = new WebSocket.Listener() {
            private final StringBuilder fragments = new StringBuilder();
            @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
            @Override public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                fragments.append(data);
                if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
                webSocket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            @Override public void onError(WebSocket webSocket, Throwable error) { messages.add("ERROR:" + error); }
        };
        var socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create("ws://localhost:" + port + "/messages/000/poc-session/websocket"), listener)
                .get(10, TimeUnit.SECONDS);
        try {
            expectPrefix(messages, "o");
            socket.sendText(sockJs("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0"), true).get(5, TimeUnit.SECONDS);
            expectContains(messages, "CONNECTED");
            socket.sendText(sockJs("SUBSCRIBE\nid:sub-0\ndestination:/topic/echo\nack:auto\n\n\0"), true).get(5, TimeUnit.SECONDS);
            socket.sendText(sockJs("SEND\ndestination:/app/echo\ncontent-length:5\n\nhello\0"), true).get(5, TimeUnit.SECONDS);
            String reply = expectContains(messages, "MESSAGE");
            if (!reply.contains("hello")) throw new AssertionError("STOMP reply did not contain payload: " + reply);
            System.out.println("SOCKJS_STOMP_CONNECT_SUBSCRIBE_SEND_PASS");
        } finally {
            socket.sendText("c[1000,\"done\"]", true).get(5, TimeUnit.SECONDS);
            socket.abort();
        }
    }

    private static String sockJs(String stompFrame) {
        JSONArray array = new JSONArray();
        array.add(stompFrame);
        return JSONValue.toJSONString(array);
    }

    private static String expectPrefix(LinkedBlockingQueue<String> messages, String prefix) throws Exception {
        String value = messages.poll(10, TimeUnit.SECONDS);
        if (value == null || !value.startsWith(prefix)) throw new AssertionError("Expected " + prefix + ", got " + value);
        return value;
    }

    private static String expectContains(LinkedBlockingQueue<String> messages, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            String value = messages.poll(200, TimeUnit.MILLISECONDS);
            if (value == null) continue;
            if (value.startsWith("ERROR:")) throw new AssertionError(value);
            if (value.contains(expected)) return value;
        }
        throw new AssertionError("Timed out waiting for " + expected);
    }

    @Configuration
    @EnableWebSocketMessageBroker
    static class AppConfig extends AbstractWebSocketMessageBrokerConfigurer {
        @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic");
            registry.setApplicationDestinationPrefixes("/app");
        }
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
            SockJsServiceRegistration sockJs = registry.addEndpoint("/messages")
                    .setAllowedOriginPatterns("*")
                    .setHandshakeHandler(new DefaultHandshakeHandler(new Jetty12Ee8UpgradeStrategy()))
                    .withSockJS();
            sockJs.setMessageCodec(new AbstractSockJsMessageCodec() {
                @Override public String[] decode(String input) {
                    return new String[] { (String) ((JSONArray) JSONValue.parse(input)).get(0) };
                }
                @Override public String[] decodeInputStream(java.io.InputStream input) throws IOException { return new String[0]; }
                @Override protected char[] applyJsonQuoting(String input) { return JSONValue.escape(input).toCharArray(); }
            });
        }
        @Bean EchoController echoController() { return new EchoController(); }
    }

    @Controller
    static class EchoController {
        @MessageMapping("/echo")
        @SendTo("/topic/echo")
        public String echo(String body) { return body; }
    }
}
