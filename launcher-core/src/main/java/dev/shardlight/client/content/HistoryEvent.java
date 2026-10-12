package dev.shardlight.client.content;

/**
 * One change to a project's content, kept so it can be rolled back. Stored in
 * {@code projects/<id>/.history/history.json}; a file the change replaced or removed is kept in
 * {@code projects/<id>/.history/<event id>/}.
 *
 * @param id         event id (random UUID)
 * @param time       epoch milliseconds
 * @param action     {@link #INSTALL}, {@link #UPDATE}, {@link #REMOVE}, {@link #ENABLE}, {@link #DISABLE} or
 *                   {@link #ROLLBACK}
 * @param entryId    the content entry concerned
 * @param title      the entry's title at the time
 * @param before     the entry before the change (null for an install)
 * @param after      the entry after the change (null for a removal)
 * @param storedFile the file the change took away, relative to the project folder
 *                   ({@code .history/<event id>/<file>}), or null
 */
public record HistoryEvent(String id, long time, String action, String entryId, String title, ContentEntry before,
                           ContentEntry after, String storedFile) {
    public static final String INSTALL = "install";
    public static final String UPDATE = "update";
    public static final String REMOVE = "remove";
    public static final String ENABLE = "enable";
    public static final String DISABLE = "disable";
    public static final String ROLLBACK = "rollback";
}
