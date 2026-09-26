package org.nem.nis.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.sockjs.frame.AbstractSockJsMessageCodec;

/** Jetty 12 EE8 XHR control; broker/converter/components are real NIS configuration. */
public final class Jetty12SockJsControl {
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
            ServerContainer container = (ServerContainer) servlet.getServletContext().getAttribute(ServerContainer.class.getName());
            if (!(container instanceof JavaxWebSocketServerContainer)) throw new AssertionError("Unexpected EE8 container " + container);
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
            String base = "http://localhost:" + port + "/messages/000/nis-g-control";
            HttpResponse<String> open = post(client, base + "/xhr", "", "application/javascript");
            String connect = "[\"CONNECT\\naccept-version:1.2\\nheart-beat:0,0\\n\\n\\u0000\"]";
            HttpResponse<String> send = post(client, base + "/xhr_send", connect, "application/json;charset=UTF-8");
            HttpResponse<String> poll = post(client, base + "/xhr", "", "application/javascript");
            System.out.println("JETTY12_SOCKJS_CONTROL info=" + info.statusCode() + "; open=" + open.statusCode()
                    + " body=" + printable(open.body()) + "; xhr_send=" + send.statusCode()
                    + "; poll=" + poll.statusCode() + " body=" + printable(poll.body())
                    + "; SpringInboundConnect=" + connects.get());
            if (info.statusCode() != 200 || open.statusCode() != 200 || send.statusCode() != 204 || poll.statusCode() != 200) {
                throw new AssertionError("Unexpected HTTP status in Jetty 12 SockJS control");
            }
        } finally {
            server.stop();
            server.join();
            app.close();
        }
    }

    private static HttpResponse<String> post(HttpClient client, String uri, String body, String contentType) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).header("Origin", "http://nis-test.invalid")
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private static String printable(String body) { return body.replace("\n", "\\n").replace("\r", "\\r"); }

    @Configuration
    @ComponentScan("org.nem.nis.websocket")
    @EnableWebSocketMessageBroker
    static class TestNisWebsocketInitializer extends NisWebAppWebsocketInitializer {
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/messages").setAllowedOriginPatterns("*")
                    .setHandshakeHandler(new DefaultHandshakeHandler(new Jetty12Ee8UpgradeStrategy())).withSockJS()
                    .setMessageCodec(new AbstractSockJsMessageCodec() {
                        @Override public String[] decode(String value) { return new String[] { (String) ((JSONArray) JSONValue.parse(value)).get(0) }; }
                        @Override public String[] decodeInputStream(java.io.InputStream input) throws IOException { return new String[0]; }
                        @Override protected char[] applyJsonQuoting(String value) { return JSONValue.escape(value).toCharArray(); }
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
            catch (Exception e) { throw new HandshakeFailureException("Jetty EE8 upgrade failed", e); }
        }
    }
}
