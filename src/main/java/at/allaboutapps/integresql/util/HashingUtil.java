package at.allaboutapps.integresql.util;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Utility class for generating MD5 hashes of files and directories.
 */
public final class HashingUtil {

    private static final String HASH_ALGORITHM = "MD5";
    // Used for converting byte arrays to hexadecimal strings
    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private HashingUtil() {
    }

    /**
     * Represents the result of hashing a single file.
     */
    private static class FileHashResult {
        final Path path;
        final byte[] hash; // MD5 hash bytes
        final Exception error; // Store any exception during processing

        FileHashResult(Path path, byte[] hash, Exception error) {
            this.path = path;
            this.hash = hash;
            this.error = error;
        }
    }

    /**
     * Calculates the MD5 hash for every regular file within a directory tree recursively and concurrently.
     *
     * @param rootDir The root directory to traverse.
     * @return A map where keys are the relative paths of files and values are their MD5 hash bytes.
     * @throws IOException              If an I/O error occurs during directory traversal or file reading.
     * @throws NoSuchAlgorithmException If the MD5 algorithm is not available.
     * @throws RuntimeException         If any concurrent task fails unexpectedly.
     */
    private static Map<Path, byte[]> md5All(Path rootDir) throws IOException, NoSuchAlgorithmException {
        // Use a fixed thread pool for CPU-bound hashing tasks after IO
        // Adjust pool size based on performance testing, available cores is a reasonable start.
        int poolSize = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(poolSize);
        Map<Path, byte[]> results = new ConcurrentHashMap<>(); // Thread-safe map
        List<Future<FileHashResult>> futures = new ArrayList<>();

        // Use try-with-resources for Files.walk to ensure it's closed
        try (Stream<Path> pathStream = Files.walk(rootDir)) {
            pathStream
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        Callable<FileHashResult> task = () -> {
                            try {
                                byte[] fileBytes = Files.readAllBytes(path);
                                MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
                                byte[] hashBytes = md.digest(fileBytes);
                                Path relativePath = rootDir.relativize(path);
                                return new FileHashResult(relativePath, hashBytes, null);
                            } catch (IOException | NoSuchAlgorithmException | UncheckedIOException e) {
                                // Capture exceptions to handle them after joining futures
                                Path relativePath = rootDir.relativize(path);
                                return new FileHashResult(relativePath, null, e);
                            } catch (Exception e) {
                                // Catch unexpected errors
                                Path relativePath = rootDir.relativize(path);
                                return new FileHashResult(relativePath, null, new RuntimeException("Hashing failed for " + path, e));
                            }
                        };
                        futures.add(executor.submit(task));
                    });

        } catch (UncheckedIOException e) {
            // Files.walk throws UncheckedIOException for IO errors during stream creation/traversal
            throw e.getCause();
        } finally {
            // Always shutdown executor
            executor.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                        System.err.println("Executor did not terminate");
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                executor.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }


        // Process results and handle errors
        Exception firstError = null;
        for (Future<FileHashResult> future : futures) {
            try {
                FileHashResult result = future.get(); // Wait for task completion
                if (result.error != null) {
                    // Store the first error encountered
                    if (firstError == null) {
                        firstError = result.error;
                    }
                    System.err.println("Error processing file " + rootDir.resolve(result.path) + ": " + result.error.getMessage());
                } else if (result.hash != null){
                    results.put(result.path, result.hash);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                firstError = firstError == null ? e : firstError; // Store error
                System.err.println("Hashing task interrupted.");
            } catch (ExecutionException e) {
                firstError = firstError == null ? e : firstError; // Store error
                System.err.println("Hashing task failed: " + e.getCause());
            }
        }

        // If any error occurred during file processing, rethrow the first one
        if (firstError != null) {
            if (firstError instanceof IOException) throw (IOException) firstError;
            if (firstError instanceof NoSuchAlgorithmException) throw (NoSuchAlgorithmException) firstError;
            if (firstError instanceof InterruptedException) throw new RuntimeException("Hashing interrupted", firstError);
            if (firstError instanceof ExecutionException) throw new RuntimeException("Hashing execution failed", firstError.getCause());
            throw new RuntimeException("Hashing failed", firstError);
        }

        return results;
    }

    /**
     * Calculates a stable MD5 hash for a directory.
     * The hash is derived from the MD5 hashes of all files within the directory (recursively).
     * File hashes are calculated, paths are sorted, and the hex representations of the hashes
     * are concatenated and hashed again.
     *
     * @param dirPath The path to the directory.
     * @return The MD5 hash of the directory contents as a hex string.
     * @throws IOException              If an I/O error occurs.
     * @throws NoSuchAlgorithmException If the MD5 algorithm is not available.
     * @throws IllegalArgumentException if the provided path is not a directory.
     */
    public static String getDirectoryHash(Path dirPath) throws IOException, NoSuchAlgorithmException {
        if (!Files.isDirectory(dirPath)) {
            throw new IllegalArgumentException("Path is not a directory: " + dirPath);
        }

        // 1. Get hashes of all files concurrently
        Map<Path, byte[]> fileHashes = md5All(dirPath);

        // 2. Get sorted list of relative file paths
        List<Path> sortedPaths = fileHashes.keySet().stream()
                .sorted()
                .toList();

        // 3. Create the final hash from the sorted list of individual hashes
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        for (Path path : sortedPaths) {
            byte[] fileHashBytes = fileHashes.get(path);
            // Convert individual file hash bytes to hex string
            String fileHashHex = bytesToHex(fileHashBytes);
            // Update the directory hash with the bytes of the hex string
            md.update(fileHashHex.getBytes(StandardCharsets.UTF_8));
        }

        // 4. Finalize and convert directory hash to hex string
        return bytesToHex(md.digest());
    }

    /**
     * Calculates the MD5 hash of a single file's content.
     *
     * @param filePath The path to the file.
     * @return The MD5 hash of the file content as a hex string.
     * @throws IOException              If an I/O error occurs reading the file.
     * @throws NoSuchAlgorithmException If the MD5 algorithm is not available.
     * @throws IllegalArgumentException if the provided path is not a regular file.
     */
    public static String getFileHash(Path filePath) throws IOException, NoSuchAlgorithmException {
        if (!Files.isRegularFile(filePath)) {
            throw new IllegalArgumentException("Path is not a regular file: " + filePath);
        }
        byte[] data = Files.readAllBytes(filePath);
        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
        return bytesToHex(md.digest(data));
    }

    /**
     * Calculates a template hash based on one or more file or directory paths.
     * Hashes each path individually (using getFileHash or getDirectoryHash)
     * and then creates a final hash from the concatenated individual hash strings.
     *
     * @param paths Varargs of Path objects representing files or directories.
     * @return The final MD5 template hash as a hex string.
     * @throws IOException              If an I/O error occurs.
     * @throws NoSuchAlgorithmException If the MD5 algorithm is not available.
     * @throws IllegalArgumentException If any path is invalid or not a file/directory.
     */
    public static String getTemplateHash(Path... paths) throws IOException, NoSuchAlgorithmException {
        if (paths == null || paths.length == 0) {
            throw new IllegalArgumentException("At least one path must be provided.");
        }

        MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);

        for (Path p : paths) {
            String singleHash;
            if (Files.isDirectory(p)) {
                singleHash = getDirectoryHash(p);
            } else if (Files.isRegularFile(p)) {
                singleHash = getFileHash(p);
            } else {
                if (!Files.exists(p)) {
                    throw new NoSuchFileException("Path does not exist: " + p);
                } else {
                    throw new IllegalArgumentException("Path is neither a file nor a directory: " + p);
                }
            }
            // Update the final hash with the bytes of the individual hash string
            md.update(singleHash.getBytes(StandardCharsets.UTF_8));
        }

        return bytesToHex(md.digest());
    }

    /**
     * Converts a byte array into a lowercase hexadecimal string.
     *
     * @param bytes The byte array to convert.
     * @return The hexadecimal string representation.
     */
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}