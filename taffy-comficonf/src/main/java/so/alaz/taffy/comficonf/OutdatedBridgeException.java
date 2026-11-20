package so.alaz.taffy.comficonf;

/**
 * The running bridge implements an older API version than the one this module was compiled against. Declared without
 * any {@code com.comficonf} type, so {@link ComfiConfConnection} may name it while the bridge is absent.
 */
final class OutdatedBridgeException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    private final int implemented;
    private final int needed;

    OutdatedBridgeException(int implemented, int needed) {
        super("The ComfiConf bridge implements API version " + implemented + ", older than the API version " + needed
            + " this plugin was built against");
        this.implemented = implemented;
        this.needed = needed;
    }

    /** What the bridge lacks, as the detail of {@link ComfiConfConnection#incompatibleBridgeMessage}. */
    String detail() {
        return "it implements API version " + implemented + ", older than the API version " + needed
            + " this plugin was built against";
    }
}
