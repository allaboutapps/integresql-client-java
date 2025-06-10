package at.allaboutapps.integresql.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class TemplateDatabase {
    @JsonProperty("database")
    public Database database;

    public TemplateDatabase() {}
}
