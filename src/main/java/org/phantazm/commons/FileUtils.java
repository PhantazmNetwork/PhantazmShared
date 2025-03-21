package org.phantazm.commons;

import org.jetbrains.annotations.NotNull;
import com.github.steanky.toolkit.function.ExceptionHandler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Contains utility methods related to file IO.
 */
public final class FileUtils {
    private FileUtils() {
        throw new UnsupportedOperationException();
    }

    /**
     * Searches the given root path for a file that matches the predicate. If at least one file matches the predicate,
     * its path is returned. Otherwise, an IOException is thrown, whose message is derived from {@code messageSupplier}.
     * The root directory is not searched recursively; only the top level is enumerated.
     *
     * @param root            the root path to search
     * @param predicate       the predicate used to identify the file we're looking for
     * @param messageSupplier the supplier used to construct an error message if the file cannot be found
     * @return the first file which matches the given predicate
     * @throws IOException if an IO error occurs, or if no files in root match the predicate
     */
    public static @NotNull Path findFirstOrThrow(@NotNull Path root,
        @NotNull BiPredicate<Path, BasicFileAttributes> predicate, @NotNull Supplier<String> messageSupplier)
        throws IOException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(predicate);
        Objects.requireNonNull(messageSupplier);

        Path target;
        try (Stream<Path> pathStream = Files.find(root, 1, predicate, FileVisitOption.FOLLOW_LINKS)) {
            target = pathStream.findFirst().orElseThrow(() -> new IOException(messageSupplier.get()));
        }

        return target;
    }

    /**
     * Enumerates every file in the top level of the root directory that matches the given predicate, calling
     * {@code consumer} with each path.
     *
     * @param root      the root file to search
     * @param predicate the predicate to determine which files to iterate
     * @param consumer  the consumer which is called with each matching path
     * @throws IOException if an IO error occurs
     */
    public static void forEachFileMatching(@NotNull Path root,
        @NotNull BiPredicate<Path, BasicFileAttributes> predicate, @NotNull IOConsumer<? super Path> consumer)
        throws IOException {
        Objects.requireNonNull(root);
        Objects.requireNonNull(predicate);
        Objects.requireNonNull(consumer);

        if (!Files.exists(root)) {
            return;
        }

        try (Stream<Path> stream = Files.find(root, 1, predicate, FileVisitOption.FOLLOW_LINKS)) {
            for (Path path : (Iterable<? extends Path>) (stream::iterator)) {
                consumer.accept(path);
            }
        }
    }

    public static void createFileIfNotExists(@NotNull Path file) throws IOException {
        try {
            Files.createFile(file);
        } catch (FileAlreadyExistsException ignored) {
        }
    }

    public static void tryDelete(@NotNull Path file) {
        try {
            Files.delete(file);
        } catch (IOException ignored) {
        }
    }

    /**
     * Recursively deletes all files in the given directory, if it exists.
     *
     * @param directory the directory to recursively delete files in
     * @throws IOException if an IO error occurs
     */
    public static void deleteRecursivelyIfExists(@NotNull Path directory) throws IOException {
        Objects.requireNonNull(directory);

        if (!Files.isDirectory(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                FileUtils.tryDelete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                FileUtils.tryDelete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Attempts to create the directories specified in the path array. Any thrown {@link FileAlreadyExistsException}s
     * are ignored. Any other kinds of {@link IOException} are rethrown as an {@link UncheckedIOException} after exactly
     * one attempt is made to create any necessary directories for a given array element.
     *
     * @param paths the paths array
     */
    public static void ensureDirectories(@NotNull Path... paths) {
        try (ExceptionHandler<IOException> handler = new ExceptionHandler<>(IOException.class)) {
            for (Path path : paths) {
                handler.run(() -> {
                    try {
                        Files.createDirectories(path);
                    } catch (FileAlreadyExistsException ignored) {
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A {@link Consumer}-like interface that can throw an {@link IOException}.
     *
     * @param <TType> the type of object to accept
     */
    @FunctionalInterface
    public interface IOConsumer<TType> {
        /**
         * Accepts the given object.
         *
         * @param object the object to accept
         * @throws IOException if an IOException occurs
         */
        void accept(TType object) throws IOException;
    }
}
