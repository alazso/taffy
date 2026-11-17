package so.alaz.taffy.comficonf;

import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import so.alaz.taffy.platform.ErrorReports;
import so.alaz.taffy.test.CapturedLog;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ComfiConfConnectionTest {

    private static final String FAILED_REASON = "Registering with ComfiConf failed, so these settings are edited in"
        + " their files only. The server log holds the error.";

    private final Plugin owner = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final Plugin bridge = mock(Plugin.class);
    private final CapturedLog log = new CapturedLog();
    private final ErrorReports errors = new ErrorReports();
    private final List<Map.Entry<String, Throwable>> reports = new CopyOnWriteArrayList<>();
    private final FakeLink link = new FakeLink();
    private final AtomicInteger registrations = new AtomicInteger();

    @BeforeEach
    void setUp() {
        when(owner.getServer()).thenReturn(server);
        when(owner.getLogger()).thenReturn(log.logger);
        when(server.getPluginManager()).thenReturn(plugins);
        when(plugins.getPlugin("ComfiConf")).thenReturn(bridge);
        when(bridge.isEnabled()).thenReturn(true);
        PluginMeta meta = mock(PluginMeta.class);
        when(meta.getVersion()).thenReturn("0.1.0");
        when(bridge.getPluginMeta()).thenReturn(meta);
        errors.install((source, error) -> reports.add(Map.entry(source, error)));
    }

    private Supplier<ComfiConfLink> registering(Supplier<ComfiConfLink> result) {
        return () -> {
            registrations.incrementAndGet();
            return result.get();
        };
    }

    private ComfiConfConnection connect(Supplier<ComfiConfLink> result) {
        return ComfiConfConnection.connect(owner, errors, true, registering(result));
    }

    private ComfiConfConnection connectThrowing(Throwable failure) {
        return connect(() -> {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) failure;
        });
    }

    @Test
    void switchedOffNeverLooksAtTheBridge() {
        ComfiConfConnection connection = ComfiConfConnection.connect(owner, errors, false, registering(() -> link));

        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.SWITCHED_OFF, null, null));
        assertThat(connection.resourceId()).isNull();
        verifyNoInteractions(server, plugins, bridge);
        assertThat(registrations).hasValue(0);
        assertThat(log.records).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aBridgeThatIsNotInstalledLogsNothing() {
        when(plugins.getPlugin("ComfiConf")).thenReturn(null);

        ComfiConfConnection connection = connect(() -> link);

        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.NOT_INSTALLED, null, null));
        assertThat(registrations).hasValue(0);
        assertThat(log.records).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aBridgeThatIsNotEnabledLogsNothing() {
        when(bridge.isEnabled()).thenReturn(false);

        ComfiConfConnection connection = connect(() -> link);

        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.NOT_ENABLED, null, null));
        assertThat(registrations).hasValue(0);
        assertThat(log.records).isEmpty();
        assertThat(reports).isEmpty();
    }

    @Test
    void aRegistrationThatSucceedsIsOnAndLogsOneLine() {
        ComfiConfConnection connection = connect(() -> link);

        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.ON, "0.1.0", null));
        assertThat(connection.status().describe()).isEqualTo("ComfiConf on");
        assertThat(connection.resourceId()).isEqualTo("taffy:test");
        assertThat(registrations).hasValue(1);
        assertThat(log.lines()).containsExactly("INFO Registered taffy:test with ComfiConf");
        assertThat(reports).isEmpty();
    }

    @Test
    void theStatusIsReadWhenAsked() {
        ComfiConfConnection connection = connect(() -> link);

        when(bridge.isEnabled()).thenReturn(false);
        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.NOT_ENABLED, null, null));
        when(bridge.isEnabled()).thenReturn(true);
        link.registered = false;
        assertThat(connection.status())
            .isEqualTo(new ComfiConfStatus(ComfiConfState.NOT_REGISTERED, "0.1.0", null));
        assertThat(connection.status().describe()).isEqualTo("ComfiConf 0.1.0 installed, not registered");
        link.registered = true;
        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.ON, "0.1.0", null));
        assertThat(connection.resourceId()).isEqualTo("taffy:test");
    }

    @Test
    void aMissingApiClassIsApiMissingWithOneWarningAndNoReport() {
        ComfiConfConnection connection =
            connectThrowing(new NoClassDefFoundError("com/comficonf/api/ConfigProvider"));

        ComfiConfStatus status = connection.status();
        assertThat(status.state()).isEqualTo(ComfiConfState.API_MISSING);
        assertThat(status.version()).isEqualTo("0.1.0");
        assertThat(status.reason()).contains("ComfiConf 0.1.0").contains("missing com.comficonf.api.ConfigProvider");
        assertThat(log.records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getMessage()).isEqualTo(status.reason());
            assertThat(record.getThrown()).isNull();
        });
        assertThat(connection.resourceId()).isNull();
        assertThat(reports).isEmpty();
    }

    @Test
    void anIncompatibleApiClassIsApiMissing() {
        ComfiConfConnection connection =
            connectThrowing(new IncompatibleClassChangeError("com/comficonf/api/ConfigProvider"));

        assertThat(connection.status().state()).isEqualTo(ComfiConfState.API_MISSING);
        assertThat(log.lines()).singleElement().asString().startsWith("WARNING The installed ComfiConf 0.1.0");
        assertThat(reports).isEmpty();
    }

    @Test
    void everyJvmMessageForAMissingApiMemberIsApiMissing() {
        List<LinkageError> failures = List.of(
            new NoSuchMethodError("'void com.comficonf.api.ConfigResource$Builder.order(int)'"),
            new NoSuchFieldError("Class com.comficonf.api.ApplyMode does not have member field"
                + " 'com.comficonf.api.ApplyMode LATER'"),
            new IncompatibleClassChangeError("class so.alaz.example.Provider can not implement"
                + " com.comficonf.api.ConfigProvider, because it is not an interface"),
            new NoClassDefFoundError());

        for (LinkageError failure : failures) {
            assertThat(connectThrowing(failure).status().state()).as(failure.toString())
                .isEqualTo(ComfiConfState.API_MISSING);
        }
        assertThat(log.records).hasSize(failures.size())
            .allSatisfy(record -> assertThat(record.getThrown()).isNull());
        assertThat(reports).isEmpty();
    }

    @Test
    void aMissingClassOutsideTheApiFailsWithTheStackAndOneReport() {
        NoClassDefFoundError failure = new NoClassDefFoundError("org/example/Helper");

        ComfiConfConnection connection = connectThrowing(failure);

        assertThat(connection.status())
            .isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0", FAILED_REASON));
        assertThat(log.lines()).containsExactly("WARNING ComfiConf 0.1.0 could not register this plugin's settings ("
            + failure + "), so they are edited in their files only.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(Map.entry("hook.comficonf", failure));
    }

    @Test
    void anIntegrationClassWhoseInitializerFailedBeforeFailsWithAReport() {
        NoClassDefFoundError failure =
            new NoClassDefFoundError("Could not initialize class com.comficonf.api.Schema");
        failure.initCause(new ExceptionInInitializerError("Exception java.lang.IllegalStateException: schema"));

        ComfiConfConnection connection = connectThrowing(failure);

        assertThat(connection.status())
            .isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0", FAILED_REASON));
        assertThat(log.records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getThrown()).isSameAs(failure);
        });
        assertThat(reports).containsExactly(Map.entry("hook.comficonf", failure));
    }

    @Test
    void aRegistrationThatThrowsFailsWithTheStackAndOneReport() {
        IllegalStateException failure = new IllegalStateException("taffy:test is already registered");

        ComfiConfConnection connection = connectThrowing(failure);

        assertThat(connection.status())
            .isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0", FAILED_REASON));
        assertThat(log.lines()).containsExactly("WARNING ComfiConf 0.1.0 could not register this plugin's settings ("
            + failure + "), so they are edited in their files only.");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
        assertThat(reports).containsExactly(Map.entry("hook.comficonf", failure));
        assertThat(connection.resourceId()).isNull();
    }

    @Test
    void anIntegrationClassThatFailsToInitializeFailsWithAReport() {
        ExceptionInInitializerError failure = new ExceptionInInitializerError(new IllegalStateException("schema"));

        ComfiConfConnection connection = connectThrowing(failure);

        assertThat(connection.status())
            .isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0", FAILED_REASON));
        assertThat(log.records).singleElement().satisfies(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getThrown()).isSameAs(failure);
        });
        assertThat(reports).containsExactly(Map.entry("hook.comficonf", failure));
    }

    @Test
    void aRegistrationThatReturnsNullFailsWithAReport() {
        ComfiConfConnection connection = connect(() -> null);

        assertThat(connection.status().state()).isEqualTo(ComfiConfState.FAILED);
        assertThat(log.records).singleElement()
            .satisfies(record -> assertThat(record.getThrown()).isInstanceOf(NullPointerException.class)
                .hasMessage("the registration returned null"));
        assertThat(reports).singleElement().satisfies(report -> {
            assertThat(report.getKey()).isEqualTo("hook.comficonf");
            assertThat(report.getValue()).isInstanceOf(NullPointerException.class);
        });
    }

    @Test
    void notifyChangedAndCloseForwardToTheLink() {
        ComfiConfConnection connection = connect(() -> link);

        connection.notifyChanged();
        connection.close();
        connection.close();

        assertThat(link.notified).hasValue(1);
        assertThat(link.closed).hasValue(2);
    }

    @Test
    void notifyChangedAndCloseSkipTheLinkWhileTheBridgeIsDisabled() {
        ComfiConfConnection connection = connect(() -> link);
        when(bridge.isEnabled()).thenReturn(false);

        connection.notifyChanged();
        connection.close();

        assertThat(link.notified).hasValue(0);
        assertThat(link.closed).hasValue(0);
        when(bridge.isEnabled()).thenReturn(true);
        connection.notifyChanged();
        assertThat(link.notified).hasValue(1);
    }

    @Test
    void notifyChangedAndCloseDoNothingWithoutALink() {
        ComfiConfConnection off = ComfiConfConnection.connect(owner, errors, false, registering(() -> link));
        ComfiConfConnection failed = connectThrowing(new IllegalStateException("refused"));

        for (ComfiConfConnection connection : List.of(off, failed)) {
            connection.notifyChanged();
            connection.close();
        }

        assertThat(link.notified).hasValue(0);
        assertThat(link.closed).hasValue(0);
    }

    @Test
    void linkFailuresInStatusNotifyAndCloseLogOneWarningEachAndNeverEscape() {
        ComfiConfConnection connection = connect(() -> link);
        log.records.clear();
        IllegalStateException failure = new IllegalStateException("bridge broke");
        link.failure = failure;

        for (int call = 0; call < 2; call++) {
            assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0",
                "ComfiConf could not say whether these settings are still registered, so they may not be editable"
                    + " in ComfiConf. The server log holds the error."));
            connection.notifyChanged();
            connection.close();
        }

        assertThat(link.notified).hasValue(2);
        assertThat(link.closed).hasValue(2);
        assertThat(log.lines()).containsExactly(
            "WARNING ComfiConf 0.1.0 could not say whether this plugin's settings are registered (" + failure + ").",
            "WARNING ComfiConf 0.1.0 could not pass on a change of this plugin's settings, so open editors may show"
                + " older values (" + failure + ").",
            "WARNING ComfiConf 0.1.0 could not unregister this plugin's settings (" + failure + ").");
        assertThat(log.records).allSatisfy(record -> assertThat(record.getThrown()).isSameAs(failure));
        assertThat(reports).isEmpty();
        link.failure = null;
        assertThat(connection.status()).isEqualTo(new ComfiConfStatus(ComfiConfState.ON, "0.1.0", null));
    }

    @Test
    void aLinkageErrorFromTheLinkIsCaughtToo() {
        ComfiConfConnection connection = connect(() -> link);
        log.records.clear();
        NoClassDefFoundError failure = new NoClassDefFoundError("com/comficonf/api/ResourceRegistration");
        link.failure = failure;

        connection.close();
        connection.notifyChanged();

        assertThat(connection.status().state()).isEqualTo(ComfiConfState.FAILED);
        assertThat(log.records).hasSize(3).allSatisfy(record -> {
            assertThat(record.getLevel()).isEqualTo(Level.WARNING);
            assertThat(record.getThrown()).isSameAs(failure);
        });
    }

    @Test
    void aLogHandlerThatThrowsDoesNotLetALinkFailureEscape() {
        ComfiConfConnection connection = connect(() -> link);
        log.logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                throw new IllegalStateException("log handler failed");
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        link.failure = new IllegalStateException("bridge broke");

        connection.close();

        assertThat(link.closed).hasValue(1);
    }

    @Test
    void theLockForAFileIsTheLinksWhileRegistered() {
        ComfiConfConnection connection = connect(() -> link);
        Path file = Path.of("plugins", "Test", "config.yml");

        assertThat(connection.lockFor(file)).isSameAs(link.lock);
        assertThat(link.lockedPaths).containsExactly(file);
    }

    @Test
    void withoutALinkTheLockIsOneLockOfTheConnection() {
        ComfiConfConnection off = ComfiConfConnection.connect(owner, errors, false, registering(() -> link));

        Lock lock = off.lockFor(Path.of("config.yml"));

        assertThat(lock).isNotNull().isSameAs(off.lockFor(Path.of("other.yml")));
        assertThat(lock.tryLock()).isTrue();
        lock.unlock();
        assertThat(link.lockedPaths).isEmpty();
    }

    @Test
    void aLinkThatFailsToGiveTheLockGivesTheConnectionsOwnWithOneWarning() {
        ComfiConfConnection connection = connect(() -> link);
        log.records.clear();
        IllegalStateException failure = new IllegalStateException("bridge broke");
        link.failure = failure;

        Lock first = connection.lockFor(Path.of("config.yml"));
        Lock second = connection.lockFor(Path.of("config.yml"));

        assertThat(first).isNotSameAs(link.lock).isSameAs(second);
        assertThat(log.lines()).containsExactly("WARNING ComfiConf 0.1.0 could not give the lock of config.yml, so a"
            + " ComfiConf apply may replace this plugin's own write of it (" + failure + ").");
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void aMissingClassOfTheRelocatedTaffyPackageIsAFailedRegistration() {
        List<LinkageError> failures = List.of(
            new NoClassDefFoundError("so/alaz/example/libs/taffy/comficonf/YamlBytes"),
            new IncompatibleClassChangeError("so.alaz.example.libs.taffy.comficonf.YamlFileProvider"));

        for (LinkageError failure : failures) {
            ComfiConfConnection connection = connectThrowing(failure);
            assertThat(connection.status())
                .isEqualTo(new ComfiConfStatus(ComfiConfState.FAILED, "0.1.0", FAILED_REASON));
        }
        assertThat(log.records).hasSize(2).allSatisfy(record -> assertThat(record.getThrown()).isNotNull());
        assertThat(reports).extracting(Map.Entry::getKey).containsExactly("hook.comficonf", "hook.comficonf");
        assertThat(reports).extracting(Map.Entry::getValue).containsExactlyElementsOf(failures);
    }

    @Test
    void refusesNullArguments() {
        Supplier<ComfiConfLink> registration = () -> link;

        assertThatThrownBy(() -> ComfiConfConnection.connect(null, errors, true, registration))
            .isInstanceOf(NullPointerException.class).hasMessage("owner");
        assertThatThrownBy(() -> ComfiConfConnection.connect(owner, null, true, registration))
            .isInstanceOf(NullPointerException.class).hasMessage("errors");
        assertThatThrownBy(() -> ComfiConfConnection.connect(owner, errors, true, null))
            .isInstanceOf(NullPointerException.class).hasMessage("registration");
        ComfiConfConnection connection = connect(registration);
        assertThatThrownBy(() -> connection.lockFor(null))
            .isInstanceOf(NullPointerException.class).hasMessage("file");
    }

    @Test
    void outdatedBridgeMessageNamesTheInstalledVersionAndTheMissingClass() {
        String message = ComfiConfConnection.incompatibleBridgeMessage("0.1.0",
            ComfiConfConnection.missing(new NoClassDefFoundError("com/comficonf/api/ConfigProvider")));
        assertThat(message).startsWith("The installed ComfiConf 0.1.0 does not include")
            .contains("com.comficonf.api.ConfigProvider")
            .contains("Update ComfiConf")
            .endsWith("settings are edited in their files only until then.");
    }

    @Test
    void outdatedBridgeMessageWithoutDetailNamesTheErrorType() {
        String message = ComfiConfConnection.incompatibleBridgeMessage("0.1.0",
            ComfiConfConnection.missing(new NoClassDefFoundError()));
        assertThat(message).contains("(NoClassDefFoundError)");
    }

    /** A link that counts the calls it receives, and throws {@link #failure} from each when it is set. */
    private static final class FakeLink implements ComfiConfLink {

        volatile boolean registered = true;
        volatile Throwable failure;
        final AtomicInteger notified = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        final ReentrantLock lock = new ReentrantLock();
        final List<Path> lockedPaths = new CopyOnWriteArrayList<>();

        private void failIfSet() {
            Throwable thrown = failure;
            if (thrown instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (thrown instanceof Error error) {
                throw error;
            }
        }

        @Override
        public String resourceId() {
            return "taffy:test";
        }

        @Override
        public boolean isRegistered() {
            failIfSet();
            return registered;
        }

        @Override
        public void notifyChanged() {
            notified.incrementAndGet();
            failIfSet();
        }

        @Override
        public void close() {
            closed.incrementAndGet();
            failIfSet();
        }

        @Override
        public Lock lockFor(Path file) {
            failIfSet();
            lockedPaths.add(file);
            return lock;
        }
    }
}
