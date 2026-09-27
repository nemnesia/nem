package org.nem.nis.websocket;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.ServletRegistration;
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
    private static volatile boolean correctedAsync;

    public static void main(String[] args) throws Exception {
        correctedAsync = args.length > 0 && "corrected".equals(args[0]);
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
            record("server-started mode=" + (correctedAsync ? "corrected-async" : "production-equivalent")
                    + " port=" + port + " container=" + container(servlet.getServletContext())
                    + " strategy=" + selectedStrategy);
            var info = ReadinessProbe.httpGet(port, "/w/messages/info", "http://nis-test.invalid");
            record("info method=GET url=/w/messages/info status=" + info.status() + " body=" + info.body());
            var missingPrefixInfo = ReadinessProbe.httpGet(port, "/messages/info", "http://nis-test.invalid");
            record("wrong-prefix method=GET url=/messages/info status=" + missingPrefixInfo.status()
                    + " body=" + missingPrefixInfo.body());
            record("wrong-prefix-websocket=" + ReadinessProbe.rawHandshake(port,
                    "/messages/000/phase2i-o-wrong-prefix/websocket", null, "valid"));
            runClient(port, "websocket");
            runClient(port, "xhr-polling");
            Thread.sleep(10_000);
            record("after-cleanup " + ReadinessProbe.sessions(childContext));
        } finally {
            server.stop();
            server.join();
            parent.close();
        }
        System.out.println("=== PHASE2I-O EVENTS ===");
        EVENTS.forEach(System.out::println);
    }

    private static void runClient(int port, String transport) throws Exception {
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
        record("client transport=" + transport + " exit=" + process.exitValue() + " output=" + new String(output).trim());
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
            super(parent, NisWebAppWebsocketInitializer.class, true);
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
            if (correctedAsync) {
                for (var registration : context.getFilterRegistrations().values()) {
                    ((javax.servlet.FilterRegistration.Dynamic) registration).setAsyncSupported(true);
                }
                ((ServletRegistration.Dynamic) context.getServletRegistration("Spring Websocket Dispatcher Servlet")).setAsyncSupported(true);
                record("diagnostic-correction enabled async support on every filter and DispatcherServlet");
            }
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
            if (correctedAsync) dispatcher.setAsyncSupported(true);
            record("dispatcher-registered name=Spring Websocket Dispatcher Servlet mapping=/w/* asyncSupported="
                    + correctedAsync);
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
                    + " contextPath=" + http.getContextPath() + " servletPath=" + http.getServletPath()
                    + " pathInfo=" + http.getPathInfo() + " asyncSupported=" + request.isAsyncSupported()
                    + " asyncStarted=" + request.isAsyncStarted());
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
