package so.alaz.taffy.test;

import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A data source whose statements and commits fail on demand, over connections that {@link DriverManager} opens.
 *
 * <p>Each {@link #getConnection()} opens a new connection to the URL with the properties given at construction and
 * wraps it. The wrapper checks {@link #faults()} on {@code prepareStatement} and {@code createStatement}, when a
 * statement they return is run through {@code execute}, {@code executeUpdate}, {@code executeQuery},
 * {@code executeLargeUpdate}, {@code executeBatch} or {@code executeLargeBatch}, and on {@code commit}. A statement's
 * {@code getConnection} returns the wrapper, and a wrapper is equal only to itself. Every other call,
 * {@code prepareCall} and {@code rollback} included, goes to the real connection or statement unchanged.
 * {@link Faults#kill()} makes every later checked call fail, as if the process had died; a new data source on the
 * same database is the next process.
 *
 * <p>The JDBC driver for the URL must be on the class path. This class brings none.
 */
public final class FaultyDataSource implements DataSource {

    /**
     * The failures to inject and the counts of what ran. Every failure is an {@link SQLException}: a statement
     * failure is thrown before the statement reaches the database, a commit failure after the commit was or was not
     * written.
     */
    public static final class Faults {
        private final AtomicInteger statementsLeft = new AtomicInteger();
        private final AtomicInteger commitsLeft = new AtomicInteger();
        private final AtomicInteger statements = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger killAt = new AtomicInteger(Integer.MAX_VALUE);
        private volatile boolean commitWrites;
        private volatile boolean readAfterCommitFails;
        private volatile boolean dead;
        private volatile String failing = "";
        private final AtomicInteger failingLeft = new AtomicInteger();
        private volatile String watched = "";
        private final AtomicInteger watchedLeft = new AtomicInteger();
        private volatile Runnable afterWatched = () -> {
        };

        private Faults() {
        }

        /**
         * The nth statement run from now fails once with the message {@code injected statement failure}, counted
         * from 1. A later call replaces a failure that has not fired; 0 cancels it.
         */
        public void failStatement(int nth) {
            statementsLeft.set(nth);
        }

        /**
         * The nth commit from now fails once with the message {@code injected commit failure}, counted from 1. With
         * {@code written} true the transaction is committed first, so the failure hides a commit that happened;
         * otherwise it stays uncommitted on the connection. With {@code readFails} true the next statement after the
         * failed commit fails too, replacing a pending {@link #failStatement} failure. A later call replaces a
         * failure that has not fired; 0 cancels it.
         */
        public void failCommit(int nth, boolean written, boolean readFails) {
            commitWrites = written;
            readAfterCommitFails = readFails;
            commitsLeft.set(nth);
        }

        /** The number of commits attempted so far, failed ones included. A commit after death is not counted. */
        public int commits() {
            return commits.get();
        }

        /**
         * Every later connection, prepared or created statement, statement run and commit fails with the message
         * {@code the process is dead}, except that a statement run whose SQL a pending {@link #failMatching} failure
         * matches fails with that failure instead. Nothing undoes it.
         */
        public void kill() {
            dead = true;
        }

        /**
         * The process dies once n more statements have run: the statement after them fails with the message
         * {@code the process is dead}, and from then on everything {@link #kill()} names fails.
         */
        public void killAfter(int n) {
            killAt.set(statements.get() + n);
        }

        /**
         * The number of statements run so far, those failed by {@link #failStatement}, {@link #failCommit} or
         * {@link #killAfter} included. A statement failed by {@link #failMatching} or run after death is not counted.
         */
        public int statements() {
            return statements.get();
        }

        /** Whether {@link #kill()} was called or {@link #killAfter} has fired. */
        public boolean dead() {
            return dead;
        }

        /**
         * The next {@code count} statements whose SQL contains {@code fragment} fail with the message
         * {@code injected failure of <fragment>}. The SQL of a prepared statement is the text it was prepared with;
         * of a created statement, the text passed to the run call. An empty fragment matches nothing. A later call
         * replaces this one.
         */
        public void failMatching(String fragment, int count) {
            failing = fragment;
            failingLeft.set(count);
        }

        /**
         * Runs {@code action} once, on the thread that ran the statement, right after the nth statement whose SQL
         * contains {@code fragment} returns, counted from 1. A statement that fails is not counted. SQL is matched as
         * in {@link #failMatching}. A later call replaces this one, and {@link #disarm()} leaves it in place. An
         * exception the action throws propagates from the run call, after the statement took effect.
         */
        public void afterMatching(String fragment, int nth, Runnable action) {
            watched = fragment;
            afterWatched = action;
            watchedLeft.set(nth);
        }

        /**
         * Cancels a {@link #failStatement}, {@link #failCommit} or {@link #failMatching} failure that has not fired
         * yet. A kill, a pending {@link #killAfter} and an {@link #afterMatching} action stay.
         */
        public void disarm() {
            statementsLeft.set(0);
            commitsLeft.set(0);
            failingLeft.set(0);
        }

        private void statement(String sql) throws SQLException {
            if (!failing.isEmpty() && sql.contains(failing) && failingLeft.get() > 0
                && failingLeft.decrementAndGet() >= 0) {
                throw new SQLException("injected failure of " + failing);
            }
            statement();
        }

        private void executed(String sql) {
            if (!watched.isEmpty() && sql.contains(watched) && watchedLeft.get() > 0
                && watchedLeft.decrementAndGet() == 0) {
                afterWatched.run();
            }
        }

        private void statement() throws SQLException {
            alive();
            if (statements.incrementAndGet() > killAt.get()) {
                dead = true;
                throw new SQLException("the process is dead");
            }
            if (statementsLeft.get() > 0 && statementsLeft.decrementAndGet() == 0) {
                throw new SQLException("injected statement failure");
            }
        }

        private boolean commitFails() throws SQLException {
            alive();
            commits.incrementAndGet();
            boolean fails = commitsLeft.get() > 0 && commitsLeft.decrementAndGet() == 0;
            if (fails && readAfterCommitFails) {
                statementsLeft.set(1);
            }
            return fails;
        }

        private void alive() throws SQLException {
            if (dead) {
                throw new SQLException("the process is dead");
            }
        }
    }

    private static final Set<String> EXECUTES = Set.of("execute", "executeUpdate", "executeQuery",
        "executeLargeUpdate", "executeBatch", "executeLargeBatch");

    private final String url;
    private final Properties properties = new Properties();
    private final Faults faults = new Faults();

    /**
     * A data source on {@code url} whose connections are opened with {@code properties}, with no fault armed. The
     * properties, defaults included, are copied, so a later change to them has no effect.
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    public FaultyDataSource(String url, Properties properties) {
        this.url = Objects.requireNonNull(url, "url");
        Objects.requireNonNull(properties, "properties");
        for (String name : properties.stringPropertyNames()) {
            this.properties.setProperty(name, properties.getProperty(name));
        }
    }

    /** The faults of this data source and of every connection it opened. */
    public Faults faults() {
        return faults;
    }

    /**
     * A new connection to the URL, wrapped so that {@link #faults()} applies to it. Its
     * {@code unwrap(Connection.class)} returns the real connection.
     *
     * @throws SQLException if the process is dead or the driver cannot connect
     */
    @Override
    public Connection getConnection() throws SQLException {
        faults.alive();
        return connection(DriverManager.getConnection(url, properties), faults);
    }

    /** The same as {@link #getConnection()}: the user and password are ignored. */
    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
    }

    /** Always {@code null}. */
    @Override
    public @Nullable PrintWriter getLogWriter() {
        return null;
    }

    /** Does nothing. */
    @Override
    public void setLogWriter(@Nullable PrintWriter out) {
    }

    /** Does nothing. */
    @Override
    public void setLoginTimeout(int seconds) {
    }

    /** Always 0. */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /** The global logger. */
    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }

    /**
     * This data source when it is an instance of {@code type}. It wraps no other object.
     *
     * @throws SQLException if this data source is not an instance of {@code type}
     */
    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (type.isInstance(this)) {
            return type.cast(this);
        }
        throw new SQLException("not a wrapper");
    }

    /** Whether this data source is an instance of {@code type}. */
    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }

    // A wrapper equals only itself and hashes by identity, as the real object it stands for does.
    private static @Nullable Object identity(Object proxy, Method method, Object @Nullable [] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && method.getParameterTypes()[0] == Object.class
                ? proxy == args[0] : null;
            case "hashCode" -> args == null ? System.identityHashCode(proxy) : null;
            default -> null;
        };
    }

    private static Connection connection(Connection real, Faults faults) {
        return (Connection) Proxy.newProxyInstance(FaultyDataSource.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                Object same = identity(proxy, method, args);
                if (same != null) {
                    return same;
                }
                Connection wrapper = (Connection) proxy;
                switch (method.getName()) {
                    case "prepareStatement" -> {
                        faults.alive();
                        return statement((PreparedStatement) call(real, method, args), PreparedStatement.class,
                            faults, (String) args[0], wrapper);
                    }
                    case "createStatement" -> {
                        faults.alive();
                        return statement((Statement) call(real, method, args), Statement.class, faults, "",
                            wrapper);
                    }
                    case "commit" -> {
                        if (faults.commitFails()) {
                            if (faults.commitWrites) {
                                real.commit();
                            }
                            throw new SQLException("injected commit failure");
                        }
                        return call(real, method, args);
                    }
                    case "unwrap" -> {
                        return args[0] == Connection.class ? real : call(real, method, args);
                    }
                    default -> {
                        return call(real, method, args);
                    }
                }
            });
    }

    private static <T extends Statement> T statement(T real, Class<T> type, Faults faults, String sql,
                                                     Connection connection) {
        return type.cast(Proxy.newProxyInstance(FaultyDataSource.class.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> {
                Object same = identity(proxy, method, args);
                if (same != null) {
                    return same;
                }
                if (method.getName().equals("getConnection") && args == null) {
                    return connection;
                }
                if (!EXECUTES.contains(method.getName())) {
                    return call(real, method, args);
                }
                String text = sql.isEmpty() && args != null && args.length > 0 && args[0] instanceof String given
                    ? given : sql;
                faults.statement(text);
                Object result = call(real, method, args);
                faults.executed(text);
                return result;
            }));
    }
}
