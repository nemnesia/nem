import java.io.Closeable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.nem.core.model.primitive.BlockHeight;
import org.nem.nis.boot.NisPeerNetworkHost;
import org.nem.nis.cache.ReadOnlyNisCache;
import org.nem.nis.connect.HttpConnectorPool;
import org.nem.nis.dao.BlockDao;
import org.nem.nis.dbmodel.DbBlock;
import org.nem.nis.service.BlockChainLastBlockLayer;
import org.nem.nis.sync.BlockChainUpdater;
import org.nem.specific.deploy.NisConfiguration;
import org.nem.specific.deploy.appconfig.NisAppConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/**
 * Opt-in runtime probe for a disposable H2 copy. Put an external config.properties first on the classpath with
 * nem.folder, nem.network, nis.shouldAutoBoot=false, nis.shouldAutoHarvestOnBoot=false,
 * nis.delayBlockLoading=false, and nis.useNetworkTime=false. Start this in a fresh JVM for each network.
 */
public final class Phase2fNisRuntimeProbe {
	private Phase2fNisRuntimeProbe() {
	}

	public static void main(final String[] args) throws Exception {
		if (1 != args.length || !("mainnet".equals(args[0]) || "testnet".equals(args[0]))) {
			throw new IllegalArgumentException("Usage: Phase2fNisRuntimeProbe <mainnet|testnet>");
		}

		AnnotationConfigApplicationContext context = null;
		String jdbcUrl = null;
		boolean success = false;
		try {
			context = new AnnotationConfigApplicationContext(NisAppConfig.class);
			final NisConfiguration config = context.getBean(NisConfiguration.class);
			if (!args[0].equals(config.getNetworkName())) {
				throw new IllegalStateException("expected network does not match loaded NIS configuration");
			}
			if (config.shouldAutoBoot()) {
				throw new IllegalStateException("peer auto-boot must be disabled for this offline probe");
			}

			final Path runtimeFolder = Path.of(config.getNemFolder()).toAbsolutePath().normalize();
			final Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
			if (runtimeFolder.startsWith(repository)) {
				throw new IllegalStateException("refusing a runtime database directory inside the repository");
			}
			jdbcUrl = "jdbc:h2:file:" + runtimeFolder.resolve("nis/data/nis5_" + config.getNetworkName())
					+ ";MODE=LEGACY;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";

			final BlockDao blockDao = context.getBean(BlockDao.class);
			final BlockChainLastBlockLayer lastBlockLayer = context.getBean(BlockChainLastBlockLayer.class);
			final ReadOnlyNisCache cache = context.getBean(ReadOnlyNisCache.class);
			final BlockChainUpdater updater = context.getBean(BlockChainUpdater.class);
			final long blockCount = blockDao.count();
			final long initializedHeight = lastBlockLayer.getLastBlockHeight().getRaw();
			final DbBlock tip = lastBlockLayer.getLastDbBlock();
			if (null == tip || tip.getHeight() != blockCount || initializedHeight != blockCount) {
				throw new IllegalStateException("runtime initialized height, DAO count, and tip height differ");
			}
			if (lastBlockLayer.isLoading()) {
				throw new IllegalStateException("production BlockAnalyzer did not finish loading the chain");
			}

			DbBlock previous = null;
			for (long height = 1; height <= blockCount; ++height) {
				final DbBlock block = blockDao.findByHeight(new BlockHeight(height));
				if (null == block || block.getHeight() != height) {
					throw new IllegalStateException("missing block at height " + height);
				}
				if (null != previous && !previous.getBlockHash().equals(block.getPrevBlockHash())) {
					throw new IllegalStateException("broken previous-block linkage at height " + height);
				}
				previous = block;
			}
			if (!tip.getBlockHash().equals(previous.getBlockHash())) {
				throw new IllegalStateException("tip changed during full traversal");
			}

			final MigrationInfoService flywayInfo = context.getBean(Flyway.class).info();
			final MigrationInfo current = flywayInfo.current();
			System.out.println("RUNTIME_RESULT network=" + config.getNetworkName()
					+ " dbBlocks=" + blockCount + " initializedHeight=" + initializedHeight
					+ " genesisHash=" + blockDao.findByHeight(BlockHeight.ONE).getBlockHash()
					+ " tipHash=" + tip.getBlockHash() + " chainScore=" + updater.getScore()
					+ " accountCache=" + cache.getAccountCache().size()
					+ " accountStateCache=" + cache.getAccountStateCache().size()
					+ " namespaceCache=" + cache.getNamespaceCache().size()
					+ " loaded=true allBlockLinks=valid");
			System.out.println("FLYWAY_RESULT current=" + (null == current ? "none" : current.getVersion())
					+ " applied=" + flywayInfo.applied().length + " pending=" + flywayInfo.pending().length);
			success = true;
		} finally {
			if (null != context) {
				closeNetworkResources(context);
				context.close();
				System.out.println("SPRING_CONTEXT_CLOSE=complete");
			}
			if (null != jdbcUrl) {
				try (var connection = DriverManager.getConnection(jdbcUrl, "", "");
						var statement = connection.createStatement()) {
					statement.execute("SHUTDOWN");
				}
				System.out.println("H2_DATABASE_SHUTDOWN=complete");
			}
		}
		if (success) {
			Thread.sleep(1200L);
			for (final Thread thread : Thread.getAllStackTraces().keySet()) {
				if (thread.isAlive() && !thread.isDaemon() && !"main".equals(thread.getName())) {
					throw new IllegalStateException("non-daemon thread survived probe cleanup: " + thread.getName());
				}
			}
			System.out.println("NON_DAEMON_THREADS=none");
		}
	}

	private static void closeNetworkResources(final AnnotationConfigApplicationContext context) throws Exception {
		context.getBean(NisPeerNetworkHost.class).close();
		final HttpConnectorPool connectorPool = context.getBean(HttpConnectorPool.class);
		final Field clientField = HttpConnectorPool.class.getDeclaredField("httpMethodClient");
		clientField.setAccessible(true);
		((Closeable) clientField.get(connectorPool)).close();
	}
}
