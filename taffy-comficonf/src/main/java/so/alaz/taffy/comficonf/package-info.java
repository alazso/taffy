/**
 * Optional ComfiConf support: a plugin's settings file registered with the ComfiConf bridge, so the server owner
 * edits it in the web config editor.
 *
 * <p>This is the one Taffy module whose public signatures may name {@code com.comficonf.api} types. The bridge plugin
 * provides that API at runtime, so the module compiles against it but ships none of it, and its published metadata
 * does not name it. {@link so.alaz.taffy.comficonf.ComfiConfState}, {@link so.alaz.taffy.comficonf.ComfiConfStatus},
 * {@link so.alaz.taffy.comficonf.ComfiConfLink} and {@link so.alaz.taffy.comficonf.ComfiConfConnection} name no
 * {@code com.comficonf} type, so a class the plugin loads at startup may hold them on a server without the bridge.
 *
 * <p>{@link so.alaz.taffy.comficonf.ComfiConfRegistration} and {@link so.alaz.taffy.comficonf.YamlFileProvider} name
 * {@code com.comficonf.api} types, so loading either on a server without the bridge fails with a
 * {@link java.lang.NoClassDefFoundError}. The plugin names them only inside its own ComfiConf integration class,
 * which it reaches only through the supplier it passes to
 * {@link so.alaz.taffy.comficonf.ComfiConfConnection#connect}; the plugin's main class and every class it loads
 * before that call never name them.
 *
 * <h2>Declaring the bridge</h2>
 *
 * <p>The plugin declares the API itself, as {@code compileOnly(alaz.comficonf.api)} and
 * {@code testImplementation(alaz.comficonf.api)}, and never bundles or relocates {@code com.comficonf}: a second copy
 * of the API classes never finds the bridge. Its {@code paper-plugin.yml} lists the bridge as an optional server
 * dependency whose classes the plugin sees:
 *
 * <pre>{@code
 * dependencies:
 *   server:
 *     ComfiConf:
 *       load: BEFORE
 *       required: false
 *       join-classpath: true
 * }</pre>
 *
 * <h2>At enable</h2>
 *
 * <p>The plugin's integration class builds the provider and registers it, returning the API-free link:
 *
 * <pre>{@code
 * final class MyComfiConf {
 *     static ComfiConfLink register(MyPlugin plugin, ConfigHolder<Settings> holder) {
 *         YamlFileProvider provider = YamlFileProvider.builder(plugin.configFile(), SettingsSchema.mapper(),
 *                 holder::reload)
 *             .build(plugin.getLogger());
 *         return ComfiConfRegistration.register(plugin, RESOURCE_ID, DISPLAY_NAME, DESCRIPTION, provider);
 *     }
 * }
 * }</pre>
 *
 * <p>At the end of {@code onEnable}, after the settings file loaded and after any migration that writes it, the main
 * class connects through a supplier, so the integration class loads only after the bridge was found installed,
 * enabled and visible:
 *
 * <pre>{@code
 * comfiConf = ComfiConfConnection.connect(this, errors, true, () -> MyComfiConf.register(this, holder));
 * }</pre>
 *
 * <p>The switch is {@code false} when the server owner turned the connection off. {@code connect} reads it once, so
 * a switch kept in the file the provider exposes is either left out of the schema or declared
 * {@code restartRequired()} and read from {@link so.alaz.taffy.config.ConfigHolder#startup()}. {@code connect} never
 * returns {@code null}, so once that line ran the plugin holds a connection whatever the outcome; the field is still
 * {@code null} when {@code onEnable} stopped before it.
 *
 * <h2>Reloads and the plugin's own writes</h2>
 *
 * <p>After each successful reload of the file by its own command, when {@code holder.reload()} returned
 * {@code true}, and after each write of the file outside ComfiConf, the plugin calls
 * {@link so.alaz.taffy.comficonf.ComfiConfConnection#notifyChanged()}, so open editors read the new values. It never
 * calls it after an apply.
 *
 * <p>The plugin's own write of the file holds the lock
 * {@link so.alaz.taffy.comficonf.ComfiConfConnection#lockFor(java.nio.file.Path)} gives, from reading the file anew
 * to saving it, so an apply racing the write reports a conflict instead of replacing it. A write without the lock can
 * be replaced by an apply with no conflict, and a write of values read before an apply reverts that apply:
 *
 * <pre>{@code
 * Lock lock = comfiConf.lockFor(configFile);
 * lock.lock();
 * try {
 *     YamlConfiguration yaml = YamlConfiguration.loadConfiguration(configFile.toFile());
 *     yaml.set("radius", radius);
 *     yaml.save(configFile.toFile());
 * } finally {
 *     lock.unlock();
 * }
 * holder.reload();
 * comfiConf.notifyChanged();
 * }</pre>
 *
 * <h2>Showing the state to the server owner</h2>
 *
 * <p>{@link so.alaz.taffy.comficonf.ComfiConfConnection#status()} is read when asked.
 * {@link so.alaz.taffy.comficonf.ComfiConfStatus#describe()} names the state, and
 * {@link so.alaz.taffy.comficonf.ComfiConfStatus#reason()}, when it is set, says what is missing or what went wrong,
 * and what the owner can do when there is something to do; together they are the owner's line.
 * {@link so.alaz.taffy.comficonf.ComfiConfConnection#resourceId()} gives the registered resource id for the same
 * line.
 *
 * <h2>At disable</h2>
 *
 * <p>{@link so.alaz.taffy.comficonf.ComfiConfConnection#close()} is the first step of {@code onDisable}, so no apply
 * reaches a plugin that is half disabled. It never passes on a failure of the bridge, so the steps after it run. The
 * field is checked first, because it is still {@code null} when {@code onEnable} stopped before {@code connect}:
 *
 * <pre>{@code
 * if (comfiConf != null) {
 *     comfiConf.close();
 * }
 * }</pre>
 */
package so.alaz.taffy.comficonf;
