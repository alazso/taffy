package so.alaz.taffy.comficonf;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The state of this plugin's ComfiConf connection, for a plugin to show to the server owner. {@link #describe()}
 * names the state; {@link #reason()}, when there is one, says what is missing or what went wrong, and what the owner
 * can do when there is something to do.
 *
 * @param state what became of the connection
 * @param version the version of the ComfiConf bridge plugin, or {@code null} when it was not read: a status gives
 *     {@code null} with {@link ComfiConfState#SWITCHED_OFF}, {@link ComfiConfState#NOT_INSTALLED} and
 *     {@link ComfiConfState#NOT_ENABLED}, and the bridge's version with every other state
 * @param reason one or more full sentences for the server owner on what is missing or what went wrong, and what to
 *     do when there is something to do, each ending with a full stop, or {@code null} when {@link #describe()} says
 *     enough. It is given for {@link ComfiConfState#NOT_VISIBLE}, {@link ComfiConfState#API_MISSING} and
 *     {@link ComfiConfState#FAILED}
 */
public record ComfiConfStatus(ComfiConfState state, @Nullable String version, @Nullable String reason) {

    /**
     * Checks the state and the reason.
     *
     * @throws NullPointerException if {@code state} is {@code null}
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public ComfiConfStatus {
        Objects.requireNonNull(state, "state");
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason is blank");
        }
    }

    /**
     * Describes the state in a few words that start with {@code ComfiConf}, such as {@code ComfiConf not installed}
     * or {@code ComfiConf 1.0 installed, not registered}. A version that was not read shows as {@code unknown}. The
     * reason is never included.
     *
     * @return one of {@code ComfiConf switched off}, {@code ComfiConf not installed},
     *     {@code ComfiConf installed but not enabled}, {@code ComfiConf <version> not visible to this plugin},
     *     {@code ComfiConf <version> lacks the API this plugin needs},
     *     {@code ComfiConf <version> installed, not registered},
     *     {@code ComfiConf <version> could not register this plugin's settings} and {@code ComfiConf on}
     */
    public String describe() {
        String shown = version == null ? "unknown" : version;
        return switch (state) {
            case SWITCHED_OFF -> "ComfiConf switched off";
            case NOT_INSTALLED -> "ComfiConf not installed";
            case NOT_ENABLED -> "ComfiConf installed but not enabled";
            case NOT_VISIBLE -> "ComfiConf " + shown + " not visible to this plugin";
            case API_MISSING -> "ComfiConf " + shown + " lacks the API this plugin needs";
            case NOT_REGISTERED -> "ComfiConf " + shown + " installed, not registered";
            case FAILED -> "ComfiConf " + shown + " could not register this plugin's settings";
            case ON -> "ComfiConf on";
        };
    }
}
