package dev.shardlight.client.minecraft.model;

/**
 * The {@code os} part of a rule.
 *
 * @param name    windows, osx or linux
 * @param version a regular expression matched against {@code os.version}
 * @param arch    a CPU architecture, in practice only {@code x86}
 */
public record OsRule(String name, String version, String arch) {
}
