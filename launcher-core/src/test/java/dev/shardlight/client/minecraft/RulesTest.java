package dev.shardlight.client.minecraft;

import dev.shardlight.client.minecraft.model.OsRule;
import dev.shardlight.client.minecraft.model.Rule;
import dev.shardlight.client.util.OsInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesTest {
    static final OsInfo WIN11 = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X64, "10.0");
    static final OsInfo WIN32 = new OsInfo(OsInfo.Os.WINDOWS, OsInfo.Arch.X86, "6.1");
    static final OsInfo MAC = new OsInfo(OsInfo.Os.MACOS, OsInfo.Arch.ARM64, "14.5");
    static final OsInfo LINUX = new OsInfo(OsInfo.Os.LINUX, OsInfo.Arch.X64, "6.8.0-45-generic");

    @Test
    void noRulesAllows() {
        assertTrue(Rules.allows(List.of(), WIN11));
        assertTrue(Rules.allows(null, MAC));
    }

    @Test
    void rulesStartDisallowed() {
        assertFalse(Rules.allows(List.of(new Rule("disallow", null, null)), WIN11));
        assertFalse(Rules.allows(List.of(Rule.allowOs("osx")), WIN11), "no rule matched");
        assertTrue(Rules.allows(List.of(Rule.allowOs("osx")), MAC));
    }

    @Test
    void lastMatchWins() {
        List<Rule> allExceptMac = List.of(Rule.allow(), Rule.disallowOs("osx"));
        assertTrue(Rules.allows(allExceptMac, WIN11));
        assertTrue(Rules.allows(allExceptMac, LINUX));
        assertFalse(Rules.allows(allExceptMac, MAC));
        List<Rule> reversed = List.of(Rule.disallowOs("osx"), Rule.allow());
        assertTrue(Rules.allows(reversed, MAC));
    }

    @Test
    void osVersionIsARegex() {
        List<Rule> win10 = List.of(new Rule("allow", new OsRule("windows", "^10\\.", null), null));
        assertTrue(Rules.allows(win10, WIN11));
        assertFalse(Rules.allows(win10, WIN32));
        assertFalse(Rules.allows(List.of(new Rule("allow", new OsRule("osx", "^10\\.5\\.\\d$", null), null)), MAC));
        // a broken pattern never matches instead of crashing
        assertFalse(Rules.allows(List.of(new Rule("allow", new OsRule(null, "([", null), null)), LINUX));
    }

    @Test
    void archMatchesThirtyTwoBit() {
        List<Rule> x86 = List.of(new Rule("allow", new OsRule(null, null, "x86"), null));
        assertTrue(Rules.allows(x86, WIN32));
        assertFalse(Rules.allows(x86, WIN11));
        assertFalse(Rules.allows(x86, MAC));
        assertTrue(Rules.allows(List.of(new Rule("allow", new OsRule(null, null, "aarch64"), null)), MAC));
        assertTrue(Rules.allows(List.of(new Rule("allow", new OsRule("windows", null, "x86_64"), null)), WIN11));
    }

    @Test
    void osNamesAndAliases() {
        assertTrue(Rules.allows(List.of(Rule.allowOs("macos")), MAC));
        assertFalse(Rules.allows(List.of(Rule.allowOs("windows")), LINUX));
        assertFalse(Rules.allows(List.of(Rule.allowOs("linux")), new OsInfo(OsInfo.Os.OTHER, OsInfo.Arch.X64, "")));
        assertTrue(Rules.allows(List.of(new Rule("allow", new OsRule("", "", ""), null)), LINUX), "blank conditions are ignored");
    }

    @Test
    void features() {
        List<Rule> demo = List.of(new Rule("allow", null, Map.of(Rules.DEMO_USER, true)));
        assertFalse(Rules.allows(demo, WIN11), "absent features are false");
        assertTrue(Rules.allows(demo, WIN11, Rules.features(true, false, false, false, false, false)));
        assertFalse(Rules.allows(demo, WIN11, Rules.features(false, true, true, true, true, true)));
        List<Rule> both = List.of(new Rule("allow", null, Map.of(Rules.QUICK_PLAYS_SUPPORT, true, Rules.QUICK_PLAY_SINGLEPLAYER, true)));
        assertFalse(Rules.allows(both, WIN11, Rules.features(false, false, true, false, false, false)));
        assertTrue(Rules.allows(both, WIN11, Rules.features(false, false, true, true, false, false)));
        List<Rule> notDemo = List.of(new Rule("allow", null, Map.of(Rules.DEMO_USER, false)));
        assertTrue(Rules.allows(notDemo, WIN11, Map.of()));
        assertTrue(Rules.mentionsFeature(both, Rules.QUICK_PLAY_FEATURES));
        assertFalse(Rules.mentionsFeature(demo, Rules.QUICK_PLAY_FEATURES));
    }

    @Test
    void unknownActionDisallows() {
        assertFalse(Rules.allows(List.of(new Rule("maybe", null, null)), WIN11));
        assertFalse(Rules.allows(List.of(Rule.allow(), new Rule(null, null, null)), WIN11));
    }
}
