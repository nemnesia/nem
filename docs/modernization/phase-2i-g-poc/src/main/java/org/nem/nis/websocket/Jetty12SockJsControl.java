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
            });
            if (info.statusCode() != 200) throw new AssertionError("SockJS /info status=" + info.statusCode());
            checkOriginMatrix(client, port);
            runSockJsClient(port, "websocket", connects, "Jetty 12 websocket", iterations);
            runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling", iterations);
            dumpStats(app, "Jetty 12 after normal cycles");
            runSockJsClient(port, "websocket", connects, "Jetty 12 websocket abrupt close", 1, "abrupt");
            dumpStats(app, "Jetty 12 after WebSocket abrupt close");
            runSockJsClient(port, "websocket", connects, "Jetty 12 reconnect after WebSocket close", 1);
            runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling abandonment", 1, "abrupt");
            dumpStats(app, "Jetty 12 after XHR abandonment");
            runSockJsClient(port, "xhr-polling", connects, "Jetty 12 reconnect after XHR abandonment", 1);
            runErrorProbe(port, "websocket");
            dumpStats(app, "Jetty 12 after malformed WebSocket STOMP");
            runErrorProbe(port, "xhr-polling");
            Thread.sleep(10000);
            System.out.println("Jetty 12 Spring session stats=" + app.getBean(WebSocketMessageBrokerStats.class).getWebSocketSessionStatsInfo());
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

    private static void runErrorProbe(int port, String transport) throws Exception {
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/messages", transport, "5000", "", "invalid-stomp").inheritIO().start();
        if (!process.waitFor(Duration.ofSeconds(8).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Malformed STOMP probe timed out for " + transport);
        }
        if (process.exitValue() != 0) throw new AssertionError("Malformed STOMP probe did not terminate for " + transport);
    }

    private static void dumpStats(AnnotationConfigWebApplicationContext app, String label) {
        WebSocketMessageBrokerStats stats = app.getBean(WebSocketMessageBrokerStats.class);
        System.out.println(label + " sessions=" + stats.getWebSocketSessionStatsInfo()
                + "; stomp=" + stats.getStompSubProtocolStatsInfo());
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
