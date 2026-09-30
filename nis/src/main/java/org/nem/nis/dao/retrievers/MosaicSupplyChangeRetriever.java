package org.nem.nis.dao.retrievers;

import java.util.*;
import java.util.stream.Collectors;
import org.hibernate.*;
import org.nem.nis.dao.*;
import org.nem.nis.dbmodel.*;

/**
 * Class for for retrieving mosaic supply change transactions.
 */
@SuppressWarnings("deprecation")
public class MosaicSupplyChangeRetriever implements TransactionRetriever {

	@Override
	public Collection<TransferBlockPair> getTransfersForAccount(final Session session, final long accountId, final long maxId,
			final int limit, final ReadOnlyTransferDao.TransferType transferType) {
		if (ReadOnlyTransferDao.TransferType.ALL == transferType) {
			throw new IllegalArgumentException("transfer type ALL not supported by transaction retriever classes");
		}

		if (ReadOnlyTransferDao.TransferType.INCOMING == transferType) {
			return Collections.emptyList();
		}

		final String hql = "select distinct t from DbMosaicSupplyChangeTransaction t join fetch t.block join fetch t.sender "
				+ "where t.sender.id = :accountId and t.senderProof is not null and t.id < :maxId "
				+ "order by t.sender.id asc, t.id desc";
		final List<DbMosaicSupplyChangeTransaction> list = session.createQuery(hql, DbMosaicSupplyChangeTransaction.class)
				.setParameter("accountId", accountId).setParameter("maxId", maxId).setMaxResults(limit).getResultList();
		return list.stream().map(t -> new TransferBlockPair(t, t.getBlock())).collect(Collectors.toList());
	}
}
