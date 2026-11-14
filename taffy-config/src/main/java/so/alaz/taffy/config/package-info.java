/**
 * Strict loading of a plugin's YAML settings.
 *
 * <p>{@link so.alaz.taffy.config.StrictYaml} is the one way a file becomes a
 * {@link org.bukkit.configuration.file.YamlConfiguration}: a file that is missing, is not UTF-8 or is not valid
 * YAML fails with a {@link so.alaz.taffy.config.ConfigLoadException}, whose message is written for the server
 * owner. {@link so.alaz.taffy.config.ConfigLoader} loads {@code config.yml} from a data folder: it writes the
 * file shipped inside the plugin jar when none is there and adds the shipped keys an older file lacks.
 * {@link so.alaz.taffy.config.ConfigReader} reads typed values out of the loaded configuration.
 * {@link so.alaz.taffy.config.ConfigHolder} holds the settings in effect and replaces them on a reload only when
 * the new ones load. {@link so.alaz.taffy.config.RestartKeys} compares a reloaded configuration with the one
 * loaded at startup and names the changed settings that need a restart. {@link so.alaz.taffy.config.YamlFolder}
 * reads a folder in which each {@code .yml} file is one entry, and reports a file that fails without stopping
 * the others.
 *
 * <h2>The settings model</h2>
 *
 * <p>The module has no base type for a plugin's settings. A plugin writes them as an immutable record with a
 * static {@code read(ConfigReader)} factory that takes a {@link so.alaz.taffy.config.ConfigReader}:
 *
 * <ul>
 *   <li>Each component is read with one reader call, and its default is a constant of the record passed as
 *       the fallback of that call.</li>
 *   <li>A value that depends on the running server, such as the name of a world, is checked through a
 *       predicate passed to the factory, so the factory runs without a server.</li>
 *   <li>After the factory returns, {@link so.alaz.taffy.config.ConfigReader#errors()} tells the plugin whether
 *       the settings can be used.</li>
 *   <li>The record is held in a {@link so.alaz.taffy.config.ConfigHolder}, whose loader loads the file, reads
 *       the record and checks the errors. Settings that take effect only after a restart are read from
 *       {@link so.alaz.taffy.config.ConfigHolder#startup()}, and
 *       {@link so.alaz.taffy.config.RestartKeys#changed} compares the reloaded configuration with the one
 *       loaded at startup to name the ones the owner changed. The holder keeps only the record, so a record
 *       that needs this keeps the {@link org.bukkit.configuration.ConfigurationSection} it was read from as
 *       a component, which the factory takes from {@link so.alaz.taffy.config.ConfigReader#root()}.</li>
 *   <li>A plugin that warns about keys no call read, through
 *       {@link so.alaz.taffy.config.ConfigReader#unreadKeys()}, leaves out
 *       {@link so.alaz.taffy.config.ConfigLoader#VERSION_KEY}, which the loader keeps in every file.</li>
 *   <li>A value whose form a parser in {@code so.alaz.taffy.platform} checks, such as a duration, a colour or an
 *       expiry, is read with the {@code parsed} overload of {@link so.alaz.taffy.config.ConfigReader} that
 *       takes a fallback text, with the parser's {@code EXPECTED} text as the expected form and the fallback
 *       written as the owner would write it. A date with a time has to be written in quotes.</li>
 * </ul>
 *
 * <h2>Settings with errors</h2>
 *
 * <p>When {@link so.alaz.taffy.config.ConfigReader#errors()} is not empty, the loader passes each error line
 * to its own sink, such as the plugin's logger, and then throws a
 * {@link so.alaz.taffy.config.ConfigLoadException} with a one-line summary. At startup the exception stops the
 * start; on a reload the holder keeps the settings in effect and reports the summary. The owner sees every
 * error line, followed by one line that says what happened. The loader, the reader's warning consumer and the
 * error sink are all fixed when the holder is built, so a reload reports to the same place every time and its
 * caller learns only whether it succeeded.
 *
 * <p>The reader passes each warning on as soon as it finds it, so a warning that names a fallback as used comes
 * before the loader knows whether the load fails. When it fails, the line the holder reports says that no value
 * read by that reload is used. A loader that keeps such warnings out of a failed load passes the reader a
 * consumer that adds to a list, and logs the list only when the reader has no errors.
 *
 * <p>A reload of a data folder without {@code config.yml} writes the shipped file and succeeds, so the shipped
 * defaults take effect; the loader logs that it wrote the default file.
 *
 * <h2>The lines an owner sees</h2>
 *
 * <p>Every warning and error line of {@link so.alaz.taffy.config.ConfigReader} names the path first and is a
 * full sentence without the plugin's name, which the logger adds. A value that is replaced reads
 * {@code <path> is <value>; expected <what>, so <fallback> is used.}, a list entry that is dropped reads
 * {@code <path> has <entry>, which is not a single value, so it is skipped.}, and a value where a group of keys belongs reads {@code <path> should hold keys beneath it, so its value is
 * ignored.} The message of a {@link so.alaz.taffy.config.ConfigLoadException} is a clause that follows a
 * lead-in, and the lines of a {@link so.alaz.taffy.config.YamlFolder} start with the name of their file.
 *
 * <p>No class here keeps a plugin or a thread, and a logger is used only where the caller passes one in. File
 * reads and writes block the calling thread.
 */
package so.alaz.taffy.config;
