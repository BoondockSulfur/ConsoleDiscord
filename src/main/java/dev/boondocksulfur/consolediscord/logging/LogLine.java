package dev.boondocksulfur.consolediscord.logging;

import org.apache.logging.log4j.Level;

/**
 * One console line with the level of its log event.
 *
 * @param level The log level (taken from the event, not parsed from the text)
 * @param text The formatted console line incl. time and thread
 */
public record LogLine(Level level, String text) {
}
