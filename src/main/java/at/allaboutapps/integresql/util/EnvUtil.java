package at.allaboutapps.integresql.util;

/**
 * Utility class for retrieving environment variables with default values and
 * type conversion.
 */
public final class EnvUtil {

    // Private constructor to prevent instantiation
    private EnvUtil() {
    }

    /**
     * Retrieves the value of an environment variable.
     * If the environment variable is not set, the default value is returned.
     * Note: Unlike some interpretations, this returns the value even if it's an
     * empty string,
     * only defaulting if the variable is completely unset.
     *
     * @param key        The name of the environment variable.
     * @param defaultVal The default value to return if the variable is not set.
     * @return The value of the environment variable or the default value.
     */
    public static String getEnv(String key, String defaultVal) {
        String value = System.getenv(key);
        return value != null ? value : defaultVal;
    }

    /**
     * Retrieves the value of an environment variable and parses it as an integer.
     * If the variable is not set or cannot be parsed as an integer, the default
     * value is returned.
     *
     * @param key        The name of the environment variable.
     * @param defaultVal The default integer value.
     * @return The integer value of the environment variable or the default value.
     */
    public static int getEnvAsInt(String key, int defaultVal) {
        String strVal = getEnv(key, null); // Pass null default to distinguish unset from unparseable

        if (strVal != null) {
            try {
                return Integer.parseInt(strVal.trim()); // Use trim() to handle surrounding whitespace
            } catch (NumberFormatException e) {
                System.err.println("WARN: Failed to parse env var '" + key + "' as int: " + e.getMessage());
                // Parsing failed, fall through to return defaultVal
            }
        }
        // Variable was not set or parsing failed
        return defaultVal;
    }

    /**
     * Retrieves the value of an environment variable and parses it as a boolean.
     * This method accepts specific string values to determine the boolean value:
     * "1", "t", "T", "true", "TRUE", "True" as true, and
     * "0", "f", "F", "false", "FALSE", "False" as false.
     * Any other value (or if the variable is unset) results in the default value
     * being returned.
     *
     * @param key        The name of the environment variable.
     * @param defaultVal The default boolean value.
     * @return The boolean value of the environment variable or the default value.
     */
    public static boolean getEnvAsBool(String key, boolean defaultVal) {
        String strVal = getEnv(key, null); // Pass null default to check if set

        if (strVal != null) {
            String lowerVal = strVal.trim().toLowerCase();
            switch (lowerVal) {
                case "1":
                case "t":
                case "true":
                    return true;
                case "0":
                case "f":
                case "false":
                    return false;
                default:
                    // Log the parsing error if needed
                    System.err.println("WARN: Failed to parse env var '" + key + "' as bool (value: '" + strVal + "')");
                    break; // Explicit break, though falling through works too
            }
        }
        // Variable was not set or parsing failed
        return defaultVal;
    }

}