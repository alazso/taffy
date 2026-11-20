package so.alaz.taffy.comficonf;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.platform.Classes;
import so.alaz.taffy.platform.ErrorReports;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This plugin's connection to the ComfiConf bridge: what {@link #connect} found, and the link it registered.
 *
 * <p>This class names no {@code com.comficonf} type, so the plugin's main class may call {@link #connect} and hold
 * the connection on a server without the bridge. The only place an API-typed class is reached is the body of the
 * supplier passed to {@link #connect}, which runs after the bridge was found installed, enabled and visible.
 *
 * <p>{@link #status()}, {@link #notifyChanged()}, {@link #close()} and {@link #lockFor(Path)} never pass on a
 * {@link RuntimeException} or {@link LinkageError} from the link, so a broken bridge cannot break {@code onDisable},
 * a reload command or a diagnostics command. Each logs the first such failure as one warning with its stack on the
 * logger of the plugin given to {@link #connect}, and later failures of the same method are not logged.
 *
 * <p>Thread-safe: {@link #status()}, {@link #resourceId()}, {@link #notifyChanged()}, {@link #close()} and
 * {@link #lockFor(Path)} may be called from any thread, and forward only to the {@link ComfiConfLink}, whose
 * implementations are thread-safe.
 */
public final class ComfiConfConnection {

    private static final String PLUGIN_NAME = "ComfiConf";
    private static final String PROBE = "com.comficonf.api.ComfiConfApi";
    private static final String SOURCE = "hook.comficonf";
    private static final String STATUS_FAILED_REASON = "ComfiConf could not say whether these settings are still"
        + " registered, so they may not be editable in ComfiConf. The server log holds the error.";
    private static final String BUNDLED_REASON = "This plugin's jar holds its own copy of the ComfiConf API, so it"
        + " cannot reach the installed ComfiConf and its settings are edited in their files only. Only a build of"
        + " this plugin without that copy fixes this.";
    private static final String NOT_REGISTERED_REASON = "ComfiConf no longer holds these settings, so they are edited"
        + " in their files only until this plugin connects to ComfiConf again, at the latest when the server"
        + " restarts.";

    private final @Nullable Plugin bridge;
    private final @Nullable ComfiConfLink link;
    private final ComfiConfStatus found;
    private final Logger logger;
    private final ReentrantLock ownLock = new ReentrantLock();
    private final AtomicBoolean statusFailed = new AtomicBoolean();
    private final AtomicBoolean notifyFailed = new AtomicBoolean();
    private final AtomicBoolean closeFailed = new AtomicBoolean();
    private final AtomicBoolean lockFailed = new AtomicBoolean();

    private ComfiConfConnection(@Nullable Plugin bridge, @Nullable ComfiConfLink link, ComfiConfStatus found,
                                Logger logger) {
        this.bridge = bridge;
        this.link = link;
        this.found = found;
        this.logger = logger;
    }

    /**
     * Connects this plugin's settings to ComfiConf when the bridge is there. Call at the end of {@code onEnable},
     * on the main thread, after the settings file loaded and after any migration that writes it. It may be called
     * again later, on the main thread, after {@link #close()} of the previous connection, as the package description
     * states.
     *
     * <p>The checks run in the order of {@link ComfiConfState}: the switch, then the bridge plugin installed,
     * enabled and its API visible to this plugin, and only then {@code registration}. Nothing is logged when the
     * connection is switched off or the bridge is not installed or not enabled. A bridge this plugin cannot see, its
     * own copy of the API included, or one that lacks an API class, logs one warning and reports nothing. A
     * registration that throws or returns {@code null} logs one warning with the stack and is reported under
     * {@code hook.comficonf}. A {@link LinkageError} means the bridge lacks an API class when its message names a
     * {@code com.comficonf} class or it has no message; any other, such as a class of the plugin that failed to
     * initialize, counts as a failed registration. A bridge whose API version is lower than the one
     * {@link ComfiConfRegistration#register} was compiled against lacks the API too: it logs the same warning, naming
     * both versions, registers nothing and reports nothing. A registration that succeeds logs one line naming the
     * resource id.
     * A {@link RuntimeException} or {@link LinkageError} from {@code registration} never leaves this method.
     *
     * @param owner the plugin whose settings are registered; its logger receives the lines
     * @param errors where a failed registration is reported
     * @param switchedOn {@code false} when the server owner turned the connection off; the bridge is then not
     *     looked at
     * @param registration registers the settings through the plugin's own integration class, as
     *     {@code this::registerComfiConf} in the package description, and returns the link; called at most once,
     *     on this thread, and only after every check passed
     * @return the connection, never {@code null}; a field that holds it is still {@code null} when {@code onEnable}
     *     stopped before this call
     */
    public static ComfiConfConnection connect(Plugin owner, ErrorReports errors, boolean switchedOn,
                                              Supplier<ComfiConfLink> registration) {
        return connect(owner, errors, switchedOn, registration, bridge -> bridge.getClass().getClassLoader());
    }

    /**
     * {@link #connect(Plugin, ErrorReports, boolean, Supplier)} with the class loader that defines the bridge's API
     * taken from {@code loaderOf}, applied to the bridge plugin once the API was found visible to this plugin.
     */
    static ComfiConfConnection connect(Plugin owner, ErrorReports errors, boolean switchedOn,
                                       Supplier<ComfiConfLink> registration,
                                       Function<Plugin, ClassLoader> loaderOf) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(errors, "errors");
        Objects.requireNonNull(registration, "registration");
        Logger logger = owner.getLogger();
        if (!switchedOn) {
            return off(ComfiConfState.SWITCHED_OFF, logger);
        }
        Plugin bridge = owner.getServer().getPluginManager().getPlugin(PLUGIN_NAME);
        if (bridge == null) {
            return off(ComfiConfState.NOT_INSTALLED, logger);
        }
        if (!bridge.isEnabled()) {
            return off(ComfiConfState.NOT_ENABLED, logger);
        }
        String version = bridge.getPluginMeta().getVersion();
        if (!Classes.present(PROBE, ComfiConfConnection.class, logger)) {
            if (Classes.present(PROBE, bridge.getClass(), logger)) {
                logger.warning(PLUGIN_NAME + " " + version + " is not visible to this plugin, so this plugin's"
                    + " settings are edited in their files only: this plugin's paper-plugin.yml does not list "
                    + PLUGIN_NAME + " under dependencies.server with join-classpath: true.");
                return new ComfiConfConnection(bridge, null, new ComfiConfStatus(ComfiConfState.NOT_VISIBLE,
                    version, "This plugin's paper-plugin.yml does not list " + PLUGIN_NAME + " under"
                    + " dependencies.server with join-classpath: true, so this plugin's settings are edited in their"
                    + " files only."), logger);
            }
            return apiMissing(bridge, version, "missing " + PROBE, logger);
        }
        if (!sameApi(ComfiConfConnection.class.getClassLoader(), loaderOf.apply(bridge))) {
            logger.warning(BUNDLED_REASON);
            return new ComfiConfConnection(bridge, null, new ComfiConfStatus(ComfiConfState.NOT_VISIBLE, version,
                BUNDLED_REASON), logger);
        }
        ComfiConfLink link;
        try {
            link = Objects.requireNonNull(registration.get(), "the registration returned null");
        } catch (ExceptionInInitializerError e) {
            return failed(bridge, version, e, logger, errors);
        } catch (LinkageError e) {
            if (bridgeLacksApi(e)) {
                return apiMissing(bridge, version, missing(e), logger);
            }
            return failed(bridge, version, e, logger, errors);
        } catch (OutdatedBridgeException e) {
            return apiMissing(bridge, version, e.detail(), logger);
        } catch (RuntimeException e) {
            return failed(bridge, version, e, logger, errors);
        }
        logger.info("Registered " + link.resourceId() + " with " + PLUGIN_NAME);
        return new ComfiConfConnection(bridge, link, new ComfiConfStatus(ComfiConfState.ON, version, null), logger);
    }

    /**
     * The state of the connection now. A connection that reached {@link ComfiConfState#ON} reads
     * {@link ComfiConfState#NOT_ENABLED} while the bridge plugin is disabled and {@link ComfiConfState#NOT_REGISTERED}
     * with a reason while the bridge no longer holds the resource, and {@link ComfiConfState#FAILED} with a reason
     * while the link throws when asked whether it holds the resource; every other state is the one {@link #connect}
     * found.
     *
     * @return the status, never {@code null}
     */
    public ComfiConfStatus status() {
        if (link == null || bridge == null) {
            return found;
        }
        if (!bridge.isEnabled()) {
            return new ComfiConfStatus(ComfiConfState.NOT_ENABLED, null, null);
        }
        boolean registered;
        try {
            registered = link.isRegistered();
        } catch (RuntimeException | LinkageError e) {
            warnOnce(statusFailed, "could not say whether this plugin's settings are registered", e);
            return new ComfiConfStatus(ComfiConfState.FAILED, found.version(), STATUS_FAILED_REASON);
        }
        if (!registered) {
            return new ComfiConfStatus(ComfiConfState.NOT_REGISTERED, found.version(),
                NOT_REGISTERED_REASON);
        }
        return found;
    }

    /**
     * The id of the registered resource, for a diagnostics line.
     *
     * @return the resource id of the link {@link #connect} registered, or {@code null} when it registered none
     */
    public @Nullable String resourceId() {
        return link == null ? null : link.resourceId();
    }

    /**
     * Tells the bridge that the settings changed outside ComfiConf, so open editors load the newer values. Call it
     * after each successful reload of the file by the plugin's own command and after each write of the file outside
     * ComfiConf, never after an apply. Does nothing when {@link #connect} registered no resource, while the bridge
     * plugin is disabled, and once the resource is no longer registered. A {@link RuntimeException} or
     * {@link LinkageError} from the link is logged as the class description states and not passed on.
     */
    public void notifyChanged() {
        if (link != null && bridge != null && bridge.isEnabled()) {
            try {
                link.notifyChanged();
            } catch (RuntimeException | LinkageError e) {
                warnOnce(notifyFailed, "could not pass on a change of this plugin's settings, so open editors may"
                    + " show older values", e);
            }
        }
    }

    /**
     * Unregisters the resource. Call it first in {@code onDisable}. Does nothing when {@link #connect} registered no
     * resource and while the bridge plugin is disabled. Calling it more than once has no further effect. A
     * {@link RuntimeException} or {@link LinkageError} from the link is logged as the class description states and
     * not passed on.
     */
    public void close() {
        if (link != null && bridge != null && bridge.isEnabled()) {
            try {
                link.close();
            } catch (RuntimeException | LinkageError e) {
                warnOnce(closeFailed, "could not unregister this plugin's settings", e);
            }
        }
    }

    /**
     * The lock the plugin holds around each of its own writes of {@code file}, so that a ComfiConf apply racing the
     * write reports a conflict instead of replacing it. While {@link #connect} registered a resource, it is the lock
     * an apply of {@code file} holds from its revision check to the move of the new content over the file.
     * Otherwise, and when the link throws a {@link RuntimeException} or {@link LinkageError}, which is logged as the
     * class description states, it is one lock of this connection that no apply takes.
     *
     * @param file the settings file the plugin writes
     * @return the lock, never {@code null}
     */
    public Lock lockFor(Path file) {
        Objects.requireNonNull(file, "file");
        if (link != null) {
            try {
                return Objects.requireNonNull(link.lockFor(file), "the link returned no lock");
            } catch (RuntimeException | LinkageError e) {
                warnOnce(lockFailed, "could not give the lock of " + file.getFileName()
                    + ", so a ComfiConf apply may replace this plugin's own write of it", e);
            }
        }
        return ownLock;
    }

    /** Logs {@code failure} as a warning with its stack unless {@code logged} is already set; never throws. */
    private void warnOnce(AtomicBoolean logged, String what, Throwable failure) {
        if (!logged.compareAndSet(false, true)) {
            return;
        }
        try {
            logger.log(Level.WARNING, PLUGIN_NAME + " " + found.version() + " " + what + " (" + failure + ").",
                failure);
        } catch (RuntimeException ignored) {
            // A log handler that throws drops the line; the caller goes on either way.
        }
    }

    /**
     * Whether {@code mine} and {@code bridges} give the same ComfiConf API class. A lookup that fails on either
     * loader counts as the same, since connect handles a missing API on its other checks.
     */
    static boolean sameApi(@Nullable ClassLoader mine, @Nullable ClassLoader bridges) {
        try {
            return Class.forName(PROBE, false, mine) == Class.forName(PROBE, false, bridges);
        } catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            return true;
        }
    }

    /** The warning and reason for an installed bridge that lacks an API class this plugin was built against. */
    static String incompatibleBridgeMessage(String bridgeVersion, String missing) {
        return "The installed ComfiConf " + bridgeVersion + " does not include the ComfiConf API this plugin needs ("
            + missing + "). Update ComfiConf to the version this plugin was built for"
            + " (https://docs.comficonf.com/bridge/updating#when-a-plugin-needs-a-newer-bridge); settings are"
            + " edited in their files only until then.";
    }

    /**
     * Whether {@code error} comes from the bridge lacking an API class: it names a {@code com.comficonf} class, or
     * names nothing, and is not the reuse of a class whose initializer failed.
     */
    static boolean bridgeLacksApi(LinkageError error) {
        if (error.getCause() instanceof ExceptionInInitializerError) {
            return false;
        }
        String message = error.getMessage();
        return message == null || message.contains("com/comficonf/") || message.contains("com.comficonf.");
    }

    /** What {@code error} says is missing: the class it names with dots, or its type when it names none. */
    static String missing(LinkageError error) {
        return error.getMessage() == null
            ? error.getClass().getSimpleName()
            : "missing " + error.getMessage().replace('/', '.');
    }

    private static ComfiConfConnection off(ComfiConfState state, Logger logger) {
        return new ComfiConfConnection(null, null, new ComfiConfStatus(state, null, null), logger);
    }

    private static ComfiConfConnection apiMissing(Plugin bridge, String version, String missing, Logger logger) {
        String message = incompatibleBridgeMessage(version, missing);
        logger.warning(message);
        return new ComfiConfConnection(bridge, null, new ComfiConfStatus(ComfiConfState.API_MISSING, version,
            message), logger);
    }

    private static ComfiConfConnection failed(Plugin bridge, String version, Throwable failure, Logger logger,
                                              ErrorReports errors) {
        logger.log(Level.WARNING, PLUGIN_NAME + " " + version + " could not register this plugin's settings ("
            + failure + "), so they are edited in their files only.", failure);
        errors.report(SOURCE, failure);
        return new ComfiConfConnection(bridge, null, new ComfiConfStatus(ComfiConfState.FAILED, version,
            "Registering with ComfiConf failed, so these settings are edited in their files only. The server log"
            + " holds the error."), logger);
    }
}
