package org.nem.nis.dao.retrievers;

import java.util.*;
import java.util.stream.Collectors;
import org.hibernate.*;
import org.nem.nis.dao.*;
import org.nem.nis.dbmodel.*;

/**
 * Class for for retrieving provision namespace transactions.
 */
@SuppressWarnings("deprecation")
public class ProvisionNamespaceRetriever implements TransactionRetriever {

	@Override
	public Collection<TransferBlockPair> getTransfersForAccount(final Session session, final long accountId, final long maxId,
			final int limit, final ReadOnlyTransferDao.TransferType transferType) {
		if (ReadOnlyTransferDao.TransferType.ALL == transferType) {
			throw new IllegalArgumentException("transfer type ALL not supported by transaction retriever classes");
		}

		final String senderOrRentalFeeSink = ReadOnlyTransferDao.TransferType.OUTGOING.equals(transferType) ? "sender" : "rentalFeeSink";
		final String hql = "select distinct t from DbProvisionNamespaceTransaction t join fetch t.block join fetch t.sender "
				+ "join fetch t.rentalFeeSink join fetch t.namespace where t." + senderOrRentalFeeSink
				+ ".id = :accountId and t.senderProof is not null and t.id < :maxId order by t." + senderOrRentalFeeSink + ".id asc, t.id desc";
		final List<DbProvisionNamespaceTransaction> list = session.createQuery(hql, DbProvisionNamespaceTransaction.class)
				.setParameter("accountId", accountId).setParameter("maxId", maxId).setMaxResults(limit).getResultList();
		return list.stream().map(t -> new TransferBlockPair(t, t.getBlock())).collect(Collectors.toList());
	}
}
