package so.alaz.taffy.comficonf;

import com.comficonf.api.ApplyResult;
import com.comficonf.api.ApplyStatus;
import com.comficonf.api.ChangeKind;
import com.comficonf.api.ConfigSnapshot;
import com.comficonf.api.ConfigValues;
import com.comficonf.api.FieldChange;
import com.comficonf.api.ValidationIssue;
import com.comficonf.api.ValidationResult;
import com.comficonf.api.bukkit.SectionMapper;
import com.comficonf.api.io.BackupPolicy;
import com.comficonf.api.io.Revisions;
import com.comficonf.api.io.SafeFileWriter;
import com.comficonf.api.schema.ApplyMode;
import com.comficonf.api.schema.ConfigField;
import com.comficonf.api.schema.ConfigSchema;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import so.alaz.taffy.config.ConfigLoadException;
import so.alaz.taffy.test.CapturedLog;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YamlFileProviderTest {

    private static final String SETTINGS = """
        # Settings of the test plugin
        greeting: Hello # shown on join
        radius: 16
        mode: fast
        motd: |-
          Welcome
          Have fun
        aliases:
        - one
        - two
        database:
          # Address of the database server
          host: localhost
          password: s3cret
        extras:
          tiers:
            gold: 0.5
        """;

    private static final String NOT_LOADED = "The values were saved, but the plugin could not load them, so they are"
        + " not in effect.";

    private static final String LOAD_FAILED = "WARNING Saved config.yml from ComfiConf, but could not load it";

    @TempDir
    Path folder;

    private Path file;
    private Path backups;
    private final CapturedLog log = new CapturedLog();
    /** The plugin's loaded copy of the file, as the activation leaves it. */
    private final AtomicReference<YamlConfiguration> loaded = new AtomicReference<>();
    private final AtomicInteger activations = new AtomicInteger();
    private final AtomicBoolean lockedDuringActivation = new AtomicBoolean();
    private YamlFileProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        Path dataFolder = Files.createDirectories(folder.resolve("plugin"));
        file = dataFolder.resolve("config.yml");
        backups = dataFolder.resolve("backups");
        Files.writeString(file, SETTINGS);
        loaded.set(YamlConfiguration.loadConfiguration(file.toFile()));
        provider = providerActivatedBy(() -> {
            activations.incrementAndGet();
            lockedDuringActivation.set(SafeFileWriter.lockFor(file).isLocked());
            loaded.set(YamlConfiguration.loadConfiguration(file.toFile()));
            return true;
        });
    }

    private static SectionMapper mapper() {
        return SectionMapper.of(ConfigSchema.of(
            ConfigField.string("greeting"),
            ConfigField.integer("radius").range(1, 64),
            ConfigField.enumeration("mode").choice("fast", "Fast").choice("safe", "Safe").restartRequired(),
            ConfigField.text("motd").optional(),
            ConfigField.stringList("aliases").optional(),
            ConfigField.object("database").fields(
                ConfigField.string("host").restartRequired(),
                ConfigField.string("password").optional().writeOnly().restartRequired())));
    }

    private static SectionMapper serversMapper() {
        return SectionMapper.of(ConfigSchema.of(
            ConfigField.objectList("servers").itemKey("id").fields(
                ConfigField.string("id"),
                ConfigField.integer("port").range(1, 65535).restartRequired())));
    }

    private static Handler throwingHandler() {
        return new Handler() {
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
        };
    }

    private YamlFileProvider providerActivatedBy(Callable<Boolean> activation) {
        return YamlFileProvider.builder(file, mapper(), activation).build(log.logger);
    }

    private TestApplyContext changing(YamlFileProvider target, String path, Object value, ApplyMode mode)
        throws Exception {
        ConfigSnapshot current = target.read();
        return TestApplyContext.changing(current.revision(), current.values(), path, value, mode);
    }

    @Test
    void readsValuesAndTheRevisionOfTheSameBytes() throws Exception {
        ConfigSnapshot snapshot = provider.read();
        assertThat(snapshot.revision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(snapshot.values().getString("greeting")).isEqualTo("Hello");
        assertThat(snapshot.values().get("extras")).isNull();
        Files.writeString(file, Files.readString(file) + "\n# edited by hand\n");
        assertThat(provider.read().revision()).isNotEqualTo(snapshot.revision());
    }

    @Test
    void describesTheMappersSchema() {
        SectionMapper mapper = mapper();
        YamlFileProvider described = YamlFileProvider.builder(file, mapper, () -> true).build(log.logger);

        assertThat(described.describe()).isSameAs(mapper.schema());
    }

    @Test
    void appliesALiveChangeKeepsCommentsAndUndeclaredDataAndActivatesIt() throws Exception {
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        byte[] written = Files.readAllBytes(file);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(written));
        assertThat(new String(written, StandardCharsets.UTF_8))
            .contains("greeting: Hi")
            .contains("# Settings of the test plugin")
            .contains("# Address of the database server")
            .contains("gold: 0.5");
        assertThat(context.syncCalls).hasValue(1);
        assertThat(activations).hasValue(1);
        assertThat(lockedDuringActivation).isFalse();
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).hasSize(1);
        assertThat(log.lines()).containsExactly("INFO Applied a config.yml change from ComfiConf requested by owner");
    }

    @Test
    void aLogHandlerThatThrowsLeavesALiveChangeApplied() throws Exception {
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
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(activations).hasValue(1);
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(log.lines()).containsExactly("INFO Applied a config.yml change from ComfiConf requested by owner");
    }

    @Test
    void savesAFileWrittenWithCarriageReturnsWithCarriageReturns() throws Exception {
        Files.write(file, withCarriageReturns(SETTINGS));

        ApplyResult result = provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        String written = Files.readString(file);
        assertThat(written).contains("greeting: Hi");
        assertThat(written.replace("\r\n", "")).doesNotContain("\n").doesNotContain("\r");
    }

    @Test
    void savesAFileWrittenWithLineFeedsWithLineFeeds() throws Exception {
        ApplyResult result = provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(Files.readString(file)).contains("greeting: Hi").doesNotContain("\r");
    }

    @Test
    void readsBackTheValuesOfAFileSavedWithCarriageReturns() throws Exception {
        Files.write(file, withCarriageReturns(SETTINGS));

        provider.apply(changing(provider, "radius", 4L, ApplyMode.LIVE));

        assertThat(loaded.get().getInt("radius")).isEqualTo(4);
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hello");
        assertThat(loaded.get().getString("motd")).isEqualTo("Welcome\nHave fun");
    }

    @Test
    void reportsAConflictAndWritesNothingWhenTheFileChanged() throws Exception {
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);
        Files.writeString(file, Files.readString(file) + "\n# edited by hand\n");
        byte[] edited = Files.readAllBytes(file);

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.CONFLICT);
        assertThat(result.currentRevision()).isEqualTo(Revisions.of(edited));
        assertThat(Files.readAllBytes(file)).isEqualTo(edited);
        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).isEmpty();
        assertThat(context.syncCalls).hasValue(0);
    }

    @Test
    void reportsAConflictWhenTheFileChangesWhileTheApplyWaitsForTheLock() throws Exception {
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);
        FutureTask<ApplyResult> apply = new FutureTask<>(() -> provider.apply(context));
        ReentrantLock lock = SafeFileWriter.lockFor(file);
        byte[] edited;
        lock.lock();
        try {
            Thread worker = new Thread(apply, "bridge-worker");
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!lock.hasQueuedThreads() && !apply.isDone() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(lock.hasQueuedThreads()).isTrue();
            Files.writeString(file, SETTINGS + "# edited by hand\n");
            edited = Files.readAllBytes(file);
        } finally {
            lock.unlock();
        }

        ApplyResult result = apply.get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ApplyStatus.CONFLICT);
        assertThat(result.currentRevision()).isEqualTo(Revisions.of(edited));
        assertThat(Files.readAllBytes(file)).isEqualTo(edited);
        assertThat(context.syncCalls).hasValue(0);
        assertThat(activations).hasValue(0);
    }

    @Test
    void readWaitsForTheLockAndNeverSeesAHalfWrittenFile() throws Exception {
        byte[] whole = SETTINGS.replace("greeting: Hello", "greeting: Hi").getBytes(StandardCharsets.UTF_8);
        FutureTask<ConfigSnapshot> read = new FutureTask<>(() -> provider.read());
        ReentrantLock lock = SafeFileWriter.lockFor(file);
        lock.lock();
        try {
            Files.write(file, Arrays.copyOf(whole, whole.length / 2));
            new Thread(read, "bridge-worker").start();
            awaitQueuedThread(lock, read);
            assertThat(lock.hasQueuedThreads()).isTrue();
            assertThat(read.isDone()).isFalse();
            Files.write(file, whole);
        } finally {
            lock.unlock();
        }

        ConfigSnapshot snapshot = read.get(10, TimeUnit.SECONDS);

        assertThat(snapshot.revision()).isEqualTo(Revisions.of(whole));
        assertThat(snapshot.values().getString("greeting")).isEqualTo("Hi");
        assertThat(snapshot.values().getString("database.host")).isEqualTo("localhost");
    }

    @Test
    void applyWaitsForTheLockAndNeverSeesAHalfWrittenFile() throws Exception {
        byte[] whole = Files.readAllBytes(file);
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);
        FutureTask<ApplyResult> apply = new FutureTask<>(() -> provider.apply(context));
        ReentrantLock lock = SafeFileWriter.lockFor(file);
        lock.lock();
        try {
            Files.write(file, Arrays.copyOf(whole, whole.length / 2));
            new Thread(apply, "bridge-worker").start();
            awaitQueuedThread(lock, apply);
            assertThat(lock.hasQueuedThreads()).isTrue();
            assertThat(apply.isDone()).isFalse();
            Files.write(file, whole);
        } finally {
            lock.unlock();
        }

        ApplyResult result = apply.get(10, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(Files.readString(file)).contains("greeting: Hi").contains("password: s3cret");
        assertThat(lock.isLocked()).isFalse();
        assertThat(lockedDuringActivation).isFalse();
    }

    @Test
    void buildWaitsForTheLockAndTakesTheStartupValuesOfTheWholeFile() throws Exception {
        byte[] whole = Files.readAllBytes(file);
        FutureTask<YamlFileProvider> build = new FutureTask<>(() -> providerActivatedBy(() -> true));
        ReentrantLock lock = SafeFileWriter.lockFor(file);
        lock.lock();
        try {
            Files.write(file, Arrays.copyOf(whole, whole.length / 2));
            new Thread(build, "server-thread").start();
            awaitQueuedThread(lock, build);
            assertThat(lock.hasQueuedThreads()).isTrue();
            Files.write(file, whole);
        } finally {
            lock.unlock();
        }
        YamlFileProvider built = build.get(10, TimeUnit.SECONDS);

        ApplyResult result = built.apply(changing(built, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    @Test
    void neverReportsAppliedWhenTheFileDoesNotLoad() throws Exception {
        YamlFileProvider failing = providerActivatedBy(() -> false);

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.persisted()).isTrue();
        assertThat(result.activated()).isFalse();
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(log.lines()).isEmpty();
    }

    @Test
    void aFailedActivationCarriesThePluginsNotLoadedMessage() throws Exception {
        String message = "The values were saved, but Example could not load them. They take effect after /example "
            + "reload or a restart.";
        IllegalStateException failure = new IllegalStateException("reload failed");
        YamlFileProvider refusing = YamlFileProvider.builder(file, mapper(), () -> false)
            .notLoadedMessage(message).build(log.logger);
        YamlFileProvider throwing = YamlFileProvider.builder(file, mapper(), () -> {
            throw failure;
        }).notLoadedMessage(message).build(log.logger);

        ApplyResult refused = refusing.apply(changing(refusing, "greeting", "Hi", ApplyMode.LIVE));
        ApplyResult thrown = throwing.apply(changing(throwing, "greeting", "Hey", ApplyMode.LIVE));

        assertThat(refused.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(refused.message()).isEqualTo(message);
        assertThat(refused.restartRequired()).isEmpty();
        assertThat(thrown.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(thrown.message()).isEqualTo(message);
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
    }

    @Test
    void aFailedActivationCarriesTheDefaultMessageWhenThePluginSetsNone() throws Exception {
        YamlFileProvider failing = YamlFileProvider.builder(file, mapper(), () -> false).build(log.logger);

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.message()).isEqualTo("The values were saved, but the plugin could not load them, so they "
            + "are not in effect.");
    }

    @Test
    void thePluginsNotLoadedMessageLeavesTheRestartOnlyMessageAlone() throws Exception {
        YamlFileProvider messaged = YamlFileProvider.builder(file, mapper(), () -> true)
            .notLoadedMessage("Run /example reload.").build(log.logger);

        ApplyResult result = messaged.apply(changing(messaged, "mode", "safe", ApplyMode.RESTART));

        assertThat(result.message()).isEqualTo("Saved. Settings marked Restart required take effect after a server "
            + "restart; all other changes are active.");
    }

    @Test
    void neverReportsAppliedWhenTheActivationReturnsNull() throws Exception {
        YamlFileProvider failing = providerActivatedBy(() -> null);

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(Files.readString(file)).contains("greeting: Hi");
    }

    @Test
    void neverReportsAppliedWhenTheReloadThrows() throws Exception {
        IllegalStateException failure = new IllegalStateException("reload failed");
        YamlFileProvider failing = providerActivatedBy(() -> {
            throw failure;
        });

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(Files.readString(file)).contains("greeting: Hi");
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void aLogHandlerThatThrowsLeavesAFailedActivationSaved() throws Exception {
        log.logger.addHandler(throwingHandler());
        IllegalStateException failure = new IllegalStateException("reload failed");
        YamlFileProvider failing = providerActivatedBy(() -> {
            throw failure;
        });

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(Files.readString(file)).contains("greeting: Hi");
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void neverThrowsWhenTheActivationFailsToInitializeAClass() throws Exception {
        ExceptionInInitializerError failure = new ExceptionInInitializerError(new IllegalStateException("static"));
        YamlFileProvider failing = providerActivatedBy(() -> {
            throw failure;
        });

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void neverThrowsWhenTheActivationFailsAnAssertion() throws Exception {
        AssertionError failure = new AssertionError("unreachable");
        YamlFileProvider failing = providerActivatedBy(() -> {
            throw failure;
        });

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(Files.readString(file)).contains("greeting: Hi");
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void neverThrowsWhenTheActivationMissesAClass() throws Exception {
        NoClassDefFoundError failure = new NoClassDefFoundError("org/example/Missing");
        YamlFileProvider failing = providerActivatedBy(() -> {
            throw failure;
        });

        ApplyResult result = failing.apply(changing(failing, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(failure);
    }

    @Test
    void anActivationThatTimesOutIsNotApplied() throws Exception {
        TimeoutException timeout = new TimeoutException("main thread busy");
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE).onMainThread(task -> {
            throw timeout;
        });

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(Files.readString(file)).contains("greeting: Hi");
        assertThat(context.syncCalls).hasValue(1);
        assertThat(activations).hasValue(0);
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(log.records.getFirst().getThrown()).isSameAs(timeout);
    }

    @Test
    void anActivationThatRunsAfterACallSyncTimeoutStillTakesEffect() throws Exception {
        AtomicReference<Callable<?>> queued = new AtomicReference<>();
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE).onMainThread(task -> {
            queued.set(task);
            throw new TimeoutException("main thread busy");
        });

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(activations).hasValue(0);
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hello");

        Object late = queued.get().call();

        assertThat(late).isEqualTo(true);
        assertThat(activations).hasValue(1);
        assertThat(lockedDuringActivation).isFalse();
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
        assertThat(provider.read().revision()).isEqualTo(result.newRevision());
    }

    @Test
    void anApplyAfterACallSyncTimeoutStartsFromTheRevisionOfTheSavedFile() throws Exception {
        TestApplyContext timedOut = changing(provider, "greeting", "Hi", ApplyMode.LIVE).onMainThread(task -> {
            throw new TimeoutException("main thread busy");
        });
        ApplyResult first = provider.apply(timedOut);
        ConfigSnapshot saved = provider.read();

        ApplyResult second = provider.apply(TestApplyContext.changing(first.newRevision(), saved.values(), "radius",
            8L, ApplyMode.LIVE));

        assertThat(second.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(activations).hasValue(1);
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(loaded.get().getInt("radius")).isEqualTo(8);
    }

    @Test
    void twoConsecutiveAppliesEachStartFromTheRevisionOfTheOneBefore() throws Exception {
        ConfigSnapshot start = provider.read();
        ApplyResult first = provider.apply(TestApplyContext.changing(start.revision(), start.values(), "greeting",
            "Hi", ApplyMode.LIVE));
        ConfigSnapshot afterFirst = provider.read();

        ApplyResult second = provider.apply(TestApplyContext.changing(first.newRevision(), afterFirst.values(),
            "radius", 8L, ApplyMode.LIVE));

        assertThat(first.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(afterFirst.revision()).isEqualTo(first.newRevision());
        assertThat(second.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(second.newRevision()).isNotEqualTo(first.newRevision())
            .isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(Files.readString(file)).contains("greeting: Hi").contains("radius: 8")
            .contains("# Settings of the test plugin");
        assertThat(activations).hasValue(2);
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(loaded.get().getInt("radius")).isEqualTo(8);
        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).hasSize(2);
    }

    @Test
    void aSecondApplyFromTheRevisionBeforeTheFirstIsAConflict() throws Exception {
        ConfigSnapshot start = provider.read();
        ApplyResult first = provider.apply(TestApplyContext.changing(start.revision(), start.values(), "greeting",
            "Hi", ApplyMode.LIVE));
        byte[] afterFirst = Files.readAllBytes(file);
        TestApplyContext stale = TestApplyContext.changing(start.revision(), start.values(), "radius", 8L,
            ApplyMode.LIVE);

        ApplyResult second = provider.apply(stale);

        assertThat(second.status()).isEqualTo(ApplyStatus.CONFLICT);
        assertThat(second.currentRevision()).isEqualTo(first.newRevision());
        assertThat(Files.readAllBytes(file)).isEqualTo(afterFirst);
        assertThat(stale.syncCalls).hasValue(0);
        assertThat(activations).hasValue(1);
    }

    @Test
    void anInterruptedActivationKeepsTheInterruptFlag() throws Exception {
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE).onMainThread(task -> {
            throw new InterruptedException();
        });

        ApplyResult result;
        boolean interrupted;
        try {
            result = provider.apply(context);
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(interrupted).isTrue();
        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.message()).isEqualTo(NOT_LOADED);
        assertThat(activations).hasValue(0);
        assertThat(log.lines()).containsExactly(LOAD_FAILED);
    }

    @Test
    void keepsARestartOnlyChangeWaiting() throws Exception {
        ApplyResult result = provider.apply(changing(provider, "mode", "safe", ApplyMode.RESTART));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("mode");
        assertThat(result.message()).isEqualTo("Saved. Settings marked Restart required take effect after a server "
            + "restart; all other changes are active.");
        assertThat(Files.readString(file)).contains("mode: safe");
        assertThat(activations).hasValue(1);
    }

    @Test
    void listsOnlyTheRestartOnlyPathsOfAMixedChange() throws Exception {
        ConfigSnapshot current = provider.read();
        ConfigValues before = current.values();
        List<FieldChange> changes = List.of(
            new FieldChange("greeting", ChangeKind.MODIFIED, ApplyMode.LIVE, false, null, before.get("greeting"), "Hi"),
            new FieldChange("mode", ChangeKind.MODIFIED, ApplyMode.RESTART, false, null, before.get("mode"), "safe"));
        ConfigValues proposed = before.with("greeting", "Hi").with("mode", "safe");

        ApplyResult result = provider.apply(new TestApplyContext(current.revision(), before, proposed, changes));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("mode");
        assertThat(Files.readString(file)).contains("greeting: Hi").contains("mode: safe");
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
    }

    @Test
    void reportsRestoringTheStartupValueAsApplied() throws Exception {
        provider.apply(changing(provider, "mode", "safe", ApplyMode.RESTART));

        ApplyResult result = provider.apply(changing(provider, "mode", "fast", ApplyMode.RESTART));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(Files.readString(file)).contains("mode: fast");
    }

    @Test
    void aLaterLiveOnlyApplyStillNamesTheWaitingRestartOnlyChange() throws Exception {
        ApplyResult first = provider.apply(changing(provider, "mode", "safe", ApplyMode.RESTART));
        TestApplyContext liveOnly = changing(provider, "greeting", "Hi", ApplyMode.LIVE);

        ApplyResult second = provider.apply(liveOnly);

        assertThat(first.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(first.restartRequired()).containsExactly("mode");
        assertThat(liveOnly.requiresRestart()).isFalse();
        assertThat(second.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(second.restartRequired()).containsExactly("mode");
        assertThat(second.message()).isEqualTo("Saved. Settings marked Restart required take effect after a server "
            + "restart; all other changes are active.");
        assertThat(second.newRevision()).isEqualTo(Revisions.of(Files.readAllBytes(file)));
        assertThat(loaded.get().getString("greeting")).isEqualTo("Hi");
        assertThat(activations).hasValue(2);
    }

    @Test
    void aLiveOnlyApplyIsAppliedOnceTheFileHoldsTheStartupValueAgain() throws Exception {
        provider.apply(changing(provider, "mode", "safe", ApplyMode.RESTART));
        provider.apply(changing(provider, "mode", "fast", ApplyMode.RESTART));

        ApplyResult result = provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(result.restartRequired()).isEmpty();
    }

    @Test
    void aLiveOnlyApplyNamesARestartOnlyValueEditedInTheFile() throws Exception {
        Files.writeString(file, SETTINGS.replace("host: localhost", "host: db.example.net"));

        ApplyResult result = provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("database.host");
    }

    @Test
    void aLaterLiveOnlyApplyStillNamesTheWaitingChangeOfAListItemByItsItemKey() throws Exception {
        Path servers = file.resolveSibling("servers.yml");
        Files.writeString(servers, """
            servers:
            - id: a
              port: 1
            - id: b
              port: 2
            """);
        YamlFileProvider listed = YamlFileProvider.builder(servers, serversMapper(), () -> true)
            .build(log.logger);
        ConfigSnapshot current = listed.read();
        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("id", "b");
        changed.put("port", 1L);
        listed.apply(new TestApplyContext(current.revision(), current.values(),
            current.values().with("servers", List.of(changed)), List.of(
                new FieldChange("servers[0]", ChangeKind.REMOVED, ApplyMode.LIVE, false, "a", null, null),
                new FieldChange("servers[0].port", ChangeKind.MODIFIED, ApplyMode.RESTART, false, "b", 2L, 1L))));
        ConfigSnapshot saved = listed.read();
        Map<String, Object> restored = new LinkedHashMap<>();
        restored.put("id", "a");
        restored.put("port", 1L);

        ApplyResult result = listed.apply(new TestApplyContext(saved.revision(), saved.values(),
            saved.values().with("servers", List.of(restored, changed)), List.of(
                new FieldChange("servers[0]", ChangeKind.ADDED, ApplyMode.LIVE, false, "a", null, null))));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("servers[1].port");
    }

    @Test
    void keepsEveryRestartOnlyChangeWaitingWhenTheFileWasUnreadableAtBuild() throws Exception {
        Files.writeString(file, "greeting: [unclosed\n");
        YamlFileProvider unreadable = providerActivatedBy(() -> true);
        Files.writeString(file, SETTINGS);
        unreadable.apply(changing(unreadable, "mode", "safe", ApplyMode.RESTART));

        ApplyResult result = unreadable.apply(changing(unreadable, "mode", "fast", ApplyMode.RESTART));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("mode");
    }

    @Test
    void aLiveOnlyApplyNamesNoEarlierChangeWhenTheFileWasUnreadableAtBuild() throws Exception {
        Files.writeString(file, "greeting: [unclosed\n");
        YamlFileProvider unreadable = providerActivatedBy(() -> true);
        Files.writeString(file, SETTINGS);
        unreadable.apply(changing(unreadable, "mode", "safe", ApplyMode.RESTART));

        ApplyResult result = unreadable.apply(changing(unreadable, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
    }

    @Test
    void keepsARestartOnlyChangeInsideAListWaitingAfterAnEarlierItemWasRemoved() throws Exception {
        Path servers = file.resolveSibling("servers.yml");
        Files.writeString(servers, """
            servers:
            - id: a
              port: 1
            - id: b
              port: 2
            """);
        YamlFileProvider listed = YamlFileProvider.builder(servers, serversMapper(), () -> true)
            .build(log.logger);
        ConfigSnapshot current = listed.read();
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", "b");
        second.put("port", 1L);
        ConfigValues proposed = current.values().with("servers", List.of(second));
        List<FieldChange> changes = List.of(
            new FieldChange("servers[0]", ChangeKind.REMOVED, ApplyMode.LIVE, false, "a", null, null),
            new FieldChange("servers[0].port", ChangeKind.MODIFIED, ApplyMode.RESTART, false, "b", 2L, 1L));

        ApplyResult result = listed.apply(new TestApplyContext(current.revision(), current.values(), proposed,
            changes));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("servers[0].port");
        assertThat(listed.read().values().get("servers[0].port")).isEqualTo(1L);
    }

    @Test
    void keepsARestartOnlyChangeInsideAListWaitingAfterTheItemsWereReordered() throws Exception {
        Path servers = file.resolveSibling("servers.yml");
        Files.writeString(servers, """
            servers:
            - id: a
              port: 1
            - id: b
              port: 2
            """);
        YamlFileProvider listed = YamlFileProvider.builder(servers, serversMapper(), () -> true)
            .build(log.logger);
        ConfigSnapshot current = listed.read();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", "b");
        first.put("port", 2L);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", "a");
        second.put("port", 2L);
        ConfigValues proposed = current.values().with("servers", List.of(first, second));
        List<FieldChange> changes = List.of(
            new FieldChange("servers[1].port", ChangeKind.MODIFIED, ApplyMode.RESTART, false, "a", 1L, 2L));

        ApplyResult result = listed.apply(new TestApplyContext(current.revision(), current.values(), proposed,
            changes));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(result.restartRequired()).containsExactly("servers[1].port");
    }

    @Test
    void writesNumbersAsNumbers() throws Exception {
        provider.apply(changing(provider, "radius", 10L, ApplyMode.LIVE));

        assertThat(Files.readString(file)).contains("radius: 10\n").doesNotContain("10.0");
        assertThat(loaded.get().getLong("radius")).isEqualTo(10L);
    }

    @Test
    void refusesTextYamlCannotStoreAndWritesNothing() throws Exception {
        byte[] before = Files.readAllBytes(file);

        TestApplyContext context = changing(provider, "greeting", "\u001B[31mHello", ApplyMode.LIVE);
        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.REJECTED);
        assertThat(result.issues()).isNotEmpty().isEqualTo(mapper().validate(context.proposed()).issues());
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).isEmpty();
        assertThat(context.syncCalls).hasValue(0);
    }

    @Test
    void reportsAConflictBeforeReadingAStaleFileThatIsNoLongerValidYaml() throws Exception {
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);
        Files.writeString(file, "greeting: [unclosed\n");
        byte[] broken = Files.readAllBytes(file);

        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.CONFLICT);
        assertThat(result.currentRevision()).isEqualTo(Revisions.of(broken));
        assertThat(Files.readAllBytes(file)).isEqualTo(broken);
    }

    @Test
    void aListThatReadsBackWithAnotherSizeOrAMapThatReadsBackAsAValueIsAltered() {
        List<String> altered = new ArrayList<>();

        YamlFileProvider.collectAltered(Map.of("aliases", List.of("one", "two"), "database", Map.of("host", "x")),
            Map.of("aliases", List.of("one"), "database", "x"), "", altered);

        assertThat(altered).containsExactlyInAnyOrder("aliases", "database");
    }

    @Test
    void keepsTheByteOrderMarkOfTheFile() throws Exception {
        byte[] mark = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = withCarriageReturns(SETTINGS);
        byte[] marked = Arrays.copyOf(mark, mark.length + content.length);
        System.arraycopy(content, 0, marked, mark.length, content.length);
        Files.write(file, marked);

        ApplyResult result = provider.apply(changing(provider, "radius", 17L, ApplyMode.LIVE));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        byte[] written = Files.readAllBytes(file);
        assertThat(Arrays.copyOf(written, mark.length)).isEqualTo(mark);
        assertThat(new String(written, StandardCharsets.UTF_8)).contains("radius: 17\r\n")
            .contains("# Settings of the test plugin");
        assertThat(provider.read().values().getString("greeting")).isEqualTo("Hello");
    }

    @Test
    void refusesValuesThatReadBackChangedAndWritesNothing() throws Exception {
        ConfigSnapshot current = provider.read();
        byte[] before = Files.readAllBytes(file);
        ConfigValues proposed = current.values()
            .with("greeting", 5L)
            .with("aliases", List.of("one", 2L))
            .with("database.host", 7L);

        TestApplyContext context = new TestApplyContext(current.revision(), current.values(), proposed, List.of());
        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.REJECTED);
        assertThat(result.issues()).extracting(ValidationIssue::path)
            .containsExactlyInAnyOrder("greeting", "aliases[1]", "database.host");
        assertThat(result.issues()).extracting(ValidationIssue::message)
            .containsOnly("config.yml cannot store this value unchanged, so nothing was saved.");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(context.syncCalls).hasValue(0);
    }

    @Test
    void refusesValuesThatDoNotReadBackAtAllAndWritesNothing() throws Exception {
        ConfigSnapshot current = provider.read();
        byte[] before = Files.readAllBytes(file);
        ConfigValues proposed = current.values().with("greeting", Map.of("nested", "value"));

        TestApplyContext context = new TestApplyContext(current.revision(), current.values(), proposed, List.of());
        ApplyResult result = provider.apply(context);

        assertThat(result.status()).isEqualTo(ApplyStatus.REJECTED);
        assertThat(result.issues()).extracting(ValidationIssue::path).containsExactly("");
        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(log.lines()).containsExactly("WARNING config.yml would not read back the values from ComfiConf");
        assertThat(log.records.getFirst().getThrown()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void appliesUnchangedValuesToTheSameValues() throws Exception {
        ConfigSnapshot current = provider.read();

        ApplyResult result = provider.apply(new TestApplyContext(current.revision(), current.values(),
            current.values(), List.of()));

        assertThat(result.status()).isEqualTo(ApplyStatus.APPLIED);
        assertThat(provider.read().values()).isEqualTo(current.values());
    }

    @Test
    void replacesTheWriteOnlyPasswordAndKeepsItOutOfTheResult() throws Exception {
        ApplyResult result = provider.apply(changing(provider, "database.password", "n3w-secret",
            ApplyMode.RESTART));

        assertThat(result.status()).isEqualTo(ApplyStatus.SAVED_RESTART_REQUIRED);
        assertThat(Files.readString(file)).contains("password: n3w-secret");
        assertThat(result.toString()).doesNotContain("n3w-secret");
        assertThat(result.message()).doesNotContain("n3w-secret");
        assertThat(log.records).extracting(LogRecord::getMessage).noneMatch(line -> line.contains("n3w-secret"));
    }

    @Test
    void refusesAFileThatIsNotUtf8AndLeavesItAlone() throws Exception {
        byte[] legacy = SETTINGS.replace("greeting: Hello", "greeting: Grüße")
            .getBytes(Charset.forName("windows-1252"));
        Files.write(file, legacy);

        assertThatThrownBy(() -> provider.read()).isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not saved as UTF-8");
        TestApplyContext context = new TestApplyContext(Revisions.of(legacy), ConfigValues.empty(),
            ConfigValues.empty(), List.of());
        assertThatThrownBy(() -> provider.apply(context)).isInstanceOf(ConfigLoadException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(legacy);
        assertThat(context.syncCalls).hasValue(0);
    }

    @Test
    void refusesAnAliasBombAndLeavesItAlone() throws Exception {
        byte[] bomb = nestedAliases(7).getBytes(StandardCharsets.UTF_8);
        Files.write(file, bomb);

        assertThatThrownBy(() -> provider.read()).isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is not valid YAML: its aliases repeat more than 100000 values");
        TestApplyContext context = new TestApplyContext(Revisions.of(bomb), ConfigValues.empty(),
            ConfigValues.empty(), List.of());
        assertThatThrownBy(() -> provider.apply(context)).isInstanceOf(ConfigLoadException.class);
        assertThat(Files.readAllBytes(file)).isEqualTo(bomb);
    }

    @Test
    void aFileThatDoesNotParseIsRefusedWithoutQuotingTheLine() throws Exception {
        Files.writeString(file, SETTINGS.replace("radius: 16", "radius: hunter2: x"));

        assertThatThrownBy(() -> provider.read()).isInstanceOf(ConfigLoadException.class)
            .hasMessageStartingWith("config.yml is not valid YAML: ")
            .hasNoCause()
            .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("hunter2"));
    }

    @Test
    void aFileOverTheSizeLimitIsRefusedAndLeftAlone() throws Exception {
        byte[] large = new byte[YamlBytes.MAX_FILE_BYTES + 1];
        Arrays.fill(large, (byte) '#');
        Files.write(file, large);

        assertThatThrownBy(() -> provider.read()).isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is larger than 16 MiB");
        TestApplyContext context = new TestApplyContext(Revisions.of(large), ConfigValues.empty(),
            ConfigValues.empty(), List.of());
        assertThatThrownBy(() -> provider.apply(context)).isInstanceOf(ConfigLoadException.class)
            .hasMessage("config.yml is larger than 16 MiB");
        assertThat(Files.readAllBytes(file)).isEqualTo(large);
        assertThat(context.syncCalls).hasValue(0);
    }

    @Test
    void readingAMissingFileThrowsIOException() throws Exception {
        Files.delete(file);

        assertThatThrownBy(() -> provider.read()).isInstanceOf(IOException.class);
    }

    @Test
    void keepsBackupsInTheFolderBesideTheFile() throws Exception {
        provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));
        provider.apply(changing(provider, "greeting", "Hey", ApplyMode.LIVE));

        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).hasSize(2)
            .allSatisfy(backup -> assertThat(backup.getParent()).isEqualTo(backups));
        assertThat(new String(Files.readAllBytes(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))
            .getLast()), StandardCharsets.UTF_8)).isEqualTo(SETTINGS);
    }

    @Test
    void keepsBackupsUnderThePolicyThePluginSets() throws Exception {
        BackupPolicy policy = new BackupPolicy(folder.resolve("archive"), 1);
        YamlFileProvider archiving = YamlFileProvider.builder(file, mapper(), () -> true)
            .backups(policy).build(log.logger);

        archiving.apply(changing(archiving, "greeting", "Hi", ApplyMode.LIVE));
        archiving.apply(changing(archiving, "greeting", "Hey", ApplyMode.LIVE));

        List<Path> kept = SafeFileWriter.listBackups(file, policy);
        assertThat(kept).hasSize(1).allSatisfy(backup -> assertThat(backup.getParent())
            .isEqualTo(folder.resolve("archive")));
        assertThat(Files.readString(kept.getFirst())).isEqualTo(SETTINGS.replace("greeting: Hello", "greeting: Hi"));
        assertThat(backups).doesNotExist();
    }

    @Test
    void aBackupIsAFullCopyOfTheFileWithItsSecrets() throws Exception {
        provider.apply(changing(provider, "greeting", "Hi", ApplyMode.LIVE));

        assertThat(SafeFileWriter.listBackups(file, BackupPolicy.in(backups))).singleElement()
            .satisfies(backup -> assertThat(Files.readString(backup)).isEqualTo(SETTINGS)
                .contains("password: s3cret"));
    }

    @Test
    void aBackupFolderThatIsAFileFailsTheWriteAndLeavesTheFileAlone() throws Exception {
        Files.writeString(backups, "not a folder");
        byte[] before = Files.readAllBytes(file);
        TestApplyContext context = changing(provider, "greeting", "Hi", ApplyMode.LIVE);

        assertThatThrownBy(() -> provider.apply(context)).isInstanceOf(IOException.class);

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(context.syncCalls).hasValue(0);
        assertThat(activations).hasValue(0);
    }

    @Test
    void validatesWithTheMapperThenThePluginsRules() throws Exception {
        List<ConfigValues> seen = new ArrayList<>();
        YamlFileProvider ruled = YamlFileProvider.builder(file, mapper(), () -> true)
            .rules(proposed -> {
                seen.add(proposed);
                ValidationResult.Builder rules = ValidationResult.builder();
                if (proposed.getLong("radius", 0) > 32) {
                    rules.error("radius", "Keep the radius at 32 or below.");
                }
                return rules.warning("motd", "Checked.").build();
            })
            .build(log.logger);
        ConfigValues values = ruled.read().values();

        ValidationResult accepted = ruled.validate(values);
        ValidationResult refused = ruled.validate(values.with("greeting", "\u001B[31mHello").with("radius", 40L));

        assertThat(accepted.isValid()).isTrue();
        assertThat(accepted.warnings()).extracting(ValidationIssue::path).containsExactly("motd");
        assertThat(refused.isValid()).isFalse();
        assertThat(refused.issues()).extracting(ValidationIssue::path).containsExactly("greeting", "radius", "motd");
        assertThat(seen).hasSize(2);
    }

    @Test
    void rulesThatReturnNullFailValidationNamingTheCause() throws Exception {
        YamlFileProvider ruled = YamlFileProvider.builder(file, mapper(), () -> true)
            .rules(proposed -> null)
            .build(log.logger);
        ConfigValues values = ruled.read().values();

        assertThatThrownBy(() -> ruled.validate(values))
            .isInstanceOf(NullPointerException.class).hasMessage("rules returned null");
    }

    @Test
    void validatesWithTheMapperAloneWithoutRules() throws Exception {
        ConfigValues values = provider.read().values();

        assertThat(provider.validate(values).issues()).isEmpty();
        assertThat(provider.validate(values.with("greeting", "\u001B[31mHello")).errors())
            .extracting(ValidationIssue::path).containsExactly("greeting");
    }

    @Test
    void refusesNullInputs() {
        SectionMapper mapper = mapper();
        Callable<Boolean> activation = () -> true;

        assertThatThrownBy(() -> YamlFileProvider.builder(null, mapper, activation))
            .isInstanceOf(NullPointerException.class).hasMessage("file");
        assertThatThrownBy(() -> YamlFileProvider.builder(file, null, activation))
            .isInstanceOf(NullPointerException.class).hasMessage("mapper");
        assertThatThrownBy(() -> YamlFileProvider.builder(file, mapper, null))
            .isInstanceOf(NullPointerException.class).hasMessage("activation");
        YamlFileProvider.Builder builder = YamlFileProvider.builder(file, mapper, activation);
        assertThatThrownBy(() -> builder.rules(null))
            .isInstanceOf(NullPointerException.class).hasMessage("rules");
        assertThatThrownBy(() -> builder.notLoadedMessage(null))
            .isInstanceOf(NullPointerException.class).hasMessage("message");
        assertThatThrownBy(() -> builder.backups(null))
            .isInstanceOf(NullPointerException.class).hasMessage("policy");
        assertThatThrownBy(() -> builder.build(null))
            .isInstanceOf(NullPointerException.class).hasMessage("logger");
    }

    /** Returns once a thread waits for {@code lock}, {@code task} is done, or 10 seconds passed. */
    private static void awaitQueuedThread(ReentrantLock lock, FutureTask<?> task) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!lock.hasQueuedThreads() && !task.isDone() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static byte[] withCarriageReturns(String text) {
        return text.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    /** Levels of nine aliases of the level below, so the last level stands for nine to the power levels values. */
    private static String nestedAliases(int levels) {
        StringBuilder text = new StringBuilder();
        for (int level = 0; level <= levels; level++) {
            String value = level == 0 ? "x" : "*l" + (level - 1);
            List<String> entries = new ArrayList<>();
            for (int entry = 0; entry < 9; entry++) {
                entries.add(value);
            }
            text.append("l").append(level).append(": &l").append(level).append(" [")
                .append(String.join(", ", entries)).append("]\n");
        }
        return text.toString();
    }
}
