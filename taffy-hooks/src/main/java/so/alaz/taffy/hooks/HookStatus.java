package so.alaz.taffy.hooks;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * The state of one integration, for a plugin to show to the server owner. {@link #describe()} names the state;
 * {@link #reason()}, when there is one, says what is missing or what went wrong, and what the owner can do when there
 * is something to do. {@link #line()} joins the two.
 *
 * @param plugin the name of the integration plugin, such as {@code Vault}
 * @param state what became of the integration
 * @param version the version of the integration plugin, or {@code null} when it was not read: a status gives
 *     {@code null} with {@link HookState#SWITCHED_OFF}, {@link HookState#NOT_INSTALLED} and
 *     {@link HookState#NOT_ENABLED}, and a version, possibly {@code unknown}, with every other state,
 *     {@link HookState#NOT_REGISTERED} included
 * @param reason one or more full sentences for the server owner on what is missing or what went wrong, and what to
 *     do when there is something to do, each ending with a full stop, or {@code null} when {@link #describe()} says enough. It is given for every
 *     {@link HookState#NOT_VISIBLE} status, for the Vault economy's {@link HookState#NOT_INSTALLED},
 *     {@link HookState#NOT_REGISTERED}, {@link HookState#API_MISSING} and {@link HookState#FAILED}, for a
 *     placeholder backend's {@link HookState#FAILED}, and for a {@link HookState#NOT_ENABLED} read after detection
 *     while another instance of the plugin is enabled
 */
public record HookStatus(String plugin, HookState state, @Nullable String version, @Nullable String reason) {

    /**
     * Checks the plugin name, the state and the reason.
     *
     * @throws NullPointerException if {@code plugin} or {@code state} is {@code null}
     * @throws IllegalArgumentException if {@code reason} is blank
     */
    public HookStatus {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(state, "state");
        if (reason != null && reason.isBlank()) {
            throw new IllegalArgumentException("reason is blank");
        }
    }

    /**
     * Describes the state in a few words that start with the plugin name, such as {@code Nexo not installed} or
     * {@code Nexo 1.0 lacks the API that is read}. A version that was not read shows as {@code unknown}. The reason
     * is never included.
     *
     * @return one of {@code <plugin> switched off}, {@code <plugin> not installed},
     *     {@code <plugin> installed but not enabled}, {@code <plugin> <version> not visible to this plugin},
     *     {@code <plugin> <version> lacks the API that is read},
     *     {@code <plugin> <version> has nothing registered with it}, {@code <plugin> <version> could not be hooked},
     *     {@code <plugin> on} and {@code <plugin> on, waiting for its items to load}
     */
    public String describe() {
        String shown = version == null ? "unknown" : version;
        return switch (state) {
            case SWITCHED_OFF -> plugin + " switched off";
            case NOT_INSTALLED -> plugin + " not installed";
            case NOT_ENABLED -> plugin + " installed but not enabled";
            case NOT_VISIBLE -> plugin + " " + shown + " not visible to this plugin";
            case API_MISSING -> plugin + " " + shown + " lacks the API that is read";
            case NOT_REGISTERED -> plugin + " " + shown + " has nothing registered with it";
            case FAILED -> plugin + " " + shown + " could not be hooked";
            case ON -> plugin + " on";
            case LOADING -> plugin + " on, waiting for its items to load";
        };
    }

    /**
     * The owner's line: {@link #describe()} alone when there is no reason, and otherwise {@link #describe()}, a
     * full stop, one space and {@link #reason()}, such as
     * {@code Vault 1.7.3 has nothing registered with it. Install an economy plugin.} The line has no line break
     * unless the reason holds one, and ends with a full stop only when there is a reason.
     *
     * @return {@code <describe()>} or {@code <describe()>. <reason()>}
     */
    public String line() {
        return reason == null ? describe() : describe() + ". " + reason;
    }
}
