package org.nem.nis.dao;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.hibernate.*;
import org.hibernate.query.Query;
import org.nem.core.crypto.HashChain;
import org.nem.core.model.*;
import org.nem.core.model.mosaic.MosaicId;
import org.nem.core.model.namespace.NamespaceId;
import org.nem.core.model.primitive.*;
import org.nem.core.time.TimeInstant;
import org.nem.nis.cache.MosaicIdCache;
import org.nem.nis.dbmodel.*;
import org.nem.nis.mappers.TransactionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressWarnings({ "rawtypes", "deprecation" })
public class BlockDaoImpl implements BlockDao {
	private static final Logger LOGGER = Logger.getLogger(BlockDaoImpl.class.getName());

	private final SessionFactory sessionFactory;

	private final Function<Address, Collection<Address>> cosignatoriesLookup;
	private final MosaicIdCache mosaicIdCache;

	@Autowired(required = true)
	public BlockDaoImpl(final SessionFactory sessionFactory, final Function<Address, Collection<Address>> cosignatoriesLookup,
			final MosaicIdCache mosaicIdCache) {
		this.sessionFactory = sessionFactory;
		this.cosignatoriesLookup = cosignatoriesLookup;
		this.mosaicIdCache = mosaicIdCache;
	}

	private Session getCurrentSession() {
		return this.sessionFactory.getCurrentSession();
	}

	private void saveSingleBlock(final DbBlock block) {
		this.prepareAccountReferences(block, new HashMap<>(), new IdentityHashMap<>());
		this.getCurrentSession().persist(block);
		this.addToMosaicIdsCache(block);

		final ArrayList<DbMultisigSend> sendList = new ArrayList<>(100);
		final ArrayList<DbMultisigReceive> receiveList = new ArrayList<>(100);

		@SuppressWarnings("unchecked")
		final TransactionRegistry.Entry<DbMultisigTransaction, ?> multisigEntry = (TransactionRegistry.Entry<DbMultisigTransaction, ?>) TransactionRegistry
				.findByType(TransactionTypes.MULTISIG);

		assert null != multisigEntry;
		final List<DbMultisigTransaction> multisigTransactions = multisigEntry.getFromBlock.apply(block);
		for (final DbMultisigTransaction transaction : multisigTransactions) {
			final Long height = block.getHeight();
			final Long id = transaction.getId();
			for (final TransactionRegistry.Entry<? extends AbstractBlockTransfer, ?> entry : TransactionRegistry.iterate()) {
				final Integer txType = this.processInnerTransaction(transaction, entry, height, id, sendList, receiveList);

				if (0 != txType) {
					break;
				}
			}
		}

		for (final DbMultisigSend send : sendList) {
			this.getCurrentSession().persist(send);
		}

		for (final DbMultisigReceive receive : receiveList) {
			this.getCurrentSession().persist(receive);
		}
	}

	private void prepareAccountReferences(final Object value, final Map<String, DbAccount> accounts,
			final IdentityHashMap<Object, Boolean> visited) {
		if (null == value || visited.put(value, Boolean.TRUE) != null) {
			return;
		}
		if (value instanceof Collection<?>) {
			final Collection<?> collection = (Collection<?>) value;
			final List<Object> normalized = new ArrayList<>(collection.size());
			for (final Object item : collection) {
				if (item instanceof DbAccount) {
					normalized.add(this.getManagedAccount((DbAccount) item, accounts));
				} else {
					this.prepareAccountReferences(item, accounts, visited);
					normalized.add(item);
				}
			}
			@SuppressWarnings("unchecked")
			final Collection<Object> mutableCollection = (Collection<Object>) collection;
			mutableCollection.clear();
			mutableCollection.addAll(normalized);
			return;
		}
		if (!value.getClass().getPackageName().equals(DbBlock.class.getPackageName())) {
			return;
		}
		for (Class<?> type = value.getClass(); null != type && type != Object.class; type = type.getSuperclass()) {
			for (final Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				try {
					field.setAccessible(true);
					final Object fieldValue = field.get(value);
					if (fieldValue instanceof DbAccount) {
						field.set(value, this.getManagedAccount((DbAccount) fieldValue, accounts));
					} else {
						this.prepareAccountReferences(fieldValue, accounts, visited);
					}
				} catch (final IllegalAccessException e) {
					throw new IllegalStateException("Unable to prepare account references for persistence", e);
				}
			}
		}
	}

	private DbAccount getManagedAccount(final DbAccount account, final Map<String, DbAccount> accounts) {
		final String key = account.getPrintableKey();
		final DbAccount cached = accounts.get(key);
		if (null != cached) {
			return cached;
		}
		final Session session = this.getCurrentSession();
		DbAccount managed = null == account.getId() ? null : session.find(DbAccount.class, account.getId());
		if (null != managed && !key.equals(managed.getPrintableKey())) {
			managed = null;
		}
		if (null == managed) {
			managed = session.createSelectionQuery("from DbAccount a where a.printableKey = :key", DbAccount.class)
					.setParameter("key", key).getSingleResultOrNull();
		}
		if (null == managed) {
			managed = session.merge(account);
			account.setId(managed.getId());
		} else if (null == managed.getPublicKey() && null != account.getPublicKey()) {
			managed.setPublicKey(account.getPublicKey());
		}
		accounts.put(key, managed);
		return managed;
	}

	private <TDbModel extends AbstractBlockTransfer> int processInnerTransaction(final DbMultisigTransaction transaction,
			final TransactionRegistry.Entry<TDbModel, ?> theEntry, final Long height, final Long id,
			final ArrayList<DbMultisigSend> sendList, final ArrayList<DbMultisigReceive> receiveList) {
		final TDbModel transfer = theEntry.getFromMultisig.apply(transaction);
		if (null == transfer) {
			return 0;
		}

		sendList.add(this.createSend(transfer.getSender().getId(), theEntry.type, height, id));
		final Collection<Address> cosignatories = this.cosignatoriesLookup
				.apply(Address.fromEncoded(transfer.getSender().getPrintableKey()));
		final Collection<Long> accountIds = this.getAccountIds(cosignatories);
		accountIds.stream().forEach(accountId -> sendList.add(this.createSend(accountId, theEntry.type, height, id)));

		final DbAccount recipient = theEntry.getRecipient.apply(transfer);
		if (null != recipient) {
			receiveList.add(this.createReceive(recipient.getId(), theEntry.type, height, id));
		}

		theEntry.getOtherAccounts.apply(transfer).stream()
				.forEach(a -> receiveList.add(this.createReceive(a.getId(), theEntry.type, height, id)));

		return theEntry.type;
	}

	private DbMultisigSend createSend(final Long accountId, final Integer type, final Long height, final Long transactionId) {
		final DbMultisigSend send = new DbMultisigSend();
		send.setAccountId(accountId);
		send.setType(type);
		send.setHeight(height);
		send.setTransactionId(transactionId);
		return send;
	}

	private DbMultisigReceive createReceive(final Long accountId, final Integer type, final Long height, final Long transactionId) {
		final DbMultisigReceive receive = new DbMultisigReceive();
		receive.setAccountId(accountId);
		receive.setType(type);
		receive.setHeight(height);
		receive.setTransactionId(transactionId);
		return receive;
	}

	@Override
	@Transactional
	public void save(final DbBlock block) {
		this.saveSingleBlock(block);

		this.getCurrentSession().flush();
		this.getCurrentSession().clear();
	}

	@Override
	@Transactional
	public void save(final Collection<DbBlock> dbBlocks) {
		dbBlocks.forEach(this::saveSingleBlock);
		this.getCurrentSession().flush();
		this.getCurrentSession().clear();
	}

	@Override
	@Transactional(readOnly = true)
	public Long count() {
		return (Long) this.getCurrentSession().createQuery("select count (*) from DbBlock").uniqueResult();
	}

	// region find*

	@Override
	@Transactional(readOnly = true)
	public DbBlock findByHeight(final BlockHeight height) {
		final BlockLoader blockLoader = new BlockLoader(this.sessionFactory.getCurrentSession());
		final List<DbBlock> blocks = blockLoader.loadBlocks(height, height);
		if (blocks.isEmpty()) {
			return null;
		}

		return blocks.get(0);
	}

	@Transactional(readOnly = true)
	private DbBlock findById(final Long id) {
		final BlockLoader blockLoader = new BlockLoader(this.sessionFactory.getCurrentSession());
		return blockLoader.getBlockById(id);
	}

	// endregion

	@Override
	@Transactional(readOnly = true)
	public HashChain getHashesFrom(final BlockHeight height, final int limit) {
		final List<byte[]> blockList = this.prepareCriteriaGetFor("blockHash", height, limit);
		return HashChain.fromRawHashes(blockList);
	}

	@Override
	@Transactional(readOnly = true)
	public List<BlockDifficulty> getDifficultiesFrom(final BlockHeight height, final int limit) {
		final List<Long> rawDifficulties = this.prepareCriteriaGetFor("difficulty", height, limit);
		return rawDifficulties.stream().map(BlockDifficulty::new).collect(Collectors.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public List<TimeInstant> getTimeStampsFrom(final BlockHeight height, final int limit) {
		final List<Integer> rawTimeStamps = this.prepareCriteriaGetFor("timeStamp", height, limit);
		return rawTimeStamps.stream().map(TimeInstant::new).collect(Collectors.toList());
	}

	@Override
	@Transactional(readOnly = true)
	public Collection<DbBlock> getBlocksForAccount(final Account account, final Long id, final int limit) {
		final long height = null == id ? Long.MAX_VALUE : this.findById(id).getHeight();
		return this.getLatestBlocksForAccount(account, height, limit);
	}

	private Collection<DbBlock> getLatestBlocksForAccount(final Account account, final long height, final int limit) {
		// note that since h2 is not very good in optimizing the execution plan we should limit the returned
		// result set as early as possible or it will scan through the entire index.
		// note also that it is better to use a union because otherwise we have an OR in the where clause which
		// will prevent h2 from using an index to speed up the query.
		final Long accountId = this.getAccountId(account);
		final String queryString = "((SELECT b.* FROM blocks b WHERE height<:height AND harvesterId=:accountId limit :limit) UNION "
				+ "(SELECT b.* FROM blocks b WHERE height<:height AND harvestedInName=:accountId limit :limit)) "
				+ "ORDER BY height DESC limit :limit";
		final Query query = this.getCurrentSession().createNativeQuery(queryString) // preserve-newline
				.addEntity(DbBlock.class) // preserve-newline
				.setParameter("height", height) // preserve-newline
				.setParameter("accountId", accountId) // preserve-newline
				.setParameter("limit", limit);
		return HibernateUtils.listAndCast(query);
	}

	private Long getAccountId(final Account account) {
		return DaoUtils.getAccountId(this.getCurrentSession(), account.getAddress());
	}

	private Collection<Long> getAccountIds(final Collection<Address> addresses) {
		return DaoUtils.getAccountIds(this.getCurrentSession(), addresses);
	}

	@Override
	@Transactional
	public Collection<DbBlock> getBlocksAfter(final BlockHeight height, final int limit) {
		final BlockLoader blockLoader = new BlockLoader(this.sessionFactory.getCurrentSession());
		final long start = System.currentTimeMillis();
		final List<DbBlock> dbBlocks = blockLoader.loadBlocks(height.next(), new BlockHeight(height.getRaw() + limit));
		final long stop = System.currentTimeMillis();
		LOGGER.info(String.format("loadBlocks (from height %d to height %d) needed %dms", height.getRaw() + 1, height.getRaw() + limit,
				stop - start));
		return dbBlocks;
	}

	@Override
	@Transactional
	public Collection<DbBlock> getBlocksAfterAndUpdateCache(final BlockHeight height, final int limit) {
		final Collection<DbBlock> dbBlocks = this.getBlocksAfter(height, limit);
		dbBlocks.forEach(this::addToMosaicIdsCache);
		return dbBlocks;
	}

	@Override
	@Transactional
	public void deleteBlocksAfterHeight(final BlockHeight blockHeight) {
		// apparently delete on blocks is not enough, as
		// delete is not cascaded :/
		//
		// "A delete operation only applies to entities of the specified class and its subclasses.
		// It does not cascade to related entities."

		// DbMultisigTransaction needs to dropped first because it has foreign key references to the other
		// transaction tables; attempting to delete other transactions first will break referential integrity
		// Be sure to delete the entries from the auxiliary tables when deleting the db multisig transactions
		// because it depends on the multisig transaction ids.
		this.dropMultisigTransactions(blockHeight);
		this.dropTransferTransactions(blockHeight);
		this.dropTransfers(blockHeight, "DbImportanceTransferTransaction", "ImportanceTransfers", v -> {
		});
		this.dropMultisigAggregateModificationTransactions(blockHeight);
		this.dropProvisionNamespaceTransactions(blockHeight);
		this.dropMosaicDefinitionCreationTransactions(blockHeight);
		this.dropTransfers(blockHeight, "DbMosaicSupplyChangeTransaction", "MosaicSupplyChanges", v -> {
		});
		final Query query = this.getCurrentSession().createQuery("delete from DbBlock a where a.height > :height") // preserve-newline
				.setParameter("height", blockHeight.getRaw());
		query.executeUpdate();
		this.sessionFactory.getCurrentSession().flush();
		this.sessionFactory.getCurrentSession().clear();
	}

	private void dropTransferTransactions(final BlockHeight blockHeight) {
		this.dropTransfers(blockHeight, "DbTransferTransaction", "Transfers", transactionsToDelete -> {
			// TODO 20151124 J-B: consider refactoring into createDeleteQuery("DbMosaic", "transferTransaction.id", transactions)
			final MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
			Query preQuery = this.getCurrentSession()
					.createQuery("delete from DbMosaic m where m.transferTransaction.id >= :min AND transferTransaction.id <= :max")
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			preQuery.executeUpdate();
		});
	}

	private void dropMultisigTransactions(final BlockHeight blockHeight) {
		this.dropTransfers(blockHeight, "DbMultisigTransaction", "MultisigTransactions", transactionsToDelete -> {
			final MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
			Query preQuery = this.getCurrentSession().createQuery(
					"delete from DbMultisigSignatureTransaction m where m.multisigTransaction.id >= :min AND m.multisigTransaction.id <= :max")
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			preQuery.executeUpdate();
			preQuery = this.getCurrentSession()
					.createQuery("delete from DbMultisigSend s where s.transactionId >= :min AND s.transactionId <= :max")
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			preQuery.executeUpdate();
			preQuery = this.getCurrentSession()
					.createQuery("delete from DbMultisigReceive r where r.transactionId >= :min AND r.transactionId <= :max")
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			preQuery.executeUpdate();
		});
	}

	private void dropMultisigAggregateModificationTransactions(final BlockHeight blockHeight) {
		final List<Long> minCosignatoriesModificationIds = new ArrayList<>();
		this.dropTransfers(blockHeight, "DbMultisigAggregateModificationTransaction", "MultisigSignerModifications",
				transactionsToDelete -> {
					final MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
					Query preQuery = this.getCurrentSession().createQuery(
							"delete from DbMultisigModification m where m.multisigAggregateModificationTransaction.id >= :min AND m.multisigAggregateModificationTransaction.id <= :max")
							.setParameter("min", minMaxLong.min) // preserve-newline
							.setParameter("max", minMaxLong.max);
					preQuery.executeUpdate();
					preQuery = this.getCurrentSession().createQuery(
							"select tx.multisigMinCosignatoriesModification.id from DbMultisigAggregateModificationTransaction tx where tx.id >= :min AND tx.id <= :max")
							.setParameter("min", minMaxLong.min) // preserve-newline
							.setParameter("max", minMaxLong.max);
					minCosignatoriesModificationIds.addAll(HibernateUtils.listAndCast(preQuery));
				});

		final MinMaxLong minMaxLong = getMinMax(minCosignatoriesModificationIds);
		final Query deleteQuery = this.getCurrentSession()
				.createQuery("delete from DbMultisigMinCosignatoriesModification t where t.id >= :min AND t.id <= :max")
				.setParameter("min", minMaxLong.min) // preserve-newline
				.setParameter("max", minMaxLong.max);
		deleteQuery.executeUpdate();
	}

	private void dropProvisionNamespaceTransactions(final BlockHeight blockHeight) {
		final List<Long> namespaceIds = new ArrayList<>();
		this.dropTransfers(blockHeight, "DbProvisionNamespaceTransaction", "NamespaceProvisions", transactionsToDelete -> {
			final MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
			final Query preQuery = this.getCurrentSession()
					.createQuery("select tx.namespace.id from DbProvisionNamespaceTransaction tx where tx.id >= :min AND tx.id <= :max")
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			namespaceIds.addAll(HibernateUtils.listAndCast(preQuery));
		});

		final MinMaxLong minMaxLong = getMinMax(namespaceIds);
		final Query deleteQuery = this.getCurrentSession().createQuery("delete from DbNamespace t where t.id >= :min AND t.id <= :max")
				.setParameter("min", minMaxLong.min) // preserve-newline
				.setParameter("max", minMaxLong.max);
		deleteQuery.executeUpdate();
	}

	private void dropMosaicDefinitionCreationTransactions(final BlockHeight blockHeight) {
		final List<Long> mosaicIds = new ArrayList<>();
		final List<Long> mosaicPropertyIds = new ArrayList<>();
		this.dropTransfers(blockHeight, "DbMosaicDefinitionCreationTransaction", "MosaicDefinitionCreationTransactions",
				transactionsToDelete -> {
					MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
					Query query = this.getCurrentSession().createQuery(
							"select tx.mosaicDefinition.id from DbMosaicDefinitionCreationTransaction tx where tx.id >= :min AND tx.id <= :max")
							.setParameter("min", minMaxLong.min) // preserve-newline
							.setParameter("max", minMaxLong.max);
					mosaicIds.addAll(HibernateUtils.listAndCast(query));
					minMaxLong = getMinMax(mosaicIds);
					query = this.getCurrentSession().createQuery(
							"select mp.id from DbMosaicProperty mp where mp.mosaicDefinition.id >= :min AND mp.mosaicDefinition.id <= :max")
							.setParameter("min", minMaxLong.min) // preserve-newline
							.setParameter("max", minMaxLong.max);
					mosaicPropertyIds.addAll(HibernateUtils.listAndCast(query));
				});
		mosaicIds.forEach(id -> this.mosaicIdCache.remove(new DbMosaicId(id)));
		MinMaxLong minMaxLong = getMinMax(mosaicPropertyIds);
		Query query = this.getCurrentSession().createQuery("delete from DbMosaicProperty mp where mp.id >= :min AND mp.id <= :max")
				.setParameter("min", minMaxLong.min) // preserve-newline
				.setParameter("max", minMaxLong.max);
		query.executeUpdate();
		minMaxLong = getMinMax(mosaicIds);
		query = this.getCurrentSession().createQuery("delete from DbMosaicDefinition m where m.id >= :min AND m.id <= :max")
				.setParameter("min", minMaxLong.min) // preserve-newline
				.setParameter("max", minMaxLong.max);
		query.executeUpdate();
	}

	private void dropTransfers(final BlockHeight blockHeight, final String tableName, final String transfersName,
			final Consumer<List<Long>> preQuery) {
		final Query getTransactionIdsQuery = this.getCurrentSession()
				.createNativeQuery("select tx.id as txid from blocks b left outer join " + transfersName
						+ " tx on b.id=tx.blockid where b.height > :height and tx.id is not null") // preserve-newline
				.addScalar("txid", Long.class) // preserve-newline
				.setParameter("height", blockHeight.getRaw());
		final List<Long> transactionsToDelete = HibernateUtils.listAndCast(getTransactionIdsQuery);

		if (!transactionsToDelete.isEmpty()) {
			preQuery.accept(transactionsToDelete);

			final MinMaxLong minMaxLong = getMinMax(transactionsToDelete);
			final Query dropTxes = this.getCurrentSession()
					.createQuery("delete from " + tableName + " t where t.id >= :min AND t.id <= :max") // preserve-newline
					.setParameter("min", minMaxLong.min) // preserve-newline
					.setParameter("max", minMaxLong.max);
			dropTxes.executeUpdate();
		}
	}

	private static MinMaxLong getMinMax(final Collection<Long> ids) {
		final Long[] minMax = {
				Long.MAX_VALUE, Long.MIN_VALUE
		};
		ids.stream().filter(id -> null != id).forEach(id -> {
			minMax[0] = minMax[0] > id ? id : minMax[0];
			minMax[1] = minMax[1] < id ? id : minMax[1];
		});

		return new MinMaxLong(minMax[0], minMax[1]);
	}

	private <T> List<T> prepareCriteriaGetFor(final String name, final BlockHeight height, final int limit) {
		final Query<?> query = this.getCurrentSession()
				.createQuery("select b." + name + " from DbBlock b where b.height >= :height order by b.height asc", Object.class)
				.setParameter("height", height.getRaw())
				.setMaxResults(limit);
		@SuppressWarnings("unchecked")
		final List<T> values = (List<T>) (List<?>) query.getResultList();
		return values;
	}

	private void addToMosaicIdsCache(final DbBlock block) {
		// make copies of DbMosaicId because hibernate might have issues if the original objects are modified
		getDbMosaicDefinitionCreationTransactions(block).stream().map(DbMosaicDefinitionCreationTransaction::getMosaicDefinition)
				.forEach(m -> this.mosaicIdCache.add(createMosaicId(m), new DbMosaicId(m.getId())));
	}

	private static List<DbMosaicDefinitionCreationTransaction> getDbMosaicDefinitionCreationTransactions(final DbBlock block) {
		final List<DbMosaicDefinitionCreationTransaction> transactions = new ArrayList<>(
				block.getBlockMosaicDefinitionCreationTransactions());
		transactions.addAll(block.getBlockMultisigTransactions().stream().map(DbMultisigTransaction::getMosaicDefinitionCreationTransaction)
				.filter(t -> null != t).collect(Collectors.toList()));
		return transactions;
	}

	private static MosaicId createMosaicId(final DbMosaicDefinition dbMosaicDefinition) {
		final NamespaceId namespaceId = new NamespaceId(dbMosaicDefinition.getNamespaceId());
		return new MosaicId(namespaceId, dbMosaicDefinition.getName());
	}

	private static class MinMaxLong {
		private final Long min;
		private final Long max;

		private MinMaxLong(final Long min, final Long max) {
			this.min = min;
			this.max = max;
		}
	}
}
