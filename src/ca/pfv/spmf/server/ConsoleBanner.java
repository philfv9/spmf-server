package ca.pfv.spmf.server;

/*
 * Copyright (c) 2026 Philippe Fournier-Viger
 *
 * This file is part of the SPMF SERVER
 * (http://www.philippe-fournier-viger.com/spmf).
 *
 * SPMF is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * SPMF is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with SPMF. If not, see <http://www.gnu.org/licenses/>.
 */

import ca.pfv.spmf.server.ServerConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Prints a startup banner and configuration summary to {@code System.out}
 * when the SPMF-Server is launched in headless (CLI) mode.
 * <p>
 * The banner is written directly to {@code System.out} (not via JUL) so that
 * it appears cleanly on the console regardless of the configured log level or
 * log format — log records at INFO level would be prefixed with timestamps and
 * class names, which would break the visual alignment of the ASCII art.
 * <p>
 * All methods are static; this class cannot be instantiated.
 *
 * @author Philippe Fournier-Viger
 */
public final class ConsoleBanner {

    // ANSI escape codes — used for optional colour output.
    // These are safe on any ANSI-compatible terminal (Linux, macOS, Windows 10+).
    // They are benign (but visible) on terminals that do not support ANSI.
    private static final String ANSI_RESET  = "\u001B[0m";
    private static final String ANSI_BOLD   = "\u001B[1m";
    private static final String ANSI_CYAN   = "\u001B[36m";
    private static final String ANSI_GREEN  = "\u001B[32m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_WHITE  = "\u001B[37m";

    /** Width of the banner box in characters (including the border pipes). */
    private static final int BOX_WIDTH = 62;

    /** Prevent instantiation. */
    private ConsoleBanner() {}

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Print the full startup banner to {@code System.out}.
     * <p>
     * The banner consists of three sections:
     * <ol>
     *   <li>ASCII-art logo for "SPMF Server"</li>
     *   <li>A framed information box showing version, URL, and licence</li>
     *   <li>A configuration summary table (host, port, threads, etc.)</li>
     * </ol>
     *
     * @param version    server version string (e.g. {@code "1.0.0"})
     * @param configPath path to the configuration file that was loaded
     * @param config     active server configuration; must not be {@code null}
     */
    public static void print(String version,
                             String configPath,
                             ServerConfig config) {
        StringBuilder sb = new StringBuilder();

        // Blank line before the banner for visual breathing room
        sb.append('\n');

        // ── Section 1: ASCII-art logo ──────────────────────────────────────
        appendLogo(sb);

        // ── Section 2: Information box ─────────────────────────────────────
        appendInfoBox(sb, version);

        // ── Section 3: Configuration summary ──────────────────────────────
        appendConfigSummary(sb, configPath, config);

        // ── Section 4: Ready line ──────────────────────────────────────────
        sb.append('\n');
        sb.append(ANSI_GREEN).append(ANSI_BOLD)
          .append("  Server is READY — press Ctrl+C to stop.")
          .append(ANSI_RESET).append('\n');
        sb.append('\n');

        // Write in a single call to minimise interleaving with log output
        System.out.print(sb);
        System.out.flush();
    }

    // ── Private helpers ────────────────────────────────────────────────────

    /**
     * Append the multi-line ASCII-art "SPMF Server" logo to the buffer.
     * <p>
     * The logo uses block-letter characters formed from standard ASCII
     * symbols so it renders on any monospace terminal font.
     *
     * @param sb the target string builder
     */
    private static void appendLogo(StringBuilder sb) {

        // Each line of the logo is individually coloured for visual impact.
        // The logo spells "SPMF" on the first four rows and "SERVER" below.
        String c = ANSI_CYAN + ANSI_BOLD;
        String r = ANSI_RESET;

        sb.append(c).append("  ███████╗██████╗ ███╗   ███╗███████╗").append(r).append('\n');
        sb.append(c).append("  ██╔════╝██╔══██╗████╗ ████║██╔════╝").append(r).append('\n');
        sb.append(c).append("  ███████╗██████╔╝██╔████╔██║█████╗  ").append(r).append('\n');
        sb.append(c).append("  ╚════██║██╔═══╝ ██║╚██╔╝██║██╔══╝  ").append(r).append('\n');
        sb.append(c).append("  ███████║██║     ██║ ╚═╝ ██║██║     ").append(r).append('\n');
        sb.append(c).append("  ╚══════╝╚═╝     ╚═╝     ╚═╝╚═╝     ").append(r).append('\n');
        sb.append('\n');

        String y = ANSI_YELLOW + ANSI_BOLD;

        sb.append(y).append("  ███████╗███████╗██████╗ ██╗   ██╗███████╗██████╗ ").append(r).append('\n');
        sb.append(y).append("  ██╔════╝██╔════╝██╔══██╗██║   ██║██╔════╝██╔══██╗").append(r).append('\n');
        sb.append(y).append("  ███████╗█████╗  ██████╔╝██║   ██║█████╗  ██████╔╝").append(r).append('\n');
        sb.append(y).append("  ╚════██║██╔══╝  ██╔══██╗╚██╗ ██╔╝██╔══╝  ██╔══██╗").append(r).append('\n');
        sb.append(y).append("  ███████║███████╗██║  ██║ ╚████╔╝ ███████╗██║  ██║").append(r).append('\n');
        sb.append(y).append("  ╚══════╝╚══════╝╚═╝  ╚═╝  ╚═══╝  ╚══════╝╚═╝  ╚═╝").append(r).append('\n');
        sb.append('\n');
    }

    /**
     * Append a framed box containing version, project URL, and licence text.
     *
     * @param sb      the target string builder
     * @param version server version string
     */
    private static void appendInfoBox(StringBuilder sb, String version) {

        String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String border = ANSI_WHITE + ANSI_BOLD
                + "  +" + "─".repeat(BOX_WIDTH - 4) + "+"
                + ANSI_RESET;

        sb.append(border).append('\n');
        appendBoxLine(sb, "SPMF Server  v" + version,       ANSI_GREEN + ANSI_BOLD);
        appendBoxLine(sb, "http://www.philippe-fournier-viger.com/spmf", ANSI_CYAN);
        appendBoxLine(sb, "Started: " + timestamp,           ANSI_WHITE);
        appendBoxLine(sb, "Licence: GNU General Public License v3",      ANSI_WHITE);
        sb.append(border).append('\n');
        sb.append('\n');
    }

    /**
     * Append a single line inside the information box, padded to align the
     * right border.
     *
     * @param sb    the target string builder
     * @param text  the line content (without border characters)
     * @param color ANSI colour/style escape to apply to the text
     */
    private static void appendBoxLine(StringBuilder sb,
                                      String text,
                                      String color) {
        // Inner width = BOX_WIDTH minus 4 characters (2 for "| " and 2 for " |")
        int innerWidth = BOX_WIDTH - 4;

        // Truncate if the text is somehow wider than the box
        if (text.length() > innerWidth) {
            text = text.substring(0, innerWidth - 3) + "...";
        }

        // Padding to right-align the closing pipe
        int padding = innerWidth - text.length();

        sb.append(ANSI_WHITE).append(ANSI_BOLD).append("  | ").append(ANSI_RESET)
          .append(color).append(text).append(ANSI_RESET)
          .append(" ".repeat(padding))
          .append(ANSI_WHITE).append(ANSI_BOLD).append(" |").append(ANSI_RESET)
          .append('\n');
    }

    /**
     * Append a two-column configuration summary table showing the key
     * server settings that an operator needs to know at a glance.
     *
     * @param sb         the target string builder
     * @param configPath path of the loaded configuration file
     * @param config     active server configuration
     */
    private static void appendConfigSummary(StringBuilder sb,
                                            String configPath,
                                            ServerConfig config) {

        sb.append(ANSI_BOLD).append(ANSI_WHITE)
          .append("  Configuration")
          .append(ANSI_RESET).append('\n');

        appendConfigRow(sb, "Config file",
                configPath);
        appendConfigRow(sb, "Bind address",
                config.getHost() + ":" + config.getPort());
        appendConfigRow(sb, "HTTP endpoint",
                "http://" + resolveDisplayHost(config.getHost())
                + ":" + config.getPort() + "/api/");
        appendConfigRow(sb, "Thread pool",
                "core=" + config.getCoreThreads()
                + "  max=" + config.getMaxThreads());
        appendConfigRow(sb, "Job queue size",
                String.valueOf(config.getMaxQueueSize()));
        appendConfigRow(sb, "Job TTL",
                config.getJobTtlMinutes() + " min");
        appendConfigRow(sb, "Job timeout",
                config.getJobTimeoutMinutes() + " min");
        appendConfigRow(sb, "Max upload size",
                config.getMaxInputSizeMb() + " MB");
        appendConfigRow(sb, "Work directory",
                config.getWorkDir());
        appendConfigRow(sb, "Log level",
                config.getLogLevel());
        appendConfigRow(sb, "Log file",
                config.getLogFile().isBlank() ? "(console only)" : config.getLogFile());
        appendConfigRow(sb, "API key auth",
                config.isApiKeyEnabled() ? "ENABLED" : "disabled");
        sb.append('\n');
    }

    /**
     * Append a single two-column row to the configuration summary.
     * <p>
     * The label column is fixed at 18 characters so that values in the second
     * column are vertically aligned.
     *
     * @param sb    the target string builder
     * @param label the setting name (left column)
     * @param value the setting value (right column)
     */
    private static void appendConfigRow(StringBuilder sb,
                                        String label,
                                        String value) {
        sb.append(ANSI_WHITE)
          .append("    ")
          .append(String.format("%-18s", label))
          .append(ANSI_RESET)
          .append(ANSI_GREEN)
          .append(value)
          .append(ANSI_RESET)
          .append('\n');
    }

    /**
     * Convert a bind address to something useful for display in a URL.
     * <p>
     * When the server binds to {@code 0.0.0.0} (all interfaces) it is more
     * helpful to show {@code localhost} in the example URL, since
     * {@code http://0.0.0.0:8585/} confuses most browsers.
     *
     * @param host configured bind host
     * @return {@code "localhost"} when {@code host} is {@code "0.0.0.0"} or
     *         {@code "::"} (IPv6 wildcard), otherwise {@code host} unchanged
     */
    private static String resolveDisplayHost(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            return "localhost";
        }
        return host;
    }
}