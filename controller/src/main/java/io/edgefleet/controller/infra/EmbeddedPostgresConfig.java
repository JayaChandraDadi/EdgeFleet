package io.edgefleet.controller.infra;

import com.zaxxer.hikari.HikariDataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runs a real PostgreSQL server in-process when {@code edgefleet.dev.embedded-postgres=true},
 * so the controller works on a laptop without Docker. Data is kept in
 * {@code edgefleet.dev.data-dir} across restarts unless that is empty.
 *
 * <p>The data source is a Hikari pool, as in production. The embedded server's own
 * data source opens a new connection per use; under a few hundred concurrent device
 * sessions that exhausts Postgres's connection limit.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "edgefleet.dev.embedded-postgres", havingValue = "true")
public class EmbeddedPostgresConfig {

    @Bean(destroyMethod = "close")
    EmbeddedPostgres embeddedPostgres(@Value("${edgefleet.dev.data-dir:}") String dataDir,
                                      @Value("${edgefleet.dev.port:0}") int port) {
        try {
            // After an unclean stop (kill -9, power loss) Postgres replays its WAL before
            // accepting connections, which can take well over the default 10 s.
            EmbeddedPostgres.Builder b = EmbeddedPostgres.builder().setPGStartupWait(Duration.ofSeconds(90));
            if (port > 0) {
                b.setPort(port);
            }
            if (!dataDir.isBlank()) {
                Path dir = Path.of(dataDir);
                Files.createDirectories(dir);
                stopOrphanedServer(dir);
                b.setDataDirectory(dir).setCleanDataDirectory(false);
            }
            return b.start();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start embedded postgres", e);
        }
    }

    /**
     * Postgres runs as its own OS process. If the previous controller was killed with
     * SIGKILL its shutdown hook never ran, and that server is still alive holding this
     * data directory, so a new one cannot start. This data directory belongs to the
     * controller alone, so a live postgres named in its lock file is such an orphan:
     * stop it cleanly (SIGTERM, no clients remain) before starting a new one.
     */
    static void stopOrphanedServer(Path dir) throws IOException {
        Path pidFile = dir.resolve("postmaster.pid");
        if (!Files.exists(pidFile)) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(Files.readAllLines(pidFile).getFirst().trim());
        } catch (RuntimeException e) {
            return; // unreadable lock file: postgres itself will report it
        }
        java.util.Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        boolean isPostgres = handle.flatMap(h -> h.info().command()).map(c -> c.endsWith("postgres")).orElse(false);
        if (handle.isEmpty() || !handle.get().isAlive() || !isPostgres) {
            return;
        }
        org.slf4j.LoggerFactory.getLogger(EmbeddedPostgresConfig.class)
                .warn("stopping orphaned postgres (pid {}) left running in {} by a previous controller", pid, dir);
        ProcessHandle ph = handle.get();
        ph.destroy();
        try {
            ph.onExit().get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            ph.destroyForcibly(); // postgres replays its WAL on the next start
        } catch (Exception e) {
            Thread.currentThread().interrupt();
        }
    }

    @Bean(destroyMethod = "close")
    DataSource dataSource(EmbeddedPostgres pg,
                          @Value("${spring.datasource.hikari.maximum-pool-size:20}") int poolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(pg.getJdbcUrl("postgres", "postgres"));
        ds.setUsername("postgres");
        ds.setMaximumPoolSize(poolSize);
        ds.setPoolName("edgefleet-embedded");
        return ds;
    }
}
