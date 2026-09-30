package org.nem.nis.dao.retrievers;

import java.util.*;
import java.util.stream.Collectors;
import org.hibernate.*;
import org.nem.nis.dao.*;
import org.nem.nis.dbmodel.*;

/**
 * Class for for retrieving importance transfer transactions.
 */
@SuppressWarnings("deprecation")
public class ImportanceTransferRetriever implements TransactionRetriever {

	@Override
	public Collection<TransferBlockPair> getTransfersForAccount(final Session session, final long accountId, final long maxId,
			final int limit, final ReadOnlyTransferDao.TransferType transferType) {
		if (ReadOnlyTransferDao.TransferType.ALL == transferType) {
			throw new IllegalArgumentException("transfer type ALL not supported by transaction retriever classes");
		}

		final String senderOrRecipient = ReadOnlyTransferDao.TransferType.OUTGOING.equals(transferType) ? "sender" : "remote";
		final String hql = "select distinct t from DbImportanceTransferTransaction t join fetch t.block join fetch t.sender join fetch t.remote "
				+ "where t." + senderOrRecipient + ".id = :accountId and t.senderProof is not null and t.id < :maxId "
				+ "order by t." + senderOrRecipient + ".id asc, t.id desc";
		final List<DbImportanceTransferTransaction> list = session.createQuery(hql, DbImportanceTransferTransaction.class)
				.setParameter("accountId", accountId).setParameter("maxId", maxId).setMaxResults(limit).getResultList();
		return list.stream().map(t -> new TransferBlockPair(t, t.getBlock())).collect(Collectors.toList());
	}
}
