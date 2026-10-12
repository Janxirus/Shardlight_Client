package dev.shardlight.client.app.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FuzzyTest {
    @Test
    void lettersMustAppearInOrder() {
        assertTrue(Fuzzy.score("lib", "Library") > 0);
        assertTrue(Fuzzy.score("lbr", "Library") > 0);
        assertEquals(-1, Fuzzy.score("rbl", "Library"));
        assertEquals(-1, Fuzzy.score("x", "Library"));
        assertEquals(0, Fuzzy.score("", "Library"));
    }

    @Test
    void prefixesAndWordStartsRankFirst() {
        assertTrue(Fuzzy.score("ultra", "Switch to Ultra Performance") > Fuzzy.score("ultra", "Sculk ultrasonic radar mod"));
        assertTrue(Fuzzy.score("set", "Settings") > Fuzzy.score("set", "Reset the window"));
        assertTrue(Fuzzy.score("np", "New project") > Fuzzy.score("np", "Open logs in a new panel"));
        assertTrue(Fuzzy.score("sodium", "Sodium") > Fuzzy.score("sodium", "Sodium Extra Options Pack"));
    }

    @Test
    void ignoresCaseAndSpaces() {
        assertEquals(Fuzzy.score("newproject", "New project"), Fuzzy.score("New Project", "New project"));
    }
}
