package org.nem.specific.deploy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONValue;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHolder;
import org.eclipse.jetty.ee11.servlet.FilterHolder;
import jakarta.websocket.server.ServerContainer;
import org.junit.Test;
import org.nem.deploy.CommonConfiguration;
import org.nem.deploy.PropertiesExtensions;
import org.nem.deploy.server.NemServerBootstrapper;
import org.nem.deploy.server.NemWebsockServerBootstrapper;
import org.nem.specific.deploy.appconfig.NisAppConfig;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.nem.deploy.CommonStarter;
import org.mockito.Mockito;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.socket.config.WebSocketMessageBrokerStats;

import static org.junit.Assert.*;

/**
 * Runtime probe that boots the production Jetty/Spring/NIS web bootstrappers on EE11.
 * The only substitutions are an isolated in-memory database and ephemeral listener ports.
 */
public class Ee11ProductionWebRuntimeProbe {
	private static final Duration CLIENT_TIMEOUT = Duration.ofSeconds(12);

	@Test
	public void productionBootstrappersServeRestWebSocketAndSockJsXhr() throws Exception {
		final String originalUserHome = System.getProperty("user.home");
		System.setProperty("user.home", java.nio.file.Files.createTempDirectory("phase2j-d-user-home-").toString());
		final AnnotationConfigApplicationContext appContext = new AnnotationConfigApplicationContext();
		appContext.register(RuntimeAppConfig.class);
		appContext.refresh();
		final NisConfiguration configuration = appContext.getBean(NisConfiguration.class);
		final NisConfigurationPolicy policy = new NisConfigurationPolicy();
		final Server rest = new NemServerBootstrapper(appContext, configuration, policy).boot();
		final Server websocket = new NemWebsockServerBootstrapper(appContext, configuration, policy).boot();
		try {
			rest.start();
			websocket.start();
			final int restPort = connector(rest).getLocalPort();
			final int websocketPort = connector(websocket).getLocalPort();
			assertTargetRuntime(rest, websocket);
			assertAsyncSupport(rest, false);
			assertAsyncSupport(websocket, true);
			assertLiveRest(restPort);
			final WebSocketMessageBrokerStats brokerStats = brokerStats(websocket);
			assertNativeSockJsWebSocket(websocketPort);
			await(() -> noActiveSessions(brokerStats), "graceful WebSocket session cleanup");
			final int transportErrorsBeforeAbruptClose = brokerStats.getWebSocketSessionStats().getTransportErrorSessions();
			assertAbruptSockJsWebSocket(websocketPort, brokerStats);
			final int transportErrorsAfterAbruptClose = brokerStats.getWebSocketSessionStats().getTransportErrorSessions();
			System.out.printf("EE11 abrupt close transport-error callback sessions: before=%d after=%d; active=%s%n",
					transportErrorsBeforeAbruptClose, transportErrorsAfterAbruptClose, brokerStats.getWebSocketSessionStats());
			assertNativeSockJsWebSocket(websocketPort);
			await(() -> noActiveSessions(brokerStats), "reconnected WebSocket session cleanup");
			assertSockJsXhr(websocketPort);
			await(() -> noActiveSessions(brokerStats), "SockJS XHR session cleanup");
		} finally {
			stopAndJoin(websocket);
			stopAndJoin(rest);
			appContext.close();
			assertTrue("Spring context did not close", !appContext.isActive());
			org.nem.core.test.Utils.resetGlobals();
			if (null == originalUserHome) System.clearProperty("user.home"); else System.setProperty("user.home", originalUserHome);
		}
	}

	private static void assertLiveRest(final int port) throws Exception {
		final HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + "/heartbeat")).timeout(CLIENT_TIMEOUT).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode());
		assertTrue(response.headers().firstValue("content-type").orElse("").contains("application/json"));
		assertTrue("unexpected NIS heartbeat body: " + response.body(), response.body().contains("\"type\":2"));
		assertTrue("unexpected NIS heartbeat status code: " + response.body(), response.body().contains("\"code\":1"));
		assertTrue(response.body().contains("ok"));
	}

	private static void assertNativeSockJsWebSocket(final int port) throws Exception {
		final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
		final CompletableFuture<Void> closed = new CompletableFuture<>();
		final String sessionId = "ee11-native-" + System.nanoTime();
		final URI uri = URI.create("ws://127.0.0.1:" + port + "/w/messages/000/" + sessionId + "/websocket");
		final WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(CLIENT_TIMEOUT)
				.header("Origin", "http://ee11-test.invalid").subprotocols("v12.stomp")
				.buildAsync(uri, new WebSocket.Listener() {
					private final StringBuilder fragments = new StringBuilder();
					@Override
					public void onOpen(final WebSocket webSocket) {
						webSocket.request(1);
					}
					@Override
					public java.util.concurrent.CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data,
							final boolean last) {
						this.fragments.append(data);
						if (last) {
							final String sockJsFrame = this.fragments.toString();
							if (sockJsFrame.startsWith("a")) {
								final Object decoded = JSONValue.parse(sockJsFrame.substring(1));
								if (decoded instanceof JSONArray) {
									for (final Object frame : (JSONArray) decoded) frames.add(String.valueOf(frame));
								}
							} else {
								frames.add(sockJsFrame);
							}
							this.fragments.setLength(0);
						}
						webSocket.request(1);
						return null;
					}
					@Override
					public java.util.concurrent.CompletionStage<?> onClose(final WebSocket webSocket, final int statusCode,
							final String reason) {
						frames.offer("__close__" + statusCode + ":" + reason);
						closed.complete(null);
						return null;
					}
					@Override
					public void onError(final WebSocket webSocket, final Throwable error) {
						frames.offer("__error__" + error);
						closed.completeExceptionally(error);
					}
				}).get(CLIENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

		try {
			assertEquals("v12.stomp", socket.getSubprotocol());
			assertSockJsOpen(takeFrame(frames));
			sendSockJs(socket, "CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\n\n\u0000");
			assertStompFrame(takeStompFrame(frames), "CONNECTED");
			sendSockJs(socket, "SUBSCRIBE\nid:blocks\ndestination:/blocks\nack:auto\n\n\u0000");
			sendSockJs(socket, "SEND\ndestination:/w/api/block/last\ncontent-length:0\n\n\u0000");
			final String message = takeStompFrame(frames);
			assertStompFrame(message, "MESSAGE");
			assertTrue("NIS block message must contain serialized payload", message.contains("\n\n"));
			sendSockJs(socket, "DISCONNECT\nreceipt:ee11-ws-disconnect\n\n\u0000");
			assertTrue("DISCONNECT receipt not received", takeStompFrame(frames).startsWith("RECEIPT\nreceipt-id:ee11-ws-disconnect"));
			socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe complete").get(CLIENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
			closed.get(CLIENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		} finally {
			if (!closed.isDone()) {
				socket.abort();
			}
		}
	}

	private static void assertSockJsXhr(final int port) throws Exception {
		final String sessionId = "ee11-xhr-" + System.nanoTime();
		final String session = "http://127.0.0.1:" + port + "/w/messages/000/" + sessionId;
		final HttpClient client = HttpClient.newBuilder().connectTimeout(CLIENT_TIMEOUT).build();
		final String endpoint = session.substring(0, session.lastIndexOf('/'))
				.substring(0, session.substring(0, session.lastIndexOf('/')).lastIndexOf('/'));
		final HttpResponse<String> info = client.send(HttpRequest.newBuilder(URI.create(endpoint + "/info"))
				.timeout(CLIENT_TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(200, info.statusCode());
		assertTrue(info.body(), info.body().contains("\"websocket\":true"));
		final HttpResponse<String> opening = pollSockJs(client, session + "/xhr");
		assertTrue("SockJS XHR opening frame: " + opening.body(), opening.body().startsWith("o\n"));
		postSockJs(client, session + "/xhr_send", "CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000");
		assertStompFrame(firstSockJsMessage(pollSockJs(client, session + "/xhr").body()), "CONNECTED");
		postSockJs(client, session + "/xhr_send", "SUBSCRIBE\nid:blocks-xhr\ndestination:/blocks\nack:auto\n\n\u0000");
		postSockJs(client, session + "/xhr_send", "SEND\ndestination:/w/api/block/last\ncontent-length:0\n\n\u0000");
		assertStompFrame(firstSockJsMessage(pollSockJs(client, session + "/xhr").body()), "MESSAGE");
		postSockJs(client, session + "/xhr_send", "DISCONNECT\nreceipt:ee11-xhr-disconnect\n\n\u0000");
		assertTrue("XHR DISCONNECT receipt not received", firstSockJsMessage(pollSockJs(client, session + "/xhr").body())
				.startsWith("RECEIPT\nreceipt-id:ee11-xhr-disconnect"));
	}

	private static void assertAbruptSockJsWebSocket(final int port, final WebSocketMessageBrokerStats stats) throws Exception {
		final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
		final String sessionId = "ee11-abrupt-" + System.nanoTime();
		final WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(CLIENT_TIMEOUT)
				.header("Origin", "http://ee11-test.invalid").subprotocols("v12.stomp")
				.buildAsync(URI.create("ws://127.0.0.1:" + port + "/w/messages/000/" + sessionId + "/websocket"),
						new WebSocket.Listener() {
							@Override public void onOpen(final WebSocket webSocket) { webSocket.request(1); }
							@Override public java.util.concurrent.CompletionStage<?> onText(final WebSocket webSocket, final CharSequence data,
									final boolean last) {
								if (last) {
									final String frame = data.toString();
									if (frame.startsWith("a")) {
										final Object decoded = JSONValue.parse(frame.substring(1));
										if (decoded instanceof JSONArray) for (final Object value : (JSONArray) decoded) frames.add(String.valueOf(value));
									} else frames.add(frame);
								}
								webSocket.request(1);
								return null;
							}
						}).get(CLIENT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
		assertSockJsOpen(takeFrame(frames));
		sendSockJs(socket, "CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000");
		assertStompFrame(takeStompFrame(frames), "CONNECTED");
		socket.abort();
		await(() -> noActiveSessions(stats), "abrupt WebSocket session cleanup");
	}

	private static boolean noActiveSessions(final WebSocketMessageBrokerStats stats) {
		return stats.getWebSocketSessionStats().toString().startsWith("0 current");
	}

	private static WebSocketMessageBrokerStats brokerStats(final Server server) {
		final ServletContextHandler context = servletContextHandler(server);
		final java.util.Enumeration<String> attributes = context.getServletContext().getAttributeNames();
		while (attributes.hasMoreElements()) {
			final Object attribute = context.getServletContext().getAttribute(attributes.nextElement());
			if (attribute instanceof WebApplicationContext) {
				final WebApplicationContext webContext = (WebApplicationContext) attribute;
				if (webContext.getBeansOfType(WebSocketMessageBrokerStats.class).size() > 0) {
					return webContext.getBean(WebSocketMessageBrokerStats.class);
				}
			}
		}
		throw new AssertionError("Spring WebSocket broker statistics bean not found in the production DispatcherServlet context");
	}

	private static void assertAsyncSupport(final Server server, final boolean hasWebsocket) {
		final ServletContextHandler context = servletContextHandler(server);
		final jakarta.servlet.ServletRegistration dispatcher = context.getServletContext().getServletRegistration(
				hasWebsocket ? "Spring Websocket Dispatcher Servlet" : "Spring MVC Dispatcher Servlet");
		assertNotNull("production DispatcherServlet registration missing", dispatcher);
		assertFalse("production DispatcherServlet mapping missing", dispatcher.getMappings().isEmpty());
		for (final ServletHolder servlet : context.getServletHandler().getServlets()) {
			if (dispatcher.getName().equals(servlet.getName())) {
				assertTrue("DispatcherServlet async support is disabled", servlet.isAsyncSupported());
			}
		}
		for (final FilterHolder filter : context.getServletHandler().getFilters()) {
			assertTrue("filter async support is disabled: " + filter.getName(), filter.isAsyncSupported());
		}
	}

	private static ServletContextHandler servletContextHandler(final Server server) {
		Object handler = server.getHandler();
		try {
			while (!ServletContextHandler.class.isInstance(handler)) handler = handler.getClass().getMethod("getHandler").invoke(handler);
			return (ServletContextHandler) handler;
		} catch (final ReflectiveOperationException e) {
			throw new AssertionError("could not inspect Jetty handler tree", e);
		}
	}

	private static HttpResponse<String> pollSockJs(final HttpClient client, final String endpoint) throws Exception {
		final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(endpoint)).timeout(CLIENT_TIMEOUT)
				.header("Origin", "http://ee11-test.invalid").header("Content-Type", "application/javascript; charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 200, response.statusCode());
		return response;
	}

	private static String firstSockJsMessage(final String body) {
		for (final String line : body.split("\\n")) {
			if (line.startsWith("a")) {
				final Object decoded = JSONValue.parse(line.substring(1));
				assertTrue("SockJS data frame: " + line, decoded instanceof JSONArray && !((JSONArray) decoded).isEmpty());
				return String.valueOf(((JSONArray) decoded).get(0));
			}
		}
		throw new AssertionError("No SockJS message in response: " + body);
	}

	private static void postSockJs(final HttpClient client, final String endpoint, final String stomp) throws Exception {
		final JSONArray message = new JSONArray();
		message.add(stomp);
		final HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(endpoint))
				.timeout(CLIENT_TIMEOUT).header("Content-Type", "application/json;charset=UTF-8")
				.POST(HttpRequest.BodyPublishers.ofString(JSONValue.toJSONString(message), StandardCharsets.UTF_8)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(response.body(), 204, response.statusCode());
	}

	private static void sendSockJs(final WebSocket socket, final String stomp) {
		final JSONArray message = new JSONArray();
		message.add(stomp);
		socket.sendText(JSONValue.toJSONString(message), true).join();
	}

	private static String takeStompFrame(final BlockingQueue<String> frames) throws Exception {
		for (int i = 0; i < 8; ++i) {
			final String frame = takeFrame(frames);
			if (frame.startsWith("CONNECTED") || frame.startsWith("MESSAGE") || frame.startsWith("RECEIPT") || frame.startsWith("ERROR")) {
				return frame;
			}
		}
		throw new AssertionError("No STOMP frame received");
	}

	private static String takeFrame(final BlockingQueue<String> frames) throws Exception {
		final String frame = frames.poll(CLIENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		assertNotNull("Timed out waiting for SockJS frame; queued=" + frames, frame);
		assertFalse(frame, frame.startsWith("__reader_error__"));
		assertFalse(frame, frame.startsWith("__error__") || frame.startsWith("__close__"));
		return frame;
	}

	private static void assertSockJsOpen(final String frame) {
		assertEquals("o", frame);
	}

	private static void assertStompFrame(final String frame, final String command) {
		assertTrue("expected " + command + " but got: " + frame, frame.startsWith(command + "\n"));
	}

	private static void await(final java.util.function.BooleanSupplier condition, final String description) throws Exception {
		final long deadline = System.nanoTime() + CLIENT_TIMEOUT.toNanos();
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
		assertTrue("Timed out waiting for " + description, condition.getAsBoolean());
	}

	private static void assertTargetRuntime(final Server... servers) {
		assertEquals("12.1.13", org.eclipse.jetty.server.Server.class.getPackage().getImplementationVersion());
		assertEquals("7.0.9", org.springframework.context.ApplicationContext.class.getPackage().getImplementationVersion());
		assertEquals("6.1", jakarta.servlet.Servlet.class.getPackage().getSpecificationVersion());
		assertEquals("2.2", jakarta.websocket.Endpoint.class.getPackage().getSpecificationVersion());
		for (int i = 0; i < servers.length; ++i) {
			final Server server = servers[i];
			Object handler = server.getHandler();
			assertEquals("org.eclipse.jetty.server.handler.gzip.GzipHandler", handler.getClass().getName());
			try {
				while (!ServletContextHandler.class.isInstance(handler)) {
					handler = handler.getClass().getMethod("getHandler").invoke(handler);
					assertNotNull("missing Jetty servlet context handler", handler);
				}
				final ServletContextHandler context = (ServletContextHandler) handler;
				if (i == 1) {
					final Object container = context.getServletContext().getAttribute(ServerContainer.class.getName());
					assertTrue("missing Jakarta EE11 WebSocket container", container instanceof ServerContainer);
					assertTrue("not the Jetty EE11 websocket container: " + container.getClass().getName(),
							container.getClass().getName().startsWith("org.eclipse.jetty.ee11.websocket."));
					final boolean providerFound = ServiceLoader.load(NisWebSocketUpgradeStrategyProvider.class)
							.stream().map(ServiceLoader.Provider::get).anyMatch(provider -> null != provider.createIfSupported(container));
					assertTrue("NIS Spring WebSocket strategy provider did not accept the EE11 container", providerFound);
				}
			} catch (final ReflectiveOperationException e) {
				throw new AssertionError("could not inspect production Jetty handler tree", e);
			}
		}
	}

	private static ServerConnector connector(final Server server) {
		return (ServerConnector) server.getConnectors()[0];
	}

	private static void stopAndJoin(final Server server) throws Exception {
		if (server.isRunning()) server.stop();
		server.join();
		assertFalse("Jetty server did not stop", server.isRunning());
		assertFalse("Jetty thread pool still running", ((org.eclipse.jetty.util.component.LifeCycle) server.getThreadPool()).isRunning());
	}

	@Configuration
	public static class RuntimeAppConfig extends NisAppConfig {
		@Bean
		@Override
		public NisConfiguration nisConfiguration() {
			final Properties properties = PropertiesExtensions.loadFromResource(NisConfiguration.class, "config-default.properties", true);
			properties.setProperty("nem.httpPort", "0");
			properties.setProperty("nem.websocketPort", "0");
			properties.setProperty("nem.useDosFilter", "true");
			properties.setProperty("nis.shouldAutoBoot", "false");
			properties.setProperty("nis.delayBlockLoading", "false");
			return new NisConfiguration(properties);
		}

		@Bean
		@Override
		public javax.sql.DataSource dataSource() {
			final DriverManagerDataSource dataSource = new DriverManagerDataSource();
			dataSource.setDriverClassName("org.h2.Driver");
			dataSource.setUrl("jdbc:h2:mem:phase2jd-web-runtime;MODE=LEGACY;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1");
			return dataSource;
		}

		@Bean(initMethod = "migrate")
		@Override
		public Flyway flyway() {
			return Flyway.configure(NisAppConfig.class.getClassLoader()).dataSource(this.dataSource()).locations("db/h2")
					.table("schema_version").validateOnMigrate(false).load();
		}

		@Bean
		@Override
		public org.nem.core.time.TimeProvider timeProvider() {
			return new org.nem.core.time.SystemTimeProvider();
		}

		@Bean
		@Override
		public CommonStarter commonStarter() {
			return Mockito.mock(CommonStarter.class);
		}
	}
}
