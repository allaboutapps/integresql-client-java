package at.allaboutapps.integresql.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TestDatabase {
    @JsonProperty("id")
    public Integer id;

    @JsonProperty("database")
    public Database database;

    public TestDatabase() {}
}