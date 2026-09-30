import java.io.Closeable;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.nem.core.model.primitive.BlockHeight;
import org.nem.nis.boot.NisPeerNetworkHost;
import org.nem.nis.cache.ReadOnlyNisCache;
import org.nem.nis.connect.HttpConnectorPool;
import org.nem.nis.dao.AccountDao;
import org.nem.nis.dao.BlockDao;
import org.nem.nis.dbmodel.DbAccount;
import org.nem.nis.dbmodel.DbBlock;
import org.nem.nis.service.BlockChainLastBlockLayer;
import org.nem.nis.sync.BlockChainUpdater;
import org.nem.specific.deploy.NisConfiguration;
import org.nem.specific.deploy.appconfig.NisAppConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.orm.jpa.hibernate.HibernateTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Opt-in persistence lifecycle probe for a disposable H2 copy. Run each phase in its own JVM:
 * write, verify, cleanup, verify-clean. Use Phase2fNisRuntimeProbe's external config.properties setup.
 */
public final class Phase2kPersistedChainLifecycleProbe {
	private static final String MAINNET_COMMIT = "K2BM000000000000000000000000000000000000";
	private static final String MAINNET_ROLLBACK = "K2BR000000000000000000000000000000000000";
	private static final String TESTNET_COMMIT = "K2BT000000000000000000000000000000000000";
	private static final String TESTNET_ROLLBACK = "K2BS000000000000000000000000000000000000";

	private Phase2kPersistedChainLifecycleProbe() {
	}

	public static void main(final String[] args) throws Exception {
		if (2 != args.length || !("mainnet".equals(args[0]) || "testnet".equals(args[0]))
				|| !("write".equals(args[1]) || "verify".equals(args[1]) || "cleanup".equals(args[1])
						|| "verify-clean".equals(args[1]))) {
			throw new IllegalArgumentException("Usage: Phase2kPersistedChainLifecycleProbe <mainnet|testnet> <write|verify|cleanup|verify-clean>");
		}

		final String network = args[0];
		final String phase = args[1];
		final String commitKey = "mainnet".equals(network) ? MAINNET_COMMIT : TESTNET_COMMIT;
		final String rollbackKey = "mainnet".equals(network) ? MAINNET_ROLLBACK : TESTNET_ROLLBACK;
		AnnotationConfigApplicationContext context = null;
		String jdbcUrl = null;
		boolean success = false;
		try {
			context = new AnnotationConfigApplicationContext(NisAppConfig.class);
			final NisConfiguration config = context.getBean(NisConfiguration.class);
			if (!network.equals(config.getNetworkName()) || config.shouldAutoBoot()) {
				throw new IllegalStateException("network mismatch or peer auto-boot enabled");
			}
			final Path runtimeFolder = Path.of(config.getNemFolder()).toAbsolutePath().normalize();
			final Path repository = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
			if (runtimeFolder.startsWith(repository)) {
				throw new IllegalStateException("refusing a runtime database directory inside the repository");
			}
			jdbcUrl = "jdbc:h2:file:" + runtimeFolder.resolve("nis/data/nis5_" + network)
					+ ";MODE=LEGACY;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1";

			final SessionFactory sessionFactory = context.getBean(SessionFactory.class);
			final AccountDao accountDao = context.getBean(AccountDao.class);
			final BlockDao blockDao = context.getBean(BlockDao.class);
			verifyChain(context, blockDao, network);

			switch (phase) {
			case "write" -> writeAndRollback(context, sessionFactory, accountDao, commitKey, rollbackKey);
			case "verify" -> {
				assertAccount(accountDao, commitKey, true, "committed row did not survive process restart");
				assertAccount(accountDao, rollbackKey, false, "rolled-back row appeared after process restart");
			}
			case "cleanup" -> {
				assertAccount(accountDao, commitKey, true, "committed row missing before cleanup");
				assertAccount(accountDao, rollbackKey, false, "rolled-back row appeared before cleanup");
				final TransactionTemplate transaction = new TransactionTemplate(context.getBean(HibernateTransactionManager.class));
				transaction.execute(status -> {
					final Session session = sessionFactory.getCurrentSession();
					final DbAccount persisted = findInSession(session, commitKey);
					if (null == persisted) {
						throw new IllegalStateException("committed probe row not found for cleanup");
					}
					session.remove(persisted);
					return null;
				});
				assertAccount(accountDao, commitKey, false, "cleanup commit did not remove probe row");
			}
			case "verify-clean" -> {
				assertAccount(accountDao, commitKey, false, "committed probe row remains after cleanup restart");
				assertAccount(accountDao, rollbackKey, false, "rolled-back probe row remains after cleanup restart");
			}
			default -> throw new IllegalStateException("unsupported phase");
			}

			final MigrationInfoService flywayInfo = context.getBean(Flyway.class).info();
			final MigrationInfo current = flywayInfo.current();
			if (null == current || !"1.0.7".equals(current.getVersion().getVersion())
					|| 8 != flywayInfo.applied().length || 0 != flywayInfo.pending().length) {
				throw new IllegalStateException("unexpected Flyway state");
			}
			System.out.println("LIFECYCLE_RESULT network=" + network + " phase=" + phase
					+ " chainReplay=valid version=" + current.getVersion()
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
			System.out.println("PROCESS_EXIT_READY=true");
		}
	}

	private static void writeAndRollback(final AnnotationConfigApplicationContext context, final SessionFactory sessionFactory,
			final AccountDao accountDao, final String commitKey, final String rollbackKey) {
		assertAccount(accountDao, commitKey, false, "commit marker already exists in the starting database");
		assertAccount(accountDao, rollbackKey, false, "rollback marker already exists in the starting database");
		final TransactionTemplate transaction = new TransactionTemplate(context.getBean(HibernateTransactionManager.class));
		transaction.execute(status -> {
			final Session session = sessionFactory.getCurrentSession();
			session.persist(new DbAccount(commitKey, null));
			session.flush();
			if (null == findInSession(session, commitKey)) {
				throw new IllegalStateException("commit marker not visible in its writing transaction");
			}
			assertNotVisibleToIndependentSession(sessionFactory, commitKey);
			return null;
		});
		assertAccount(accountDao, commitKey, true, "committed row not visible outside its transaction");
		System.out.println("TRANSACTION_COMMIT=visible_after_transaction");

		transaction.execute(status -> {
			final Session session = sessionFactory.getCurrentSession();
			session.persist(new DbAccount(rollbackKey, null));
			session.flush();
			if (null == findInSession(session, rollbackKey)) {
				throw new IllegalStateException("rollback marker not visible in its writing transaction");
			}
			assertNotVisibleToIndependentSession(sessionFactory, rollbackKey);
			status.setRollbackOnly();
			return null;
		});
		assertAccount(accountDao, rollbackKey, false, "rolled-back row remains visible after rollback");
		System.out.println("TRANSACTION_ROLLBACK=absent_after_rollback");
	}

	private static void verifyChain(final AnnotationConfigApplicationContext context, final BlockDao blockDao, final String network) {
		final BlockChainLastBlockLayer lastBlockLayer = context.getBean(BlockChainLastBlockLayer.class);
		final BlockChainUpdater updater = context.getBean(BlockChainUpdater.class);
		final ReadOnlyNisCache cache = context.getBean(ReadOnlyNisCache.class);
		final boolean mainnet = "mainnet".equals(network);
		final long expectedHeight = mainnet ? 2001L : 1601L;
		final String expectedGenesis = mainnet
				? "438cf6375dab5a0d32f9b7bf151d4539e00a590f7c022d5572c7d41815a24be4"
				: "6e34a7895ce1653dde64a614f2ad4215f4217d9e0255b4c84a846e5f7677c6c5";
		final String expectedTip = mainnet
				? "dcda75bc2fd65096ad3f3f4b5a104a0412601c20dab3efe051db761d675af02a"
				: "9406f3d9c16737004418d140894f15d8d96a3e25f8eb5e5bbf786e0b7fadbd51";
		final long expectedScore = mainnet ? 24964532368849513L : 79553937490635759L;
		final int expectedAccounts = mainnet ? 1377 : 21;
		if (lastBlockLayer.isLoading() || blockDao.count() != expectedHeight
				|| lastBlockLayer.getLastBlockHeight().getRaw() != expectedHeight
				|| !updater.getScore().getRaw().equals(java.math.BigInteger.valueOf(expectedScore))
				|| cache.getAccountCache().size() != expectedAccounts
				|| cache.getAccountStateCache().size() != expectedAccounts || cache.getNamespaceCache().size() != 1) {
			throw new IllegalStateException("replayed chain height, score, or cache state differs from baseline");
		}
		final DbBlock genesis = blockDao.findByHeight(BlockHeight.ONE);
		final DbBlock tip = lastBlockLayer.getLastDbBlock();
		if (null == genesis || !expectedGenesis.equals(genesis.getBlockHash().toString())
				|| null == tip || !expectedTip.equals(tip.getBlockHash().toString())) {
			throw new IllegalStateException("replayed genesis or tip differs from baseline");
		}
		DbBlock previous = null;
		for (long height = 1; height <= expectedHeight; ++height) {
			final DbBlock block = blockDao.findByHeight(new BlockHeight(height));
			if (null == block || block.getHeight() != height
					|| (null != previous && !previous.getBlockHash().equals(block.getPrevBlockHash()))) {
				throw new IllegalStateException("height gap or broken block link at " + height);
			}
			previous = block;
		}
		System.out.println("CHAIN_REPLAY network=" + network + " height=" + expectedHeight
				+ " genesis=" + expectedGenesis + " tip=" + expectedTip + " score=" + expectedScore
				+ " accounts=" + expectedAccounts + " namespaceCache=1 links=valid");
	}

	private static DbAccount findInSession(final Session session, final String key) {
		return session.createSelectionQuery("from DbAccount a where a.printableKey = :key", DbAccount.class)
				.setParameter("key", key).getSingleResultOrNull();
	}

	private static void assertNotVisibleToIndependentSession(final SessionFactory sessionFactory, final String key) {
		try (Session session = sessionFactory.openSession()) {
			final var transaction = session.beginTransaction();
			final DbAccount otherTransaction = findInSession(session, key);
			transaction.commit();
			if (null != otherTransaction) {
				throw new IllegalStateException("uncommitted row leaked into an independent transaction");
			}
		}
	}

	private static void assertAccount(final AccountDao accountDao, final String key, final boolean expected,
			final String failureMessage) {
		if ((null != accountDao.getAccountByPrintableAddress(key)) != expected) {
			throw new IllegalStateException(failureMessage);
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
