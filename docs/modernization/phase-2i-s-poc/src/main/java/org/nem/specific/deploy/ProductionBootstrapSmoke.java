package org.nem.specific.deploy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.HttpServlet;
import org.nem.core.node.Node;
import org.nem.core.serialization.AccountLookup;
import org.nem.core.time.TimeProvider;
import org.nem.deploy.CommonConfiguration;
import org.nem.deploy.CommonStarter;
import org.nem.deploy.NemConfigurationPolicy;
import org.nem.deploy.PropertiesExtensions;
import org.nem.deploy.server.NemServerBootstrapper;
import org.nem.deploy.server.NemWebsockServerBootstrapper;
import org.nem.nis.BlockChain;
import org.nem.nis.boot.NisPeerNetworkHost;
import org.nem.nis.controller.LocalController;
import org.nem.nis.harvesting.UnconfirmedState;
import org.nem.nis.harvesting.UnconfirmedTransactionsFilter;
import org.nem.nis.mappers.NisDbModelToModelMapper;
import org.nem.nis.service.AccountInfoFactory;
import org.nem.nis.service.AccountIo;
import org.nem.nis.service.AccountMetaDataFactory;
import org.nem.nis.service.BlockChainLastBlockLayer;
import org.nem.nis.service.MosaicInfoFactory;
import org.nem.peer.PeerNetwork;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurationSupport;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;
import org.springframework.web.socket.sockjs.transport.SockJsSession;

/** Starts the real NIS Jetty bootstrap classes with isolated application dependencies for runtime smoke tests. */
public final class ProductionBootstrapSmoke {
    private ProductionBootstrapSmoke() { }

    public static void main(String[] args) throws Exception {
        System.out.println("Servlet API loaded from " + javax.servlet.ServletContext.class.getProtectionDomain().getCodeSource().getLocation());
        Properties properties = PropertiesExtensions.loadFromResource(NisConfiguration.class, "config-default.properties", true);
        properties.setProperty("nem.httpPort", "0");
        properties.setProperty("nem.websocketPort", "0");
        properties.setProperty("nem.maxThreads", "200");
        NisConfiguration configuration = new NisConfiguration(properties);
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.register(TestDependencies.class);
        parent.refresh();
        try {
            if (args.length == 0 || "all".equals(args[0]) || "rest".equals(args[0])) runRest(configuration, parent);
            if (args.length == 0 || "all".equals(args[0]) || "websocket".equals(args[0])) runWebsocket(configuration, parent);
        } finally {
            parent.close();
        }
    }

    private static void runRest(NisConfiguration configuration, AnnotationConfigApplicationContext parent) throws Exception {
        Server server = new NemServerBootstrapper(parent, configuration, new SmokePolicy(RestInitializer.class)).boot();
        try {
            server.start();
            int port = localPort(server);
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/heartbeat")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("REST production-bootstrap GET /heartbeat status=" + response.statusCode()
                    + " body=" + response.body());
            if (response.statusCode() != 200) throw new AssertionError("heartbeat status=" + response.statusCode());
            HttpResponse<String> missing = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/no-such-route")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            System.out.println("REST production-bootstrap GET /no-such-route status=" + missing.statusCode());
            if (missing.statusCode() != 404) throw new AssertionError("not-found status=" + missing.statusCode());
        } finally {
            server.stop();
            server.join();
        }
    }

    @SuppressWarnings("removal")
    private static void runWebsocket(NisConfiguration configuration, AnnotationConfigApplicationContext parent) throws Exception {
        Server server = new NemWebsockServerBootstrapper(parent, configuration, new SmokePolicy(NisWebAppWebsocketInitializer.class)).boot();
        try {
            server.start();
            int port = localPort(server);
            ServletContextHandler context = server.getContainedBeans(ServletContextHandler.class).iterator().next();
            logRegistrations(context);
            logQtp(server, "startup");
            HttpResponse<String> info = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/w/messages/info")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            Object serverContainer = context.getServletContext().getAttribute("javax.websocket.server.ServerContainer");
            boolean strategySelected = false;
            for (NisWebSocketUpgradeStrategyProvider provider : ServiceLoader.load(NisWebSocketUpgradeStrategyProvider.class,
                    Thread.currentThread().getContextClassLoader())) {
                strategySelected |= provider.createIfSupported(serverContainer) instanceof Jetty12WebSocketUpgradeStrategy;
            }
            System.out.println("SockJS production-bootstrap GET /w/messages/info status=" + info.statusCode()
                    + " body=" + info.body() + " serverContainer=" + serverContainer.getClass().getName()
                    + " jetty12StrategyProviderSelected=" + strategySelected);
            if (info.statusCode() != 200) throw new AssertionError("SockJS info status=" + info.statusCode());
            if (!strategySelected) throw new AssertionError("Jetty 12 WebSocket strategy provider not selected");
            String endpoint = "http://127.0.0.1:" + port + "/w/messages";
            for (String transport : new String[] { "websocket", "xhr-polling" }) {
                for (int run = 1; run <= 2; run++) {
                    runSockJsClient(endpoint, transport, run);
                    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
                    int sessions;
                    do {
                        sessions = sessionCount(context);
                        if (sessions == 0) break;
                        Thread.sleep(250);
                    } while (System.nanoTime() < deadline);
                    System.out.println("SockJS cleanup transport=" + transport + " run=" + run + " sessions=" + sessions);
                    if (sessions != 0) throw new AssertionError("SockJS session did not clean up: " + sessions);
                    logQtp(server, "normal-" + transport + "-" + run);
                }
            }
            runAbandonedClient(endpoint, "xhr-polling");
            long cleanupMs = awaitSessionCleanup(context, Duration.ofSeconds(70));
            System.out.println("SockJS abandoned cleanup transport=xhr-polling elapsedMs=" + cleanupMs
                    + " sessions=" + sessionCount(context));
            if (cleanupMs < 0) throw new AssertionError("abandoned XHR SockJS session did not clean up");
            logQtp(server, "after-abandoned-xhr-cleanup");
            runAbandonedClient(endpoint, "websocket");
            cleanupMs = awaitSessionCleanup(context, Duration.ofSeconds(20));
            System.out.println("SockJS abrupt WebSocket cleanup elapsedMs=" + cleanupMs
                    + " sessions=" + sessionCount(context));
            if (cleanupMs < 0) throw new AssertionError("abrupt WebSocket SockJS session did not clean up");
            logQtp(server, "after-abrupt-websocket-cleanup");
            runSockJsClient(endpoint, "websocket", 3);
        } finally {
            server.stop();
            server.join();
            logQtp(server, "after-shutdown");
        }
    }

    private static void runSockJsClient(String endpoint, String transport, int run) throws Exception {
        Process process = new ProcessBuilder("node", "../phase-2i-h-poc/sockjs-client-probe.js", endpoint, transport,
                "20000", "expect-nis-message", "normal")
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("sockjs client timeout transport=" + transport + " run=" + run);
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        System.out.println("SockJS production-bootstrap transport=" + transport + " run=" + run + " exit=" + process.exitValue()
                + " endpoint=" + endpoint + " client=" + output);
        if (process.exitValue() != 0) throw new AssertionError("sockjs client failed " + output);
    }

    private static void runAbandonedClient(String endpoint, String transport) throws Exception {
        Process process = new ProcessBuilder("node", "../phase-2i-h-poc/sockjs-abrupt-batch.js", endpoint, transport, "1")
                .redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("abrupt SockJS client timeout transport=" + transport);
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        System.out.println("SockJS abrupt transport=" + transport + " exit=" + process.exitValue() + " output=" + output);
        if (process.exitValue() != 0) throw new AssertionError("abrupt SockJS client failed " + output);
    }

    private static long awaitSessionCleanup(ServletContextHandler context, Duration timeout) throws Exception {
        long started = System.nanoTime();
        long deadline = System.nanoTime() + timeout.toNanos();
        int sessions;
        do {
            sessions = sessionCount(context);
            if (sessions == 0) return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);
        return -1;
    }

    @SuppressWarnings("removal")
    private static void logQtp(Server server, String stage) {
        QueuedThreadPool pool = (QueuedThreadPool) server.getThreadPool();
        if (!pool.isRunning()) {
            System.out.println("QTP stage=" + stage + " poolState=STOPPED total=" + pool.getThreads() + " queue=" + pool.getQueueSize());
            return;
        }
        System.out.println("QTP stage=" + stage + " total=" + pool.getThreads() + " busy=" + pool.getBusyThreads()
                + " idle=" + pool.getIdleThreads() + " queue=" + pool.getQueueSize() + " min=" + pool.getMinThreads()
                + " max=" + pool.getMaxThreads() + " idleTimeoutMs=" + pool.getIdleTimeout()
                + " reservedCurrent=" + pool.getCurrentReservedThreads() + " reservedAvailable=" + pool.getAvailableReservedThreads());
    }

    private static int localPort(Server server) {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    private static void logRegistrations(ServletContextHandler context) {
        for (ServletHolder holder : context.getServletHandler().getServlets()) {
            System.out.println("Servlet registration name=" + holder.getName() + " class=" + holder.getClassName()
                    + " asyncSupported=" + holder.isAsyncSupported());
        }
        for (FilterHolder holder : context.getServletHandler().getFilters()) {
            System.out.println("Filter registration name=" + holder.getName() + " class=" + holder.getClassName()
                    + " asyncSupported=" + holder.isAsyncSupported());
        }
    }

    @SuppressWarnings("unchecked")
    private static int sessionCount(ServletContextHandler context) throws Exception {
        String attribute = "org.springframework.web.servlet.FrameworkServlet.CONTEXT.Spring Websocket Dispatcher Servlet";
        WebApplicationContext app = (WebApplicationContext) context.getServletContext().getAttribute(attribute);
        if (app == null) throw new IllegalStateException("Spring DispatcherServlet context not found");
        for (AbstractUrlHandlerMapping mapping : app.getBeansOfType(AbstractUrlHandlerMapping.class).values()) {
            for (Object handler : mapping.getHandlerMap().values()) {
                if (handler instanceof SockJsHttpRequestHandler sockJs) {
                    TransportHandlingSockJsService service = (TransportHandlingSockJsService) sockJs.getSockJsService();
                    Field sessions = TransportHandlingSockJsService.class.getDeclaredField("sessions");
                    sessions.setAccessible(true);
                    return ((Map<String, SockJsSession>) sessions.get(service)).size();
                }
            }
        }
        throw new IllegalStateException("SockJS endpoint handler not found");
    }

    private static final class SmokePolicy implements NemConfigurationPolicy {
        private final Class<?> webInitializer;
        SmokePolicy(Class<?> webInitializer) { this.webInitializer = webInitializer; }
        @Override public Class<?> getAppConfigClass() { return TestDependencies.class; }
        @Override public Class<?> getWebAppInitializerClass() { return this.webInitializer; }
        @Override public Class<?> getWebAppWebsockInitializerClass() { return this.webInitializer; }
        @Override public Class<? extends HttpServlet> getJarFileServletClass() { throw new UnsupportedOperationException(); }
        @Override public Class<? extends HttpServlet> getRootServletClass() { throw new UnsupportedOperationException(); }
        @Override public CommonConfiguration loadConfig(String[] args) { return null; }
    }

    @Configuration
    @Import(LocalController.class)
    static class RestInitializer extends WebMvcConfigurationSupport { }

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
        @Bean AccountLookup accountLookup() { return mock(AccountLookup.class); }
        @Bean NisConfiguration nisConfiguration() { return new NisConfiguration(); }
        @Bean CommonStarter commonStarter() { return mock(CommonStarter.class); }
    }
}
