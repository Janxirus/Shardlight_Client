package dev.shardlight.client.app.flows;

import dev.shardlight.client.error.ErrorCode;
import dev.shardlight.client.error.LauncherException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignInErrorsTest {
    @Test
    void everyErrorCodeGetsAPlainTitleAndNextStep() {
        List<String> problems = new ArrayList<>();
        for (ErrorCode code : ErrorCode.values()) {
            SignInErrors e = SignInErrors.of(new LauncherException(code, "error.internal", "detail"));
            for (String text : List.of(e.title(), e.nextStep())) {
                if (text == null || text.isBlank() || text.startsWith("ui.") || text.startsWith("!")) {
                    problems.add(code + " -> " + text);
                }
            }
        }
        assertEquals(List.of(), problems);
    }

    @Test
    void waitingForMinecraftApprovalCanBeRetried() {
        SignInErrors e = SignInErrors.of(new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, SignInErrors.APP_NOT_APPROVED));
        assertTrue(e.retry());
        assertNotNull(e.helpUrl());
        assertTrue(e.nextStep().toLowerCase().contains("approval") || e.title().toLowerCase().contains("approval"));
    }

    @Test
    void aMissingAppIsFixedInSettingsNotByRetrying() {
        SignInErrors e = SignInErrors.of(new LauncherException(ErrorCode.AUTH_NOT_CONFIGURED, "error.internal", "x"));
        assertTrue(e.settings());
        assertFalse(e.retry());
    }

    @Test
    void anythingElseStillSaysWhatToDo() {
        SignInErrors e = SignInErrors.of(new IllegalStateException("boom"));
        assertTrue(e.retry());
        assertFalse(e.nextStep().isBlank());
    }

    @Test
    void helpLinksAreHttps() {
        for (ErrorCode code : ErrorCode.values()) {
            String url = SignInErrors.of(new LauncherException(code, "error.internal", "x")).helpUrl();
            assertTrue(url == null || url.startsWith("https://"), code + " " + url);
        }
    }
}
