package org.nem.nis.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.ee8.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.nem.core.node.Node;
import org.nem.core.time.TimeProvider;
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
import org.nem.specific.deploy.NisWebSocketUpgradeStrategyProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;

/** Jetty 12 EE8 XHR control; broker/converter/components are real NIS configuration. */
public final class Jetty12SockJsControl {
    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        boolean cleanupOnly = args.length > 1 && "cleanup-only".equals(args[1]);
        boolean normalOnly = args.length > 1 && "normal-only".equals(args[1]);
        ServletContextHandler servlet = new ServletContextHandler();
        servlet.setContextPath("/");
        JavaxWebSocketServletContainerInitializer.configure(servlet, null);
        AnnotationConfigWebApplicationContext app = new AnnotationConfigWebApplicationContext();
        app.setServletContext(servlet.getServletContext());
        app.register(NisWebAppWebsocketInitializer.class, TestDependencies.class);
        servlet.addServlet(new ServletHolder(new DispatcherServlet(app)), "/");
        Server server = new Server(0);
        server.setHandler(servlet);
        server.start();
        int port = server.getURI().getPort();
        try {
            ServerContainer container = (ServerContainer) servlet.getServletContext().getAttribute(ServerContainer.class.getName());
            if (!(container instanceof JavaxWebSocketServerContainer)) throw new AssertionError("Unexpected EE8 container " + container);
            if (!Jetty12Provider.selected) throw new AssertionError("Production initializer did not select the Jetty 12 provider");
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> info = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/info"))
                    .header("Origin", "http://nis-test.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            AtomicInteger connects = new AtomicInteger();
            ((ExecutorSubscribableChannel) app.getBean("clientInboundChannel", SubscribableChannel.class)).addInterceptor(new ChannelInterceptor() {
                @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    if (StompHeaderAccessor.getCommand(message.getHeaders()) == StompCommand.CONNECT) connects.incrementAndGet();
                    return message;
                }
                @Override public void afterReceiveCompletion(Message<?> message, MessageChannel channel, Exception ex) { }
            });
            if (info.statusCode() != 200) throw new AssertionError("SockJS /info status=" + info.statusCode());
            checkOriginMatrix(client, port);
            runSockJsClient(port, "websocket", connects, "Jetty 12 websocket", iterations);
            runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling", iterations);
            dumpStats(server, app, "Jetty 12 after normal cycles");
            if (!normalOnly) {
                runSockJsClient(port, "websocket", connects, "Jetty 12 websocket abrupt close", 1, "abrupt");
                dumpStats(server, app, "Jetty 12 after WebSocket abrupt close");
                runSockJsClient(port, "websocket", connects, "Jetty 12 reconnect after WebSocket close", 1);
                runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling abandonment", 1, "abrupt");
                dumpStats(server, app, "Jetty 12 after XHR abandonment");
                runSockJsClient(port, "xhr-polling", connects, "Jetty 12 reconnect after XHR abandonment", 1);
                runAbnormalBatch(port, "websocket", connects, 100, app, "Jetty 12 abrupt WebSocket batch");
                runAbnormalBatch(port, "xhr-polling", connects, 100, app, "Jetty 12 abandoned XHR batch");
            }
            if (!cleanupOnly && !normalOnly) {
                for (String errorCase : new String[] { "error-invalid-command", "error-missing-destination", "error-invalid-subscribe" }) {
                    runErrorProbe(port, "websocket", errorCase);
                }
                dumpStats(server, app, "Jetty 12 after malformed WebSocket STOMP");
                for (String errorCase : new String[] { "error-invalid-command", "error-missing-destination", "error-invalid-subscribe" }) {
                    runErrorProbe(port, "xhr-polling", errorCase);
                }
            }
            for (int i = 0; i <= 9; i++) {
                System.out.println("Jetty 12 cleanup t=" + (i * 5000) + "ms " + ReadinessProbe.sessions(app) + "; "
                        + ReadinessProbe.stats(app) + "; " + ReadinessProbe.jettyPool(server));
                if (i < 4) Thread.sleep(5000);
            }
            System.out.println("Jetty 12 raw handshake no-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-origin/websocket", null, "valid"));
            System.out.println("Jetty 12 raw handshake same-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-same-origin/websocket", "http://localhost:" + port, "valid"));
            System.out.println("Jetty 12 raw handshake unrelated-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-unrelated-origin/websocket", "https://otherwise-unmatched.invalid", "valid"));
            System.out.println("Jetty 12 raw handshake missing-upgrade=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-upgrade/websocket", null, "missing-upgrade"));
            System.out.println("Jetty 12 raw handshake bad-version=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-bad-version/websocket", null, "bad-version"));
            System.out.println("Jetty 12 raw handshake missing-key=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-key/websocket", null, "missing-key"));
            System.out.println("Jetty 12 raw handshake invalid-key=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-invalid-key/websocket", null, "invalid-key"));
            System.out.println("Jetty 12 raw handshake malformed-path=" + ReadinessProbe.rawHandshake(port, "/messages/invalid/websocket", null, "valid"));
            System.out.println("JETTY12_STANDARD_SOCKJS info=" + info.statusCode() + "; SpringInboundConnectTotal=" + connects.get()
                    + "; codec=production; initializer=production; providerSelected=" + Jetty12Provider.selected
                    + "; container=" + container.getClass().getName());
        } finally {
            server.stop();
            server.join();
            app.close();
        }
    }

    private static void runSockJsClient(int port, String transport, AtomicInteger connects, String label, int iterations) throws Exception {
        runSockJsClient(port, transport, connects, label, iterations, "normal");
    }

    private static void runSockJsClient(int port, String transport, AtomicInteger connects, String label, int iterations, String mode) throws Exception {
        int before = connects.get();
        int threadsBefore = ManagementFactory.getThreadMXBean().getThreadCount();
        int threadsPeak = threadsBefore;
        for (int i = 0; i < iterations; i++) {
            ProcessBuilder builder = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                    "http://localhost:" + port + "/messages", transport, "12000", "normal".equals(mode) ? "expect-nis-message" : "", mode);
            if (iterations > 1) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            else builder.inheritIO();
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssertionError("sockjs-client timed out for " + transport + " iteration " + i);
            }
            if (process.exitValue() != 0) throw new AssertionError("sockjs-client failed for " + transport + " iteration " + i);
            if ("abrupt".equals(mode)) Thread.sleep(1500);
            threadsPeak = Math.max(threadsPeak, ManagementFactory.getThreadMXBean().getThreadCount());
        }
        Thread.sleep(500);
        int threadsAfter = ManagementFactory.getThreadMXBean().getThreadCount();
        int connectDelta = connects.get() - before;
        if (connectDelta != iterations) throw new AssertionError(label + " inbound CONNECT count=" + connectDelta + " expected=" + iterations);
        System.out.println(label + " sessions=" + iterations + " inboundConnectDelta=" + connectDelta
                + " jvmThreads(before/peak/after)=" + threadsBefore + "/" + threadsPeak + "/" + threadsAfter);
    }

    private static void checkOriginMatrix(HttpClient client, int port) throws Exception {
        for (String origin : new String[] { null, "https://allowed.example", "https://otherwise-unmatched.invalid" }) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/info"));
            if (null != origin) request.header("Origin", origin);
            HttpResponse<String> response = client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new AssertionError("Origin policy response=" + response.statusCode() + " origin=" + origin);
            System.out.println("Jetty 12 SockJS Origin=" + origin + " status=" + response.statusCode()
                    + " allowOrigin=" + response.headers().firstValue("Access-Control-Allow-Origin").orElse("<absent>"));
        }
        HttpResponse<String> invalid = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/not-sockjs"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        System.out.println("Jetty 12 invalid SockJS path status=" + invalid.statusCode());
    }

    private static void runErrorProbe(int port, String transport, String errorCase) throws Exception {
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/messages", transport, "5000", "", errorCase).inheritIO().start();
        if (!process.waitFor(Duration.ofSeconds(8).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Malformed STOMP probe timed out for " + transport);
        }
        if (process.exitValue() != 0) throw new AssertionError("Malformed STOMP probe did not terminate for " + transport);
    }

    private static void runAbnormalBatch(int port, String transport, AtomicInteger connects, int count,
            AnnotationConfigWebApplicationContext app, String label) throws Exception {
        int before = connects.get();
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-abrupt-batch.js",
                "http://localhost:" + port + "/messages", transport, Integer.toString(count)).inheritIO().start();
        if (!process.waitFor(Duration.ofSeconds(40).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError(label + " timed out");
        }
        if (process.exitValue() != 0) throw new AssertionError(label + " exit=" + process.exitValue());
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (connects.get() - before < count && System.nanoTime() < deadline) Thread.sleep(100);
        if (connects.get() - before != count) throw new AssertionError(label + " inbound CONNECT count=" + (connects.get() - before));
        System.out.println(label + " abruptSessions=" + count + " inboundConnectDelta=" + (connects.get() - before)
                + "; " + ReadinessProbe.sessions(app) + "; " + ReadinessProbe.stats(app));
    }

    private static void dumpStats(Server server, AnnotationConfigWebApplicationContext app, String label) throws Exception {
        WebSocketMessageBrokerStats stats = app.getBean(WebSocketMessageBrokerStats.class);
        System.out.println(label + " sessions=" + stats.getWebSocketSessionStatsInfo()
                + "; stomp=" + stats.getStompSubProtocolStatsInfo() + "; " + ReadinessProbe.sessions(app)
                + "; " + ReadinessProbe.stats(app) + "; " + ReadinessProbe.jettyPool(server));
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

    public static final class Jetty12Provider implements NisWebSocketUpgradeStrategyProvider {
        static volatile boolean selected;
        @Override public org.springframework.web.socket.server.RequestUpgradeStrategy createIfSupported(Object candidate) {
            if (!(candidate instanceof JavaxWebSocketServerContainer)) return null;
            selected = true;
            return new Jetty12Ee8UpgradeStrategy();
        }
    }

    static final class Jetty12Ee8UpgradeStrategy extends AbstractStandardUpgradeStrategy {
        @Override public String[] getSupportedVersions() { return new String[] { "13" }; }
        @Override protected void upgradeInternal(org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response, String protocol,
                List<Extension> extensions, Endpoint endpoint) throws HandshakeFailureException {
            var servletRequest = getHttpServletRequest(request);
            var servletResponse = getHttpServletResponse(response);
            var config = new ServerEndpointRegistration(servletRequest.getRequestURI(), endpoint);
            config.setSubprotocols(protocol == null || protocol.isEmpty() ? java.util.Collections.emptyList() : java.util.Collections.singletonList(protocol));
            config.setExtensions(extensions);
            ServerContainer standardContainer = getContainer(servletRequest);
            if (!(standardContainer instanceof JavaxWebSocketServerContainer jetty)) {
                throw new HandshakeFailureException("Expected Jetty EE8 ServerContainer, got " + standardContainer);
            }
            try { jetty.upgradeHttpToWebSocket(servletRequest, servletResponse, config, java.util.Collections.emptyMap()); }
            catch (Exception e) { throw new HandshakeFailureException("Jetty EE8 upgrade failed", e); }
        }
    }
}
