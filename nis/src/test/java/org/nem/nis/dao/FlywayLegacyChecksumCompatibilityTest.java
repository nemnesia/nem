package org.nem.nis.dao;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import org.junit.Test;

/** Guards the Flyway 3.x raw-byte checksum trust anchor for shipped migrations. */
public class FlywayLegacyChecksumCompatibilityTest {
	private static final String[][] MIGRATIONS = {
			{ "V1.0.0__initial.sql", "1235287926" },
			{ "V1.0.1__min_cosignatories.sql", "-867359331" },
			{ "V1.0.2__increase_message_size.sql", "88353754" },
			{ "V1.0.3__namespace_tables.sql", "1899963525" },
			{ "V1.0.4__mosaic_tables.sql", "-1352422778" },
			{ "V1.0.5__add_accounts_index.sql", "1524875301" },
			{ "V1.0.6__increase_message_size.sql", "-1123275759" },
			{ "V1.0.7__increase_message_size.sql", "1393077514" }
	};

	@Test
	public void flyway32RawByteChecksumsMatchLegacyHistory() throws IOException {
		for (final String[] migration : MIGRATIONS) {
			final CRC32 crc = new CRC32();
			try (InputStream input = this.getClass().getResourceAsStream("/db/h2/" + migration[0])) {
				if (null == input) {
					throw new IOException("Missing migration resource: " + migration[0]);
				}

				final byte[] buffer = new byte[4096];
				int bytesRead;
				while (-1 != (bytesRead = input.read(buffer))) {
					crc.update(buffer, 0, bytesRead);
				}
			}

			assertEquals(migration[0], Integer.parseInt(migration[1]), (int) crc.getValue());
		}
	}
}
