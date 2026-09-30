package org.nem.nis.dao;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nem.core.crypto.Hash;
import org.nem.core.test.Utils;
import org.nem.nis.dbmodel.DbAccount;
import org.nem.nis.dbmodel.DbBlock;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

/** Exercises Hibernate 7 persistence operations against the synthetic legacy Flyway schema. */
@ContextConfiguration(classes = TestConf.class)
@SuppressWarnings("deprecation")
@RunWith(SpringRunner.class)
public class Hibernate7PersistenceRuntimeTest {
	@Autowired
	private SessionFactory sessionFactory;

	@Test
	public void sessionFactoryCommitsAndRollsBackAgainstFlywaySchema() {
		Assert.assertTrue(this.sessionFactory.isOpen());

		final DbAccount committed = new DbAccount(Utils.generateRandomAddress());
		try (Session session = this.sessionFactory.openSession()) {
			final Transaction transaction = session.beginTransaction();
			session.persist(committed);
			transaction.commit();
		}
		Assert.assertNotNull(committed.getId());
		try (Session session = this.sessionFactory.openSession()) {
			final DbAccount reloaded = session.createSelectionQuery("from DbAccount a where a.id = :id", DbAccount.class)
					.setParameter("id", committed.getId()).getSingleResultOrNull();
			Assert.assertNotNull(reloaded);
			Assert.assertEquals(committed.getPrintableKey(), reloaded.getPrintableKey());
		}

		final DbAccount rolledBack = new DbAccount(Utils.generateRandomAddress());
		try (Session session = this.sessionFactory.openSession()) {
			final Transaction transaction = session.beginTransaction();
			session.persist(rolledBack);
			transaction.rollback();
		}
		try (Session session = this.sessionFactory.openSession()) {
			final DbAccount absent = session.createSelectionQuery("from DbAccount a where a.printableKey = :key", DbAccount.class)
					.setParameter("key", rolledBack.getPrintableKey()).getSingleResultOrNull();
			Assert.assertNull(absent);
		}
	}

	@Test
	public void closingSpringPersistenceContextClosesSessionFactory() {
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConf.class);
		final SessionFactory contextSessionFactory = context.getBean(SessionFactory.class);
		Assert.assertTrue(contextSessionFactory.isOpen());

		context.close();

		Assert.assertFalse(contextSessionFactory.isOpen());
	}

	@Test
	public void blockCascadePersistsHarvesterAndBlockTransactionsRemainLazy() {
		final DbBlock block = new DbBlock();
		block.setVersion(1);
		block.setPrevBlockHash(Hash.ZERO);
		block.setBlockHash(Utils.generateRandomHash());
		block.setGenerationHash(Hash.ZERO);
		block.setTimeStamp(1);
		block.setHarvester(new DbAccount(Utils.generateRandomAddress()));
		block.setHarvesterProof(new byte[66]);
		block.setHeight(1L);
		block.setTotalFee(0L);
		block.setDifficulty(1L);

		try (Session session = this.sessionFactory.openSession()) {
			final Transaction transaction = session.beginTransaction();
			session.persist(block);
			transaction.commit();
		}

		try (Session session = this.sessionFactory.openSession()) {
			final DbBlock reloaded = session.find(DbBlock.class, block.getId());
			Assert.assertNotNull(reloaded.getHarvester().getId());
			Assert.assertFalse(this.sessionFactory.getPersistenceUnitUtil().isLoaded(reloaded, "blockTransferTransactions"));
			Assert.assertTrue(reloaded.getBlockTransferTransactions().isEmpty());
			Assert.assertTrue(this.sessionFactory.getPersistenceUnitUtil().isLoaded(reloaded, "blockTransferTransactions"));
		}
	}
}
