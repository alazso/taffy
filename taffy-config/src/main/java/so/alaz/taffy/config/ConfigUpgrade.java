package so.alaz.taffy.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * The steps that bring an older settings file to the layout of a newer version: each step renames, moves or
 * removes keys, and is registered under the version it upgrades from.
 *
 * <pre>{@code
 * ConfigUpgrade upgrades = ConfigUpgrade.none()
 *     .from(1, keys -> keys.move("cooldown", "cooldown-seconds"))
 *     .from(2, keys -> {
 *         keys.move("storage.host", "storage.mysql.host");
 *         keys.remove("legacy-mode");
 *     });
 * }</pre>
 *
 * <p>{@link ConfigLoader#upgrades} runs the steps on {@code config.yml}, and a {@link YamlFolder} built with a
 * version key runs them on each file of its folder. For a file of version {@code v} and a current version
 * {@code c}, every step registered under a version from {@code v} up to, not including, {@code c} runs once, in
 * ascending order of those versions, on the parsed file in memory and before anything is written. A version
 * without a step is skipped. A step never runs on a file that already has the current version, so a file that
 * was upgraded and saved is not touched by the next load.
 *
 * <p>An instance is immutable and may be shared between threads. A step is called on the thread that loads the
 * file.
 */
public final class ConfigUpgrade {

    private static final ConfigUpgrade NONE = new ConfigUpgrade(new TreeMap<>());

    private final TreeMap<Integer, Consumer<Keys>> steps;

    private ConfigUpgrade(TreeMap<Integer, Consumer<Keys>> steps) {
        this.steps = steps;
    }

    /** An upgrade without steps. */
    public static ConfigUpgrade none() {
        return NONE;
    }

    /**
     * A copy of this upgrade with {@code step} added. This upgrade is not changed.
     *
     * @param version the version of the files the step upgrades, 0 or more. A file without a version counts as
     *     the baseline version of its loader, which is 0 unless the loader states another.
     * @param step changes the keys of one file. A {@link RuntimeException} it throws fails the upgrade of that
     *     file, which is then left on the disk as it was, byte for byte.
     * @throws IllegalArgumentException when {@code version} is negative or already has a step
     */
    public ConfigUpgrade from(int version, Consumer<Keys> step) {
        Objects.requireNonNull(step, "step");
        if (version < 0) {
            throw new IllegalArgumentException("the version of a step is " + version + "; expected 0 or more");
        }
        if (steps.containsKey(version)) {
            throw new IllegalArgumentException("version " + version + " already has a step");
        }
        TreeMap<Integer, Consumer<Keys>> copy = new TreeMap<>(steps);
        copy.put(version, step);
        return new ConfigUpgrade(copy);
    }

    /**
     * Runs the steps registered under {@code from} up to, not including, {@code to} on {@code yaml}.
     *
     * @throws Failure when a step throws; {@code yaml} then holds what the steps did up to that point
     */
    void run(YamlConfiguration yaml, int from, int to) throws Failure {
        Keys keys = new Keys(yaml);
        for (var step : steps.subMap(from, true, to, false).entrySet()) {
            try {
                step.getValue().accept(keys);
            } catch (RuntimeException failure) {
                throw new Failure(step.getKey(), failure);
            }
        }
    }

    /** A step that threw. */
    static final class Failure extends Exception {

        private static final long serialVersionUID = 1L;

        private final int version;

        Failure(int version, RuntimeException cause) {
            super(cause);
            this.version = version;
        }

        /** The version the step upgrades from. */
        int version() {
            return version;
        }

        /** The simple class name of what the step threw, then a space and its message when it has one. */
        String reason() {
            String message = getCause().getMessage();
            String name = getCause().getClass().getSimpleName();
            return message == null || message.isBlank() ? name : name + " " + message;
        }
    }

    /**
     * The keys of the file a step upgrades. Every path is a full dotted path from the top of the file, such as
     * {@code storage.mysql.host}.
     *
     * <p>An instance is used only inside the step it was passed to.
     */
    public static final class Keys {

        private final YamlConfiguration yaml;

        Keys(YamlConfiguration yaml) {
            this.yaml = yaml;
        }

        /**
         * The parsed file, for a change {@link #move} and {@link #remove} do not cover, such as a value written
         * in another unit. A change made here is saved with the upgrade.
         */
        public YamlConfiguration yaml() {
            return yaml;
        }

        /**
         * Moves the value or the section at {@code from} to {@code to}, and so renames a key when both paths
         * lie in the same section. The value is kept as the owner wrote it, with the comments above it and
         * beside it, and a section moves with every key beneath it and their comments. Missing sections on the
         * way to {@code to} are created.
         *
         * <p>The moved key is written after the keys its new section already has. No other key changes its
         * place or its comments, and the section {@code from} was in stays, also when the move leaves it empty.
         *
         * <p>What the file already holds at {@code to} wins: a value there is kept and the moved one is dropped.
         * When both paths hold a section, each key beneath {@code from} moves unless the section at {@code to}
         * already has it. A moved key is also dropped where the file holds a plain value at a path above its
         * new place, as {@code storage: local} does for {@code storage.mysql.host}. In every case the key is
         * gone from {@code from} afterwards.
         *
         * <p>When the file has no key at {@code from}, or both paths are the same, nothing changes, so a step
         * that runs again on a file it already changed leaves it as it is.
         *
         * @return whether the file held a key at {@code from} that is no longer there
         * @throws IllegalArgumentException when a path is blank
         */
        public boolean move(String from, String to) {
            check(from);
            check(to);
            if (from.equals(to) || !yaml.contains(from, true)) {
                return false;
            }
            List<Moved> moved = new ArrayList<>();
            moved.add(moved(from, to));
            ConfigurationSection section = yaml.getConfigurationSection(from);
            if (section != null) {
                for (String key : section.getKeys(true)) {
                    moved.add(moved(from + "." + key, to + "." + key));
                }
            }
            yaml.set(from, null);
            for (Moved key : moved) {
                if (yaml.contains(key.to(), true) || ConfigLoader.underScalar(yaml, key.to())) {
                    continue;
                }
                if (key.value() == null) {
                    yaml.createSection(key.to());
                } else {
                    yaml.set(key.to(), key.value());
                }
                yaml.setComments(key.to(), key.comments());
                yaml.setInlineComments(key.to(), key.inlineComments());
            }
            return true;
        }

        /**
         * Removes the key at {@code path} with its comments, and every key beneath it when it holds a section.
         * The section the key was in stays, also when it is left empty. Nothing changes when the file has no
         * such key.
         *
         * @return whether the file held the key
         * @throws IllegalArgumentException when {@code path} is blank
         */
        public boolean remove(String path) {
            check(path);
            if (!yaml.contains(path, true)) {
                return false;
            }
            yaml.set(path, null);
            return true;
        }

        private Moved moved(String from, String to) {
            return new Moved(to, yaml.isConfigurationSection(from) ? null : yaml.get(from), yaml.getComments(from),
                yaml.getInlineComments(from));
        }

        private static void check(String path) {
            if (path.isBlank()) {
                throw new IllegalArgumentException("a path of a config upgrade is blank");
            }
        }

        /** One key on its way to {@code to}; a {@code null} value stands for a section. */
        private record Moved(String to, @Nullable Object value, List<String> comments, List<String> inlineComments) {
        }
    }
}
