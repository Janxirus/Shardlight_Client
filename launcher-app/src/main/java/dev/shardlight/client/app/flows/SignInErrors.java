package dev.shardlight.client.app.flows;

import dev.shardlight.client.app.ui.Tasks;
import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import dev.shardlight.client.i18n.I18n;

/**
 * What to tell a player when sign-in fails: a plain title, the next step in plain words, and which buttons help
 * (try again, open a page, open the account settings). Chosen from the error code, so every failure the sign-in
 * service reports has its own advice.
 *
 * @param title      what happened, in a few words
 * @param nextStep   what to do about it
 * @param retry      whether trying again can help
 * @param helpLabel  label of a button that opens {@code helpUrl}, or null
 * @param helpUrl    a page that explains or fixes it, or null
 * @param settings   whether Settings > Accounts is where to go
 * @param details    the technical message, for the small print
 */
public record SignInErrors(String title, String nextStep, boolean retry, String helpLabel, String helpUrl, boolean settings,
                           String details) {
    /** the key of the error the sign-in service raises while Mojang has not approved the launcher's app */
    static final String APP_NOT_APPROVED = "auth.error.appNotApproved";

    public static SignInErrors of(Throwable error) {
        Throwable cause = Tasks.unwrap(error);
        String details = Tasks.message(error);
        if (!(cause instanceof LauncherException e)) {
            return new SignInErrors(I18n.tr("ui.signin.err.generic.title"), I18n.tr("ui.signin.err.generic.next"), true,
                null, null, false, details);
        }
        ErrorCode code = e.code();
        String key = e.messageKey() == null ? "" : e.messageKey();
        return switch (code) {
            case NETWORK, HTTP_STATUS, RATE_LIMITED -> make("network", true, null, null, false, details);
            case AUTH_NOT_CONFIGURED -> key.equals(APP_NOT_APPROVED)
                ? make("approval", true, "ui.signin.err.approval.help", "https://aka.ms/mce-reviewappid", false, details)
                : make("setup", false, null, null, true, details);
            case AUTH_FAILED -> key.equals("auth.error.codeExpired") ? make("expired", true, null, null, false, details)
                : key.equals("auth.error.declined") ? make("declined", true, null, null, false, details)
                : make("failed", true, null, null, false, details);
            case AUTH_EXPIRED -> make("signedOut", true, null, null, false, details);
            case AUTH_NO_XBOX_ACCOUNT -> make("xbox", true, "ui.signin.err.xbox.help", "https://www.xbox.com", false, details);
            case AUTH_REGION_BLOCKED -> make("region", false, "ui.signin.err.region.help",
                "https://support.xbox.com/help/account-profile/manage-account/xbox-live-countries-regions", false, details);
            case AUTH_CHILD_ACCOUNT -> make("child", true, "ui.signin.err.child.help",
                "https://account.microsoft.com/family", false, details);
            case AUTH_ADULT_VERIFICATION -> make("adult", true, "ui.signin.err.adult.help", "https://www.xbox.com", false, details);
            case AUTH_NOT_OWNED -> make("notOwned", false, "ui.signin.err.notOwned.help",
                "https://www.minecraft.net/store/minecraft-java-bedrock-edition-pc", false, details);
            case SECRET_STORE_UNAVAILABLE -> make("store", true, null, null, true, details);
            default -> new SignInErrors(I18n.tr("ui.signin.err.generic.title"), I18n.tr("ui.signin.err.generic.next"), true,
                null, null, false, details);
        };
    }

    private static SignInErrors make(String kind, boolean retry, String helpLabelKey, String helpUrl, boolean settings,
                                     String details) {
        return new SignInErrors(I18n.tr("ui.signin.err." + kind + ".title"), I18n.tr("ui.signin.err." + kind + ".next"), retry,
            helpLabelKey == null ? null : I18n.tr(helpLabelKey), helpUrl, settings, details);
    }
}
