package poc;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.websocket.jakarta.server.config.JakartaWebSocketServletContainerInitializer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;

public class EmbeddedSpring7Ee11 {
    @Configuration
    @EnableWebMvc
    @EnableWebSocketMessageBroker
    static class WebConfig implements WebSocketMessageBrokerConfigurer {
        @RestController
        static class ProbeController {
            @GetMapping("/probe")
            String probe() { return "spring7-ee11-ok"; }
        }
        @Override public void configureMessageBroker(MessageBrokerRegistry registry) {
            registry.enableSimpleBroker("/topic");
        }
        @Override public void registerStompEndpoints(StompEndpointRegistry registry) {
            registry.addEndpoint("/messages").withSockJS();
        }
    }

    public static void main(String[] args) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addEventListener(new ServletContextListener() {
            @Override public void contextInitialized(ServletContextEvent event) {
                event.getServletContext().setAttribute("poc.listener.initialized", Boolean.TRUE);
            }
        });
        context.addFilter((Filter)(ServletRequest request, ServletResponse response, FilterChain chain) -> {
            ((HttpServletResponse)response).setHeader("X-POC-Filter", "registered");
            chain.doFilter(request, response);
        }, "/*", EnumSet.of(DispatcherType.REQUEST));
        JakartaWebSocketServletContainerInitializer.configure(context, null);
        server.setHandler(context);
        AnnotationConfigWebApplicationContext spring = new AnnotationConfigWebApplicationContext();
        spring.register(WebConfig.class);
        ServletHolder dispatcher = new ServletHolder("spring-dispatcher", new DispatcherServlet(spring));
        dispatcher.setAsyncSupported(true);
        context.addServlet(dispatcher, "/w/*");
        server.start();
        int port = connector.getLocalPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> mvc = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/w/probe")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> sockJsInfo = client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/w/messages/info")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        System.out.println("POC_MVC=" + mvc.statusCode() + " " + mvc.body() + " filter=" + mvc.headers().firstValue("X-POC-Filter").orElse("absent"));
        System.out.println("POC_SOCKJS_INFO=" + sockJsInfo.statusCode() + " " + sockJsInfo.body());
        Object wsContainer = context.getServletContext().getAttribute("jakarta.websocket.server.ServerContainer");
        System.out.println("POC_JAKARTA_SERVER_CONTAINER=" + (null == wsContainer ? "absent" : wsContainer.getClass().getName()));
        Object listener = context.getServletContext().getAttribute("poc.listener.initialized");
        System.out.println("POC_JAKARTA_LISTENER=" + listener);
        if (mvc.statusCode() != 200 || !"spring7-ee11-ok".equals(mvc.body()) || !"registered".equals(mvc.headers().firstValue("X-POC-Filter").orElse("")) || sockJsInfo.statusCode() != 200 || null == wsContainer || !Boolean.TRUE.equals(listener)) {
            throw new AssertionError("Spring MVC/SockJS/EE11 initialization failed");
        }
        spring.close();
        server.stop();
        server.join();
    }
}
