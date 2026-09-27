package org.nem.nis.websocket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ScheduledFuture;
import javax.servlet.AsyncContext;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.SockJsSession;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;
import org.springframework.web.socket.sockjs.transport.session.AbstractSockJsSession;
import org.springframework.web.socket.sockjs.transport.session.AbstractHttpSockJsSession;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Test-only Spring/Jetty lifecycle and raw handshake instrumentation. */
final class ReadinessProbe {
    private static final Map<String, List<String>> CALLBACKS = new ConcurrentHashMap<>();
    private static final AtomicInteger TRANSPORT_ERROR_CALLBACKS = new AtomicInteger();
    private static final Set<Long> PREVIOUS_QTP_IDS = new HashSet<>();

    private ReadinessProbe() { }

    static String sessions(AnnotationConfigWebApplicationContext app) throws Exception {
        TransportHandlingSockJsService service = sockJsService(app);
        Map<String, SockJsSession> sessions = sessionMap(service);
        Map<String, Integer> byType = new LinkedHashMap<>();
        int open = 0;
        int activeTransport = 0;
        int closed = 0;
        long maxIdleMs = 0;
        List<String> samples = new ArrayList<>();
        for (SockJsSession session : sessions.values()) {
            byType.merge(session.getClass().getSimpleName(), 1, Integer::sum);
            if (session.isOpen()) open++;
            if (((AbstractSockJsSession) session).isActive()) activeTransport++;
            if (((AbstractSockJsSession) session).isClosed()) closed++;
            maxIdleMs = Math.max(maxIdleMs, session.getTimeSinceLastActive());
            if (samples.size() < 3) samples.add(sessionDetails(session));
        }
        Field cleanupField = TransportHandlingSockJsService.class.getDeclaredField("sessionCleanupTask");
        cleanupField.setAccessible(true);
        ScheduledFuture<?> cleanup = (ScheduledFuture<?>) cleanupField.get(service);
        return "sockJsSessions=" + sessions.size() + "; open=" + open + "; activeTransport=" + activeTransport
                + "; closed=" + closed + "; byType=" + byType
                + "; maxIdleMs=" + maxIdleMs + "; disconnectDelayMs=" + service.getDisconnectDelay()
                + "; heartbeatTimeMs=" + service.getHeartbeatTime()
                + "; cleanupTask=" + (null == cleanup ? "<not scheduled>" : "cancelled=" + cleanup.isCancelled()
                    + ",done=" + cleanup.isDone() + ",nextRunMs=" + cleanup.getDelay(java.util.concurrent.TimeUnit.MILLISECONDS))
                + "; samples=" + samples;
    }

    static int sessionCount(AnnotationConfigWebApplicationContext app) throws Exception {
        return sessionMap(sockJsService(app)).size();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, SockJsSession> sessionMap(TransportHandlingSockJsService service) throws ReflectiveOperationException {
        Field sessionsField = TransportHandlingSockJsService.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        return (Map<String, SockJsSession>) sessionsField.get(service);
    }

    private static String sessionDetails(SockJsSession session) throws ReflectiveOperationException {
        if (!(session instanceof AbstractSockJsSession abstractSession)) {
            return "{id=" + session.getId() + ",type=" + session.getClass().getName() + ",open=" + session.isOpen() + "}";
        }
        Class<?> base = AbstractSockJsSession.class;
        Object state = field(base, "state").get(session);
        Object created = field(base, "timeCreated").get(session);
        Object lastActive = field(base, "timeLastActive").get(session);
        Object async = session instanceof AbstractHttpSockJsSession
                ? field(session.getClass(), "asyncRequestControl").get(session) : null;
        String asyncState = "none";
        if (null != async) {
            Object context = field(async.getClass(), "asyncContext").get(async);
            Object timeout = null == context ? "not-created" : ((AsyncContext) context).getTimeout();
            asyncState = async.getClass().getSimpleName() + "(started=" + async.getClass().getMethod("isStarted").invoke(async)
                    + ",completed=" + async.getClass().getMethod("isCompleted").invoke(async) + ",timeoutMs=" + timeout + ")";
        }
        return "{id=" + session.getId() + ",state=" + state + ",created=" + created + ",lastActive=" + lastActive
                + ",idleMs=" + session.getTimeSinceLastActive() + ",active=" + abstractSession.isActive()
                + ",async=" + asyncState + "}";
    }

    static WebSocketHandlerDecoratorFactory callbackRecorder() {
        return delegate -> new WebSocketHandlerDecorator(delegate) {
            @Override public void handleTransportError(WebSocketSession session, Throwable error) throws Exception {
                TRANSPORT_ERROR_CALLBACKS.incrementAndGet();
                record(session, "transportError:" + throwable(error));
                super.handleTransportError(session, error);
            }

            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                record(session, "closed:" + status.getCode() + ":" + status.getReason());
                super.afterConnectionClosed(session, status);
            }
        };
    }

    private static void record(WebSocketSession session, String event) {
        CALLBACKS.computeIfAbsent(session.getId(), id -> new CopyOnWriteArrayList<>()).add(event);
    }

    private static String throwable(Throwable error) {
        Throwable root = error;
        while (null != root.getCause() && root.getCause() != root) root = root.getCause();
        return error.getClass().getName() + ":" + error.getMessage() + ";root=" + root.getClass().getName() + ":" + root.getMessage();
    }

    static void resetCallbacks() {
        CALLBACKS.clear();
        TRANSPORT_ERROR_CALLBACKS.set(0);
    }

    static String callbackSummary() {
        List<String> examples = CALLBACKS.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(v -> v.startsWith("transportError")))
                .limit(3).map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
        long closed = CALLBACKS.values().stream().filter(events -> events.stream().anyMatch(v -> v.startsWith("closed:"))).count();
        List<String> closeExamples = CALLBACKS.entrySet().stream().filter(entry -> entry.getValue().stream().anyMatch(v -> v.startsWith("closed:")))
                .limit(3).map(entry -> entry.getKey() + "=" + entry.getValue()).toList();
        return "handlerTransportErrorCallbacks=" + TRANSPORT_ERROR_CALLBACKS.get() + "; sessionsWithCallbacks=" + CALLBACKS.size()
                + "; afterConnectionClosedCallbacks=" + closed + "; errorExamples=" + examples + "; closeExamples=" + closeExamples;
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> current = owner; null != current; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name + " on " + owner.getName() + " hierarchy");
    }

    static String stats(AnnotationConfigWebApplicationContext app) {
        WebSocketMessageBrokerStats brokerStats = app.getBean(WebSocketMessageBrokerStats.class);
        SubProtocolWebSocketHandler handler = app.getBeansOfType(SubProtocolWebSocketHandler.class).values().iterator().next();
        try {
            Field statsField = SubProtocolWebSocketHandler.class.getDeclaredField("stats");
            statsField.setAccessible(true);
            Object stats = statsField.get(handler);
            return "springStats=" + brokerStats.getWebSocketSessionStatsInfo()
                    + "; protocolSessions(total/ws/httpStream/httpPoll)=" + call(stats, "getTotalSessions") + "/"
                    + call(stats, "getWebSocketSessions") + "/" + call(stats, "getHttpStreamingSessions") + "/"
                    + call(stats, "getHttpPollingSessions") + "; transportErrors=" + call(stats, "getTransportErrorSessions");
        } catch (ReflectiveOperationException ex) {
            return "springStats=" + brokerStats.getWebSocketSessionStatsInfo() + "; protocolStatsUnavailable=" + ex;
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        var method = target.getClass().getMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    static String jettyPool(org.eclipse.jetty.server.Server server) {
        if (server.getThreadPool() instanceof QueuedThreadPool pool) {
            return "jettyPool(threads/busy/idle/queue)=" + pool.getThreads() + "/" + pool.getBusyThreads()
                    + "/" + pool.getIdleThreads() + "/" + pool.getQueueSize()
                    + "; min=" + invoke(pool, "getMinThreads") + "; max=" + invoke(pool, "getMaxThreads")
                    + "; idleTimeout=" + invoke(pool, "getIdleTimeout") + "; reserved=" + invoke(pool, "getReservedThreads")
                    + "; maxReserved=" + invoke(pool, "getMaxReservedThreads")
                    + "; ready=" + invoke(pool, "getReadyThreads") + "; leased=" + invoke(pool, "getLeasedThreads")
                    + "; utilized=" + invoke(pool, "getUtilizedThreads") + "; availableReserved=" + invoke(pool, "getAvailableReservedThreads")
                    + "; currentReserved=" + invoke(pool, "getCurrentReservedThreads")
                    + "; maxEvictCount=" + invoke(pool, "getMaxEvictCount")
                    + "; lowThreadsThreshold=" + invoke(pool, "getLowThreadsThreshold") + "; pool=" + pool;
        }
        return "jettyPool=" + server.getThreadPool().getClass().getName();
    }

    static String threadSummary() {
        Map<String, Integer> groups = new LinkedHashMap<>();
        Map<String, Integer> jettyTopFrames = new LinkedHashMap<>();
        Map<Thread.State, Integer> states = new LinkedHashMap<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            String name = thread.getName();
            String group = name.startsWith("qtp") ? "jetty-qtp" : name.toLowerCase().contains("scheduler")
                    || name.toLowerCase().contains("task") ? "scheduler/task" : name.startsWith("HttpClient")
                    || name.startsWith("java-http") ? "http-client" : "other";
            groups.merge(group, 1, Integer::sum);
            states.merge(thread.getState(), 1, Integer::sum);
            if (name.startsWith("qtp")) {
                StackTraceElement[] trace = thread.getStackTrace();
                String top = trace.length == 0 ? "<no-stack>" : trace[0].getClassName() + "." + trace[0].getMethodName();
                jettyTopFrames.merge(thread.getState() + ":" + top, 1, Integer::sum);
            }
        }
        return "jvmThreadGroups=" + groups + "; states=" + states + "; jettyWorkerTopFrames=" + jettyTopFrames;
    }

    static synchronized String qtpIdentitySnapshot(org.eclipse.jetty.server.Server server) {
        String prefix = server.getThreadPool() instanceof QueuedThreadPool pool ? pool.getName() : "qtp";
        Map<String, Integer> frameCounts = new LinkedHashMap<>();
        List<String> identities = new ArrayList<>();
        int all = 0, qtp = 0, selectors = 0, acceptors = 0, reserved = 0, workers = 0;
        Set<Long> ids = new HashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            all++;
            if (!thread.getName().startsWith(prefix)) continue;
            qtp++;
            ids.add(thread.getId());
            StackTraceElement[] stack = thread.getStackTrace();
            String stackText = java.util.Arrays.toString(stack);
            String kind = stackText.contains("ManagedSelector") || stackText.contains("SelectorProducer") ? "selector"
                    : stackText.contains("Acceptor") || stackText.contains("ServerConnector.accept") ? "acceptor"
                    : stackText.contains("ReservedThreadExecutor") || stackText.contains("reservedWait") ? "reserved"
                    : "worker";
            switch (kind) { case "selector" -> selectors++; case "acceptor" -> acceptors++; case "reserved" -> reserved++; default -> workers++; }
            String top = stack.length == 0 ? "<no-stack>" : stack[0].getClassName() + "." + stack[0].getMethodName();
            frameCounts.merge(kind + ":" + thread.getState() + ":" + top, 1, Integer::sum);
            if (identities.size() < 12) {
                String frame = stack.length == 0 ? "<no-stack>" : stack[0].toString();
                identities.add(thread.getName() + "#" + thread.getId() + ":" + thread.getState() + ":" + kind + ":" + frame);
            }
        }
        Set<Long> retained = new HashSet<>(ids);
        retained.retainAll(PREVIOUS_QTP_IDS);
        Set<Long> added = new HashSet<>(ids);
        added.removeAll(PREVIOUS_QTP_IDS);
        Set<Long> removed = new HashSet<>(PREVIOUS_QTP_IDS);
        removed.removeAll(ids);
        PREVIOUS_QTP_IDS.clear();
        PREVIOUS_QTP_IDS.addAll(ids);
        return "threadIdentity(allJvm=" + all + ",qtp=" + qtp + ",selector=" + selectors + ",acceptor=" + acceptors
                + ",reserved=" + reserved + ",otherWorkers=" + workers + ",qtpIds=" + ids.size()
                + ",retained/added/removed=" + retained.size() + "/" + added.size() + "/" + removed.size()
                + "); qtpTopFrames=" + frameCounts + "; qtpExamples=" + identities;
    }

    static void httpSnapshot(org.eclipse.jetty.server.Server server, AnnotationConfigWebApplicationContext app, String phase) throws Exception {
        System.out.println("QTP_CHECKPOINT " + phase + "; " + jettyPool(server) + "; " + qtpIdentitySnapshot(server)
                + "; " + threadSummary() + "; " + sessions(app) + "; " + stats(app));
    }

    static void httpRequests(int port, int count, int concurrency, org.eclipse.jetty.server.Server server) throws Exception {
        QtpSampler sampler = new QtpSampler(server);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency, runnable -> {
            Thread thread = new Thread(runnable, "phase2i-l-http-client");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<Integer>> requests = new ArrayList<>();
            for (int i = 0; i < count; i++) requests.add(executor.submit(() -> {
                HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/phase2i-l-ping?delay=100").openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(5000);
                try {
                    int status = connection.getResponseCode();
                    try (var stream = connection.getInputStream()) { stream.readAllBytes(); }
                    return status;
                } finally { connection.disconnect(); }
            }));
            for (Future<Integer> request : requests) if (200 != request.get(15, TimeUnit.SECONDS)) throw new AssertionError("HTTP probe status != 200");
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
            sampler.close();
            System.out.println("HTTP_LOAD count=" + count + "; concurrency=" + concurrency + "; " + sampler.summary());
        }
    }

    static final class QtpSampler implements AutoCloseable {
        private final org.eclipse.jetty.server.Server server;
        private final java.util.concurrent.atomic.AtomicBoolean sampling = new java.util.concurrent.atomic.AtomicBoolean(true);
        private final java.util.concurrent.atomic.AtomicInteger peakThreads = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peakBusy = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peakQueue = new java.util.concurrent.atomic.AtomicInteger();
        private final Thread thread;

        QtpSampler(org.eclipse.jetty.server.Server server) {
            this.server = server;
            this.thread = new Thread(this::sample, "phase2i-l-qtp-sampler");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void sample() {
            while (sampling.get()) {
                if (server.getThreadPool() instanceof QueuedThreadPool pool) {
                    peakThreads.accumulateAndGet(pool.getThreads(), Math::max);
                    peakBusy.accumulateAndGet(pool.getBusyThreads(), Math::max);
                    peakQueue.accumulateAndGet(pool.getQueueSize(), Math::max);
                }
                try { Thread.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); return; }
            }
        }

        String summary() { return "peakQtpThreads=" + peakThreads.get() + "; peakBusy=" + peakBusy.get() + "; peakQueue=" + peakQueue.get(); }

        @Override public void close() throws InterruptedException {
            sampling.set(false);
            thread.join(1000);
        }
    }

    static HttpResult httpGet(int port, String path, String origin) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL("http://127.0.0.1:" + port + path).openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(5000);
        if (null != origin) connection.setRequestProperty("Origin", origin);
        try {
            int status = connection.getResponseCode();
            var stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
            String body = null == stream ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new HttpResult(status, connection.getHeaderField("Access-Control-Allow-Origin"), body);
        } finally { connection.disconnect(); }
    }

    record HttpResult(int status, String allowOrigin, String body) { }

    static void checkpoints(org.eclipse.jetty.server.Server server, AnnotationConfigWebApplicationContext app,
            String prefix, int... seconds) throws Exception {
        long start = System.nanoTime();
        for (int second : seconds) {
            long target = start + TimeUnit.SECONDS.toNanos(second);
            long wait = target - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
            httpSnapshot(server, app, prefix + "@" + second + "s");
        }
    }

    private static Object invoke(Object target, String method) {
        try { return target.getClass().getMethod(method).invoke(target); }
        catch (ReflectiveOperationException ex) { return "unavailable"; }
    }

    static Map<String, String> rawHandshake(int port, String path, String origin, String variant) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            var out = socket.getOutputStream();
            StringBuilder request = new StringBuilder("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: localhost:").append(port).append("\r\n")
                    .append("missing-upgrade".equals(variant) ? "" : "Connection: Upgrade\r\nUpgrade: websocket\r\n");
            if (!"missing-key".equals(variant)) request.append("Sec-WebSocket-Key: ")
                    .append("invalid-key".equals(variant) ? "not-base64" : "dGhlIHNhbXBsZSBub25jZQ==").append("\r\n");
            request.append("Sec-WebSocket-Version: ").append("bad-version".equals(variant) ? "12" : "13").append("\r\n");
            if (null != origin) request.append("Origin: ").append(origin).append("\r\n");
            request.append("\r\n");
            out.write(request.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            String status = reader.readLine();
            Map<String, String> headers = new LinkedHashMap<>();
            for (String line; null != (line = reader.readLine()) && !line.isEmpty();) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(), line.substring(colon + 1).trim());
            }
            return Map.of("status", String.valueOf(status), "headers", headers.toString());
        }
    }

    private static TransportHandlingSockJsService sockJsService(AnnotationConfigWebApplicationContext app) {
        for (AbstractUrlHandlerMapping mapping : app.getBeansOfType(AbstractUrlHandlerMapping.class).values()) {
            for (Object handler : mapping.getHandlerMap().values()) {
                if (handler instanceof SockJsHttpRequestHandler sockJsHandler
                        && sockJsHandler.getSockJsService() instanceof TransportHandlingSockJsService service) return service;
            }
        }
        throw new IllegalStateException("Could not find NIS SockJsHttpRequestHandler");
    }
}
