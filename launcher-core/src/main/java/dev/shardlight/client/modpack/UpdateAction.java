package dev.shardlight.client.modpack;

/** What a modpack update does with one file. The first three change the disk; the rest leave the player's files alone. */
public enum UpdateAction {
    /** the new pack has a file the project doesn't: it is added */
    ADD(true),
    /** the pack changed a file the player didn't touch: it is replaced */
    UPDATE(true),
    /** the pack no longer has a file the player didn't touch: it is removed */
    REMOVE(true),
    /** a file the pack never shipped: kept */
    KEEP_ADDED(false),
    /** the player edited a file the pack didn't change, or the pack dropped it: kept */
    KEEP_EDITED(false),
    /** the player edited it and the pack changed it too: the player's file stays */
    KEEP_CONFLICT(false),
    /** the player deleted a file the pack ships: it stays deleted */
    KEEP_REMOVED(false);

    private final boolean writes;

    UpdateAction(boolean writes) {
        this.writes = writes;
    }

    /** true when applying the update adds, replaces or removes this file */
    public boolean writes() {
        return writes;
    }
}
