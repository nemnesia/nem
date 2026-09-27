package org.nem.nis.websocket;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletMapping;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;
import org.eclipse.jetty.ee8.servlet.FilterHolder;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.servlet.ServletHolder;
import org.eclipse.jetty.ee8.servlet.ServletMapping;
import org.eclipse.jetty.ee8.servlet.ServletHandler;
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer;
import org.eclipse.jetty.ee8.websocket.javax.server.config.JavaxWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.nem.deploy.server.AbstractNemServletContextListener;
import org.nem.specific.deploy.NisWebAppWebsocketInitializer;
import org.nem.specific.deploy.NisWebSocketUpgradeStrategyProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.RequestUpgradeStrategy;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;

/** Test-only Jetty 12 bootstrap mirroring NemWebsockServerBootstrapper and its production context listener. */
public final class ProductionParityControl {
    private static final List<String> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile Class<?> selectedStrategy;
    private static volatile AnnotationConfigWebApplicationContext childContext;
    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.register(Jetty12SockJsControl.TestDependencies.class);
        parent.refresh();

        ServletContextHandler servlet = new ServletContextHandler();
        servlet.setContextPath("/");
        record("server-context-created contextPath=/ attributes=" + attributes(servlet.getServletContext()));
        JavaxWebSocketServletContainerInitializer.configure(servlet, (context, container) -> {
            record("jetty-websocket-initializer container=" + container.getClass().getName()
                    + " attributeIdentity=" + (context.getAttribute(ServerContainer.class.getName()) == container)
                    + " attributes=" + attributes(context));
        });
        servlet.addEventListener(new ProductionWebsocketListener(parent));
        servlet.addEventListener(new ContextLoaderListener());
        servlet.addEventListener(new LifecycleObserver());
        record("listeners-registered custom,ContextLoaderListener,observer");

        Server server = new Server(new QueuedThreadPool(200, 8));
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(servlet);
        server.start();
        int port = connector.getLocalPort();
        try {
            logRuntimeRegistrations(servlet, "after-start");
            AtomicInteger connects = observeConnects();
            record("server-started mode=production-async-enabled-candidate"
                    + " port=" + port + " container=" + container(servlet.getServletContext())
                    + " strategy=" + selectedStrategy);
            var info = ReadinessProbe.httpGet(port, "/w/messages/info", "http://nis-test.invalid");
            record("info method=GET url=/w/messages/info status=" + info.status() + " body=" + info.body());
            ReadinessProbe.httpSnapshot(server, childContext, "async-enabled-baseline");
            for (int i = 1; i <= 2; i++) {
                runClient(port, "websocket", i);
                runClient(port, "xhr-polling", i);
                ReadinessProbe.httpSnapshot(server, childContext, "smoke-cycle-" + i);
            }
            if (args.length > 0 && "lifecycle".equals(args[0])) {
                runNormalBatch(port, "websocket", 100, connects, server, "websocket-normal-100");
                runNormalBatch(port, "xhr-polling", 10, connects, server, "xhr-normal-10-additional");
                runAbnormalBatch(port, "websocket", 100, connects, server, "websocket-abrupt-100");
                awaitCleanup(server, "websocket-abrupt-100", 15);
                for (int batch = 1; batch <= 3; batch++) {
                    runAbnormalBatch(port, "xhr-polling", 100, connects, server, "xhr-abandoned-100-batch-" + batch);
                    awaitCleanup(server, "xhr-abandoned-100-batch-" + batch, 70);
                }
                int[] checkpoints = { 10, 30, 60, 90, 120 };
                int previous = 0;
                for (int checkpoint : checkpoints) {
                    Thread.sleep((checkpoint - previous) * 1000L);
                    previous = checkpoint;
                    ReadinessProbe.httpSnapshot(server, childContext, "post-abandoned-idle-" + checkpoint + "s");
                }
            } else {
                awaitCleanup(server, "smoke", 12);
            }
            record("final-sessions " + ReadinessProbe.sessions(childContext) + "; connects=" + connects.get()
                    + "; callbacks=" + ReadinessProbe.callbackSummary());
        } finally {
            server.stop();
            server.join();
            parent.close();
        }
        System.out.println("=== PHASE2I-O EVENTS ===");
        EVENTS.forEach(System.out::println);
    }

    private static AtomicInteger observeConnects() {
        AtomicInteger connects = new AtomicInteger();
        ((ExecutorSubscribableChannel) childContext.getBean("clientInboundChannel", SubscribableChannel.class))
                .addInterceptor(new ChannelInterceptor() {
                    @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                        if (StompCommand.CONNECT == StompHeaderAccessor.getCommand(message.getHeaders())) connects.incrementAndGet();
                        return message;
                    }
                });
        return connects;
    }

    private static void runClient(int port, String transport, int iteration) throws Exception {
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/w/messages", transport, "12000", "expect-nis-message", "normal")
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        boolean exited = process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            record("client transport=" + transport + " timed-out output=" + new String(output));
            return;
        }
        if (process.exitValue() != 0) throw new AssertionError("client failed transport=" + transport + " output=" + new String(output));
        record("client iteration=" + iteration + " transport=" + transport + " exit=" + process.exitValue()
                + " output=" + new String(output).trim());
    }

    private static void runNormalBatch(int port, String transport, int count, AtomicInteger connects,
            Server server, String label) throws Exception {
        int before = connects.get();
        ReadinessProbe.httpSnapshot(server, childContext, label + "-before");
        if ("xhr-polling".equals(transport)) {
            for (int i = 1; i <= count; i++) runClient(port, transport, i);
            if (connects.get() - before != count) throw new AssertionError(label + " CONNECT delta=" + (connects.get() - before));
            awaitCleanup(server, label, 20);
            ReadinessProbe.httpSnapshot(server, childContext, label + "-after-cleanup");
            return;
        }
        for (int completed = 0; completed < count; completed += 10) {
            int batchSize = Math.min(10, count - completed);
            Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-normal-batch.js",
                    "http://localhost:" + port + "/w/messages", transport, Integer.toString(batchSize),
                    "xhr-polling".equals(transport) ? "1" : "10").inheritIO().start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError(label + " timed out after " + completed + " cycles");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(label + " failed after " + completed + " cycles exit=" + process.exitValue());
            }
        }
        if (connects.get() - before != count) throw new AssertionError(label + " CONNECT delta=" + (connects.get() - before));
        awaitCleanup(server, label, 15);
        ReadinessProbe.httpSnapshot(server, childContext, label + "-after-cleanup");
    }

    private static void runAbnormalBatch(int port, String transport, int count, AtomicInteger connects,
            Server server, String label) throws Exception {
        int before = connects.get();
        ReadinessProbe.resetCallbacks();
        ReadinessProbe.httpSnapshot(server, childContext, label + "-before");
        ReadinessProbe.QtpSampler sampler = new ReadinessProbe.QtpSampler(server);
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-abrupt-batch.js",
                "http://localhost:" + port + "/w/messages", transport, Integer.toString(count)).inheritIO().start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                throw new AssertionError(label + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new AssertionError(label + " failed exit=" + process.exitValue());
            }
        } finally { sampler.close(); }
        if (connects.get() - before != count) throw new AssertionError(label + " CONNECT delta=" + (connects.get() - before));
        record(label + " sampler=" + sampler.summary() + "; callbacks=" + ReadinessProbe.callbackSummary()
                + "; sessions=" + ReadinessProbe.sessions(childContext) + "; pool=" + ReadinessProbe.jettyPool(server));
        ReadinessProbe.httpSnapshot(server, childContext, label + "-connected");
    }

    private static void awaitCleanup(Server server, String label, int timeoutSeconds) throws Exception {
        long start = System.nanoTime();
        int[] checkpoints = { 0, 10, 30, 45, 60, timeoutSeconds };
        int nextCheckpoint = 0;
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) <= timeoutSeconds) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            int elapsedSeconds = (int) (elapsedMs / 1000);
            while (nextCheckpoint < checkpoints.length && checkpoints[nextCheckpoint] <= elapsedSeconds) {
                ReadinessProbe.httpSnapshot(server, childContext, label + "-cleanup-t" + checkpoints[nextCheckpoint] + "s");
                nextCheckpoint++;
            }
            if (elapsedMs > 0 && ReadinessProbe.sessionCount(childContext) == 0) {
                record(label + " cleanupElapsedMs=" + elapsedMs);
                return;
            }
            Thread.sleep(250);
        }
        if (ReadinessProbe.sessionCount(childContext) != 0) throw new AssertionError(label + " left sessions " + ReadinessProbe.sessions(childContext));
        record(label + " cleanupElapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void logRuntimeRegistrations(ServletContextHandler context, String when) {
        ServletHandler handler = context.getServletHandler();
        for (ServletHolder servlet : handler.getServlets()) {
            record("servlet when=" + when + " name=" + servlet.getName() + " class=" + servlet.getClassName()
                    + " asyncSupported=" + servlet.isAsyncSupported());
        }
        for (ServletMapping mapping : handler.getServletMappings()) {
            record("servlet-mapping when=" + when + " servlet=" + mapping.getServletName()
                    + " paths=" + java.util.Arrays.toString(mapping.getPathSpecs()));
        }
        for (FilterHolder filter : handler.getFilters()) {
            record("filter when=" + when + " name=" + filter.getName() + " class=" + filter.getClassName()
                    + " asyncSupported=" + filter.isAsyncSupported());
        }
        for (var mapping : handler.getFilterMappings()) {
            record("filter-mapping when=" + when + " filter=" + mapping.getFilterName()
                    + " paths=" + java.util.Arrays.toString(mapping.getPathSpecs())
                    + " dispatchers=" + mapping.getDispatcherTypes());
        }
    }

    private static String container(ServletContext context) {
        Object value = context.getAttribute(ServerContainer.class.getName());
        return value == null ? "<absent>" : value.getClass().getName();
    }

    private static String attributes(ServletContext context) {
        StringBuilder result = new StringBuilder("{");
        Enumeration<String> names = context.getAttributeNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.toLowerCase().contains("websocket") || name.toLowerCase().contains("servercontainer")
                    || name.toLowerCase().contains("jetty")) {
                result.append(name).append('=').append(context.getAttribute(name)).append(';');
            }
        }
        return result.append('}').toString();
    }

    private static void record(String event) { EVENTS.add(event); }

    private static final class ProductionWebsocketListener extends AbstractNemServletContextListener {
        ProductionWebsocketListener(AnnotationConfigApplicationContext parent) {
            super(parent, Jetty12SockJsControl.InstrumentedNisWebsocketInitializer.class, true);
        }

        @Override
        public void contextInitialized(ServletContextEvent event) {
            record("production-listener-enter contextPath=" + event.getServletContext().getContextPath()
                    + " before-container=" + container(event.getServletContext())
                    + " attrs=" + attributes(event.getServletContext()));
            super.contextInitialized(event);
            ServletContext context = event.getServletContext();
            var diag = context.addFilter("Phase2IORequestTrace", new RequestTraceFilter());
            diag.setAsyncSupported(true);
            diag.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            record("production-listener-exit servletRegs=" + context.getServletRegistrations().keySet()
                    + " filterRegs=" + context.getFilterRegistrations().keySet()
                    + " attrs=" + attributes(context));
        }

        @Override
        protected void initialize(AnnotationConfigWebApplicationContext webCtx, ServletContext context) {
            childContext = webCtx;
            webCtx.register(Diagnostics.class);
            webCtx.addApplicationListener(event -> {
                if (event instanceof org.springframework.context.event.ContextRefreshedEvent) {
                    record("spring-child-context-refreshed context=" + event.getSource().getClass().getName()
                            + " container=" + container(context) + " attrs=" + attributes(context));
                }
            });
            var dispatcher = context.addServlet("Spring Websocket Dispatcher Servlet", new DispatcherServlet(webCtx));
            dispatcher.addMapping("/w/*");
            dispatcher.setLoadOnStartup(1);
            dispatcher.setAsyncSupported(true);
            record("dispatcher-registered name=Spring Websocket Dispatcher Servlet mapping=/w/* asyncSupported="
                    + true);
        }
    }

    private static final class LifecycleObserver implements javax.servlet.ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent event) {
            record("context-loader-returned root=" + event.getServletContext().getAttribute(
                    "org.springframework.web.context.WebApplicationContext.ROOT")
                    + " container=" + container(event.getServletContext()));
        }
        @Override public void contextDestroyed(ServletContextEvent event) { }
    }

    private static final class RequestTraceFilter implements Filter {
        @Override public void init(FilterConfig config) { }
        @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            record("request-enter method=" + http.getMethod() + " uri=" + http.getRequestURI()
                    + " query=" + http.getQueryString() + " dispatcher=" + http.getDispatcherType()
                    + " contextPath=" + http.getContextPath() + " servletPath=" + http.getServletPath()
                    + " pathInfo=" + http.getPathInfo() + " asyncSupported=" + request.isAsyncSupported()
                    + " asyncStarted=" + request.isAsyncStarted() + " mapping=" + servletMapping(http));
            try {
                chain.doFilter(request, response);
                record("request-exit uri=" + http.getRequestURI() + " status=" + ((HttpServletResponse) response).getStatus()
                        + " asyncSupported=" + request.isAsyncSupported() + " asyncStarted=" + request.isAsyncStarted());
            } catch (Throwable t) {
                record("request-exception uri=" + http.getRequestURI() + " asyncSupported=" + request.isAsyncSupported()
                        + " asyncStarted=" + request.isAsyncStarted() + " exceptionChain=" + causes(t));
                if (t instanceof IOException) throw (IOException) t;
                if (t instanceof ServletException) throw (ServletException) t;
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new ServletException(t);
            }
        }
        @Override public void destroy() { }
    }

    private static String servletMapping(HttpServletRequest request) {
        HttpServletMapping mapping = request.getHttpServletMapping();
        var registration = request.getServletContext().getServletRegistration(mapping.getServletName());
        return "name=" + mapping.getServletName() + ",pattern=" + mapping.getPattern() + ",match="
                + mapping.getMatchValue() + ",kind=" + mapping.getMappingMatch() + ",class="
                + (registration == null ? "<unknown>" : registration.getClassName());
    }

    @Configuration
    static class Diagnostics {
        @Bean MappedInterceptor phase2iOHandlerTrace() {
            return new MappedInterceptor(new String[] { "/**" }, new HandlerInterceptor() {
                @Override public boolean preHandle(javax.servlet.http.HttpServletRequest request,
                        javax.servlet.http.HttpServletResponse response, Object handler) {
                    record("spring-handler method=" + request.getMethod() + " uri=" + request.getRequestURI()
                            + " servletPath=" + request.getServletPath() + " pathInfo=" + request.getPathInfo()
                            + " handler=" + handler.getClass().getName());
                    return true;
                }
            });
        }
    }

    public static final class Jetty12Provider implements NisWebSocketUpgradeStrategyProvider {
        @Override public RequestUpgradeStrategy createIfSupported(Object candidate) {
            if (!(candidate instanceof JavaxWebSocketServerContainer)) return null;
            selectedStrategy = LoggedUpgradeStrategy.class;
            record("spring-provider-selected container=" + candidate.getClass().getName());
            return new LoggedUpgradeStrategy();
        }
    }

    private static final class LoggedUpgradeStrategy extends AbstractStandardUpgradeStrategy {
        @Override public String[] getSupportedVersions() { return new String[] { "13" }; }
        @Override protected void upgradeInternal(org.springframework.http.server.ServerHttpRequest request,
                org.springframework.http.server.ServerHttpResponse response, String protocol,
                List<Extension> extensions, Endpoint endpoint) throws HandshakeFailureException {
            var servletRequest = getHttpServletRequest(request);
            var servletResponse = getHttpServletResponse(response);
            var config = new ServerEndpointRegistration(servletRequest.getRequestURI(), endpoint);
            config.setSubprotocols(protocol == null || protocol.isEmpty() ? Collections.emptyList() : Collections.singletonList(protocol));
            config.setExtensions(extensions);
            ServerContainer container = getContainer(servletRequest);
            record("upgrade-callback uri=" + servletRequest.getRequestURI() + " contextPath=" + servletRequest.getContextPath()
                    + " servletPath=" + servletRequest.getServletPath() + " pathInfo=" + servletRequest.getPathInfo()
                    + " protocol=" + protocol + " container=" + container.getClass().getName());
            if (!(container instanceof JavaxWebSocketServerContainer jetty)) {
                throw new HandshakeFailureException("Unexpected container " + container.getClass().getName());
            }
            try { jetty.upgradeHttpToWebSocket(servletRequest, servletResponse, config, Collections.emptyMap()); }
            catch (Exception e) { record("upgrade-exception=" + causes(e)); throw new HandshakeFailureException("EE8 upgrade failed", e); }
        }
    }

    private static String causes(Throwable t) {
        StringBuilder result = new StringBuilder();
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (result.length() > 0) result.append(" <- ");
            result.append(current.getClass().getName()).append(':').append(current.getMessage());
        }
        return result.toString();
    }
}
