package so.alaz.taffy.platform;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A plugin's diagnostics report for the server owner: titled sections of lines, built when asked and rendered as
 * Adventure components for a command sender or as plain text for the console.
 *
 * <p>A report starts with the section {@code Server}, which holds four lines: {@code Plugin} with the plugin's name
 * and version, {@code Server} with the server's name and Minecraft version, {@code Java} with the version of the
 * running JVM, and {@code Scheduler} with {@code Paper} or {@code Folia}. The plugin adds the sections that follow.
 * The other Taffy modules give the lines of theirs: {@code Storage.diagnostics} of {@code taffy-storage},
 * {@code Metrics.diagnostics} of {@code taffy-metrics}, and {@code HookStatus.line} of {@code taffy-hooks} and
 * {@code ComfiConfStatus.line} of {@code taffy-comficonf} as the value of a line.
 *
 * <pre>{@code
 * DiagnosticReport report = DiagnosticReport.builder(plugin, scheduler.mode())
 *     .section("Storage", () -> Storage.diagnostics(storageStart))
 *     .section("Integrations", () -> hooks.stream().map(status -> DiagnosticReport.Line.of(status.line())).toList())
 *     .section("Config", () -> List.of(DiagnosticReport.Line.pendingRestart(waitingPaths)))
 *     .build();
 * report.components().forEach(sender::sendMessage);
 * }</pre>
 *
 * <p>Server owners paste a report into public support channels, so a line holds no password, token, connection URL,
 * address, player name or player UUID. The standard section and the lines of the other Taffy modules hold none; the
 * message of a failed storage start, the one text among them that Taffy did not write, is shown as
 * {@link Redaction#hide} leaves it. A plugin keeps them out of the lines it writes itself, and passes text it did not
 * write, such as the message of an exception, through {@link Line#redacted}.
 *
 * <p>{@link Builder#build()} runs every section's supplier on the thread that calls it, once, in the order the
 * sections were added. A supplier must not block, and reads only what that thread may read: on Folia a command runs
 * on the region thread of its sender or on the global region thread, which owns no other region's worlds or entities.
 * The standard section reads the plugin's name and version, the server's name and Minecraft version and the JVM's
 * version, which any thread may read, and the lines of the other Taffy modules named above read cached state without
 * blocking, so a report of those builds on any thread. A supplier that throws does not lose the report: its section
 * holds the one line {@code could not be read: <exception class>}, and the failure is logged on the plugin's logger
 * with its stack trace.
 *
 * <p>A report is bounded: at most {@value #MAX_SECTIONS} sections after the standard one, each of at most
 * {@value #MAX_LINES} lines, with a label of at most {@value #MAX_LABEL} characters and a value of at most
 * {@value #MAX_VALUE}. What is left out is counted in a line of its own.
 *
 * <p>A built report is immutable and safe to read from any thread. The texts of a report are written for the server
 * owner and can change in any release.
 */
public final class DiagnosticReport {

    /** The most sections a report holds after the standard one. Further sections are counted and not run. */
    public static final int MAX_SECTIONS = 32;

    /** The most lines a section holds. Further lines are counted in one more line. */
    public static final int MAX_LINES = 64;

    /** The most characters of a section title and of a line's label. */
    public static final int MAX_LABEL = 64;

    /** The most characters of a line's value. */
    public static final int MAX_VALUE = 512;

    /** The most paths {@link Line#pendingRestart} names. */
    public static final int MAX_PATHS = 32;

    private static final String CUT = "...";

    private static final String INDENT = "  ";

    private final List<Section> sections;

    private DiagnosticReport(List<Section> sections) {
        this.sections = List.copyOf(sections);
    }

    /**
     * One line of a report: a label and a value, shown as {@code <label>: <value>}, or a value alone when the label
     * is empty.
     *
     * <p>Each ISO control character and each Unicode line or paragraph separator in the label and in the value is
     * replaced by a space, so a line never breaks, and whitespace at either end is removed. A label longer than
     * {@value DiagnosticReport#MAX_LABEL} characters and a value longer than {@value DiagnosticReport#MAX_VALUE} are
     * cut to that length, ending in {@code ...}.
     *
     * @param label what the value is, such as {@code Database}, possibly empty
     * @param value the value
     */
    public record Line(String label, String value) {

        /**
         * Cleans and cuts both parts as the record description states.
         *
         * @throws NullPointerException if {@code label} or {@code value} is {@code null}
         */
        public Line {
            label = clean(Objects.requireNonNull(label, "label"), MAX_LABEL);
            value = clean(Objects.requireNonNull(value, "value"), MAX_VALUE);
        }

        /**
         * A line without a label, for a value that names its subject itself, such as {@code Vault on}.
         *
         * @param value the value
         * @return the line
         * @throws NullPointerException if {@code value} is {@code null}
         */
        public static Line of(String value) {
            return new Line("", value);
        }

        /**
         * A line whose value is text the plugin did not write, such as the message of an exception: the value is
         * {@code text} as {@link Redaction#hide} returns it.
         *
         * @param label what the value is, possibly empty
         * @param text the text to hide secrets and personal data in
         * @return the line
         * @throws NullPointerException if {@code label} or {@code text} is {@code null}
         */
        public static Line redacted(String label, String text) {
            Objects.requireNonNull(label, "label");
            return new Line(label, Redaction.hide(Objects.requireNonNull(text, "text")));
        }

        /**
         * The line {@code Waiting for a restart} for the settings that changed in the plugin's files and take effect
         * only after a restart, as {@code RestartKeys.changed} of {@code taffy-config} finds them between the
         * configuration loaded at startup and the current one. It names the paths and never a value, so a changed
         * {@code storage.password} shows as its path. The paths keep their order and are joined with a comma and a
         * space. At most {@value DiagnosticReport#MAX_PATHS} paths are named, and fewer when the value would pass
         * {@value DiagnosticReport#MAX_VALUE} characters; the rest are counted, as in {@code a.b, c.d and 3 more}.
         * A path is shown up to its first 128 characters.
         *
         * @param paths the dotted paths that wait for a restart, possibly empty
         * @return the line, with the value {@code none} when {@code paths} is empty
         * @throws NullPointerException if {@code paths} or one of its elements is {@code null}
         */
        public static Line pendingRestart(Collection<String> paths) {
            Objects.requireNonNull(paths, "paths");
            StringBuilder value = new StringBuilder();
            int named = 0;
            for (String path : paths) {
                String shown = clean(Objects.requireNonNull(path, "path"), 128);
                // Room for the separator and for the count of the paths left out.
                if (named == MAX_PATHS || value.length() + shown.length() + 2 > MAX_VALUE - 24) {
                    break;
                }
                if (named > 0) {
                    value.append(", ");
                }
                value.append(shown);
                named++;
            }
            if (named < paths.size()) {
                value.append(" and ").append(paths.size() - named).append(" more");
            }
            return new Line("Waiting for a restart", paths.isEmpty() ? "none" : value.toString());
        }

        /**
         * The line as plain text.
         *
         * @return {@code <label>: <value>}, or the value alone when the label is empty
         */
        public String plain() {
            return label.isEmpty() ? value : label + ": " + value;
        }
    }

    /**
     * One titled section of a report.
     *
     * @param title the title, cleaned and cut as the label of a {@link Line} is
     * @param lines the lines, unmodifiable: at most {@value DiagnosticReport#MAX_LINES} of the lines given, followed
     *     by the line {@code <n> more lines not shown} when more were given
     */
    public record Section(String title, List<Line> lines) {

        /**
         * Cleans the title and copies the lines.
         *
         * @throws NullPointerException if {@code title}, {@code lines} or a line is {@code null}
         * @throws IllegalArgumentException if the title is blank
         */
        public Section {
            title = clean(Objects.requireNonNull(title, "title"), MAX_LABEL);
            if (title.isEmpty()) {
                throw new IllegalArgumentException("title is blank");
            }
            List<Line> copy = List.copyOf(Objects.requireNonNull(lines, "lines"));
            if (copy.size() > MAX_LINES) {
                List<Line> kept = new ArrayList<>(copy.subList(0, MAX_LINES));
                kept.add(Line.of((copy.size() - MAX_LINES) + " more lines not shown"));
                copy = List.copyOf(kept);
            }
            lines = copy;
        }
    }

    /**
     * Starts a report for {@code plugin}. Nothing is read until {@link Builder#build()}.
     *
     * @param plugin the owner plugin: {@link Plugin#getName()}, the version of {@link Plugin#getPluginMeta()} and
     *     {@link Plugin#getServer()} are read for the standard section, and {@link Plugin#getLogger()} receives the
     *     failure of a section that could not be read
     * @param mode the scheduler mode the standard section shows, from {@link Scheduler#mode()}
     * @return the builder
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Builder builder(Plugin plugin, Scheduler.Mode mode) {
        return new Builder(Objects.requireNonNull(plugin, "plugin"), Objects.requireNonNull(mode, "mode"));
    }

    /** Collects the sections of a report. Not thread-safe: one thread builds one report. */
    public static final class Builder {

        private record Pending(String title, Supplier<? extends Collection<Line>> lines) {
        }

        private final Plugin plugin;
        private final Scheduler.Mode mode;
        private final List<Pending> pending = new ArrayList<>();
        private int leftOut;

        private Builder(Plugin plugin, Scheduler.Mode mode) {
            this.plugin = plugin;
            this.mode = mode;
        }

        /**
         * Adds a section whose lines are read when the report is built. The supplier runs once in each
         * {@link #build()}, on the thread that calls it, and may throw: the section then holds the one line
         * {@code could not be read: <exception class>}. A supplier that returns {@code null}, or a collection
         * that holds {@code null}, counts as one that threw. Once the builder holds
         * {@value DiagnosticReport#MAX_SECTIONS} sections, a further one is counted and its supplier never runs.
         *
         * @param title the section title, shown above its lines
         * @param lines gives the lines of the section, possibly none
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         * @throws IllegalArgumentException if the title is blank
         */
        public Builder section(String title, Supplier<? extends Collection<Line>> lines) {
            Objects.requireNonNull(lines, "lines");
            String shown = new Section(title, List.of()).title();
            if (pending.size() == MAX_SECTIONS) {
                leftOut++;
            } else {
                pending.add(new Pending(shown, lines));
            }
            return this;
        }

        /**
         * Adds a section of lines the plugin already holds.
         *
         * @param title the section title, shown above its lines
         * @param lines the lines of the section, possibly none
         * @return this builder
         * @throws NullPointerException if an argument or a line is {@code null}
         * @throws IllegalArgumentException if the title is blank
         */
        public Builder section(String title, Collection<Line> lines) {
            List<Line> copy = List.copyOf(Objects.requireNonNull(lines, "lines"));
            return section(title, () -> copy);
        }

        /**
         * Builds the report on the calling thread: the standard section, then each section in the order it was
         * added, then the section {@code Not shown} when sections were left out. Never throws for what a section's
         * supplier does. The builder can build again, which reads every section again.
         *
         * @return the report
         */
        public DiagnosticReport build() {
            List<Section> built = new ArrayList<>();
            built.add(read("Server", this::server));
            for (Pending section : pending) {
                built.add(read(section.title(), section.lines()));
            }
            if (leftOut > 0) {
                built.add(new Section("Not shown", List.of(Line.of(leftOut + " more sections"))));
            }
            return new DiagnosticReport(built);
        }

        private List<Line> server() {
            Server server = plugin.getServer();
            return List.of(
                new Line("Plugin", plugin.getName() + " " + plugin.getPluginMeta().getVersion()),
                new Line("Server", server.getName() + " " + server.getMinecraftVersion()),
                new Line("Java", Runtime.version().toString()),
                new Line("Scheduler", mode == Scheduler.Mode.FOLIA ? "Folia" : "Paper"));
        }

        private Section read(String title, Supplier<? extends Collection<Line>> lines) {
            try {
                return new Section(title, List.copyOf(lines.get()));
            } catch (RuntimeException | LinkageError failure) {
                warn(title, failure);
                return new Section(title, List.of(Line.of("could not be read: " + failure.getClass().getName())));
            }
        }

        private void warn(String title, Throwable failure) {
            try {
                Logger logger = plugin.getLogger();
                logger.log(Level.WARNING, "The diagnostics section '" + title + "' could not be read", failure);
            } catch (RuntimeException | LinkageError unlogged) {
                // A logger that fails must not lose the report.
            }
        }
    }

    /**
     * The sections of the report, the standard one first.
     *
     * @return the sections, unmodifiable
     */
    public List<Section> sections() {
        return sections;
    }

    /**
     * The report as plain text for the console or a file: for each section its title on a line of its own, then
     * each of its lines as {@link Line#plain()} behind two spaces.
     *
     * @return the lines of text, unmodifiable, none of which holds a line break
     */
    public List<String> plainText() {
        List<String> text = new ArrayList<>();
        for (Section section : sections) {
            text.add(section.title());
            for (Line line : section.lines()) {
                text.add(INDENT + line.plain());
            }
        }
        return List.copyOf(text);
    }

    /**
     * The report as one component for each line of {@link #plainText()}, with the same text: a section title in
     * gold, a label with its colon in gray and a value in white. Every part is plain text, so a value is never read
     * as MiniMessage or as legacy colour codes. A plugin sends each to its command sender on the thread that owns
     * the sender.
     *
     * @return the components, unmodifiable
     */
    public List<Component> components() {
        List<Component> components = new ArrayList<>();
        for (Section section : sections) {
            components.add(Component.text(section.title(), NamedTextColor.GOLD));
            for (Line line : section.lines()) {
                Component value = Component.text(line.value(), NamedTextColor.WHITE);
                components.add(line.label().isEmpty()
                    ? Component.text(INDENT).append(value)
                    : Component.text(INDENT + line.label() + ": ", NamedTextColor.GRAY).append(value));
            }
        }
        return List.copyOf(components);
    }

    /**
     * {@code text} with each control character and line separator as a space, without whitespace at either end,
     * cut to {@code max} characters.
     */
    private static String clean(String text, int max) {
        StringBuilder cleaned = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cleaned.append(Character.isISOControl(c) || c == 0x2028 || c == 0x2029 ? ' ' : c);
        }
        String result = cleaned.toString().strip();
        if (result.length() <= max) {
            return result;
        }
        int end = max - CUT.length();
        // A cut between the two halves of a surrogate pair would leave half a character.
        if (Character.isHighSurrogate(result.charAt(end - 1))) {
            end--;
        }
        return result.substring(0, end).stripTrailing() + CUT;
    }
}
