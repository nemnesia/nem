package org.nem.nis.test;

import org.hibernate.Session;
import org.hibernate.Transaction;
import org.nem.core.utils.ExceptionUtils;
import org.nem.nis.cache.*;
import org.nem.nis.dbmodel.*;

/**
 * Static class containing helper functions for db related tests.
 */
public class DbTestUtils {

	/**
	 * Creates a transfer db model.
	 *
	 * @param dbModelClass The transfer db model class.
	 * @param <T> The transfer db model type.
	 * @return The created transfer db model.
	 */
	@SuppressWarnings({
			"deprecation", "rawtypes"
	})
	public static <T extends AbstractBlockTransfer> T createTransferDbModel(final Class<T> dbModelClass) {
		final T dbTransfer = ExceptionUtils.propagate(dbModelClass::newInstance);

		// initialize any derived required fields
		if (dbModelClass.equals(DbProvisionNamespaceTransaction.class)) {
			((DbProvisionNamespaceTransaction) dbTransfer).setNamespace(new DbNamespace());
		}

		return dbTransfer;
	}

	/**
	 * Cleans up the database.
	 *
	 * @param session The session.
	 */
	public static void dbCleanup(final Session session) {
		executeInTransaction(session, () -> cleanupDatabase(session));
	}

	private static void cleanupDatabase(final Session session) {
		session.createNativeMutationQuery("delete from multisigsignatures").executeUpdate();
		session.createNativeMutationQuery("delete from multisigtransactions").executeUpdate();
		session.createNativeMutationQuery("delete from transferredmosaics").executeUpdate();
		session.createNativeMutationQuery("delete from transfers").executeUpdate();
		session.createNativeMutationQuery("delete from importancetransfers").executeUpdate();
		session.createNativeMutationQuery("delete from multisigmodifications").executeUpdate();
		session.createNativeMutationQuery("delete from multisigsignermodifications").executeUpdate();
		session.createNativeMutationQuery("delete from mincosignatoriesmodifications").executeUpdate();
		session.createNativeMutationQuery("delete from multisigsends").executeUpdate();
		session.createNativeMutationQuery("delete from multisigreceives").executeUpdate();
		session.createNativeMutationQuery("delete from namespaceprovisions").executeUpdate();
		session.createNativeMutationQuery("delete from namespaces").executeUpdate();
		session.createNativeMutationQuery("delete from mosaicdefinitioncreationtransactions").executeUpdate();
		session.createNativeMutationQuery("delete from mosaicproperties").executeUpdate();
		session.createNativeMutationQuery("delete from mosaicdefinitions").executeUpdate();
		session.createNativeMutationQuery("delete from mosaicsupplychanges").executeUpdate();
		session.createNativeMutationQuery("delete from blocks").executeUpdate();
		session.createNativeMutationQuery("delete from accounts").executeUpdate();
		session.createNativeMutationQuery("ALTER SEQUENCE transaction_id_seq RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE multisigmodifications ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE multisigsends ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE multisigreceives ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE namespaces ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE mosaicproperties ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE mosaicdefinitions ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE transferredmosaics ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE blocks ALTER COLUMN id RESTART WITH 1").executeUpdate();
		session.createNativeMutationQuery("ALTER TABLE accounts ALTER COLUMN id RESTART WITH 1").executeUpdate();

		session.flush();
		session.clear();
	}

	/**
	 * Runs database test setup or cleanup in a transaction when the session does not already have one.
	 *
	 * @param session The session.
	 * @param operation The database operation.
	 */
	public static void executeInTransaction(final Session session, final Runnable operation) {
		final Transaction transaction = session.getTransaction();
		final boolean startedTransaction = !transaction.isActive();
		if (startedTransaction) {
			transaction.begin();
		}

		try {
			operation.run();
			if (startedTransaction) {
				transaction.commit();
			}
		} catch (final RuntimeException e) {
			if (startedTransaction && transaction.isActive()) {
				transaction.rollback();
			}
			throw e;
		}
	}

	/**
	 * Cleans up the cache.
	 *
	 * @param cache The cache.
	 */
	public static void cacheCleanup(final SynchronizedAccountStateCache cache) {
		final SynchronizedAccountStateCache mutableCache = cache.copy();
		mutableCache.contents().stream().forEach(a -> mutableCache.removeFromCache(a.getAddress()));
		mutableCache.commit();
	}
}
