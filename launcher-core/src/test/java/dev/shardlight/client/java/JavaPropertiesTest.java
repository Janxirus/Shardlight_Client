package dev.shardlight.client.java;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaPropertiesTest {
    /** what Temurin 21 on Windows prints (stderr), CRLF line ends and wrapped library path included */
    static final String TEMURIN_21_WINDOWS = String.join("\r\n",
        "Property settings:",
        "    file.encoding = UTF-8",
        "    file.separator = \\",
        "    java.class.version = 65.0",
        "    java.home = C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.5.11-hotspot",
        "    java.library.path = C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.5.11-hotspot\\bin",
        "        C:\\WINDOWS\\Sun\\Java\\bin",
        "        C:\\WINDOWS\\system32",
        "    java.runtime.name = OpenJDK Runtime Environment",
        "    java.runtime.version = 21.0.5+11-LTS",
        "    java.specification.name = Java Platform API Specification",
        "    java.specification.vendor = Oracle Corporation",
        "    java.specification.version = 21",
        "    java.vendor = Eclipse Adoptium",
        "    java.vendor.url = https://adoptium.net/",
        "    java.version = 21.0.5",
        "    java.version.date = 2024-10-15",
        "    os.arch = amd64",
        "    os.name = Windows 11",
        "    os.version = 10.0",
        "",
        "openjdk version \"21.0.5\" 2024-10-15 LTS",
        "OpenJDK Runtime Environment Temurin-21.0.5+11 (build 21.0.5+11-LTS)",
        "OpenJDK 64-Bit Server VM Temurin-21.0.5+11 (build 21.0.5+11-LTS, mixed mode, sharing)",
        "");

    static final String ORACLE_8 = """
        Picked up _JAVA_OPTIONS: -Xmx1G
        Property settings:
            awt.toolkit = sun.awt.windows.WToolkit
            java.specification.version = 1.8
            java.vendor = Oracle Corporation
            java.version = 1.8.0_402
            os.arch = x86

        java version "1.8.0_402"
        Java(TM) SE Runtime Environment (build 1.8.0_402-b06)
        """;

    @Test
    void parsesModernAndJava8Output() {
        JavaProperties p = JavaProperties.parse(TEMURIN_21_WINDOWS).orElseThrow();
        assertEquals("21.0.5", p.version());
        assertEquals(21, p.majorVersion());
        assertEquals("Eclipse Adoptium", p.vendor());
        assertEquals("amd64", p.arch());

        JavaProperties eight = JavaProperties.parse(ORACLE_8).orElseThrow();
        assertEquals("1.8.0_402", eight.version());
        assertEquals(8, eight.majorVersion());
        assertEquals("x86", eight.arch());
    }

    @Test
    void fallsBackToTheVersionLine() {
        JavaProperties p = JavaProperties.parse("openjdk version \"25\" 2025-09-16\nOpenJDK Runtime Environment").orElseThrow();
        assertEquals(25, p.majorVersion());
        assertEquals("25", p.version());
        assertEquals("", p.vendor());
    }

    @Test
    void rejectsNonJavaOutput() {
        assertEquals(Optional.empty(), JavaProperties.parse(null));
        assertEquals(Optional.empty(), JavaProperties.parse(""));
        assertEquals(Optional.empty(), JavaProperties.parse("bash: java: command not found"));
        assertEquals(Optional.empty(), JavaProperties.parse("    java.version = banana\n    java.specification.version = x"));
    }

    @Test
    void majorAndComparison() {
        assertEquals(8, JavaProperties.majorOf("1.8.0_402"));
        assertEquals(8, JavaProperties.majorOf("1.8"));
        assertEquals(17, JavaProperties.majorOf("17.0.12"));
        assertEquals(21, JavaProperties.majorOf("21"));
        assertEquals(-1, JavaProperties.majorOf("abc"));
        assertEquals(-1, JavaProperties.majorOf(null));
        assertEquals(-1, JavaProperties.majorOf("123456789"));
        assertTrue(JavaProperties.compareVersions("21.0.7+6", "21.0.5") > 0);
        assertTrue(JavaProperties.compareVersions("17.0.12", "17.0.9") > 0);
        assertTrue(JavaProperties.compareVersions("1.8.0_402", "1.8.0_51") > 0);
        assertEquals(0, JavaProperties.compareVersions("21", "21.0.0"));
        assertEquals("x64", JavaProperties.archFamily("AMD64"));
        assertEquals("arm64", JavaProperties.archFamily("aarch64"));
        assertEquals("x86", JavaProperties.archFamily("i686"));
        assertEquals("other", JavaProperties.archFamily(null));
    }
}
