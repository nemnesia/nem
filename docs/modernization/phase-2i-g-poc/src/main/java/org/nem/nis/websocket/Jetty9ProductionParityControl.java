package org.nem.nis.websocket;

import java.io.IOException;
import java.util.EnumSet;
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
import javax.websocket.server.ServerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.servlet.ServletMapping;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
import org.nem.deploy.server.AbstractNemServletContextListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.web.context.ContextLoaderListener;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;

/** Test-only Jetty 9 runtime with the NIS production listener/filter/servlet registration path. */
public final class Jetty9ProductionParityControl {
    private static final java.util.List<String> EVENTS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile AnnotationConfigWebApplicationContext childContext;

    public static void main(String[] args) throws Exception {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.register(Jetty9SockJsControl.TestDependencies.class);
        parent.refresh();

        ServletContextHandler servlet = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servlet.setContextPath("/");
        WebSocketServerContainerInitializer.configureContext(servlet);
        servlet.addEventListener(new ProductionListener(parent));
        servlet.addEventListener(new ContextLoaderListener());
        servlet.addEventListener(new LifecycleObserver());

        Server server = new Server(new QueuedThreadPool(200, 8));
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(servlet);
        server.start();
        int port = connector.getLocalPort();
        try {
            record("jetty-websocket-initializer container=" + container(servlet.getServletContext()));
            logRegistrations(servlet, "after-start");
            AtomicInteger connects = new AtomicInteger();
            ((ExecutorSubscribableChannel) childContext.getBean("clientInboundChannel", SubscribableChannel.class))
                    .addInterceptor(new ChannelInterceptor() {
                        @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                            if (StompCommand.CONNECT == StompHeaderAccessor.getCommand(message.getHeaders())) connects.incrementAndGet();
                            return message;
                        }
                    });
            System.out.println("PRODUCTION_EQUIVALENT_JETTY9 port=" + port + " container="
                    + container(servlet.getServletContext()) + " endpoint=/w/messages");
            ReadinessProbe.httpSnapshot(server, childContext, "jetty9-async-enabled-baseline");
            for (int i = 1; i <= 2; i++) {
                runClient(port, "websocket", i);
                runClient(port, "xhr-polling", i);
                ReadinessProbe.httpSnapshot(server, childContext, "jetty9-smoke-cycle-" + i);
            }
            if (args.length > 0 && "lifecycle".equals(args[0])) {
                runNormalBatch(port, "websocket", 100, connects, server, "jetty9-websocket-normal-100");
                runNormalBatch(port, "xhr-polling", 10, connects, server, "jetty9-xhr-normal-10-additional");
                runAbnormalBatch(port, "websocket", 100, connects, server, "jetty9-websocket-abrupt-100");
                awaitCleanup(server, "jetty9-websocket-abrupt-100", 20);
                for (int batch = 1; batch <= 3; batch++) {
                    String label = "jetty9-xhr-abandoned-100-batch-" + batch;
                    runAbnormalBatch(port, "xhr-polling", 100, connects, server, label);
                    awaitCleanup(server, label, 70);
                }
                ReadinessProbe.checkpoints(server, childContext, "jetty9-post-lifecycle-idle", 30, 60, 90, 120);
            } else {
                awaitCleanup(server, "jetty9-smoke", 20);
            }
            System.out.println("JETTY9_FINAL sessions=" + ReadinessProbe.sessions(childContext)
                    + "; connects=" + connects.get() + "; callbacks=" + ReadinessProbe.callbackSummary());
        } finally {
            server.stop();
            server.join();
            parent.close();
        }
        System.out.println("=== JETTY9 PRODUCTION PARITY EVENTS ===");
        EVENTS.forEach(System.out::println);
    }

    private static void runClient(int port, String transport, int iteration) throws Exception {
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/w/messages", transport, "12000", "expect-nis-message", "normal")
                .redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("client timeout transport=" + transport + " output=" + new String(output));
        }
        if (process.exitValue() != 0) throw new AssertionError("client failed transport=" + transport + " output=" + new String(output));
        System.out.println("JETTY9_CLIENT iteration=" + iteration + " transport=" + transport + " output=" + new String(output).trim());
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
        awaitCleanup(server, label, 20);
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
        System.out.println(label + " sampler=" + sampler.summary() + "; callbacks=" + ReadinessProbe.callbackSummary()
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
                System.out.println(label + " cleanupElapsedMs=" + elapsedMs);
                return;
            }
            Thread.sleep(250);
        }
        if (ReadinessProbe.sessionCount(childContext) != 0) throw new AssertionError(label + " retained " + ReadinessProbe.sessions(childContext));
        System.out.println(label + " cleanupElapsedMs=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    private static void logRegistrations(ServletContextHandler context, String when) {
        ServletHandler handler = context.getServletHandler();
        for (ServletHolder holder : handler.getServlets()) record("servlet when=" + when + " name=" + holder.getName()
                + " class=" + holder.getClassName() + " asyncSupported=" + holder.isAsyncSupported());
        for (ServletMapping mapping : handler.getServletMappings()) record("servlet-mapping servlet=" + mapping.getServletName()
                + " paths=" + java.util.Arrays.toString(mapping.getPathSpecs()));
        for (FilterHolder holder : handler.getFilters()) record("filter when=" + when + " name=" + holder.getName()
                + " class=" + holder.getClassName() + " asyncSupported=" + holder.isAsyncSupported());
        for (var mapping : handler.getFilterMappings()) record("filter-mapping filter=" + mapping.getFilterName()
                + " paths=" + java.util.Arrays.toString(mapping.getPathSpecs()) + " dispatchers=" + mapping.getDispatcherTypes());
    }

    private static String container(ServletContext context) {
        Object value = context.getAttribute(ServerContainer.class.getName());
        return value == null ? "<absent>" : value.getClass().getName();
    }

    private static void record(String value) { EVENTS.add(value); }

    private static final class ProductionListener extends AbstractNemServletContextListener {
        ProductionListener(AnnotationConfigApplicationContext parent) {
            super(parent, Jetty9SockJsControl.InstrumentedNisWebsocketInitializer.class, true);
        }
        @Override public void contextInitialized(ServletContextEvent event) {
            record("production-listener-enter container=" + container(event.getServletContext()));
            super.contextInitialized(event);
            var trace = event.getServletContext().addFilter("Phase2IQRequestTrace", new RequestTraceFilter());
            trace.setAsyncSupported(true);
            trace.addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), true, "/*");
            record("production-listener-exit servlets=" + event.getServletContext().getServletRegistrations().keySet()
                    + " filters=" + event.getServletContext().getFilterRegistrations().keySet());
        }
        @Override protected void initialize(AnnotationConfigWebApplicationContext webCtx, ServletContext context) {
            childContext = webCtx;
            webCtx.register(Diagnostics.class);
            var dispatcher = context.addServlet("Spring Websocket Dispatcher Servlet", new DispatcherServlet(webCtx));
            dispatcher.addMapping("/w/*");
            dispatcher.setAsyncSupported(true);
            dispatcher.setLoadOnStartup(1);
        }
    }

    private static final class LifecycleObserver implements javax.servlet.ServletContextListener {
        @Override public void contextInitialized(ServletContextEvent event) {
            record("context-loader-returned root=" + event.getServletContext().getAttribute(
                    "org.springframework.web.context.WebApplicationContext.ROOT") + " container=" + container(event.getServletContext()));
        }
        @Override public void contextDestroyed(ServletContextEvent event) { }
    }

    private static final class RequestTraceFilter implements Filter {
        @Override public void init(FilterConfig config) { }
        @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest http = (HttpServletRequest) request;
            record("request-enter method=" + http.getMethod() + " uri=" + http.getRequestURI()
                    + " contextPath=" + http.getContextPath() + " servletPath=" + http.getServletPath()
                    + " pathInfo=" + http.getPathInfo() + " asyncSupported=" + request.isAsyncSupported()
                    + " asyncStarted=" + request.isAsyncStarted() + " servlet=" + http.getHttpServletMapping().getServletName());
            try {
                chain.doFilter(request, response);
                record("request-exit uri=" + http.getRequestURI() + " status=" + ((HttpServletResponse) response).getStatus()
                        + " asyncSupported=" + request.isAsyncSupported() + " asyncStarted=" + request.isAsyncStarted());
            } catch (Throwable t) {
                record("request-exception uri=" + http.getRequestURI() + " exception=" + t);
                if (t instanceof IOException) throw (IOException) t;
                if (t instanceof ServletException) throw (ServletException) t;
                if (t instanceof RuntimeException) throw (RuntimeException) t;
                throw new ServletException(t);
            }
        }
        @Override public void destroy() { }
    }

    @Configuration
    static class Diagnostics {
        @Bean MappedInterceptor phase2iQHandlerTrace() {
            return new MappedInterceptor(new String[] { "/**" }, new HandlerInterceptor() {
                @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    record("spring-handler uri=" + request.getRequestURI() + " servletPath=" + request.getServletPath()
                            + " pathInfo=" + request.getPathInfo() + " handler=" + handler.getClass().getName());
                    return true;
                }
            });
        }
    }
}
