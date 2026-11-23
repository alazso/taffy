/**
 * JDBC storage over HikariCP for SQLite, MySQL, MariaDB and PostgreSQL.
 *
 * <p>Failures a server owner has to act on are reported as a {@link so.alaz.taffy.storage.StorageException},
 * whose message is written for the owner.
 *
 * <h2>Runtime libraries</h2>
 *
 * <p>The module compiles against HikariCP and loads the JDBC drivers by class name, but ships none of them, and
 * its published metadata names none of them. A plugin that shades it lists HikariCP and the driver of each dialect
 * it starts in {@code paperPlugin.runtimeLibraries}, so that Paper downloads them at server start, for example
 * with {@code runtimeLibrary(alaz.hikari)} and {@code runtimeLibrary(alaz.sqlite.jdbc)}. The drivers are
 * {@code alaz.sqlite.jdbc} for SQLite, {@code alaz.mysql.connector} for MySQL, {@code alaz.mariadb.jdbc} for MariaDB
 * and {@code alaz.postgresql.jdbc} for PostgreSQL.
 *
 * <p>Without HikariCP, the future of {@link so.alaz.taffy.storage.Storage#start} fails with a
 * {@link so.alaz.taffy.storage.StorageException} whose message reads {@code HikariCP is not available}. Without the
 * driver of the configured dialect, it fails with a {@link so.alaz.taffy.storage.StorageException} whose message
 * reads, for MySQL, {@code the mysql driver is not available}. Either way no connection pool is built and the
 * database threads stop.
 *
 * <p>Because taffy-storage publishes no HikariCP dependency, a plugin's own tests that start storage declare
 * {@code testRuntimeOnly} HikariCP and the driver of each dialect they start.
 *
 * <h2>Threads and shutdown</h2>
 *
 * <p>{@code Storage} owns the threads its database work runs on. It creates them when it starts and stops them
 * when it closes, so a plugin passes it no executor. While the plugin disables, storage closes after
 * {@link so.alaz.taffy.platform.PendingSettlements#settleAll} has settled the work that writes to it and before
 * {@link so.alaz.taffy.platform.DiscordWebhook#close()}, in the order the {@code so.alaz.taffy.platform} package
 * documentation gives.
 *
 * <p>A settlement that writes only queues its write on the database threads, behind the work queued before it, so
 * the drain given to {@link so.alaz.taffy.storage.Storage#close} must cover that write too. When the drain runs
 * out, the work still queued does not run, a warning is logged, and {@code close} returns {@code false}: some
 * writes may then be missing.
 *
 * <p>A plugin that disables while the future of {@link so.alaz.taffy.storage.Storage#start} is still pending may
 * cancel that future: the storage that start then builds closes its pools and threads itself.
 *
 * <h2>Backing up and restoring a SQLite database</h2>
 *
 * <p>Storage opens a SQLite database in WAL mode, so the newest commits of a running database are in the file
 * named after the database with {@code -wal} at its end, and a copy of the database file alone misses them. A
 * backup of a running server is therefore taken with {@link so.alaz.taffy.storage.Storage#backup}, which writes
 * one complete file into the {@code backups} folder. The copies a start takes before a migration are complete
 * files of the same kind.
 *
 * <p>Storage has no restore call, because a database file cannot be replaced while a pool holds it open. A
 * restore is this sequence, done by the owner with the server stopped, or by the plugin itself:
 * <ol>
 *   <li>Close storage with {@link so.alaz.taffy.storage.Storage#close} and wait for it to return, so no
 *       connection holds the file.</li>
 *   <li>Replace the database file with the copy, and remove the files named after the database with {@code -wal}
 *       and {@code -shm} at their end when they exist: they belong to the database that was replaced.</li>
 *   <li>Start storage again with {@link so.alaz.taffy.storage.Storage#start}, which runs the migrations the copy
 *       does not record.</li>
 * </ol>
 */
package so.alaz.taffy.storage;
