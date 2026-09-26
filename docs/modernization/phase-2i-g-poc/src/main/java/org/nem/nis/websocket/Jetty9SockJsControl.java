package org.nem.nis.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.jsr356.server.deploy.WebSocketServerContainerInitializer;
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
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.sockjs.frame.AbstractSockJsMessageCodec;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONValue;

/** Jetty 9 control using the unchanged NIS production websocket initializer. */
public final class Jetty9SockJsControl {
    public static void main(String[] args) throws Exception {
        boolean fixedCodec = args.length > 0 && "fixed-codec".equals(args[0]);
        ServletContextHandler servlet = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servlet.setContextPath("/");
        WebSocketServerContainerInitializer.configureContext(servlet);
        AnnotationConfigWebApplicationContext app = new AnnotationConfigWebApplicationContext();
        app.setServletContext(servlet.getServletContext());
        app.register(fixedCodec ? TestNisWebsocketInitializer.class : NisWebAppWebsocketInitializer.class, TestDependencies.class);
        servlet.addServlet(new ServletHolder(new DispatcherServlet(app)), "/");
        Server server = new Server(0);
        server.setHandler(servlet);
        server.start();
        int port = server.getURI().getPort();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> info = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/messages/info"))
                    .header("Origin", "http://nis-test.invalid").GET().build(), HttpResponse.BodyHandlers.ofString());
            AtomicInteger connects = new AtomicInteger();
            ((ExecutorSubscribableChannel) app.getBean("clientInboundChannel", SubscribableChannel.class)).addInterceptor(new ChannelInterceptor() {
                @Override public Message<?> preSend(Message<?> message, MessageChannel channel) {
                    if (StompHeaderAccessor.getCommand(message.getHeaders()) == org.springframework.messaging.simp.stomp.StompCommand.CONNECT) {
                        connects.incrementAndGet();
                    }
                    return message;
                }
            });
            if (info.statusCode() != 200) throw new AssertionError("SockJS /info status=" + info.statusCode());
            runSockJsClient(port, "websocket", connects, "Jetty 9 websocket");
            runSockJsClient(port, "xhr-polling", connects, "Jetty 9 xhr-polling");
            System.out.println("JETTY9_STANDARD_SOCKJS info=" + info.statusCode() + "; SpringInboundConnectTotal=" + connects.get()
                    + "; codec=" + (fixedCodec ? "test-only decodeInputStream" : "production"));
        } finally {
            server.stop();
            server.join();
            app.close();
        }
    }

    private static void runSockJsClient(int port, String transport, AtomicInteger connects, String label) throws Exception {
        int before = connects.get();
        Process process = new ProcessBuilder("node", "docs/modernization/phase-2i-h-poc/sockjs-client-probe.js",
                "http://localhost:" + port + "/messages", transport, "12000", "expect-nis-message").inheritIO().start();
        if (!process.waitFor(Duration.ofSeconds(20).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("sockjs-client timed out for " + transport);
        }
        if (process.exitValue() != 0) throw new AssertionError("sockjs-client failed for " + transport);
        System.out.println(label + " inboundConnectDelta=" + (connects.get() - before));
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

    @Configuration
    @ComponentScan("org.nem.nis.websocket")
    @EnableWebSocketMessageBroker
    static class TestNisWebsocketInitializer extends NisWebAppWebsocketInitializer {
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/messages").setAllowedOriginPatterns("*").withSockJS()
                    .setMessageCodec(new AbstractSockJsMessageCodec() {
                        @Override public String[] decode(String value) {
                            return new String[] { (String) ((JSONArray) JSONValue.parse(value)).get(0) };
                        }
                        @Override public String[] decodeInputStream(java.io.InputStream input) throws java.io.IOException {
                            return decode(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                        }
                        @Override protected char[] applyJsonQuoting(String value) { return JSONValue.escape(value).toCharArray(); }
                    });
        }
    }
}
