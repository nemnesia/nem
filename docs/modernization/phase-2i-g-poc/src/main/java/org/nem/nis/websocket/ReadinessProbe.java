package org.nem.nis.websocket;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;
import org.springframework.web.socket.sockjs.support.SockJsHttpRequestHandler;
import org.springframework.web.socket.sockjs.transport.SockJsSession;
import org.springframework.web.socket.sockjs.transport.TransportHandlingSockJsService;
import org.springframework.web.socket.sockjs.transport.session.AbstractSockJsSession;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Test-only Spring/Jetty lifecycle and raw handshake instrumentation. */
final class ReadinessProbe {
    private ReadinessProbe() { }

    static String sessions(AnnotationConfigWebApplicationContext app) throws Exception {
        TransportHandlingSockJsService service = sockJsService(app);
        Field sessionsField = TransportHandlingSockJsService.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, SockJsSession> sessions = (Map<String, SockJsSession>) sessionsField.get(service);
        Map<String, Integer> byType = new LinkedHashMap<>();
        int open = 0;
        int activeTransport = 0;
        int closed = 0;
        long maxIdleMs = 0;
        for (SockJsSession session : sessions.values()) {
            byType.merge(session.getClass().getSimpleName(), 1, Integer::sum);
            if (session.isOpen()) open++;
            if (((AbstractSockJsSession) session).isActive()) activeTransport++;
            if (((AbstractSockJsSession) session).isClosed()) closed++;
            maxIdleMs = Math.max(maxIdleMs, session.getTimeSinceLastActive());
        }
        return "sockJsSessions=" + sessions.size() + "; open=" + open + "; activeTransport=" + activeTransport
                + "; closed=" + closed + "; byType=" + byType
                + "; maxIdleMs=" + maxIdleMs + "; disconnectDelayMs=" + service.getDisconnectDelay();
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
                    + "/" + pool.getIdleThreads() + "/" + pool.getQueueSize();
        }
        return "jettyPool=" + server.getThreadPool().getClass().getName();
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
