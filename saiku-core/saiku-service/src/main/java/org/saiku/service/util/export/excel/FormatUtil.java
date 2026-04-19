/*
 *   Copyright 2014 OSBI Ltd
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.saiku.service.util.export.excel;

import java.util.HashMap;
import java.util.Map;

/**
 * Format Utilities
 */
public class FormatUtil {

    private static final Map<String, MacroToken> MAP = new HashMap<>();

    static {
        for (MacroToken macroToken : MacroToken.values()) {
            MAP.put(macroToken.token, macroToken);
        }
    }

    public static String getFormatString(String formatString) {
        final MacroToken macroToken = MAP.get(formatString);
        if (macroToken == null) {
            return formatString;
        }
        return macroToken.translation;
    }

    private enum MacroToken {
        CURRENCY(
                "Currency",
                null,
                "Shows currency values according to the locale's CurrencyFormat.  "
                        + "Negative numbers are inside parentheses."),
        FIXED("Fixed", "0", "Shows at least one digit."),
        STANDARD("Standard", "#,##0", "Uses a thousands separator."),
        PERCENT("Percent", "0.00%", "Multiplies the value by 100 with a percent sign at the end."),
        SCIENTIFIC("Scientific", "0.00e+00", "Uses standard scientific notation."),
        LONG_DATE(
                "Long Date",
                "dddd, mmmm dd, yyyy",
                "Uses the Long Date format specified in the Regional Settings "
                        + "dialog box of the Microsoft Windows Control Panel."),
        MEDIUM_DATE("Medium Date", "dd-mmm-yy", "Uses the dd-mmm-yy format (for example, 03-Apr-93)"),
        SHORT_DATE(
                "Short Date",
                "m/d/yy",
                "Uses the Short Date format specified in the Regional Settings "
                        + "dialog box of the Windows Control Panel."),
        LONG_TIME(
                "Long Time",
                "h:mm:ss AM/PM",
                "Shows the hour, minute, second, and \"AM\" or \"PM\" using the " + "h:mm:ss format."),
        MEDIUM_TIME(
                "Medium Time",
                "h:mm AM/PM",
                "Shows the hour, minute, and \"AM\" or \"PM\" using the \"hh:mm " + "AM/PM\" format."),
        SHORT_TIME("Short Time", "hh:mm", "Shows the hour and minute using the hh:mm format."),
        YES_NO(
                "Yes/No",
                "\\Y\\e\\s;\\Y\\e\\s;\\N\\o;\\N\\o",
                "Any nonzero numeric value (usually - 1) is Yes. Zero is No."),
        TRUE_FALSE(
                "True/False",
                "\\T\\r\\u\\e;\\T\\r\\u\\e;\\F\\a\\l\\s\\e;\\F\\a\\l\\s\\e",
                "Any nonzero numeric value (usually - 1) is True. Zero is False."),
        ON_OFF(
                "On/Off",
                "\\O\\n;\\O\\n;\\O\\f\\f;\\O\\f\\f",
                "Any nonzero numeric value (usually - 1) is On. Zero is Off.");

        /**
         * Maps macro token names with their related object. Used to fast-resolve a macro token without iterating.
         */
        MacroToken(String token, String translation, String description) {
            this.token = token;
            this.translation = translation;
            this.description = description;
            assert name().equals(token.replace(',', '_')
                    .replace(' ', '_')
                    .replace('/', '_')
                    .toUpperCase());
            assert (translation == null) == name().equals("CURRENCY");
        }

        final String token;
        final String translation;
        final String description;
    }
}
