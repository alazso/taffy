package so.alaz.taffy.hooks;

/**
 * What became of one integration. Detection stops at the first check that fails and gives its state. The checks run
 * in the order of the constants, except for the Vault economy, whose lookup never checks that Vault is enabled: it
 * checks the switch, then that the economy API is visible, then that an economy is registered, and gives
 * {@link #NOT_INSTALLED} at either of the last two when no Vault plugin is installed.
 *
 * <p>A status read after detection can move from {@link #ON} or {@link #LOADING} to {@link #NOT_ENABLED} while the
 * integration plugin is disabled, and back once it is enabled again. The Vault economy's status does not move: it
 * changes only through a new lookup.
 */
public enum HookState {
    /** The owner switched the integration off, so its plugin was not looked at. */
    SWITCHED_OFF,
    /**
     * The integration plugin is not installed, or it was no longer installed by the time the integration was
     * built.
     */
    NOT_INSTALLED,
    /**
     * The integration plugin is installed but not enabled: it was not enabled when the integration was detected, or,
     * in a status read after detection, it has been disabled since.
     */
    NOT_ENABLED,
    /**
     * The integration plugin is installed and holds the API that is read, but the plugin that uses this module cannot
     * see its classes. For every integration but Vault, the integration plugin is also enabled. A plugin described by
     * {@code paper-plugin.yml} sees another plugin's classes only when it lists that plugin under
     * {@code dependencies.server}.
     */
    NOT_VISIBLE,
    /**
     * The integration plugin is installed, but the API class that is read is not present in the installed version.
     * For every integration but Vault, the integration plugin is also enabled.
     */
    API_MISSING,
    /**
     * The integration plugin is installed and the API that is read is visible, but no plugin has registered the
     * service read through it, such as an economy with Vault. A later registration can turn the integration on.
     */
    NOT_REGISTERED,
    /** The API is present, but building or registering the integration failed, so it stays off. */
    FAILED,
    /** The integration is on. */
    ON,
    /** The integration is on and its plugin is still loading its items. */
    LOADING
}
