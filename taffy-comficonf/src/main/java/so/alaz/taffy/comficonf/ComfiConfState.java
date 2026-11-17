package so.alaz.taffy.comficonf;

/**
 * What became of this plugin's ComfiConf connection.
 *
 * <p>At connect, the checks run in the order of {@link #SWITCHED_OFF}, {@link #NOT_INSTALLED}, {@link #NOT_ENABLED},
 * {@link #NOT_VISIBLE} and {@link #API_MISSING}, and connect stops at the first that fails with its state. When every
 * check passes, the registration either fails, giving {@link #FAILED} or, for an API class the bridge lacks,
 * {@link #API_MISSING}, or succeeds, giving {@link #ON}.
 *
 * <p>A status read after connect moves from {@link #ON} to {@link #NOT_ENABLED} while the bridge plugin is disabled,
 * to {@link #NOT_REGISTERED} while the bridge is enabled but no longer holds this plugin's resource, and to
 * {@link #FAILED} while the link throws when asked whether the bridge holds it. It reads {@link #ON} again once the
 * bridge is enabled and the link reports the resource registered. The link {@link ComfiConfRegistration} gives never
 * registers again once its resource was dropped, so with it {@link #NOT_REGISTERED} lasts until the plugin connects
 * again. Every other state is fixed at connect.
 */
public enum ComfiConfState {
    /** The owner switched the connection off, so the bridge plugin was not looked at. */
    SWITCHED_OFF,
    /** The ComfiConf bridge plugin is not installed. */
    NOT_INSTALLED,
    /**
     * The bridge plugin is installed but not enabled: it was not enabled at connect, or, in a status read after
     * connect, it has been disabled since.
     */
    NOT_ENABLED,
    /**
     * The bridge plugin is enabled and holds the ComfiConf API, but this plugin cannot see its classes: its
     * {@code paper-plugin.yml} does not list {@code ComfiConf} under {@code dependencies.server} with
     * {@code join-classpath: true}, or the plugin's jar holds its own copy of {@code com.comficonf.api}, whose
     * classes are not the bridge's.
     */
    NOT_VISIBLE,
    /**
     * The bridge plugin is enabled, but the installed version lacks an API class this plugin was built against. An
     * older bridge than the API this plugin compiles against gives this state.
     */
    API_MISSING,
    /**
     * The bridge plugin is enabled but no longer holds this plugin's resource, because the bridge was reloaded, it
     * dropped every registration, or the connection was closed. Only a status read after a connect that reached
     * {@link #ON} gives this state.
     */
    NOT_REGISTERED,
    /**
     * The API is visible, but registering this plugin's settings with the bridge failed, so they stay off it, or, in
     * a status read after connect, the link failed when asked whether the bridge holds the resource.
     */
    FAILED,
    /** This plugin's settings are registered with the bridge and editable in ComfiConf. */
    ON
}
