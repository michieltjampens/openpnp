package org.openpnp.machine.reference.driver;

import static org.openpnp.machine.reference.driver.AbstractReferenceDriver.unescape;

public class GCodeTools {

    public static String preProcessCommand(String command, GCodeCommandRules rules) {

        if ( !rules.needsCleaning()) {
            return rules.isBackslashEscapedCharactersEnabled() ? unescape(command) : command;
        }
        // See http://linuxcnc.org/docs/2.4/html/gcode_overview.html
        boolean insideComment = false;
        boolean decimal = false;
        int trailingZeroes = 0;

        StringBuilder compressedCommand = new StringBuilder();
        for (int col = 0; col < command.length();) {
            char ch = command.charAt(col++);

            if (ch == ' ') {
                // Note, in Gcode, spaces are allowed in the middle of decimals.
                if (rules.doCompress()) {
                    continue;
                }
            }else if ( rules.allowCompression(ch) ) {
                trailingZeroes = compressDecimal(trailingZeroes, compressedCommand, rules.doCompress());
                decimal = false;
                // Due to ambiguities in escaping of strings and nesting of brackets, and brackets in strings,
                // just exclude everything from the left-most to the right-most exclude character.
                int pos = col - 1;
                for (char cch : rules.getCompressionExcludesCharArray() ) {
                    if (cch != ' ') { // ignore spaces the user might have added
                        int p = command.lastIndexOf(cch);
                        if (p >= pos) {
                            pos = p;
                        }
                    }
                }
                if (pos < col) {
                    // Matching char missing, just append the rest of the line as is.
                    compressedCommand.append(command.substring(col-1));
                    break;
                }
                else {
                    // Matching bracket/quote found, exclude inner string from compression.
                    compressedCommand.append(command.substring(col-1, pos+1));
                    col = pos+1;
                    continue;
                }
            }else if (ch == '(') {
                trailingZeroes = compressDecimal(trailingZeroes, compressedCommand, rules.doCompress());
                decimal = false;
                insideComment = true;
                if (rules.removeComments()) {
                    continue;
                }
            }else if (ch == ')') {
                insideComment = false;
                if (rules.removeComments()) {
                    continue;
                }
            }else if (insideComment ) {
                if (rules.removeComments()) {
                    continue;
                }
            }else if (ch == ';') {
                trailingZeroes = compressDecimal(trailingZeroes, compressedCommand,rules.doCompress());
                decimal = false;
                if (rules.removeComments()) {
                    break;
                }
                else {
                    // Not removed, append as is.
                    compressedCommand.append(command.substring(col-1));
                    break;
                }
            }else if (ch == '.') {
                decimal = true;
                trailingZeroes = 1; // treat the dot as a trailing zero character
            }else if (ch >= '1' && ch <= '9') {
                trailingZeroes = 0;
            }else if (ch == '0') {
                if (decimal) {
                    trailingZeroes++;
                }
            }else {
                trailingZeroes = compressDecimal(trailingZeroes, compressedCommand,rules.doCompress());
                decimal = false;
            }
            compressedCommand.append(ch);
        }
        compressDecimal(trailingZeroes, compressedCommand,rules.doCompress());
        command = compressedCommand.toString();

        if (rules.isBackslashEscapedCharactersEnabled()) {
            command = unescape(command);
        }
        // Removed logging, doesn't belong here
        return command;
    }
    private static int compressDecimal(int trailingZeroes, StringBuilder compressedCommand, boolean compressGcode) {
        if (compressGcode && trailingZeroes > 0
                && compressedCommand.length() - trailingZeroes > 0) {
            // Has trailing zeroes (or trailing dot).
            // Check if it has at least one digit.
            char ch = compressedCommand.charAt(compressedCommand.length() - trailingZeroes - 1);
            if (ch >= '0' && ch <= '9') {
                // Cut away trailing zeroes.
                compressedCommand.delete(compressedCommand.length() - trailingZeroes, compressedCommand.length());
            }
        }
        return 0;
    }
}
