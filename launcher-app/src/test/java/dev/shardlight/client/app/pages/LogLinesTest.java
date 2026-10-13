package dev.shardlight.client.app.pages;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogLinesTest {
    private static final String LOG = """
        [20:31:40] [main/INFO]: Loading
        [20:31:41] [main/WARN]: Something odd
        [20:31:42] [Render thread/ERROR]: Crashed
        \tat net.minecraft.Foo.bar(Foo.java:1)
        [20:31:43] [main/DEBUG]: details
        """;

    @Test
    void eachLineGetsItsLevelAndStackTracesFollowTheirError() {
        List<LogLines.Line> lines = LogLines.parse(LOG);
        assertEquals(5, lines.size());
        assertEquals(LogLines.Level.INFO, lines.get(0).level());
        assertEquals(LogLines.Level.WARN, lines.get(1).level());
        assertEquals(LogLines.Level.ERROR, lines.get(2).level());
        assertEquals(LogLines.Level.ERROR, lines.get(3).level());
        assertEquals(LogLines.Level.DEBUG, lines.get(4).level());
    }

    @Test
    void levelsMapToStyleClasses() {
        assertEquals("log-error", LogLines.Level.ERROR.styleClass());
        assertEquals("log-warn", LogLines.Level.WARN.styleClass());
        assertNull(LogLines.levelOf("no header here"));
        assertEquals(LogLines.Level.ERROR, LogLines.levelOf("[1] [t/FATAL]: x"));
    }

    @Test
    void searchIgnoresCaseAndBlankKeepsEverything() {
        List<LogLines.Line> lines = LogLines.parse(LOG);
        assertEquals(1, LogLines.filter(lines, "ODD").size());
        assertEquals(lines.size(), LogLines.filter(lines, "  ").size());
        assertEquals(0, LogLines.filter(lines, "missing").size());
    }
}
