package org.nem.experiments;

import java.util.Collections;
import java.util.List;

import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;

import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;

/** Isolated compatibility probe. It is not production NEM code. */
public final class Jetty12Ee8UpgradeStrategy extends AbstractStandardUpgradeStrategy {
    @Override
    public String[] getSupportedVersions() {
        return new String[] { "13" };
    }

    @Override
    protected void upgradeInternal(ServerHttpRequest request, ServerHttpResponse response, String selectedProtocol,
            List<Extension> selectedExtensions, Endpoint endpoint) throws HandshakeFailureException {
        var servletRequest = getHttpServletRequest(request);
        var servletResponse = getHttpServletResponse(response);
        var config = new ServerEndpointRegistration(servletRequest.getRequestURI(), endpoint);
        config.setSubprotocols(selectedProtocol == null || selectedProtocol.isEmpty()
                ? Collections.emptyList() : Collections.singletonList(selectedProtocol));
        config.setExtensions(selectedExtensions);
        ServerContainer standardContainer = getContainer(servletRequest);
        System.out.println("UPGRADE_STRATEGY_ENTERED container=" + standardContainer.getClass().getName()
                + " path=" + servletRequest.getRequestURI());
        if (!(standardContainer instanceof JavaxWebSocketServerContainer jettyContainer)) {
            throw new HandshakeFailureException("Expected Jetty 12 EE8 JavaxWebSocketServerContainer, got "
                    + standardContainer.getClass().getName());
        }
        try {
            jettyContainer.upgradeHttpToWebSocket(servletRequest, servletResponse, config, Collections.emptyMap());
        } catch (Exception e) {
            throw new HandshakeFailureException("Jetty 12 EE8 WebSocket upgrade failed", e);
        }
    }
}
