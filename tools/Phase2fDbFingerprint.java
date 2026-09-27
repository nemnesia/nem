import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-only JDBC inventory and deterministic NIS database fingerprint.
 * Requires an H2 driver on the classpath and an ACCESS_MODE_DATA=r,
 * IFEXISTS=TRUE file URL. Credentials are read only from environment vars.
 */
public final class Phase2fDbFingerprint {
	private static final Path REPO_ROOT = Path.of("").toAbsolutePath().normalize();
	private static final List<String> TRANSACTION_TABLES = List.of(
			"transfers", "importancetransfers", "mosaicdefinitioncreationtransactions", "mosaicsupplychanges",
			"multisigsignermodifications", "multisigsignatures", "multisigtransactions", "namespaceprovisions");

	private Phase2fDbFingerprint() {
	}

	public static void main(final String[] args) throws Exception {
		if (2 != args.length) {
			throw new IllegalArgumentException("Usage: Phase2fDbFingerprint <read-only-H2-jdbc-url> <output.json>");
		}
		final String url = args[0];
		final String upperUrl = url.toUpperCase(Locale.ROOT);
		if (!upperUrl.contains("ACCESS_MODE_DATA=R") || !upperUrl.contains("IFEXISTS=TRUE")) {
			throw new IllegalArgumentException("Refusing database URL without ACCESS_MODE_DATA=r;IFEXISTS=TRUE");
		}
		final Path output = Path.of(args[1]).toAbsolutePath().normalize();
		final Path databasePath = databasePathFromUrl(url);
		if (output.startsWith(REPO_ROOT) || output.startsWith(databasePath.getParent())) {
			throw new IllegalArgumentException("Evidence output must be outside the repository and database artifact directory");
		}
		final String user = System.getenv().getOrDefault("NIS_DB_USER", "");
		final String password = System.getenv().getOrDefault("NIS_DB_PASSWORD", "");
		try (Connection connection = DriverManager.getConnection(url, user, password)) {
			connection.setReadOnly(true);
			connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
			connection.setAutoCommit(false);
			final Map<String, Object> report = fingerprint(connection);
			connection.commit();
			Files.writeString(output, json(report) + "\n", StandardCharsets.UTF_8);
		}
	}

	private static Path databasePathFromUrl(final String url) {
		final String prefix = "jdbc:h2:file:";
		if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) {
			throw new IllegalArgumentException("Only file-backed H2 URLs are accepted");
		}
		final String location = url.substring(prefix.length()).split(";", 2)[0];
		return Path.of(location).toAbsolutePath().normalize();
	}

	private static Map<String, Object> fingerprint(final Connection connection) throws SQLException, NoSuchAlgorithmException, IOException {
		final DatabaseMetaData metadata = connection.getMetaData();
		final List<Table> tables = listTables(metadata);
		final Map<String, Long> counts = new TreeMap<>();
		final List<Map<String, Object>> digests = new ArrayList<>();
		final List<Map<String, Object>> schemas = new ArrayList<>();
		long transactionCount = 0;
		final List<String> transactionTablesPresent = new ArrayList<>();
		for (final Table table : tables) {
			final TableDigest digest = digestTable(connection, table);
			counts.put(table.name.toLowerCase(Locale.ROOT), digest.rows);
			if (TRANSACTION_TABLES.contains(table.name.toLowerCase(Locale.ROOT))) {
				transactionCount += digest.rows;
				transactionTablesPresent.add(table.name.toLowerCase(Locale.ROOT));
			}
			digests.add(Map.of("name", table.name.toLowerCase(Locale.ROOT), "row_count", digest.rows, "sha256", digest.sha256));
			schemas.add(Map.of("name", table.name.toLowerCase(Locale.ROOT), "columns", table.columnDefinitions,
					"primary_key", table.primaryKey.stream().map(name -> name.toLowerCase(Locale.ROOT)).toList()));
		}
		counts.put("transactions", transactionCount);
		final BlockState blocks = readBlockState(connection);
		final Map<String, Object> report = new LinkedHashMap<>();
		report.put("format", "nem-nis-chain-state-v1");
		report.put("database_product", metadata.getDatabaseProductName());
		report.put("database_version", metadata.getDatabaseProductVersion());
		report.put("driver_name", metadata.getDriverName());
		report.put("driver_version", metadata.getDriverVersion());
		report.put("network", blocks.network);
		report.put("network_version", String.format("0x%02x", blocks.networkByte));
		report.put("genesis_block_hash", blocks.genesisHash);
		report.put("height", blocks.height);
		report.put("tip_block_hash", blocks.tipHash);
		report.put("counts", counts);
		report.put("transaction_tables_included", transactionTablesPresent.stream().sorted().toList());
		report.put("schema", schemas);
		digests.sort(Comparator.comparing(row -> (String) row.get("name")));
		report.put("tables", digests);
		report.put("flyway_history", readHistory(connection, tables));
		return report;
	}

	private static List<Table> listTables(final DatabaseMetaData metadata) throws SQLException {
		final List<Table> tables = new ArrayList<>();
		try (ResultSet rows = metadata.getTables(null, null, "%", new String[]{"TABLE"})) {
			while (rows.next()) {
				final String schema = rows.getString("TABLE_SCHEM");
				final String name = rows.getString("TABLE_NAME");
				if (null == schema || "PUBLIC".equalsIgnoreCase(schema)) {
					final List<String> columns = new ArrayList<>();
					final List<Map<String, Object>> columnDefinitions = new ArrayList<>();
					try (ResultSet columnRows = metadata.getColumns(null, schema, name, "%")) {
						while (columnRows.next()) {
							final String columnName = columnRows.getString("COLUMN_NAME");
							columns.add(columnName);
							final Map<String, Object> column = new LinkedHashMap<>();
							column.put("name", columnName.toLowerCase(Locale.ROOT));
							final String type = canonicalType(columnRows.getString("TYPE_NAME"));
							column.put("type", type);
							column.put("size", isLengthType(type) || "DECIMAL".equals(type) || "NUMERIC".equals(type)
									? columnRows.getInt("COLUMN_SIZE") : null);
							column.put("scale", columnRows.getInt("DECIMAL_DIGITS"));
							column.put("nullable", columnRows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls);
							columnDefinitions.add(column);
						}
					}
					final Map<Short, String> keys = new TreeMap<>();
					try (ResultSet keyRows = metadata.getPrimaryKeys(null, schema, name)) {
						while (keyRows.next()) {
							keys.put(keyRows.getShort("KEY_SEQ"), keyRows.getString("COLUMN_NAME"));
						}
					}
					if (columns.isEmpty() || keys.isEmpty()) {
						throw new IllegalStateException("table lacks columns or primary key; deterministic scan refused: " + name);
					}
						tables.add(new Table(schema, name, columns, new ArrayList<>(keys.values()), columnDefinitions));
				}
			}
		}
		tables.sort(Comparator.comparing(table -> table.name.toLowerCase(Locale.ROOT)));
		return tables;
	}

	private static TableDigest digestTable(final Connection connection, final Table table)
			throws SQLException, NoSuchAlgorithmException, IOException {
		final MessageDigest digest = MessageDigest.getInstance("SHA-256");
		final String columns = String.join(",", table.columns.stream().map(Phase2fDbFingerprint::quote).toList());
		final String order = String.join(",", table.primaryKey.stream().map(Phase2fDbFingerprint::quote).toList());
		final String query = "SELECT " + columns + " FROM " + quote(table.name) + " ORDER BY " + order;
		long rows = 0;
		try (var statement = connection.createStatement(); ResultSet results = statement.executeQuery(query)) {
			final ResultSetMetaData resultMetadata = results.getMetaData();
			while (results.next()) {
				rows++;
				for (int index = 1; index <= resultMetadata.getColumnCount(); index++) {
					updateCell(digest, results, resultMetadata, index);
				}
				digest.update((byte) 0xff);
			}
		}
		return new TableDigest(rows, hex(digest.digest()));
	}

	private static void updateCell(final MessageDigest digest, final ResultSet row, final ResultSetMetaData metadata, final int index)
			throws SQLException, IOException {
		final int type = metadata.getColumnType(index);
		final Object object = row.getObject(index);
		if (null == object) {
			digest.update((byte) 0);
			return;
		}
		digest.update((byte) 1);
		final byte[] value;
		if (object instanceof byte[] bytes) {
			value = bytes;
		} else if (object instanceof Blob blob) {
			try (var stream = blob.getBinaryStream(); var buffer = new ByteArrayOutputStream()) {
				stream.transferTo(buffer);
				value = buffer.toByteArray();
			}
		} else if (object instanceof Clob clob) {
			value = clob.getSubString(1, Math.toIntExact(clob.length())).getBytes(StandardCharsets.UTF_8);
		} else if (isNumeric(type)) {
			value = row.getBigDecimal(index).stripTrailingZeros().toPlainString().getBytes(StandardCharsets.UTF_8);
		} else {
			value = row.getString(index).getBytes(StandardCharsets.UTF_8);
		}
		final int length = value.length;
		digest.update((byte) (length >>> 24));
		digest.update((byte) (length >>> 16));
		digest.update((byte) (length >>> 8));
		digest.update((byte) length);
		digest.update(value);
	}

	private static boolean isNumeric(final int type) {
		return switch (type) {
			case Types.BIGINT, Types.DECIMAL, Types.DOUBLE, Types.FLOAT, Types.INTEGER, Types.NUMERIC, Types.REAL, Types.SMALLINT, Types.TINYINT -> true;
			default -> false;
		};
	}

	private static BlockState readBlockState(final Connection connection) throws SQLException {
		long height = -1;
		String genesisHash = null;
		String tipHash = null;
		int networkByte = -1;
		final String query = "SELECT HEIGHT, VERSION, BLOCKHASH FROM BLOCKS WHERE HEIGHT = 1 OR HEIGHT = (SELECT MAX(HEIGHT) FROM BLOCKS) ORDER BY HEIGHT";
		try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query)) {
			while (rows.next()) {
				final long rowHeight = rows.getLong(1);
				final int version = rows.getInt(2);
				final String hash = hex(rows.getBytes(3));
				if (1 == rowHeight) {
					genesisHash = hash;
					networkByte = (version >>> 24) & 0xff;
				}
				height = rowHeight;
				tipHash = hash;
			}
		}
		if (null == genesisHash || null == tipHash || height < 1) {
			throw new IllegalStateException("database has no readable genesis/tip blocks");
		}
		final String network = switch (networkByte) {
			case 0x68 -> "mainnet";
			case 0x98 -> "testnet";
			default -> "unknown";
		};
		return new BlockState(height, genesisHash, tipHash, networkByte, network);
	}

	private static Map<String, Object> readHistory(final Connection connection, final List<Table> tables) throws SQLException {
		final Table history = tables.stream().filter(table -> "schema_version".equalsIgnoreCase(table.name)).findFirst()
				.orElseThrow(() -> new IllegalStateException("legacy Flyway schema_version table missing"));
		final String columns = String.join(",", history.columns.stream().map(Phase2fDbFingerprint::quote).toList());
		final String orderColumn = history.columns.stream().filter(column -> "installed_rank".equalsIgnoreCase(column)).findFirst()
				.orElse(history.primaryKey.get(0));
		final List<Map<String, Object>> rowsOut = new ArrayList<>();
		try (var statement = connection.createStatement();
				ResultSet rows = statement.executeQuery("SELECT " + columns + " FROM " + quote(history.name) + " ORDER BY " + quote(orderColumn))) {
			final ResultSetMetaData metadata = rows.getMetaData();
			while (rows.next()) {
				final Map<String, Object> values = new LinkedHashMap<>();
				for (int index = 1; index <= metadata.getColumnCount(); index++) {
					final Object value = rows.getObject(index);
					values.put(metadata.getColumnLabel(index).toLowerCase(Locale.ROOT), null == value ? null : display(value));
				}
				rowsOut.add(values);
			}
		}
		return Map.of("table", history.name.toLowerCase(Locale.ROOT), "columns", history.columns, "rows", rowsOut);
	}

	private static String display(final Object value) throws SQLException {
		if (value instanceof byte[] bytes) {
			return hex(bytes);
		}
		if (value instanceof Blob blob) {
			return hex(blob.getBytes(1, Math.toIntExact(blob.length())));
		}
		if (value instanceof Clob clob) {
			return clob.getSubString(1, Math.toIntExact(clob.length()));
		}
		return value.toString();
	}

	private static String quote(final String identifier) {
		return "\"" + identifier.replace("\"", "\"\"") + "\"";
	}

	private static String canonicalType(final String typeName) {
		return switch (typeName.toUpperCase(Locale.ROOT)) {
			case "CHARACTER VARYING" -> "VARCHAR";
			case "BINARY VARYING" -> "VARBINARY";
			default -> typeName.toUpperCase(Locale.ROOT);
		};
	}

	private static boolean isLengthType(final String typeName) {
		return switch (typeName) {
			case "CHAR", "VARCHAR", "NCHAR", "NVARCHAR", "BINARY", "VARBINARY" -> true;
			default -> false;
		};
	}

	private static String hex(final byte[] bytes) {
		final StringBuilder result = new StringBuilder(bytes.length * 2);
		for (final byte value : bytes) {
			result.append(String.format("%02x", value & 0xff));
		}
		return result.toString();
	}

	static String json(final Object value) {
		if (null == value) {
			return "null";
		}
		if (value instanceof String string) {
			return quoteJson(string);
		}
		if (value instanceof Number || value instanceof Boolean) {
			return value.toString();
		}
		if (value instanceof Map<?, ?> map) {
			final List<String> entries = new ArrayList<>();
			map.forEach((key, element) -> entries.add(quoteJson(key.toString()) + ":" + json(element)));
			return "{" + String.join(",", entries) + "}";
		}
		if (value instanceof Iterable<?> values) {
			final List<String> entries = new ArrayList<>();
			values.forEach(element -> entries.add(json(element)));
			return "[" + String.join(",", entries) + "]";
		}
		throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass());
	}

	private static String quoteJson(final String value) {
		final StringBuilder quoted = new StringBuilder("\"");
		for (final char character : value.toCharArray()) {
			switch (character) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> {
					if (character < 0x20) {
						quoted.append(String.format("\\u%04x", (int) character));
					} else {
						quoted.append(character);
					}
				}
			}
		}
		return quoted.append('"').toString();
	}

	private record Table(String schema, String name, List<String> columns, List<String> primaryKey,
			List<Map<String, Object>> columnDefinitions) {
	}

	private record TableDigest(long rows, String sha256) {
	}

	private record BlockState(long height, String genesisHash, String tipHash, int networkByte, String network) {
	}
}
