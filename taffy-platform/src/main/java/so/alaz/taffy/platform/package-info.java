/**
 * The classes every other Taffy module builds on: scheduling, shutdown, text, items, time, logging and a
 * Discord webhook.
 *
 * <p>The shutdown classes assume one order while the owning plugin disables, with each step run through
 * {@link so.alaz.taffy.platform.Shutdown#closeQuietly} so that a failing step does not stop the next:
 * <ol>
 *   <li>the unregistering of the plugin's placeholders, {@code PlaceholderRegistration.unregister} of
 *       {@code taffy-hooks};</li>
 *   <li>the close of the plugin's menus, {@code Menus.shutdown} of {@code taffy-gui}, which ends every prompt and
 *       then closes the open menus of the players the disabling thread owns;</li>
 *   <li>{@link so.alaz.taffy.platform.Scheduler#close()}, which stops the repeating tasks;</li>
 *   <li>{@link so.alaz.taffy.platform.TaskGate#close}, which refuses new async tasks and waits for the
 *       admitted ones;</li>
 *   <li>{@link so.alaz.taffy.platform.PendingSettlements#settleAll}, which settles the work no callback
 *       started;</li>
 *   <li>the close of whatever the settlements write to, such as storage;</li>
 *   <li>{@link so.alaz.taffy.platform.DiscordWebhook#close()}, which waits for the posts still in flight;</li>
 *   <li>the shutdown of the plugin's metrics, {@code Metrics.shutdown} of {@code taffy-metrics}, which stops
 *       reporting and makes the final send, so the reports raised by the steps before it are sent.</li>
 * </ol>
 */
package so.alaz.taffy.platform;
