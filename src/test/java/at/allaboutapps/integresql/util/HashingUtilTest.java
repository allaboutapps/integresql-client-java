package at.allaboutapps.integresql.util;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir; // JUnit 5 temporary directory support

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

// Import static methods for AssertJ fluent assertions (preferred)
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// Or use standard JUnit assertions
// import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the HashingUtil class.
 */
class HashingUtilTest {

    // JUnit 5 injects a temporary directory path before each test.
    // This directory is automatically created and cleaned up.
    @TempDir
    Path tempDir;

    // Helper class for test file data
    private static class TestFile {
        final String name;
        final String content;

        TestFile(String name, String content) {
            this.name = name;
            this.content = content;
        }
    }

    // Helper method to create the standard set of test files in the temp directory
    private void createTestFiles(Path directory) throws IOException {
        List<TestFile> filesToCreate = Arrays.asList(
                new TestFile("1.txt", "hello there"),
                new TestFile("2.sql", "SELECT 1;"),
                new TestFile("3.txt", "general kenobi")
        );

        for (TestFile f : filesToCreate) {
            Path filePath = directory.resolve(f.name);
            Files.writeString(filePath, f.content, StandardCharsets.UTF_8);
            // Note: Java's Files API doesn't directly mirror POSIX permissions like 0644 easily
            // across different operating systems in a simple way. For hashing, permissions
            // don't matter, only content.
        }
    }

    @Test
    @DisplayName("Should calculate correct MD5 hash for a directory")
    void testDirectoryHash() throws IOException, NoSuchAlgorithmException {
        // Setup: Create files in the temp directory provided by @TempDir
        createTestFiles(tempDir);

        // Expected hash from the Go test
        String expectedHash = "3c01b387636699191fdd281c78fcce8d";

        // Execute & Assert
        // Using AssertJ for fluent assertions and checking for exceptions
        assertThatCode(() -> {
            String actualHash = HashingUtil.getDirectoryHash(tempDir);
            assertThat(actualHash)
                    .as("Directory hash should match the expected value")
                    .isEqualTo(expectedHash);
        }).doesNotThrowAnyException(); // Verify no exceptions were thrown

        /*
        // Standard JUnit Assertion version:
        String actualHash = assertDoesNotThrow(() -> HashingUtil.getDirectoryHash(tempDir),
             "HashingUtil.getDirectoryHash should not throw an exception");
        assertEquals(expectedHash, actualHash, "Directory hash should match the expected value");
        */
    }

    @Test
    @DisplayName("Should calculate correct MD5 hash for a single file")
    void testFileHash() throws IOException, NoSuchAlgorithmException {
        // Setup
        createTestFiles(tempDir);
        Path targetFile = tempDir.resolve("2.sql");

        // Expected hash from the Go test
        String expectedHash = "71568061b2970a4b7c5160fe75356e10";

        // Execute & Assert (using AssertJ)
        assertThatCode(() -> {
            String actualHash = HashingUtil.getFileHash(targetFile);
            assertThat(actualHash)
                    .as("File hash for 2.sql should match the expected value")
                    .isEqualTo(expectedHash);
        }).doesNotThrowAnyException();

        /*
        // Standard JUnit Assertion version:
        String actualHash = assertDoesNotThrow(() -> HashingUtil.getFileHash(targetFile),
            "HashingUtil.getFileHash should not throw an exception");
        assertEquals(expectedHash, actualHash, "File hash for 2.sql should match the expected value");
        */
    }

    @Test
    @DisplayName("Should calculate correct MD5 template hash for directory and file")
    void testTemplateHash() throws IOException, NoSuchAlgorithmException {
        // Setup
        createTestFiles(tempDir);
        Path fileInput = tempDir.resolve("2.sql");

        // Expected hash from the Go test
        String expectedHash = "addb861f5dc3ea8f45908a8b3cf7f969";

        // Execute & Assert (using AssertJ)
        assertThatCode(() -> {
            // Pass the directory and the specific file path as varargs
            String actualHash = HashingUtil.getTemplateHash(tempDir, fileInput);
            assertThat(actualHash)
                    .as("Template hash for the directory and 2.sql should match the expected value")
                    .isEqualTo(expectedHash);
        }).doesNotThrowAnyException();

        /*
        // Standard JUnit Assertion version:
        String actualHash = assertDoesNotThrow(() -> HashingUtil.getTemplateHash(tempDir, fileInput),
            "HashingUtil.getTemplateHash should not throw an exception");
        assertEquals(expectedHash, actualHash, "Template hash for the directory and 2.sql should match the expected value");
        */
    }

    // Add more tests for edge cases if desired:
    // - Empty directory
    // - Empty file
    // - Non-existent path
    // - Path that is neither file nor directory

}