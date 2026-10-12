package dev.shardlight.client.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TomlReaderTest {
    @Test
    @SuppressWarnings("unchecked") // test inspects the reader's untyped tree
    void parsesTablesArraysAndValues() {
        Map<String, Object> doc = TomlReader.parse("""
            # comment
            title = "A \\"quoted\\" \\u00e9 value" # trailing
            literal = 'C:\\path\\to'
            count = 1_000
            hex = 0xff
            ratio = 1.5
            enabled = true
            when = 1979-05-27T07:32:00Z
            list = [ 1, 2,
              3, # comment inside
            ]
            nested = [[1, 2], ["a"]]
            inline = { a = 1, b.c = "d" }
            "quoted key" = "q"
            dotted.key = "v"

            [section]
            x = "y"

            [[items]]
            name = "first"
            [[items]]
            name = "second"
            [items.sub]
            deep = 1
            """);
        assertEquals("A \"quoted\" \u00e9 value", doc.get("title"));
        assertEquals("C:\\path\\to", doc.get("literal"));
        assertEquals(1000L, doc.get("count"));
        assertEquals(255L, doc.get("hex"));
        assertEquals(1.5, doc.get("ratio"));
        assertEquals(Boolean.TRUE, doc.get("enabled"));
        assertEquals("1979-05-27T07:32:00Z", doc.get("when"));
        assertEquals(List.of(1L, 2L, 3L), doc.get("list"));
        assertEquals(List.of(List.of(1L, 2L), List.of("a")), doc.get("nested"));
        Map<String, Object> inline = (Map<String, Object>) doc.get("inline");
        assertEquals(1L, inline.get("a"));
        assertEquals("d", ((Map<String, Object>) inline.get("b")).get("c"));
        assertEquals("q", doc.get("quoted key"));
        assertEquals("v", ((Map<String, Object>) doc.get("dotted")).get("key"));
        assertEquals("y", ((Map<String, Object>) doc.get("section")).get("x"));
        List<Object> items = (List<Object>) doc.get("items");
        assertEquals(2, items.size());
        Map<String, Object> second = (Map<String, Object>) items.get(1);
        assertEquals("second", second.get("name"));
        assertEquals(1L, ((Map<String, Object>) second.get("sub")).get("deep"));
    }

    @Test
    void multiLineStrings() {
        Map<String, Object> doc = TomlReader.parse("a = \"\"\"\nline one\nline \\\n    two\"\"\"\nb = '''\nraw \\n text'''\nc = \"\"\"x\"\"\"\"\"\n");
        assertEquals("line one\nline two", doc.get("a"));
        assertEquals("raw \\n text", doc.get("b"));
        assertEquals("x\"\"", doc.get("c"));
    }

    @Test
    void crlfLineEndings() {
        Map<String, Object> doc = TomlReader.parse("[[mods]]\r\nmodId=\"a\"\r\nversion=\"1\"\r\n");
        assertInstanceOf(List.class, doc.get("mods"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] {"a = ", "a = \"unterminated", "[table", "a = 1 b = 2", "a = [1, 2", "a = 1\na = 2",
            "a = {x = 1", "= 1", "a = nope", "a = \"bad \\q escape\"", "a.b = 1\n[a.b]\n", "a = '''never closed"}) {
            assertThrows(TomlReader.TomlException.class, () -> TomlReader.parse(bad), bad);
        }
    }

    @Test
    void deepNestingIsRefused() {
        String deep = "a = " + "[".repeat(100) + "]".repeat(100);
        assertThrows(TomlReader.TomlException.class, () -> TomlReader.parse(deep));
    }
}
