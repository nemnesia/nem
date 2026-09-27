package org.nem.deploy.server;

import javax.servlet.ServletContextListener;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.ee8.servlet.ServletContextHandler;
import org.eclipse.jetty.ee8.annotations.AnnotationConfiguration;
import org.eclipse.jetty.ee8.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee8.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.util.thread.*;
import org.eclipse.jetty.ee8.webapp.Configurations;
import org.nem.deploy.*;
import org.springframework.web.context.ContextLoaderListener;

/**
 * Abstract class for booting a server.
 */
public abstract class AbstractServerBootstrapper {
	private static final int IDLE_TIMEOUT = 30000;

	private final CommonConfiguration configuration;

	/**
	 * Creates a bootstrapper.
	 *
	 * @param configuration The configuration.
	 */
	protected AbstractServerBootstrapper(final CommonConfiguration configuration) {
		this.configuration = configuration;
	}

	/**
	 * Gets the configuration.
	 *
	 * @return The configuration.
	 */
	protected CommonConfiguration getConfiguration() {
		return this.configuration;
	}

	/**
	 * Boots the server.
	 *
	 * @return The server.
	 */
	public Server boot() {
		final Server server = this.createServer();
		server.addBean(new ScheduledExecutorScheduler());

		final ServerConnector connector = this.createConnector(server);
		connector.setIdleTimeout(IDLE_TIMEOUT);
		server.addConnector(connector);

		server.setHandler(this.createHandlers());
		server.setDumpAfterStart(false);
		server.setDumpBeforeStop(false);
		server.setStopAtShutdown(true);
		return server;
	}

	private Server createServer() {
		// Taken from Jetty doc
		final QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setMaxThreads(this.configuration.getMaxThreads());
		final Server server = new Server(threadPool);
		server.addBean(new ScheduledExecutorScheduler());

		if (this.configuration.isNcc()) {
			final Configurations configurations = Configurations.setServerDefault(server);
			final int fragmentIndex = configurationIndex(configurations, org.eclipse.jetty.ee8.webapp.FragmentConfiguration.class);
			if (fragmentIndex >= 0) {
				configurations.add(fragmentIndex + 1, new EnvConfiguration());
				configurations.add(fragmentIndex + 2, new PlusConfiguration());
			}
			final int jettyXmlIndex = configurationIndex(configurations, org.eclipse.jetty.ee8.webapp.JettyWebXmlConfiguration.class);
			if (jettyXmlIndex >= 0) {
				configurations.add(jettyXmlIndex, new AnnotationConfiguration());
			}
		}

		return server;
	}

	private static int configurationIndex(final Configurations configurations, final Class<?> type) {
		for (int i = 0; i < configurations.size(); ++i) {
			if (type.isInstance(configurations.get(i))) {
				return i;
			}
		}
		return -1;
	}

	@SuppressWarnings("removal")
	private Handler createHandlers() {
		final ServletContextHandler servletContext = new ServletContextHandler();
		this.configureServletContextHandler(servletContext);

		// Special Listener to set-up the environment for Spring
		servletContext.addEventListener(this.getCustomServletListener());
		servletContext.addEventListener(new ContextLoaderListener());
		servletContext.setErrorHandler(new JsonErrorHandler(CommonStarter.TIME_PROVIDER));

		final GzipHandler gzipHandler = new GzipHandler();
		gzipHandler.setIncludedMimeTypes(org.eclipse.jetty.http.MimeTypes.Type.APPLICATION_JSON.asString());
		gzipHandler.setHandler(servletContext);
		return gzipHandler;
	}

	/**
	 * Allows server variants to configure container initializers before listeners run.
	 *
	 * @param servletContext The servlet context handler.
	 */
	protected void configureServletContextHandler(final ServletContextHandler servletContext) {
	}

	/**
	 * Creates a server connector.
	 *
	 * @param server The server.
	 * @return The server connector.
	 */
	protected abstract ServerConnector createConnector(final Server server);

	/**
	 * Gets the (optional) custom servlet listener.
	 *
	 * @return The custom servlet listener.
	 */
	protected abstract ServletContextListener getCustomServletListener();
}
