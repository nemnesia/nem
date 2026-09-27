package org.nem.nis.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
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
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;

/** Jetty 12 EE8 XHR control; broker/converter/components are real NIS configuration. */
public final class Jetty12SockJsControl {
    public static void main(String[] args) throws Exception {
        int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        boolean cleanupOnly = args.length > 1 && "cleanup-only".equals(args[1]);
        boolean normalOnly = args.length > 1 && "normal-only".equals(args[1]);
        boolean xhrAbandonmentProbe = args.length > 1 && "abnormal-xhr".equals(args[1]);
        boolean websocketAbnormalProbe = args.length > 1 && "abnormal-websocket".equals(args[1]);
        boolean websocketPriorProbe = args.length > 1 && "abnormal-websocket-with-prior".equals(args[1]);
        boolean repeatedXhrProbe = args.length > 1 && "abnormal-xhr-batches".equals(args[1]);
        boolean stompErrorProbe = args.length > 1 && "stomp-error".equals(args[1]);
        boolean qtpProbe = args.length > 1 && args[1].startsWith("qtp-");
        String qtpMode = qtpProbe ? args[1] : "";
        int qtpIdleTimeout = qtpProbe && args.length > 2 ? Integer.parseInt(args[2]) : 60000;
        int xhrAbandonmentCount = xhrAbandonmentProbe && args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int websocketAbnormalCount = (websocketAbnormalProbe || websocketPriorProbe) && args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int repeatedXhrCount = repeatedXhrProbe && args.length > 2 ? Integer.parseInt(args[2]) : 100;
        int observationSeconds = xhrAbandonmentProbe && args.length > 3 ? Integer.parseInt(args[3]) : 90;
        ServletContextHandler servlet = new ServletContextHandler();
        servlet.setContextPath("/");
        servlet.addServlet(new ServletHolder(new HttpServlet() {
            @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
                try { Thread.sleep(Long.parseLong(request.getParameter("delay"))); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                response.setStatus(200);
                response.setContentType("text/plain");
                response.getWriter().write("ok");
            }
        }), "/phase2i-l-ping");
        JavaxWebSocketServletContainerInitializer.configure(servlet, null);
        AnnotationConfigWebApplicationContext app = new AnnotationConfigWebApplicationContext();
        app.setServletContext(servlet.getServletContext());
        app.register(InstrumentedNisWebsocketInitializer.class, TestDependencies.class);
        servlet.addServlet(new ServletHolder(new DispatcherServlet(app)), "/");
        Server server = new Server(0);
        if (qtpProbe) ((org.eclipse.jetty.util.thread.QueuedThreadPool) server.getThreadPool()).setIdleTimeout(qtpIdleTimeout);
        server.setHandler(servlet);
        server.start();
        int port = server.getURI().getPort();
        try {
            ServerContainer container = (ServerContainer) servlet.getServletContext().getAttribute(ServerContainer.class.getName());
            if (!(container instanceof JavaxWebSocketServerContainer)) throw new AssertionError("Unexpected EE8 container " + container);
            if (!Jetty12Provider.selected) throw new AssertionError("Production initializer did not select the Jetty 12 provider");
            int infoStatus = qtpProbe ? -1 : ReadinessProbe.httpGet(port, "/messages/info", "http://nis-test.invalid").status();
            AtomicInteger connects = new AtomicInteger();
            ((ExecutorSubscribableChannel) app.getBean("clientInboundChannel", SubscribableChannel.class)).addInterceptor(new ChannelInterceptor() {
                @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    if (StompHeaderAccessor.getCommand(message.getHeaders()) == StompCommand.CONNECT) connects.incrementAndGet();
                    return message;
                }
                @Override public void afterReceiveCompletion(Message<?> message, MessageChannel channel, Exception ex) { }
            });
            if (!qtpProbe && infoStatus != 200) throw new AssertionError("SockJS /info status=" + infoStatus);
            if (!qtpProbe) checkOriginMatrix(port);
            if (qtpProbe) {
                System.out.println("JETTY12_QTP mode=" + qtpMode + "; idleTimeout=" + qtpIdleTimeout
                        + "; EE8 container=" + container.getClass().getName() + "; providerSelected=" + Jetty12Provider.selected);
                ReadinessProbe.httpSnapshot(server, app, "qtp-start");
                if ("qtp-idle".equals(qtpMode)) {
                    ReadinessProbe.checkpoints(server, app, "idle", 0, 10, 30, 60, 90, 120, 180);
                } else if ("qtp-short".equals(qtpMode)) {
                    ReadinessProbe.httpRequests(port, 100, 100, server);
                    ReadinessProbe.httpSnapshot(server, app, "short-http-after");
                    ReadinessProbe.checkpoints(server, app, "short-idle", 0, 2, 4, 8, 16, 30);
                } else {
                    ReadinessProbe.httpRequests(port, 20, 1, server);
                    ReadinessProbe.httpSnapshot(server, app, "http-sequential-after");
                    ReadinessProbe.httpRequests(port, 10, 10, server);
                    ReadinessProbe.httpSnapshot(server, app, "http-concurrent10-after");
                    for (int batch = 1; batch <= 3; batch++) {
                        ReadinessProbe.httpSnapshot(server, app, "http100-batch" + batch + "-before");
                        ReadinessProbe.httpRequests(port, 100, 100, server);
                        ReadinessProbe.httpSnapshot(server, app, "http100-batch" + batch + "-after");
                    }
                    ReadinessProbe.checkpoints(server, app, "http-postload", 30, 60, 90, 120, 180);
                }
            } else if (stompErrorProbe) {
                String transport = args.length > 2 ? args[2] : "websocket";
                String errorCase = args.length > 3 ? args[3] : "error-invalid-command";
                runErrorProbe(port, transport, errorCase);
                dumpStats(server, app, "Jetty 12 after STOMP error observation");
            } else if (repeatedXhrProbe) {
                ReadinessProbe.httpSnapshot(server, app, "xhr-batches-baseline");
                for (int batch = 1; batch <= 3; batch++) {
                    long start = System.nanoTime();
                    runAbnormalBatch(port, "xhr-polling", connects, repeatedXhrCount, server, app, "Jetty 12 abandoned XHR batch " + batch);
                    long deadline = System.nanoTime() + Duration.ofSeconds(70).toNanos();
                    while (ReadinessProbe.sessionCount(app) > 0 && System.nanoTime() < deadline) Thread.sleep(250);
                    System.out.println("Jetty 12 repeated XHR batch=" + batch + "; cleanupMs=" + Duration.ofNanos(System.nanoTime() - start).toMillis()
                            + "; remaining=" + ReadinessProbe.sessionCount(app) + "; " + ReadinessProbe.sessions(app)
                            + "; " + ReadinessProbe.jettyPool(server) + "; " + ReadinessProbe.threadSummary());
                    ReadinessProbe.httpSnapshot(server, app, "xhr-batch" + batch + "-cleanup");
                    if (ReadinessProbe.sessionCount(app) != 0) throw new AssertionError("XHR batch did not clean up: " + batch);
                }
                Thread.sleep(65000);
                System.out.println("Jetty 12 repeated XHR after idleTimeout+5s; " + ReadinessProbe.sessions(app)
                        + "; " + ReadinessProbe.jettyPool(server) + "; " + ReadinessProbe.threadSummary());
            } else if (websocketAbnormalProbe || websocketPriorProbe) {
                ReadinessProbe.resetCallbacks();
                if (websocketPriorProbe) {
                    runSockJsClient(port, "websocket", connects, "Jetty 12 preliminary abrupt WebSocket", 1, "abrupt");
                }
                runAbnormalBatch(port, "websocket", connects, websocketAbnormalCount, server, app, "Jetty 12 abrupt WebSocket probe");
                System.out.println("Jetty 12 abrupt WebSocket callback probe " + ReadinessProbe.callbackSummary());
                dumpStats(server, app, "Jetty 12 after abrupt WebSocket callback probe");
                runSockJsClient(port, "websocket", connects, "Jetty 12 reconnect after callback probe", 1);
            } else if (xhrAbandonmentProbe) {
                System.out.println("Jetty 12 baseline t=0ms " + ReadinessProbe.sessions(app) + "; "
                        + ReadinessProbe.jettyPool(server) + "; " + ReadinessProbe.threadSummary());
                runAbnormalBatch(port, "xhr-polling", connects, xhrAbandonmentCount, server, app, "Jetty 12 abandoned XHR probe");
                int elapsedSeconds = 0;
                for (int second : java.util.Arrays.stream(new int[] { 0, 10, 30, 45, 60, observationSeconds }).distinct().sorted().toArray()) {
                    Thread.sleep((second - elapsedSeconds) * 1000L);
                    elapsedSeconds = second;
                    System.out.println("Jetty 12 abandoned XHR t=" + second + "s " + ReadinessProbe.sessions(app) + "; "
                            + ReadinessProbe.stats(app) + "; " + ReadinessProbe.jettyPool(server) + "; " + ReadinessProbe.qtpIdentitySnapshot(server)
                            + "; " + ReadinessProbe.threadSummary());
                    if (second >= 10 && ReadinessProbe.sessionCount(app) == 0) break;
                }
                if (args.length > 4 && "await-idle".equals(args[4])) {
                    Thread.sleep(65000);
                    System.out.println("Jetty 12 abandoned XHR after idleTimeout+5s " + ReadinessProbe.sessions(app)
                            + "; " + ReadinessProbe.jettyPool(server) + "; " + ReadinessProbe.threadSummary());
                }
            } else {
                runSockJsClient(port, "websocket", connects, "Jetty 12 websocket", iterations);
                runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling", iterations);
            }
            if (!qtpProbe) dumpStats(server, app, "Jetty 12 after normal cycles");
            if (!qtpProbe && !normalOnly && !xhrAbandonmentProbe && !websocketAbnormalProbe && !websocketPriorProbe && !repeatedXhrProbe && !stompErrorProbe) {
                runSockJsClient(port, "websocket", connects, "Jetty 12 websocket abrupt close", 1, "abrupt");
                dumpStats(server, app, "Jetty 12 after WebSocket abrupt close");
                runSockJsClient(port, "websocket", connects, "Jetty 12 reconnect after WebSocket close", 1);
                runSockJsClient(port, "xhr-polling", connects, "Jetty 12 xhr-polling abandonment", 1, "abrupt");
                dumpStats(server, app, "Jetty 12 after XHR abandonment");
                runSockJsClient(port, "xhr-polling", connects, "Jetty 12 reconnect after XHR abandonment", 1);
                ReadinessProbe.resetCallbacks();
                runAbnormalBatch(port, "websocket", connects, 100, server, app, "Jetty 12 abrupt WebSocket batch");
                System.out.println("Jetty 12 abrupt WebSocket callback probe " + ReadinessProbe.callbackSummary());
                runAbnormalBatch(port, "xhr-polling", connects, 100, server, app, "Jetty 12 abandoned XHR batch");
            }
            if (!qtpProbe && !cleanupOnly && !normalOnly && !xhrAbandonmentProbe && !websocketAbnormalProbe && !websocketPriorProbe && !repeatedXhrProbe && !stompErrorProbe) {
                for (String errorCase : new String[] { "error-invalid-command", "error-missing-destination", "error-invalid-subscribe" }) {
                    runErrorProbe(port, "websocket", errorCase);
                }
                dumpStats(server, app, "Jetty 12 after malformed WebSocket STOMP");
                for (String errorCase : new String[] { "error-invalid-command", "error-missing-destination", "error-invalid-subscribe" }) {
                    runErrorProbe(port, "xhr-polling", errorCase);
                }
            }
            for (int i = 0; i <= (qtpProbe || xhrAbandonmentProbe || websocketAbnormalProbe || websocketPriorProbe || repeatedXhrProbe || stompErrorProbe ? -1 : 9); i++) {
                System.out.println("Jetty 12 cleanup t=" + (i * 5000) + "ms " + ReadinessProbe.sessions(app) + "; "
                        + ReadinessProbe.stats(app) + "; " + ReadinessProbe.jettyPool(server));
                if (i < 4) Thread.sleep(5000);
            }
            if (!qtpProbe) System.out.println("Jetty 12 raw handshake no-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-origin/websocket", null, "valid"));
            if (!qtpProbe) {
            System.out.println("Jetty 12 raw handshake same-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-same-origin/websocket", "http://localhost:" + port, "valid"));
            System.out.println("Jetty 12 raw handshake unrelated-origin=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-unrelated-origin/websocket", "https://otherwise-unmatched.invalid", "valid"));
            System.out.println("Jetty 12 raw handshake missing-upgrade=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-upgrade/websocket", null, "missing-upgrade"));
            System.out.println("Jetty 12 raw handshake bad-version=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-bad-version/websocket", null, "bad-version"));
            System.out.println("Jetty 12 raw handshake missing-key=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-no-key/websocket", null, "missing-key"));
            System.out.println("Jetty 12 raw handshake invalid-key=" + ReadinessProbe.rawHandshake(port, "/messages/000/phase2i-j-invalid-key/websocket", null, "invalid-key"));
            System.out.println("Jetty 12 raw handshake malformed-path=" + ReadinessProbe.rawHandshake(port, "/messages/invalid/websocket", null, "valid"));
            System.out.println("JETTY12_STANDARD_SOCKJS info=" + (qtpProbe ? "not-requested" : infoStatus) + "; SpringInboundConnectTotal=" + connects.get()
                    + "; codec=production; initializer=production; providerSelected=" + Jetty12Provider.selected
                    + "; container=" + container.getClass().getName());
            }
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

    private static void checkOriginMatrix(int port) throws Exception {
        for (String origin : new String[] { null, "https://allowed.example", "https://otherwise-unmatched.invalid" }) {
            ReadinessProbe.HttpResult response = ReadinessProbe.httpGet(port, "/messages/info", origin);
            if (response.status() != 200) throw new AssertionError("Origin policy response=" + response.status() + " origin=" + origin);
            System.out.println("Jetty 12 SockJS Origin=" + origin + " status=" + response.status()
                    + " allowOrigin=" + (null == response.allowOrigin() ? "<absent>" : response.allowOrigin()));
        }
        System.out.println("Jetty 12 invalid SockJS path status=" + ReadinessProbe.httpGet(port, "/messages/not-sockjs", null).status());
    }

    private static void runErrorProbe(int port, String transport, String errorCase) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/messages", transport, "25000", "", errorCase).inheritIO();
        builder.environment().put("STOMP_ERROR_OBSERVATION_MS", "20000");
        Process process = builder.start();
        if (!process.waitFor(Duration.ofSeconds(25).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Malformed STOMP probe timed out for " + transport);
        }
        if (process.exitValue() != 0) throw new AssertionError("Malformed STOMP probe did not terminate for " + transport);
    }

    private static void runAbnormalBatch(int port, String transport, AtomicInteger connects, int count,
            Server server, AnnotationConfigWebApplicationContext app, String label) throws Exception {
        int before = connects.get();
        ReadinessProbe.QtpSampler sampler = new ReadinessProbe.QtpSampler(server);
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-abrupt-batch.js",
                "http://localhost:" + port + "/messages", transport, Integer.toString(count)).inheritIO().start();
        try {
            if (!process.waitFor(Duration.ofSeconds(40).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new AssertionError(label + " timed out");
            }
            if (process.exitValue() != 0) throw new AssertionError(label + " exit=" + process.exitValue());
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (connects.get() - before < count && System.nanoTime() < deadline) Thread.sleep(100);
            if (connects.get() - before != count) throw new AssertionError(label + " inbound CONNECT count=" + (connects.get() - before));
        } finally {
            sampler.close();
        }
        System.out.println(label + " abruptSessions=" + count + " inboundConnectDelta=" + (connects.get() - before)
                + "; " + sampler.summary() + "; " + ReadinessProbe.sessions(app) + "; " + ReadinessProbe.stats(app));
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

    @Configuration
    @ComponentScan("org.nem.nis.websocket")
    @org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker
    static class InstrumentedNisWebsocketInitializer extends NisWebAppWebsocketInitializer {
        @Override public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
            registration.addDecoratorFactory(ReadinessProbe.callbackRecorder());
        }
    }
}
