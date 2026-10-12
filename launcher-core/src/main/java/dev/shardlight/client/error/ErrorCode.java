package dev.shardlight.client.error;

/**
 * Every failure the launcher reports belongs to one of these categories. The UI turns the code plus the
 * exception's message key into an actionable message; logs carry the code so reports are easy to group.
 */
public enum ErrorCode {
    /** the network is unreachable, a connection was reset or a request timed out */
    NETWORK,
    /** a server answered with an unexpected HTTP status */
    HTTP_STATUS,
    /** a server asked us to slow down (HTTP 429) and the retries ran out */
    RATE_LIMITED,
    /** a downloaded file's size or hash didn't match what the metadata promised */
    CHECKSUM_MISMATCH,
    /** something we looked up (version, project, file, world) doesn't exist */
    NOT_FOUND,
    /** a file or response couldn't be parsed */
    INVALID_DATA,
    /** a stored settings/project file couldn't be read; it was set aside and defaults were used */
    CONFIG_CORRUPT,
    /** an archive or path tried to escape its folder, used a reserved name or was otherwise unsafe */
    UNSAFE_PATH,
    /** an archive is malformed, too large or contains disallowed entries */
    INVALID_ARCHIVE,
    /** reading or writing local files failed */
    IO,
    /** the user cancelled the operation */
    CANCELLED,
    /** Microsoft sign-in failed or was declined */
    AUTH_FAILED,
    /** the saved sign-in expired and couldn't be refreshed; the user must sign in again */
    AUTH_EXPIRED,
    /** the account has no Xbox profile yet (must create one at xbox.com) */
    AUTH_NO_XBOX_ACCOUNT,
    /** Xbox Live isn't available in the account's country */
    AUTH_REGION_BLOCKED,
    /** the account is a child account and must be added to a Microsoft family */
    AUTH_CHILD_ACCOUNT,
    /** the account needs adult verification (South Korea) */
    AUTH_ADULT_VERIFICATION,
    /** the account doesn't own Minecraft: Java Edition */
    AUTH_NOT_OWNED,
    /** no Microsoft app (Azure client id) is configured for sign-in */
    AUTH_NOT_CONFIGURED,
    /** the OS secret store is unavailable, so tokens can't be stored safely */
    SECRET_STORE_UNAVAILABLE,
    /** no suitable Java runtime was found or could be installed */
    JAVA_NOT_FOUND,
    /** the requested Minecraft/loader combination isn't supported */
    UNSUPPORTED_VERSION,
    /** a required dependency is missing or couldn't be resolved */
    DEPENDENCY_MISSING,
    /** two items can't be installed together */
    CONFLICT,
    /** an API that needs a key (CurseForge) has none configured */
    API_KEY_MISSING,
    /** the content's author doesn't allow third-party downloads; it must be fetched from the website */
    DISTRIBUTION_RESTRICTED,
    /** starting the game process failed */
    LAUNCH_FAILED,
    /** the game is already running for this project */
    ALREADY_RUNNING,
    /** an update's signature or hash couldn't be verified */
    SIGNATURE_INVALID,
    /** a programming error or an unexpected state */
    INTERNAL
}
