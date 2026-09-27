import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.ValidateResult;

/** Read-only Flyway 9 info/validate probe. Never calls migrate(), repair(), or baseline(). */
public final class Phase2fFlyway9Audit {
	private static final Path REPO_ROOT = Path.of("").toAbsolutePath().normalize();
	private Phase2fFlyway9Audit() {
	}

	public static void main(final String[] args) throws Exception {
		if (4 != args.length) {
			throw new IllegalArgumentException("Usage: Phase2fFlyway9Audit <read-only-H2-jdbc-url> <locations> <history-table> <output.json>");
		}
		final String url = args[0];
		final String upperUrl = url.toUpperCase(Locale.ROOT);
		if (!upperUrl.contains("ACCESS_MODE_DATA=R") || !upperUrl.contains("IFEXISTS=TRUE")) {
			throw new IllegalArgumentException("Refusing database URL without ACCESS_MODE_DATA=r;IFEXISTS=TRUE");
		}
		final Path output = Path.of(args[3]).toAbsolutePath().normalize();
		final Path databasePath = databasePathFromUrl(url);
		if (output.startsWith(REPO_ROOT) || output.startsWith(databasePath.getParent())) {
			throw new IllegalArgumentException("Evidence output must be outside the repository and database artifact directory");
		}
		final String user = System.getenv().getOrDefault("NIS_DB_USER", "");
		final String password = System.getenv().getOrDefault("NIS_DB_PASSWORD", "");
		final Flyway flyway = Flyway.configure(Phase2fFlyway9Audit.class.getClassLoader())
				.dataSource(url, user, password)
				.locations(args[1])
				.table(args[2])
				.validateOnMigrate(false)
				.load();
		final List<Map<String, Object>> migrations = new ArrayList<>();
		for (final MigrationInfo info : flyway.info().all()) {
			final Map<String, Object> migration = new LinkedHashMap<>();
			migration.put("version", null == info.getVersion() ? null : info.getVersion().getVersion());
			migration.put("description", info.getDescription());
			migration.put("type", info.getType().name());
			migration.put("script", info.getScript());
			migration.put("checksum", info.getChecksum());
			migration.put("state", info.getState().getDisplayName());
			migration.put("installed_rank", info.getInstalledRank());
			migration.put("installed_on", null == info.getInstalledOn() ? null : info.getInstalledOn().toInstant().toString());
			migration.put("installed_by", info.getInstalledBy());
			migration.put("execution_time_ms", info.getExecutionTime());
			migrations.add(migration);
		}
		final ValidateResult validation = flyway.validateWithResult();
		final Map<String, Object> report = new LinkedHashMap<>();
		report.put("flyway_version", "9.22.3");
		report.put("history_table", args[2]);
		report.put("validate_on_migrate", false);
		report.put("validation_successful", validation.validationSuccessful);
		report.put("validation_error", validation.getAllErrorMessages());
		report.put("migrations", migrations);
		report.put("pending_count", flyway.info().pending().length);
		Files.writeString(output, Phase2fDbFingerprint.json(report) + "\n", StandardCharsets.UTF_8);
	}

	private static Path databasePathFromUrl(final String url) {
		final String prefix = "jdbc:h2:file:";
		if (!url.regionMatches(true, 0, prefix, 0, prefix.length())) {
			throw new IllegalArgumentException("Only file-backed H2 URLs are accepted");
		}
		final String location = url.substring(prefix.length()).split(";", 2)[0];
		return Path.of(location).toAbsolutePath().normalize();
	}
}
