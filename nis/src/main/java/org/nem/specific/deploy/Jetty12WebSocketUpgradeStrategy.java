package org.nem.specific.deploy;

import java.util.Collections;
import java.util.List;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.server.ServerContainer;
import org.eclipse.jetty.ee8.websocket.javax.server.JavaxWebSocketServerContainer;
import org.springframework.web.socket.server.HandshakeFailureException;
import org.springframework.web.socket.server.standard.AbstractStandardUpgradeStrategy;
import org.springframework.web.socket.server.standard.ServerEndpointRegistration;

/** Bridges Spring Framework 5's JSR-356 strategy to Jetty 12 EE8's public upgrade API. */
public class Jetty12WebSocketUpgradeStrategy extends AbstractStandardUpgradeStrategy {
	@Override
	public String[] getSupportedVersions() {
		return new String[] { "13" };
	}

	@Override
	protected void upgradeInternal(final org.springframework.http.server.ServerHttpRequest request,
			final org.springframework.http.server.ServerHttpResponse response, final String protocol,
			final List<Extension> extensions, final Endpoint endpoint) throws HandshakeFailureException {
		final javax.servlet.http.HttpServletRequest servletRequest = getHttpServletRequest(request);
		final javax.servlet.http.HttpServletResponse servletResponse = getHttpServletResponse(response);
		final ServerEndpointRegistration endpointConfig = new ServerEndpointRegistration(servletRequest.getRequestURI(), endpoint);
		endpointConfig.setSubprotocols(protocol == null || protocol.isEmpty() ? Collections.emptyList() : Collections.singletonList(protocol));
		endpointConfig.setExtensions(extensions);

		final ServerContainer serverContainer = getContainer(servletRequest);
		if (!(serverContainer instanceof JavaxWebSocketServerContainer jettyContainer)) {
			throw new HandshakeFailureException("Expected Jetty 12 EE8 WebSocket container but found "
					+ serverContainer.getClass().getName());
		}

		try {
			jettyContainer.upgradeHttpToWebSocket(servletRequest, servletResponse, endpointConfig, Collections.emptyMap());
		} catch (final Exception e) {
			throw new HandshakeFailureException("Jetty 12 EE8 WebSocket upgrade failed", e);
		}
	}
}
