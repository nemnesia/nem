package org.nem.nis.dbmodel;

import java.util.*;
import jakarta.persistence.*;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.*;

/**
 * Transfer Db entity. <br>
 * Holds information about Transactions having type TransactionTypes.TRANSFER_TYPE <br>
 * Associated sender and recipient are obtained automatically (by TransferDao) thanks to @Cascade annotations.
 */
@Entity
@Table(name = "transfers")
public class DbTransferTransaction extends AbstractBlockTransfer<DbTransferTransaction> {
	@ManyToOne(cascade = { jakarta.persistence.CascadeType.PERSIST, jakarta.persistence.CascadeType.MERGE })
	@JoinColumn(name = "recipientId")
	private DbAccount recipient;

	private Long amount;

	private Integer messageType;
	private byte[] messagePayload;

	@OneToMany(cascade = jakarta.persistence.CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "transferTransaction", orphanRemoval = true)
	private Collection<DbMosaic> mosaics = new ArrayList<>();

	public DbAccount getRecipient() {
		return this.recipient;
	}

	public void setRecipient(final DbAccount recipient) {
		this.recipient = recipient;
	}

	public Long getAmount() {
		return this.amount;
	}

	public void setAmount(final Long amount) {
		this.amount = amount;
	}

	public Integer getMessageType() {
		return this.messageType;
	}

	public void setMessageType(final Integer messageType) {
		this.messageType = messageType;
	}

	public byte[] getMessagePayload() {
		return this.messagePayload;
	}

	public void setMessagePayload(final byte[] messagePayload) {
		this.messagePayload = messagePayload;
	}

	public Collection<DbMosaic> getMosaics() {
		return this.mosaics;
	}

	public void setMosaics(final Set<DbMosaic> mosaics) {
		this.mosaics = mosaics;
	}
}
