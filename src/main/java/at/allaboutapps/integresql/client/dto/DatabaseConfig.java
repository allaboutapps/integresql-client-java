package at.allaboutapps.integresql.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseConfig {
    @JsonProperty("host")
    public String host;

    @JsonProperty("port")
    public int port;

    @JsonProperty("username")
    public String username;

    @JsonProperty("password")
    public String password;

    @JsonProperty("database")
    public String database;

    @JsonProperty()
    public HashMap<String, String> additionalParams;

    // Default constructor (needed for Jackson)
    public DatabaseConfig() {
    }

    /**
     * Generates a standard PostgreSQL JDBC connection string.
     * Example: jdbc:postgresql://host:port/database?param1=value1&amp;param2=value2
     *
     * @return The JDBC connection string.
     */
    public String connectionString() {
        StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://");
        jdbcUrl.append(host).append(":").append(port).append("/").append(database);

        if (additionalParams != null && !additionalParams.isEmpty()) {
            jdbcUrl.append("?");
            additionalParams.forEach((key, value) -> jdbcUrl.append(key).append("=").append(value).append("&"));
            // Remove the last '&'
            jdbcUrl.setLength(jdbcUrl.length() - 1);
        }

        return jdbcUrl.toString();
    }

    @Override
    public String toString() {
        return "DatabaseConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", username='" + username + '\'' +
                // masking password for security
                ", password='****'" +
                ", database='" + database + '\'' +
                ", additionalParams=" + additionalParams +
                '}';
    }
}