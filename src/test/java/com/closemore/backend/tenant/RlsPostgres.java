package com.closemore.backend.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.MountableFile;

/**
 * One Postgres container for the whole JVM, started eagerly in a static initializer.
 *
 * <p>Deliberately NOT {@code @Testcontainers}/{@code @Container}. That pair starts and stops a
 * container per annotated test class, so two ITs meant two containers, two Flyway runs and two
 * Spring contexts. Here the container is a JVM-wide singleton: the first test class to touch this
 * class starts it, every later class reuses it, and Ryuk tears it down when the JVM exits. No
 * {@code stop()} call is needed or wanted.
 *
 * <p><b>The mechanism that fixes the restricted-role problem is the
 * {@code withCopyFileToContainer} call below.</b> Everything the postgres image finds in
 * {@code /docker-entrypoint-initdb.d} is executed during cluster initialisation, before the server
 * starts listening on TCP. {@code PostgreSQLContainer}'s default wait strategy waits for
 * "database system is ready to accept connections" to appear <i>twice</i> -- once for the
 * temporary init server, once for the real one -- so {@code start()} cannot return until the init
 * script has already succeeded.
 *
 * <p>That ordering is what the two rejected approaches could not give:
 * <ul>
 *   <li>a {@code @BeforeAll} grant races the Spring context, which starts Flyway and Hikari as
 *       soon as the context refreshes;</li>
 *   <li>a Flyway callback cannot create the role that Flyway's own connection needs, and in the
 *       failing setup was never discovered at all (see the header of
 *       {@code afterMigrate__grant_app_role.sql}).</li>
 * </ul>
 */
public final class RlsPostgres {

    /** Container superuser. Flyway migrates as this role -- DDL, FORCE RLS and ownership need it. */
    public static final String MIGRATION_USER = "closemore";
    public static final String MIGRATION_PASSWORD = "closemore";
    public static final String DATABASE = "closemore";

    /**
     * The restricted role the application connects as. Must match the role name and password in
     * {@code src/test/resources/db/init/01-create-app-role.sql}; if these ever drift you get the
     * exact "password authentication failed" error this setup exists to eliminate, so
     * {@code RlsWiringPreconditionsIT} asserts the connection really is this role.
     */
    public static final String APP_USER = "closemore_app";
    public static final String APP_PASSWORD = "closemore_app_pw";

    /** Classpath path, i.e. src/test/resources/db/init/01-create-app-role.sql. */
    private static final String INIT_SCRIPT = "db/init/01-create-app-role.sql";

    private static final Logger log = LoggerFactory.getLogger(RlsPostgres.class);

    private static final PostgreSQLContainer<?> CONTAINER =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName(DATABASE)
                    .withUsername(MIGRATION_USER)
                    .withPassword(MIGRATION_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forClasspathResource(INIT_SCRIPT),
                            "/docker-entrypoint-initdb.d/01-create-app-role.sql")
                    // Postgres' own stderr, forwarded into the Failsafe report. If the init script
                    // ever fails, the psql error lands here rather than being swallowed -- which
                    // matters a lot when CI is the only place the suite can run.
                    .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("postgres"));

    static {
        CONTAINER.start();
    }

    public static PostgreSQLContainer<?> instance() {
        return CONTAINER;
    }

    private RlsPostgres() {
    }
}
