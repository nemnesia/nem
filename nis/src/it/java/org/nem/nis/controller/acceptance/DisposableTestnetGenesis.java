package org.nem.nis.controller.acceptance;

import java.io.IOException;
import java.nio.file.*;
import org.nem.core.crypto.*;
import org.nem.core.messages.PlainMessage;
import org.nem.core.model.*;
import org.nem.core.model.primitive.*;
import org.nem.core.serialization.BinarySerializer;
import org.nem.core.time.TimeInstant;

/**
 * Creates the deterministic, disposable Testnet-compatible genesis used by loopback controller tests.
 * The fixture credentials are public test data and must never be used on a production network.
 */
public final class DisposableTestnetGenesis {
	private static final byte TESTNET_VERSION = (byte) 0x98;
	private static final long TOTAL_AMOUNT_NEM = 9_000_000_000L;
	private static final long CONTROLLER_ACCOUNT_AMOUNT_NEM = 1_000_000L;
	private static final String GENERATION_HASH_HEX = "7a9b8c6d5e4f32100123456789abcdef0123456789abcdef0123456789abcdef";
	private static final PrivateKey NEMESIS_PRIVATE_KEY = PrivateKey
			.fromHexString("1111111111111111111111111111111111111111111111111111111111111111");
	private static final String GENESIS_RESOURCE = "nis-it-testnet-genesis.bin";

	private DisposableTestnetGenesis() {
	}

	public static void main(final String[] args) throws IOException {
		if (1 != args.length) {
			throw new IllegalArgumentException("expected the isolated NIS classpath directory");
		}

		final Path classpathDirectory = Paths.get(args[0]);
		final Hash generationHash = Hash.fromHexString(GENERATION_HASH_HEX);
		final Account nemesis = new Account(new KeyPair(NEMESIS_PRIVATE_KEY));
		final NetworkInfo testNetwork = new NetworkInfo(TESTNET_VERSION, 'T', new NemesisBlockInfo(generationHash,
				nemesis.getAddress(), Amount.fromNem(TOTAL_AMOUNT_NEM), GENESIS_RESOURCE));
		NetworkInfos.setDefault(testNetwork);

		final PrivateKey controllerPrivateKey = AcceptanceTestConstants.PRIVATE_KEY;
		final Account controllerAccount = new Account(new KeyPair(controllerPrivateKey));
		if (!controllerAccount.getAddress().getPublicKey().equals(AcceptanceTestConstants.PUBLIC_KEY)
				|| !testNetwork.isCompatible(controllerAccount.getAddress())) {
			throw new IllegalStateException("controller fixture account is not Testnet-compatible");
		}

		final Block genesis = new Block(nemesis, Hash.ZERO, generationHash, TimeInstant.ZERO, BlockHeight.ONE);
		final TransferTransaction allocation = new TransferTransaction(1, TimeInstant.ZERO, nemesis, controllerAccount,
				Amount.fromNem(CONTROLLER_ACCOUNT_AMOUNT_NEM), new TransferTransactionAttachment(new PlainMessage("CI fixture".getBytes())));
		allocation.sign();
		genesis.addTransaction(allocation);
		genesis.sign();

		final BinarySerializer serializer = new BinarySerializer();
		genesis.serialize(serializer);
		Files.createDirectories(classpathDirectory);
		Files.write(classpathDirectory.resolve(GENESIS_RESOURCE), serializer.getBytes());

		final String customNetworkProperties = String.join("\n",
				"nem.network=nis-it-testnet",
				"nem.network.version=" + Byte.toUnsignedInt(TESTNET_VERSION),
				"nem.network.addressStartChar=T",
				"nem.network.generationHash=" + GENERATION_HASH_HEX,
				"nem.network.nemesisSignerAddress=" + nemesis.getAddress().getEncoded(),
				"nem.network.totalAmount=" + TOTAL_AMOUNT_NEM,
				"nem.network.nemesisFilePath=" + GENESIS_RESOURCE,
				"");
		Files.writeString(classpathDirectory.resolve("config.properties"), customNetworkProperties, StandardOpenOption.APPEND);
		System.out.printf("Generated disposable Testnet genesis: network=0x%02x, height=1, funded=%s, balance=%d NEM%n",
				Byte.toUnsignedInt(TESTNET_VERSION), controllerAccount.getAddress(), CONTROLLER_ACCOUNT_AMOUNT_NEM);
	}
}
