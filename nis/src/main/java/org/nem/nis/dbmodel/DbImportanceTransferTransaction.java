package org.nem.nis.dbmodel;

import jakarta.persistence.*;
import org.hibernate.annotations.Cascade;

/**
 * Importance transfer db entity <br>
 * Holds information about Transactions having type TransactionTypes.IMPORTANCE_TYPE
 */
@Entity
@Table(name = "importancetransfers")
public class DbImportanceTransferTransaction extends AbstractBlockTransfer<DbImportanceTransferTransaction> {
	@ManyToOne(cascade = { jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE })
	@JoinColumn(name = "remoteId")
	private DbAccount remote;

	private Integer mode;

	public DbAccount getRemote() {
		return this.remote;
	}

	public void setRemote(final DbAccount remote) {
		this.remote = remote;
	}

	public Integer getMode() {
		return this.mode;
	}

	public void setMode(final Integer mode) {
		this.mode = mode;
	}
}
