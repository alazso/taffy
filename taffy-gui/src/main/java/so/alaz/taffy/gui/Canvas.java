package so.alaz.taffy.gui;

/** The slots of one menu inventory. */
interface Canvas {

    /**
     * Shows the icon in the slot. It is called on the viewer's entity thread.
     *
     * @param slot the raw slot of the menu inventory, from 0 to its size minus 1
     * @param icon what the slot shows
     * @throws IndexOutOfBoundsException if {@code slot} is outside the menu inventory
     */
    void put(int slot, Icon icon);
}
