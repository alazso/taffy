/**
 * Plugin metrics on bStats and FastStats from one set of chart definitions, with the plugin's handled errors sent
 * to FastStats error tracking through its {@link so.alaz.taffy.platform.ErrorReports}.
 *
 * <p>A plugin names three types: {@link so.alaz.taffy.metrics.MetricChart}, {@link so.alaz.taffy.metrics.MetricsBuilder}
 * and {@link so.alaz.taffy.metrics.Metrics}. No public signature names a bStats or FastStats type. The module
 * publishes both SDKs as runtime dependencies, so a plugin compiles without them and its shadow jar carries them.
 *
 * <h2>Relocation</h2>
 *
 * <p>Each plugin carries its own relocated copy of both SDKs and of Taffy. A build on {@code so.alaz.taffy.paper-plugin}
 * needs nothing more: it relocates {@code org.bstats}, {@code dev.faststats} and {@code so.alaz.taffy} under
 * {@code <group>.libs} and keeps one {@code META-INF/faststats.properties}. Any other build relocates the same three
 * packages and keeps that file in its shadow jar: bStats refuses to start from classes that were not relocated, and
 * FastStats cannot create its context without the file; without it, {@code start()} logs one warning and leaves
 * FastStats off before it writes {@code plugins/faststats/config.properties}. The FastStats SDK resolves from
 * {@code https://repo.faststats.dev/releases}. Neither SDK may be listed as a Paper runtime library, which would
 * load it unrelocated.
 *
 * <h2>At enable</h2>
 *
 * <p>In {@code onEnable}, a plugin:
 *
 * <ol>
 *   <li>reads its metrics switch from its own config;</li>
 *   <li>creates a {@link so.alaz.taffy.metrics.MetricsBuilder} with itself, its
 *     {@link so.alaz.taffy.platform.ErrorReports} and the switch, and gives it its bStats service id and FastStats
 *     token. An unset id or token leaves that provider off;</li>
 *   <li>adds its charts. A chart's supplier reads in-memory state only, as {@link so.alaz.taffy.metrics.MetricChart}
 *     describes;</li>
 *   <li>calls {@link so.alaz.taffy.metrics.MetricsBuilder#start()} as the last step of enable, once the state the
 *     charts read exists, and keeps the {@link so.alaz.taffy.metrics.Metrics}.</li>
 * </ol>
 *
 * <p>An enable that gives up after the switch is read, such as when storage fails to start, still calls
 * {@code start()} before it returns, without the charts whose state never came up. Its disable then shuts the
 * metrics down, and the reports raised during enable go out in the final send. Without that call they are lost.
 *
 * <p>Metrics start once per enable and are not restarted on a config reload: the plugin keeps the handle until
 * disable. {@link so.alaz.taffy.metrics.Metrics#shutdown()} does not unregister the {@code ServerExceptionEvent}
 * listeners FastStats registered for the plugin, which stay registered until the plugin is disabled, so each
 * further start in the same enable adds to them.
 *
 * <pre>{@code
 * private final ErrorReports errors = new ErrorReports();
 * private final LongAdder commandsRun = new LongAdder();
 * private Metrics metrics;
 *
 * public void onEnable() {
 *     // config loads here, reporting to errors
 *     MetricsBuilder builder = new MetricsBuilder(this, errors, settings.metrics())
 *         .bStats(BSTATS_SERVICE_ID)
 *         .fastStats(FASTSTATS_TOKEN);
 *     if (!startStorage()) {
 *         metrics = builder.start();
 *         getServer().getPluginManager().disablePlugin(this);
 *         return;
 *     }
 *     // integrations start here, reporting to errors
 *     metrics = builder
 *         .addChart(MetricChart.string("storage_type", () -> settings.storageType()))
 *         .addChart(MetricChart.counter("commands_run", commandsRun::sum))
 *         .start();
 * }
 *
 * public void onDisable() {
 *     // the other shutdown steps first
 *     if (metrics != null) {
 *         Shutdown.closeQuietly("metrics", metrics::shutdown, getLogger(), errors);
 *     }
 * }
 * }</pre>
 *
 * <p>A provider that fails to start logs one warning through the plugin's logger and is skipped; enable goes on.
 * Taffy logs nothing else. FastStats logs through the plugin's logger too: on a server's first run it prints its
 * own first-run notice of several lines, and with its {@code debug} option on it logs each send. In its own unit
 * tests a plugin passes {@code false} as the switch.
 *
 * <h2>Handled errors</h2>
 *
 * <p>The error sink holds the reports raised before its first install or clear, the first of each source and at
 * most 16. When FastStats starts with its error service, {@link so.alaz.taffy.metrics.MetricsBuilder#start()}
 * installs the handle, which delivers the held reports to FastStats error tracking and routes every later report
 * there, each under its source. Otherwise {@code start()} clears the sink, which discards them. Plugin code keeps
 * reporting through {@link so.alaz.taffy.platform.ErrorReports#report} and never calls FastStats itself.
 *
 * <p>The sink has one target, and only its first install receives the held reports. A plugin using this module
 * therefore installs no target of its own before {@code start()}: such a target would take the held reports, and
 * {@code start()} would then replace it. A target installed after {@code start()} replaces the handle in turn, so a
 * plugin that also wants its reports elsewhere installs one target that passes each report to
 * {@link so.alaz.taffy.metrics.Metrics#report} and to its own handler. {@link so.alaz.taffy.metrics.Metrics#shutdown()}
 * clears the sink, which holds nothing after its first install or clear: a plugin that restarts its metrics on
 * reload with the same sink loses the reports raised between the shutdown and the next {@code start()}.
 *
 * <h2>Uncaught exceptions</h2>
 *
 * <p>Taffy leaves the JVM default uncaught exception handler alone: it never installs, replaces or restores it.
 * FastStats error tracking receives, among others, the reports made through
 * {@link so.alaz.taffy.platform.ErrorReports}, a chart supplier that throws (see
 * {@link so.alaz.taffy.metrics.MetricChart}) and the exceptions Paper reports for the plugin itself in a
 * {@code ServerExceptionEvent}, such as one thrown from one of its event handlers. Paper's region, async and entity
 * schedulers fire no {@code ServerExceptionEvent}, so {@link so.alaz.taffy.platform.Scheduler} reports a task Paper
 * runs for it that throws through {@link so.alaz.taffy.platform.ErrorReports}, and FastStats receives it as a
 * handled report. An uncaught exception on a thread the plugin starts itself is not sent to FastStats, and the JVM
 * prints it as usual. A plugin that wants such errors tracked catches them and reports them through
 * {@link so.alaz.taffy.platform.ErrorReports#report}.
 *
 * <h2>Diagnostics</h2>
 *
 * <p>A diagnostics or test-error command reads {@link so.alaz.taffy.metrics.Metrics#bStatsStarted()},
 * {@link so.alaz.taffy.metrics.Metrics#fastStatsSdk()}, {@link so.alaz.taffy.metrics.Metrics#fastStatsEnabled()},
 * {@link so.alaz.taffy.metrics.Metrics#fastStatsSubmitting()}, {@link so.alaz.taffy.metrics.Metrics#fastStatsCharts()}
 * and {@link so.alaz.taffy.metrics.Metrics#fastStatsErrorTracking()}. The last answers whether a handled report will
 * be sent while the sink routes to the handle. Each reader is safe on any thread and reads {@code false} or
 * {@code null} on an inert handle and after shutdown.
 *
 * <h2>At disable</h2>
 *
 * <p>{@link so.alaz.taffy.metrics.Metrics#shutdown()} is the last step of disable, after
 * {@code DiscordWebhook.close}. FastStats then sends the plugin's last errors and metrics, so the reports raised by
 * the earlier shutdown steps are sent too. The send runs on a daemon thread, and the disabling thread waits at most
 * 5 seconds for it, as {@link so.alaz.taffy.metrics.Metrics#shutdown()} describes.
 *
 * <h2>The Taffy usage chart</h2>
 *
 * <p>When the switch is on, {@link so.alaz.taffy.metrics.MetricsBuilder#start()} also starts Taffy's own usage
 * metrics on the same plugin: a second bStats instance under Taffy's bStats service id when the plugin's own bStats
 * instance started, and a second FastStats context, with metrics only, under Taffy's FastStats token when the
 * plugin's own context started. Taffy so adds no provider, configuration file or first-run notice that the plugin's
 * own metrics do not. Each reports one chart, {@code taffy_version}, the
 * Taffy version the plugin was built with, beside what the SDK sends for any plugin. The server owner's opt-outs in
 * {@code plugins/bStats/config.yml} and {@code plugins/faststats/config.properties} apply to it as to the plugin's
 * own metrics. At disable it stops first, without a final send. A started bStats instance runs on its own
 * {@code bStats-Metrics} daemon thread while bStats is enabled.
 *
 * <p>One plugin per server reports the usage chart to each provider. The first plugin to start a provider's usage
 * instance names itself in a system property, {@code taffy.usage.bstats} or {@code taffy.usage.faststats}, which
 * every relocated copy of Taffy reads, and a plugin that finds another plugin's name there starts no usage instance
 * for that provider and logs nothing. {@link so.alaz.taffy.metrics.Metrics#shutdown()} of the plugin named there
 * unsets the property; no other plugin takes the chart over until it next starts its own metrics. A copy of Taffy
 * older than 0.2.1 neither reads nor sets the properties.
 */
package so.alaz.taffy.metrics;
