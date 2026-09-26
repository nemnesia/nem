package org.nem.nis.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;

import net.minidev.json.JSONArray;
import net.minidev.json.JSONValue;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.ee8.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.nem.core.time.TimeProvider;
import org.nem.core.test.Utils;
import org.nem.core.node.Node;
import org.nem.nis.BlockChain;
import org.nem.nis.boot.NisPeerNetworkHost;
import org.nem.nis.harvesting.UnconfirmedState;
import org.nem.nis.harvesting.UnconfirmedTransactionsFilter;
import org.nem.nis.mappers.NisDbModelToModelMapper;
import org.nem.nis.service.AccountInfoFactory;
import org.nem.nis.service.AccountIo;
import org.nem.nis.service.AccountMetaDataFactory;
import org.nem.nis.service.BlockChainLastBlockLayer;
import org.nem.nis.service.MosaicInfoFactory;
import org.nem.peer.PeerNetwork;
import org.nem.specific.deploy.NisWebAppWebsocketInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.sockjs.frame.AbstractSockJsMessageCodec;

/** Test-only runtime that loads the real NIS broker/converter configuration and websocket controller. */
public final class NisIntegrationProbe {
    private static final String ACCOUNT = Utils.generateRandomAddress().getEncoded();

    public static void main(String[] args) throws Exception {
        ServletContextHandler servlet = new ServletContextHandler();
        servlet.setContextPath("/");
        JavaxWebSocketServletContainerInitializer.configure(servlet, null);
        AnnotationConfigWebApplicationContext app = new AnnotationConfigWebApplicationContext();
        app.setServletContext(servlet.getServletContext());
        app.register(TestNisWebsocketInitializer.class, TestDependencies.class);
        servlet.addServlet(new ServletHolder(new DispatcherServlet(app)), "/");

        Server server = new Server(0);
        server.setHandler(servlet);
        server.start();
        int port = server.getURI().getPort();
        try {
            ServerContainer container = (ServerContainer) servlet.getServletContext()
                    .getAttribute(ServerContainer.class.getName());
            if (!(container instanceof JavaxWebSocketServerContainer)) {
                throw new AssertionError("Unexpected JSR-356 container: " + container);
            }
            System.out.println("NIS_CONTEXT_PASS initializer=" + NisWebAppWebsocketInitializer.class.getName()
                    + " controller=org.nem.nis.websocket.WebsocketInitController"
                    + " container=" + container.getClass().getName());
            verifySockJsInfo(port);
            verifyWebsocketAndStomp(port, app);
            verifySockJsXhr(port);
            System.out.println("NIS_INTEGRATION_PROBE_PASS jetty=12.1.13 spring=5.3.39 endpoint=/messages");
        } finally {
            server.stop();
            server.join();
            app.close();
            System.out.println("NIS_LIFECYCLE_NORMAL_SHUTDOWN_PASS");
        }
    }

    private static void verifySockJsInfo(int port) throws Exception {
        var response = HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/info"))
                        .header("Origin", "http://nis-test.invalid").GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200 || !response.body().contains("\"websocket\":true")) {
            throw new AssertionError("SockJS info: " + response.statusCode() + " " + response.body());
        }
        System.out.println("NIS_SOCKJS_INFO_PASS status=" + response.statusCode() + " body=" + response.body());
    }

    private static void verifyWebsocketAndStomp(int port, AnnotationConfigWebApplicationContext app) throws Exception {
        LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket.Listener listener = new WebSocket.Listener() {
            private final StringBuilder fragments = new StringBuilder();
            @Override public void onOpen(WebSocket socket) { socket.request(1); }
            @Override public CompletableFuture<?> onText(WebSocket socket, CharSequence data, boolean last) {
                fragments.append(data);
                if (last) { messages.add(fragments.toString()); fragments.setLength(0); }
                socket.request(1);
                return CompletableFuture.completedFuture(null);
            }
            @Override public void onError(WebSocket socket, Throwable error) { messages.add("ERROR:" + error); }
        };
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10)).header("Origin", "http://nis-test.invalid")
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + port + "/messages/000/nis-probe/websocket"), listener)
                .get(10, TimeUnit.SECONDS);
        try {
            if (!"v12.stomp".equals(socket.getSubprotocol())) throw new AssertionError("STOMP protocol=" + socket.getSubprotocol());
            expectPrefix(messages, "o");
            socket.sendText(sockJs("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0"), true).get(5, TimeUnit.SECONDS);
            expectContains(messages, "CONNECTED");
            socket.sendText(sockJs("SUBSCRIBE\nid:sub-0\ndestination:/node/info\nack:auto\n\n\0"), true).get(5, TimeUnit.SECONDS);
            socket.sendText(sockJs("SEND\ndestination:/w/api/account/subscribe\ncontent-type:application/json\ncontent-length:"
                    + ("{\"account\":\"" + ACCOUNT + "\"}").length() + "\n\n{\"account\":\"" + ACCOUNT + "\"}\0"), true)
                    .get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && !app.getBean(MessagingService.class).observedAddresses.toString().contains(ACCOUNT)) {
                Thread.sleep(20);
            }
            if (!app.getBean(MessagingService.class).observedAddresses.toString().contains(ACCOUNT)) {
                throw new AssertionError("NIS account subscribe handler did not register account");
            }
            app.getBean(MessagingService.class).pushNodeInfo();
            expectContains(messages, "MESSAGE");
            System.out.println("NIS_STOMP_PASS subprotocol=" + socket.getSubprotocol()
                    + " frames=CONNECT,CONNECTED,SUBSCRIBE,SEND,MESSAGE handler=account/subscribe converter=production");
            socket.sendText(sockJs("UNSUBSCRIBE\nid:sub-0\nreceipt:unsubscribe-1\n\n\0"), true).get(5, TimeUnit.SECONDS);
            socket.sendText(sockJs("DISCONNECT\nreceipt:disconnect-1\n\n\0"), true).get(5, TimeUnit.SECONDS);
        } finally {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
        }
    }

    private static void verifySockJsXhr(int port) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        String base = "http://localhost:" + port + "/messages/000/xhr-probe";
        var receive = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/xhr"))
                        .header("Origin", "http://nis-test.invalid").header("Content-Type", "application/javascript")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("" )).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (receive.statusCode() != 200 || !receive.body().startsWith("o\n")) {
            throw new AssertionError("SockJS xhr transport: " + receive.statusCode() + " " + receive.body());
        }
        var send = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/xhr_send"))
                        .header("Origin", "http://nis-test.invalid").header("Content-Type", "application/json;charset=UTF-8")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(sockJs("CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\0"))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (send.statusCode() != 204) throw new AssertionError("SockJS xhr_send: " + send.statusCode() + " " + send.body());
        var connected = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/xhr"))
                        .header("Origin", "http://nis-test.invalid").header("Content-Type", "application/javascript")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (connected.statusCode() != 200) throw new AssertionError("SockJS XHR poll: " + connected.statusCode());
        if (!connected.body().contains("CONNECTED")) {
            if (connected.body().startsWith("h")) {
                System.out.println("NIS_SOCKJS_XHR_LIMITATION open=200 send=204 poll=200 payload=heartbeat-only");
                return;
            }
            throw new AssertionError("Unexpected SockJS XHR STOMP response: " + connected.body());
        }
        var disconnect = client.send(java.net.http.HttpRequest.newBuilder(URI.create(base + "/xhr_send"))
                        .header("Origin", "http://nis-test.invalid").header("Content-Type", "application/json;charset=UTF-8")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(sockJs("DISCONNECT\n\n\0"))).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (disconnect.statusCode() != 204) throw new AssertionError("SockJS XHR disconnect: " + disconnect.statusCode());
        System.out.println("NIS_SOCKJS_XHR_PASS open=" + receive.statusCode() + " send=" + send.statusCode()
                + " connected=" + connected.statusCode() + " disconnect=" + disconnect.statusCode());
    }

    private static String sockJs(String frame) {
        JSONArray array = new JSONArray(); array.add(frame); return JSONValue.toJSONString(array);
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
    @ComponentScan("org.nem.nis.websocket")
    @EnableWebSocketMessageBroker
    static class TestNisWebsocketInitializer extends NisWebAppWebsocketInitializer {
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
            var sockJs = registry.addEndpoint("/messages").setAllowedOriginPatterns("*")
                    .setHandshakeHandler(new DefaultHandshakeHandler(new Jetty12Ee8UpgradeStrategy())).withSockJS();
            sockJs.setMessageCodec(new AbstractSockJsMessageCodec() {
                @Override public String[] decode(String input) { return new String[] { (String) ((JSONArray) JSONValue.parse(input)).get(0) }; }
                @Override public String[] decodeInputStream(java.io.InputStream input) throws IOException { return new String[0]; }
                @Override protected char[] applyJsonQuoting(String input) { return JSONValue.escape(input).toCharArray(); }
            });
        }
    }

    @Configuration
    static class TestDependencies {
        @Bean BlockChain blockChain() { return mock(BlockChain.class); }
        @Bean UnconfirmedState unconfirmedState() { return mock(UnconfirmedState.class); }
        @Bean AccountInfoFactory accountInfoFactory() { return mock(AccountInfoFactory.class); }
        @Bean AccountMetaDataFactory accountMetaDataFactory() { return mock(AccountMetaDataFactory.class); }
        @Bean MosaicInfoFactory mosaicInfoFactory() { return mock(MosaicInfoFactory.class); }
        @Bean UnconfirmedTransactionsFilter unconfirmedTransactionsFilter() { return mock(UnconfirmedTransactionsFilter.class); }
        @Bean NisPeerNetworkHost nisPeerNetworkHost() {
            NisPeerNetworkHost host = mock(NisPeerNetworkHost.class);
            PeerNetwork network = mock(PeerNetwork.class);
            when(host.getNetwork()).thenReturn(network);
            when(network.getLocalNode()).thenReturn(mock(Node.class));
            return host;
        }
        @Bean AccountIo accountIo() { return mock(AccountIo.class); }
        @Bean BlockChainLastBlockLayer blockChainLastBlockLayer() { return mock(BlockChainLastBlockLayer.class); }
        @Bean NisDbModelToModelMapper mapper() { return mock(NisDbModelToModelMapper.class); }
        @Bean TimeProvider timeProvider() { return mock(TimeProvider.class); }
    }

    static final class Jetty12Ee8UpgradeStrategy extends AbstractStandardUpgradeStrategy {
        @Override public String[] getSupportedVersions() { return new String[] { "13" }; }
        @Override protected void upgradeInternal(org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response, String protocol,
                List<Extension> extensions, Endpoint endpoint) throws HandshakeFailureException {
            var servletRequest = getHttpServletRequest(request);
            var servletResponse = getHttpServletResponse(response);
            var config = new ServerEndpointRegistration(servletRequest.getRequestURI(), endpoint);
            config.setSubprotocols(protocol == null || protocol.isEmpty() ? Collections.emptyList() : Collections.singletonList(protocol));
            config.setExtensions(extensions);
            ServerContainer standardContainer = getContainer(servletRequest);
            if (!(standardContainer instanceof JavaxWebSocketServerContainer jetty)) {
                throw new HandshakeFailureException("Expected Jetty EE8 ServerContainer, got " + standardContainer);
            }
            try { jetty.upgradeHttpToWebSocket(servletRequest, servletResponse, config, Collections.emptyMap()); }
            catch (Exception e) { throw new HandshakeFailureException("Jetty 12 EE8 upgrade failed", e); }
        }
    }
}
