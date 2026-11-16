package so.alaz.taffy.gui;

/** A moment a menu marks with a sound. */
public enum Cue {
    /** A button acted. */
    CLICK("click"),
    /** An action finished as asked. */
    SUCCESS("success"),
    /** An action was refused or failed. */
    FAIL("fail");

    private final String key;

    Cue(String key) {
        this.key = key;
    }

    /**
     * The lower-case name of the cue, for a plugin that reads its sounds from a file.
     *
     * @return {@code click}, {@code success} or {@code fail}
     */
    public String key() {
        return key;
    }
}
