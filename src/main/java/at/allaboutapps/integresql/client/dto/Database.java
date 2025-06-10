package at.allaboutapps.integresql.client.dto;

public class Database {
    public String templateHash; // Unique hash of the database template
    public DatabaseConfig config; // Configuration details of the database

    public Database() {} // Default constructor for Jackson
}
