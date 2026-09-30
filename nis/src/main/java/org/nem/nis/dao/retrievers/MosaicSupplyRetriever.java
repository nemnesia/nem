package org.nem.nis.dao.retrievers;

import java.util.*;
import org.hibernate.*;
import org.nem.core.model.*;
import org.nem.core.model.mosaic.MosaicId;
import org.nem.core.model.namespace.NamespaceId;
import org.nem.core.model.primitive.*;
import org.nem.nis.dao.*;
import org.nem.nis.dbmodel.*;

/**
 * Class for for retrieving mosaic supplies.
 */
@SuppressWarnings("deprecation")
public class MosaicSupplyRetriever {
	final private int namespaceLifetime;

	/**
	 * Creates a retriever with default settings.
	 */
	public MosaicSupplyRetriever() {
		this(NemGlobals.getBlockChainConfiguration().getEstimatedBlocksPerDay() * (365 + 30 + 1));
	}

	/**
	 * Creates a retriever with custom namespace lifetime.
	 *
	 * @param namespaceLifetime Number of blocks a namespace is active before being pruned (includes grace period).
	 */
	public MosaicSupplyRetriever(final int namespaceLifetime) {
		this.namespaceLifetime = namespaceLifetime;
	}

	/**
	 * Gets a mosaic definition and its corresponding supply at a specified height.
	 *
	 * @param session The session.
	 * @param mosaicId The mosaic id.
	 * @param height The search height.
	 * @return The db mosaic definition and supply tuple, if found. \c null otherwise.
	 */
	public DbMosaicDefinitionSupplyTuple getMosaicDefinitionWithSupply(final Session session, final MosaicId mosaicId, final Long height) {
		final List<DbMosaicDefinitionCreationTransaction> creationTransactions = MosaicSupplyRetriever.queryCreationTransactions(session,
				mosaicId, height);

		Long supply = 0L;
		Long firstCreationHeight = 0L;
		Long lastCreationHeight = 0L;
		DbMosaicDefinition matchingMosaicDefinition = null;
		final Collection<Long> dbMosaicDefinitionIds = new HashSet<>();
		for (final DbMosaicDefinitionCreationTransaction transaction : creationTransactions) {
			final DbMosaicDefinition mosaicDefinition = transaction.getMosaicDefinition();
			if (null != matchingMosaicDefinition) {
				if (!MosaicSupplyRetriever.areDefinitionsEqual(matchingMosaicDefinition, mosaicDefinition)) {
					break;
				}
			} else {
				final DbMosaicProperty mosaicProperty = MosaicSupplyRetriever.findPropertyByName(mosaicDefinition, "initialSupply");
				supply = Long.parseLong(mosaicProperty.getValue(), 10);
				matchingMosaicDefinition = mosaicDefinition;
				lastCreationHeight = transaction.getBlock().getHeight();
			}

			firstCreationHeight = transaction.getBlock().getHeight();
			dbMosaicDefinitionIds.add(mosaicDefinition.getId());
		}

		if (0L == firstCreationHeight)
			return null;

		final List<DbMosaicSupplyChangeTransaction> supplyChangeTransactions = MosaicSupplyRetriever.querySupplyChangeTransactions(session,
				dbMosaicDefinitionIds, firstCreationHeight, height);

		for (final DbMosaicSupplyChangeTransaction transaction : supplyChangeTransactions) {
			if (MosaicSupplyType.Create.value() == transaction.getSupplyType()) {
				supply += transaction.getQuantity();
			} else {
				supply -= transaction.getQuantity();
			}
		}

		final Long expirationHeight = this.findExpirationHeight(session, matchingMosaicDefinition.getNamespaceId(), lastCreationHeight);

		return new DbMosaicDefinitionSupplyTuple(matchingMosaicDefinition, new Supply(supply), new BlockHeight(expirationHeight));
	}

	private static List<DbMosaicDefinitionCreationTransaction> queryCreationTransactions(final Session session, final MosaicId mosaicId,
			final Long height) {
		final String hql = "select t from DbMosaicDefinitionCreationTransaction t join fetch t.sender join t.block b "
				+ "join t.mosaicDefinition m where m.namespaceId = :namespaceId and m.name = :name and b.height <= :height "
				+ "order by b.height desc, t.blkIndex desc";
		return session.createQuery(hql, DbMosaicDefinitionCreationTransaction.class)
				.setParameter("namespaceId", mosaicId.getNamespaceId().toString()).setParameter("name", mosaicId.getName())
				.setParameter("height", height).getResultList();
	}

	private static List<DbMosaicSupplyChangeTransaction> querySupplyChangeTransactions(final Session session,
			final Collection<Long> dbMosaicDefinitionIds, final Long startHeight, final Long endHeight) {
		final String hql = "select t from DbMosaicSupplyChangeTransaction t join fetch t.sender join t.block b "
				+ "where t.dbMosaicId in :definitionIds and b.height >= :startHeight and b.height <= :endHeight order by b.height desc";
		return session.createQuery(hql, DbMosaicSupplyChangeTransaction.class).setParameter("definitionIds", dbMosaicDefinitionIds)
				.setParameter("startHeight", startHeight).setParameter("endHeight", endHeight).getResultList();
	}

	private static DbNamespace queryLastRootNamespace(final Session session, final String namespaceId, final Long maxHeight) {
		final NamespaceId rootNamespaceId = new NamespaceId(namespaceId).getRoot();
		return session.createQuery("select n from DbNamespace n where n.fullName = :name and n.height < :height order by n.height desc",
				DbNamespace.class).setParameter("name", rootNamespaceId.toString()).setParameter("height", maxHeight)
				.setMaxResults(1).getSingleResultOrNull();
	}

	private static List<DbNamespace> querySubsequentRootNamespaces(final Session session, final String namespaceId, final Long minHeight) {
		final NamespaceId rootNamespaceId = new NamespaceId(namespaceId).getRoot();
		return session.createQuery("select n from DbNamespace n where n.fullName = :name and n.height > :height order by n.height asc",
				DbNamespace.class).setParameter("name", rootNamespaceId.toString()).setParameter("height", minHeight).getResultList();
	}

	private Long findExpirationHeight(final Session session, final String namespaceId, final Long height) {
		final DbNamespace lastRootNamespace = MosaicSupplyRetriever.queryLastRootNamespace(session, namespaceId, height);
		final List<DbNamespace> subsequentRootNamespaces = MosaicSupplyRetriever.querySubsequentRootNamespaces(session, namespaceId,
				height);

		DbNamespace activeRootNamespace = lastRootNamespace;
		for (final DbNamespace subsequentRootNamespace : subsequentRootNamespaces) {
			if (subsequentRootNamespace.getHeight() > activeRootNamespace.getHeight() + this.namespaceLifetime) {
				// subsequentRootNamespace was registered after grace period expiration and pruning
				// so it should be treated as distinct
				break;
			}

			activeRootNamespace = subsequentRootNamespace;
		}

		return activeRootNamespace.getHeight() + this.namespaceLifetime;
	}

	private static DbMosaicProperty findPropertyByName(final DbMosaicDefinition mosaicDefinition, final String name) {
		return mosaicDefinition.getProperties().stream().filter(property -> property.getName().equals(name)).findFirst().get();
	}

	private static Boolean areDefinitionsEqual(final DbMosaicDefinition lhs, final DbMosaicDefinition rhs) {
		// check mosaic properties
		for (final DbMosaicProperty property : lhs.getProperties()) {
			if (!property.getValue().equals(MosaicSupplyRetriever.findPropertyByName(rhs, property.getName()).getValue())) {
				return false;
			}
		}

		// check other properties
		return lhs.getCreator().getId() == rhs.getCreator().getId() // preserve-newline
				&& lhs.getFeeType() == rhs.getFeeType() // preserve-newline
				&& lhs.getFeeDbMosaicId() == rhs.getFeeDbMosaicId() // preserve-newline
				&& lhs.getFeeQuantity() == rhs.getFeeQuantity() // preserve-newline
				&& (null == lhs.getFeeRecipient()) == (null == rhs.getFeeRecipient())
				&& (null == lhs.getFeeRecipient() || lhs.getFeeRecipient().getId() == rhs.getFeeRecipient().getId());
	}
}
