package so.alaz.taffy.comficonf;

import com.comficonf.api.ApplyContext;
import com.comficonf.api.ApplyResult;
import com.comficonf.api.ConfigPath;
import com.comficonf.api.ConfigProvider;
import com.comficonf.api.ConfigSnapshot;
import com.comficonf.api.ConfigValues;
import com.comficonf.api.FieldChange;
import com.comficonf.api.ValidationResult;
import com.comficonf.api.bukkit.SectionMapper;
import com.comficonf.api.io.BackupPolicy;
import com.comficonf.api.io.RevisionConflictException;
import com.comficonf.api.io.Revisions;
import com.comficonf.api.io.SafeFileWriter;
import com.comficonf.api.schema.ApplyMode;
import com.comficonf.api.schema.ConfigField;
import com.comficonf.api.schema.ConfigSchema;
import com.comficonf.api.schema.FieldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import so.alaz.taffy.config.ConfigLoadException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Exposes one YAML settings file to ComfiConf. The fields the {@link SectionMapper} declares are read from the file
 * and written into it; comments, keys the schema does not declare and the line endings of the file are kept on
 * every write. A file that is not UTF-8 or not valid YAML is never written.
 *
 * <p>The bridge calls {@link #describe()}, {@link #read()}, {@link #validate} and {@link #apply} on its worker
 * thread, and each call parses its own document. {@link #read()} takes the values and the revision from one read of
 * the file, made while holding the lock of {@link SafeFileWriter#lockFor(Path)}. {@link #apply} takes that lock,
 * reads the file, checks the base revision against the current bytes, merges the proposal into the document parsed
 * from them, refuses a value the file cannot store unchanged and writes through {@link SafeFileWriter}, which keeps
 * the 10 newest backups in the {@code backups} folder beside the file unless {@link Builder#backups} names another
 * policy, and releases the lock. Each backup is a full copy of the file as it was before the write, the secrets in
 * it included. With no lock held, it then runs the activation on the main thread through
 * {@link ApplyContext#callSync}. Nothing after the write throws: the result is {@code applied} only when the
 * activation returned {@code true} and no restart-only change waits, and {@code savedRestartRequired} otherwise.
 *
 * <h2>The activation</h2>
 *
 * <p>The activation reads the file into the plugin and returns {@code true} when the new values are in effect. A
 * {@code false} or {@code null} result, an exception, an error and a {@code callSync} that timed out or was
 * interrupted all mean the values are not in effect. A failure the activation threw and a {@code callSync} that
 * timed out or was interrupted are each logged as a warning with its stack and never reported to the plugin's
 * {@code ErrorReports}, because the message of a failure of the plugin's loader can quote a configuration value.
 * The {@code savedRestartRequired} result then carries the text of {@link Builder#notLoadedMessage}. A
 * {@code callSync} that timed out may leave the activation waiting for the main thread, which can still run it after
 * {@link #apply} returned: the values are then in effect although the result said they are not, and the outcome of
 * that late run is neither logged nor reported.
 * {@code holder::reload} of a {@link so.alaz.taffy.config.ConfigHolder} serves as the activation.
 *
 * <p>The result is true only when the schema and the plugin's rules accept no more than the loader the activation
 * runs. Each constraint of the schema is no looser than the matching {@link so.alaz.taffy.config.ConfigReader} call;
 * otherwise the reader replaces an accepted value with its fallback, and the result is {@code applied} while the
 * fallback is in effect. {@link Builder#rules} refuses every proposal the loader rejects, its errors and cross-field
 * checks included; otherwise the file is saved with values the loader refuses, the result is
 * {@code savedRestartRequired}, and the next start of the plugin fails on the file.
 *
 * <p>The loader the activation runs must not write the file: a write there changes the revision after
 * {@link #apply} returned it, so the next apply is a conflict. {@link so.alaz.taffy.config.ConfigLoader#load()}
 * writes {@code config.yml} when the file is missing and when the save of an upgrade failed, so a
 * {@code ConfigHolder} whose loader runs it is write-free in the activation only after the startup load and its
 * upgrade succeeded.
 *
 * <h2>Restart-only fields</h2>
 *
 * <p>Each apply compares the whole proposal with the values the file held when the provider was built. Every field
 * declared {@code restartRequired()} whose value differs from its value at startup is reported as waiting for a
 * restart, whether this apply changed it or an earlier apply or an edit of the file did, so an apply that changes
 * only live fields still gives {@code savedRestartRequired} with the waiting paths. A field is no longer reported
 * once the file holds its startup value again. A field declared {@code restartRequired()} that holds other fields
 * is reported under its own path when a value inside it differs.
 *
 * <p>An item of an object list is compared with the item of the same item key at startup, and with the item at the
 * same index where the list declares no item key; an item with no such item at startup has no startup values. A
 * change of this apply inside an object list has a path through the index of its item, which names another item
 * once an item was added, removed or moved, so such a change is reported as waiting even when its new value equals
 * its value at startup. A restart-only change is never reported as in effect while its value differs from the value
 * at startup. When the file could not be read as the provider was built, there are no startup values, and each
 * apply reports the restart-only changes it makes itself and no others.
 *
 * <p>The report is true only when the code using a restart-only value reads the settings of the first load,
 * {@link so.alaz.taffy.config.ConfigHolder#startup()}; code reading the settings in effect sees the new value at
 * once, and the {@code savedRestartRequired} result is then untrue.
 *
 * <h2>The plugin's own writes</h2>
 *
 * <p>An apply reads the file, checks its revision and moves the new content over it while holding the lock that
 * {@link ComfiConfConnection#lockFor(Path)} gives for the same path, and {@link #read()} and
 * {@link Builder#build(Logger)} read the file while holding it. A write of the file by the plugin outside ComfiConf
 * holds that lock from reading the file anew to saving it, so an apply racing the write reports a conflict, and
 * neither a read nor an apply sees a file the plugin has written in part. The lock is held for file access only
 * and never while the activation runs.
 * A write without the lock that lands between the revision check and the move of an apply is replaced by the apply
 * with no conflict, and a write of values read before an apply reverts that apply. The provider never calls
 * {@code notifyChanged()}: the plugin calls {@link ComfiConfConnection#notifyChanged()} after each such write and
 * after each successful reload of the file by its own command, so open editors read the new values, and never after
 * an apply.
 *
 * <h2>Where the plugin creates it</h2>
 *
 * <p>This class implements a {@code com.comficonf.api} interface and takes {@code com.comficonf.api} types, so
 * loading it on a server without the bridge fails with a {@link NoClassDefFoundError}. The plugin builds and names it
 * only inside its own ComfiConf integration class, which it reaches only after finding the bridge installed, enabled
 * and visible to the plugin.
 *
 * @see #builder(Path, SectionMapper, Callable)
 */
public final class YamlFileProvider implements ConfigProvider {

    private static final String NOT_LOADED = "The values were saved, but the plugin could not load them, so they are"
        + " not in effect.";

    private final Path file;
    private final String fileName;
    private final BackupPolicy backups;
    private final Logger logger;
    private final Callable<Boolean> activation;
    private final SectionMapper mapper;
    private final Function<ConfigValues, ValidationResult> rules;
    private final String notLoadedMessage;
    private final @Nullable ConfigValues startup;

    private YamlFileProvider(Builder builder, Logger logger) {
        this.file = builder.file;
        this.fileName = builder.file.getFileName().toString();
        this.backups = builder.backups != null ? builder.backups
            : BackupPolicy.in(builder.file.resolveSibling("backups"));
        this.logger = logger;
        this.activation = builder.activation;
        this.mapper = builder.mapper;
        this.rules = builder.rules;
        this.notLoadedMessage = builder.notLoadedMessage;
        this.startup = readStartupValues();
    }

    /**
     * Starts a provider of {@code file}.
     *
     * @param file the settings file; its name stands for it in log lines and in the messages the editor shows
     * @param mapper the schema of the file and its mapping
     * @param activation reads the file into the plugin on the main thread and returns {@code true} when the new
     *     values are in effect; it must not write the file
     * @return a builder with no plugin rules
     */
    public static Builder builder(Path file, SectionMapper mapper, Callable<Boolean> activation) {
        return new Builder(file, mapper, activation);
    }

    @Override
    public ConfigSchema describe() {
        return mapper.schema();
    }

    /**
     * Reads the file once, holding the lock of {@link SafeFileWriter#lockFor(Path)} for the read; values and revision
     * come from the same bytes, so edits by hand are detected.
     *
     * @throws IOException when the file cannot be read, a missing file included
     * @throws ConfigLoadException when the file is not UTF-8, not valid YAML or larger than 16 MiB; for invalid YAML
     *     the message is shaped as {@link so.alaz.taffy.config.StrictYaml#parse(String, String)} states: it
     *     names the line and column where the parser gives them and does not quote that line
     * @throws IllegalStateException when the mapper cannot read a stored value without losing data
     */
    @Override
    public ConfigSnapshot read() throws IOException, ConfigLoadException {
        byte[] content = readLocked();
        return ConfigSnapshot.of(mapper.read(YamlBytes.parse(content, fileName)), Revisions.of(content));
    }

    /**
     * Text the file cannot store unchanged, then the issues of the plugin's rules.
     */
    @Override
    public ValidationResult validate(ConfigValues proposed) {
        return mapper.validate(proposed).merge(Objects.requireNonNull(rules.apply(proposed), "rules returned null"));
    }

    /**
     * Writes the proposal into the file and runs the activation. The lock of {@link SafeFileWriter#lockFor(Path)} is
     * held from the read of the file to the end of the write, and released before the activation.
     *
     * @throws IOException when the file cannot be read or written; nothing was written
     * @throws ConfigLoadException when the file is not UTF-8, not valid YAML or larger than 16 MiB; nothing was
     *     written
     */
    @Override
    public ApplyResult apply(ApplyContext context) throws IOException, ConfigLoadException {
        String newRevision;
        // SafeFileWriter.write takes the same lock again: it is a ReentrantLock.
        Lock lock = SafeFileWriter.lockFor(file);
        lock.lock();
        try {
            byte[] current = YamlBytes.read(file, fileName);
            String currentRevision = Revisions.of(current);
            if (!currentRevision.equals(context.baseRevision())) {
                return ApplyResult.conflict(currentRevision);
            }
            ValidationResult storable = mapper.validate(context.proposed());
            if (!storable.isValid()) {
                return ApplyResult.rejected(storable);
            }
            YamlConfiguration yaml = YamlBytes.parse(current, fileName);
            mapper.write(context.proposed(), yaml);
            byte[] updated = YamlBytes.encodeLike(yaml.saveToString(), current);
            List<String> altered = alteredPaths(context.proposed(), YamlBytes.parse(updated, fileName));
            if (!altered.isEmpty()) {
                ValidationResult.Builder rejection = ValidationResult.builder();
                for (String path : altered) {
                    rejection.error(path, fileName + " cannot store this value unchanged, so nothing was saved.");
                }
                return ApplyResult.rejected(rejection.build());
            }
            try {
                newRevision = SafeFileWriter.write(file, updated, context.baseRevision(), backups).newRevision();
            } catch (RevisionConflictException e) {
                return ApplyResult.conflict(e.currentRevision());
            }
        } finally {
            lock.unlock();
        }
        return activate(context, newRevision);
    }

    /** The bytes of the file, read while holding the lock every {@link SafeFileWriter} write of it takes. */
    private byte[] readLocked() throws IOException, ConfigLoadException {
        Lock lock = SafeFileWriter.lockFor(file);
        lock.lock();
        try {
            return YamlBytes.read(file, fileName);
        } finally {
            lock.unlock();
        }
    }

    /** Runs the activation after the write; never throws. */
    private ApplyResult activate(ApplyContext context, String newRevision) {
        try {
            if (Boolean.TRUE.equals(context.callSync(activation))) {
                logApplied(context);
                List<String> waiting = restartPending(context);
                if (!waiting.isEmpty()) {
                    return ApplyResult.savedRestartRequired(newRevision, "Saved. Settings marked Restart required "
                        + "take effect after a server restart; all other changes are active.", waiting);
                }
                return ApplyResult.applied(newRevision);
            }
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logLoadFailed(e);
        }
        return ApplyResult.savedRestartRequired(newRevision, notLoadedMessage);
    }

    private void logLoadFailed(Throwable failure) {
        try {
            logger.log(Level.WARNING, "Saved " + fileName + " from ComfiConf, but could not load it", failure);
        } catch (RuntimeException ignored) {
            // A log handler that throws drops the line; the result stays savedRestartRequired.
        }
    }

    private void logApplied(ApplyContext context) {
        try {
            logger.info("Applied a " + fileName + " change from ComfiConf requested by " + context.actor().label());
        } catch (RuntimeException ignored) {
            // A failure while building or publishing the line (the actor's label, a log handler) drops it; the values
            // are in effect either way.
        }
    }

    /**
     * Restart-only paths of the whole proposal whose value differs from the value in effect since startup, then every
     * restart-only path inside a list that this apply changed: its index may name another item at startup, so such a
     * path is listed even when its new value equals its value at startup. Without startup values, every restart-only
     * path this apply changed.
     */
    private List<String> restartPending(ApplyContext context) {
        List<String> waiting = new ArrayList<>();
        if (startup != null) {
            collectWaiting(mapper.schema().fields(), context.proposed().asMap(), startup.asMap(), "", waiting);
        }
        for (FieldChange change : context.changes()) {
            String path = change.path();
            if (change.applyMode() == ApplyMode.RESTART && (startup == null || path.indexOf('[') >= 0)
                && !waiting.contains(path)) {
                waiting.add(path);
            }
        }
        return waiting;
    }

    /**
     * Adds to {@code waiting} the path of each restart-only field among {@code fields} whose value in
     * {@code proposed} differs from its value in {@code atStartup}; missing and null count as equal. A value that is
     * not a map holds no fields.
     */
    private static void collectWaiting(List<ConfigField> fields, @Nullable Object proposed,
                                       @Nullable Object atStartup, String path, List<String> waiting) {
        Map<?, ?> now = proposed instanceof Map<?, ?> map ? map : Map.of();
        Map<?, ?> then = atStartup instanceof Map<?, ?> map ? map : Map.of();
        for (ConfigField field : fields) {
            String fieldPath = ConfigPath.child(path, field.key());
            Object value = now.get(field.key());
            Object startupValue = then.get(field.key());
            if (field.applyMode() == ApplyMode.RESTART) {
                List<String> altered = new ArrayList<>();
                collectAltered(value, startupValue, fieldPath, altered);
                if (!altered.isEmpty()) {
                    waiting.add(fieldPath);
                }
            } else if (field.type() == FieldType.OBJECT) {
                collectWaiting(field.fields(), value, startupValue, fieldPath, waiting);
            } else if (field.type() == FieldType.OBJECT_LIST && value instanceof List<?> items) {
                List<?> startupItems = startupValue instanceof List<?> list ? list : List.of();
                for (int i = 0; i < items.size(); i++) {
                    collectWaiting(field.fields(), items.get(i),
                        startupItem(field.itemKey(), items.get(i), i, startupItems), ConfigPath.index(fieldPath, i),
                        waiting);
                }
            }
        }
    }

    /**
     * The item of {@code startupItems} that {@code item} stood for at startup: the first one with the same value
     * under {@code itemKey}, or the one at {@code index} when {@code itemKey} is null. Null when there is none.
     */
    private static @Nullable Object startupItem(@Nullable String itemKey, @Nullable Object item, int index,
                                                List<?> startupItems) {
        if (itemKey == null) {
            return index < startupItems.size() ? startupItems.get(index) : null;
        }
        Object key = item instanceof Map<?, ?> map ? map.get(itemKey) : null;
        if (key == null) {
            return null;
        }
        for (Object candidate : startupItems) {
            if (candidate instanceof Map<?, ?> map && key.equals(map.get(itemKey))) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Values of the file when the provider is built: the values the plugin's restart-only consumers use. Null when
     * unreadable.
     */
    private @Nullable ConfigValues readStartupValues() {
        try {
            return mapper.read(YamlBytes.parse(readLocked(), fileName));
        } catch (IOException | ConfigLoadException | RuntimeException e) {
            return null;
        }
    }

    /** Paths whose value in the saved document differs from the proposal; missing and null count as equal. */
    private List<String> alteredPaths(ConfigValues proposed, YamlConfiguration saved) {
        List<String> altered = new ArrayList<>();
        try {
            collectAltered(proposed.asMap(), mapper.read(saved).asMap(), "", altered);
        } catch (IllegalStateException e) {
            logger.log(Level.WARNING, fileName + " would not read back the values from ComfiConf", e);
            altered.add("");
        }
        return altered;
    }

    /** Adds to {@code altered} each path under {@code path} whose saved value differs from the expected one. */
    static void collectAltered(Object expected, Object saved, String path, List<String> altered) {
        if (Objects.equals(expected, saved)) {
            return;
        }
        if (expected instanceof Map<?, ?> left && saved instanceof Map<?, ?> right) {
            Set<Object> keys = new LinkedHashSet<>(left.keySet());
            keys.addAll(right.keySet());
            for (Object key : keys) {
                collectAltered(left.get(key), right.get(key), ConfigPath.child(path, (String) key), altered);
            }
        } else if (expected instanceof List<?> left && saved instanceof List<?> right && left.size() == right.size()) {
            for (int i = 0; i < left.size(); i++) {
                collectAltered(left.get(i), right.get(i), ConfigPath.index(path, i), altered);
            }
        } else {
            altered.add(path);
        }
    }

    /**
     * Collects the inputs of a {@link YamlFileProvider}. Not thread-safe: build it on one thread.
     */
    public static final class Builder {

        private final Path file;
        private final SectionMapper mapper;
        private final Callable<Boolean> activation;
        private Function<ConfigValues, ValidationResult> rules = proposed -> ValidationResult.ok();
        private String notLoadedMessage = NOT_LOADED;
        private @Nullable BackupPolicy backups;

        private Builder(Path file, SectionMapper mapper, Callable<Boolean> activation) {
            this.file = Objects.requireNonNull(file, "file");
            this.mapper = Objects.requireNonNull(mapper, "mapper");
            this.activation = Objects.requireNonNull(activation, "activation");
        }

        /**
         * Sets the plugin's own rules, which {@link YamlFileProvider#validate} merges after the mapper's issues. An
         * error among them refuses the proposal. They run on the bridge worker thread and must not touch the main
         * thread. They refuse every proposal the loader of the activation rejects, its cross-field checks included:
         * a proposal they accept is saved, and when the loader then rejects it, the values are not in effect and the
         * next start of the plugin fails on the file.
         *
         * @param rules returns the issues of a proposal under the plugin's rules, a result for every proposal and
         *     never {@code null}; a {@code null} result makes {@link YamlFileProvider#validate} throw
         * @return this builder
         */
        public Builder rules(Function<ConfigValues, ValidationResult> rules) {
            this.rules = Objects.requireNonNull(rules, "rules");
            return this;
        }

        /**
         * Sets the message of the {@code savedRestartRequired} result that {@link YamlFileProvider#apply} returns
         * when the file was saved and the activation did not put the values in effect. The editor shows it to the
         * server owner, so it names what makes the plugin load the file, such as the plugin's reload command. Without
         * this call the message is "The values were saved, but the plugin could not load them, so they are not in
         * effect."
         *
         * @param message the text shown to the server owner; it holds no configuration value
         * @return this builder
         */
        public Builder notLoadedMessage(String message) {
            this.notLoadedMessage = Objects.requireNonNull(message, "message");
            return this;
        }

        /**
         * Sets where {@link YamlFileProvider#apply} keeps the backups of the file and how many it keeps. Without this
         * call the 10 newest backups are kept in the {@code backups} folder beside the file.
         *
         * <p>Each backup is a full copy of the file as it was before a write: every value in it, the secrets
         * included, in plain text, and it stays until newer backups replace it. The folder of the policy is as
         * private as the file itself.
         *
         * @param policy the folder that receives the backups and the number of backups kept
         * @return this builder
         */
        public Builder backups(BackupPolicy policy) {
            this.backups = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /**
         * Builds the provider and reads the file once for the values in effect since startup. Call it after the
         * plugin loaded the file in {@code onEnable}. A file that cannot be read then gives no startup values, so
         * every later restart-only change is reported as waiting by the apply that makes it and by no later apply.
         *
         * @param logger the plugin's logger, which receives the apply lines and the activation failures with their
         *     stacks; an activation failure is logged only, because its message can carry a configuration value
         * @return the provider
         */
        public YamlFileProvider build(Logger logger) {
            return new YamlFileProvider(this, Objects.requireNonNull(logger, "logger"));
        }
    }
}
