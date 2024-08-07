package org.phantazm.loader;

import com.github.steanky.ethylene.core.ConfigCodec;
import com.github.steanky.ethylene.core.ConfigElement;
import com.github.steanky.ethylene.core.bridge.Configuration;
import com.github.steanky.ethylene.core.collection.ArrayConfigList;
import com.github.steanky.ethylene.core.collection.ConfigNode;
import com.github.steanky.ethylene.core.collection.LinkedConfigNode;
import com.github.steanky.toolkit.collection.Iterators;
import com.github.steanky.toolkit.function.ExceptionHandler;
import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A source of data. Functionally equivalent to an immutable {@link Iterator} over {@link ConfigElement} objects, but
 * can throw {@link IOException} on calls to {@link DataSource#hasNext()} or {@link DataSource#next()}.
 */
public interface DataSource extends Closeable {
    /**
     * Determines if a new {@link ConfigElement} is available. If possible, this should perform caching to speed up a
     * subsequent call to {@link DataSource#next()}.
     *
     * @return true iff there is an element available (i.e. {@link DataSource#next()} can be called)
     * @throws IOException if an IO error occurs
     */
    boolean hasNext() throws IOException;

    /**
     * Returns the next {@link ConfigElement}.
     *
     * @return the next ConfigElement
     * @throws IOException            nf an IO error occurs
     * @throws NoSuchElementException if this method is called without first invoking {@link DataSource#hasNext()}
     */
    @NotNull ConfigElement next() throws IOException;

    /**
     * The last visited {@link DataLocation}. Useful for adding information to an exception context.
     *
     * @return the last data location
     * @throws IllegalStateException if no call to {@link DataSource#next()} has been made before invoking this method
     */
    @NotNull DataLocation lastLocation();

    enum SourceType {
        LIST,
        SINGLE
    }

    record NamedSource(@NotNull DataSource dataSource,
        @NotNull String name,
        @NotNull SourceType sourceType) {
        public NamedSource {
            Objects.requireNonNull(dataSource);
            Objects.requireNonNull(name);
            Objects.requireNonNull(sourceType);
        }
    }

    static @NotNull <T> DataSource composite(@NotNull Function<@NotNull T, @NotNull DataSource> dataSourceFunction,
        @NotNull Stream<? extends T> stream) {
        Objects.requireNonNull(dataSourceFunction);
        Objects.requireNonNull(stream);
        return new Composite<>(dataSourceFunction, stream);
    }

    static @NotNull NamedSource namedSingle(@NotNull DataSource dataSource, @NotNull String name) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(name);
        return new NamedSource(dataSource, name, SourceType.SINGLE);
    }

    static @NotNull NamedSource namedList(@NotNull DataSource dataSource, @NotNull String name) {
        Objects.requireNonNull(dataSource);
        Objects.requireNonNull(name);
        return new NamedSource(dataSource, name, SourceType.LIST);
    }

    static @NotNull NamedSource namedList(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String name) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(name);
        return new NamedSource(new Directory(root.resolve(name), Integer.MAX_VALUE, codec, null,
            false), name, SourceType.LIST);
    }

    static @NotNull NamedSource namedList(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String fileName,
        @NotNull String configName) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(configName);
        return new NamedSource(new Directory(root.resolve(fileName), Integer.MAX_VALUE, codec, null,
            false), configName, SourceType.LIST);
    }

    static @NotNull NamedSource namedSingle(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String fileName,
        @NotNull String configName) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(configName);
        return new NamedSource(new SingleFile(root.resolve(fileName), codec), configName, SourceType.SINGLE);
    }

    static @NotNull NamedSource optionalNamedSingle(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String fileName,
        @NotNull String configName, @NotNull ConfigElement defaultElement) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(configName);
        return new NamedSource(new OptionalSingleFile(root.resolve(fileName), codec, defaultElement), configName, SourceType.SINGLE);
    }

    static @NotNull DataSource singleFile(@NotNull Path file, @NotNull ConfigCodec codec) {
        Objects.requireNonNull(file);
        Objects.requireNonNull(codec);
        return new SingleFile(file, codec);
    }

    /**
     * Creates a new {@link DataSource} that stitches any number of {@link NamedSource}s together. The resulting
     * DataSource will only emit a single {@link ConfigElement} created from merging the sources.
     *
     * @param sources the sources array
     * @return a new DataSource
     */
    static @NotNull DataSource merged(@NotNull NamedSource @NotNull ... sources) {
        NamedSource[] sourcesCopy = Arrays.copyOf(sources, sources.length);

        Set<String> names = new HashSet<>(sourcesCopy.length);
        for (NamedSource source : sourcesCopy) {
            Objects.requireNonNull(source);
            if (!names.add(source.name)) {
                throw new IllegalArgumentException("Source with duplicate name");
            }
        }

        return new Merged(sourcesCopy);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        return new Directory(root, Integer.MAX_VALUE, codec, null, false);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String pathMatcher) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, Integer.MAX_VALUE, codec, root.getFileSystem().getPathMatcher(pathMatcher), false);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec, @NotNull String pathMatcher,
        boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, Integer.MAX_VALUE, codec, root.getFileSystem().getPathMatcher(pathMatcher), symlink);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec, boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        return new Directory(root, Integer.MAX_VALUE, codec, null, symlink);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        return new Directory(root, depth, codec, null, false);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec,
        @NotNull String pathMatcher) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, depth, codec, root.getFileSystem().getPathMatcher(pathMatcher), false);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec,
        @NotNull String pathMatcher, boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, depth, codec, root.getFileSystem().getPathMatcher(pathMatcher), symlink);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec, boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        return new Directory(root, depth, codec, null, symlink);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec,
        @NotNull PathMatcher pathMatcher) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, Integer.MAX_VALUE, codec, pathMatcher, false);
    }

    static @NotNull DataSource directory(@NotNull Path root, @NotNull ConfigCodec codec,
        @NotNull PathMatcher pathMatcher,
        boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, Integer.MAX_VALUE, codec, pathMatcher, symlink);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec,
        @NotNull PathMatcher pathMatcher) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, depth, codec, pathMatcher, false);
    }

    static @NotNull DataSource directory(@NotNull Path root, int depth, @NotNull ConfigCodec codec,
        @NotNull PathMatcher pathMatcher, boolean symlink) {
        Objects.requireNonNull(root);
        Objects.requireNonNull(codec);
        Objects.requireNonNull(pathMatcher);
        return new Directory(root, depth, codec, pathMatcher, symlink);
    }

    abstract class DataSourceAbstract implements DataSource {
        protected boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }

        protected void validateOpen() throws LoaderException {
            if (closed) throw LoaderException.builder()
                .withMessage("this resource has been closed")
                .build();
        }

        protected static IllegalStateException missingNextBeforeLastLocation() {
            return new IllegalStateException("did not call next() before lastLocation()");
        }
    }

    abstract class FilesystemDataSource extends DataSourceAbstract {
        protected final Path path;
        private final ConfigCodec codec;

        FilesystemDataSource(Path path, ConfigCodec codec) {
            this.path = path;
            this.codec = codec;
        }

        protected ConfigElement load(Path path) throws LoaderException {
            try {
                return Configuration.read(path, codec);
            } catch (IOException e) {
                throw LoaderException.builder()
                    .withCause(e)
                    .withMessage("failed to load data from file")
                    .withDataLocation(DataLocation.path(path))
                    .build();
            }
        }
    }

    class Composite<T> extends DataSourceAbstract {
        private final Function<@NotNull T, @NotNull DataSource> function;
        private final Stream<? extends T> stream;

        private Iterator<? extends T> iterator;

        private DataSource currentSource;
        private DataLocation lastLocation;

        private Composite(Function<@NotNull T, @NotNull DataSource> function, Stream<? extends T> stream) {
            this.function = function;
            this.stream = stream;
        }

        private Iterator<? extends T> iterator() {
            Iterator<? extends T> current = iterator;
            if (current != null) {
                return current;
            }

            Iterator<? extends T> newIterator = stream.iterator();
            iterator = newIterator;
            return newIterator;
        }

        private DataSource updateCurrentSource(DataSource newSource) throws IOException {
            DataSource oldSource = currentSource;
            if (oldSource == newSource) {
                return newSource;
            }

            currentSource = newSource;

            if (oldSource != null) {
                oldSource.close();
            }

            return newSource;
        }

        private void advanceSourceUntilHasNext() throws IOException {
            DataSource current = currentSource;
            while (current == null || !current.hasNext()) {
                if (!iterator().hasNext()) {
                    updateCurrentSource(null);
                    return;
                }

                current = updateCurrentSource(Objects.requireNonNull(function.apply(Objects.requireNonNull(iterator.next(),
                    "iterator return value")), "DataSourceFunction return value"));
            }

            updateCurrentSource(current);
        }

        @Override
        public boolean hasNext() throws IOException {
            validateOpen();
            advanceSourceUntilHasNext();
            return currentSource != null;
        }

        @Override
        public @NotNull ConfigElement next() throws IOException {
            validateOpen();
            advanceSourceUntilHasNext();
            DataSource current = currentSource;
            if (current == null) {
                throw new NoSuchElementException();
            }

            ConfigElement next = current.next();
            lastLocation = current.lastLocation();
            return next;
        }

        @Override
        public @NotNull DataLocation lastLocation() {
            DataLocation location = lastLocation;
            if (location == null) {
                throw missingNextBeforeLastLocation();
            }

            return location;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            super.close();
            DataSource current = currentSource;
            if (current != null) {
                current.close();
            }

            stream.close();
        }
    }

    class Merged extends DataSourceAbstract {
        private final NamedSource[] sources;

        private boolean iterated;
        private DataLocation lastLocation;

        private Merged(NamedSource... sources) {
            this.sources = sources;
            if (sources.length == 0) {
                iterated = true;
            }

            lastLocation = DataLocation.UNKNOWN;
        }

        @Override
        public boolean hasNext() throws IOException {
            validateOpen();
            if (iterated) {
                return false;
            }

            for (NamedSource source : sources) {
                if (source.dataSource.hasNext()) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public @NotNull ConfigElement next() throws IOException {
            validateOpen();
            if (iterated) {
                throw new NoSuchElementException();
            }

            ConfigNode root = new LinkedConfigNode(sources.length);
            for (NamedSource namedSource : sources) {
                DataSource dataSource = namedSource.dataSource;

                switch (namedSource.sourceType) {
                    case LIST -> {
                        ArrayConfigList child = new ArrayConfigList();
                        root.put(namedSource.name, child);

                        while (dataSource.hasNext()) {
                            child.add(dataSource.next());
                            lastLocation = dataSource.lastLocation();
                        }

                        child.trimToSize();
                    }
                    case SINGLE -> {
                        if (dataSource.hasNext()) {
                            root.put(namedSource.name, dataSource.next());
                            lastLocation = dataSource.lastLocation();
                        }
                    }
                }
            }

            iterated = true;
            return root;
        }

        @Override
        public @NotNull DataLocation lastLocation() {
            if (!iterated) {
                throw missingNextBeforeLastLocation();
            }

            return lastLocation;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            super.close();

            try (ExceptionHandler<IOException> handler = new ExceptionHandler<>(IOException.class)) {
                for (NamedSource source : sources) {
                    handler.run(source.dataSource::close);
                }
            }
        }
    }

    class SingleFile extends FilesystemDataSource {
        private boolean iterated;

        SingleFile(Path path, ConfigCodec codec) {
            super(path, codec);
        }

        @Override
        public boolean hasNext() throws IOException {
            validateOpen();
            return !iterated;
        }

        @Override
        public @NotNull ConfigElement next() throws IOException {
            validateOpen();
            if (iterated) {
                throw new NoSuchElementException();
            }

            iterated = true;
            return load(path);
        }

        @Override
        public @NotNull DataLocation lastLocation() {
            if (!iterated) {
                throw missingNextBeforeLastLocation();
            }

            return DataLocation.path(path);
        }
    }

    class OptionalSingleFile extends SingleFile {
        private final ConfigElement defaultElement;

        OptionalSingleFile(Path path, ConfigCodec codec, ConfigElement defaultElement) {
            super(path, codec);

            this.defaultElement = defaultElement;
        }

        @Override
        public @NotNull ConfigElement next() throws IOException {
            try {
                return super.next();
            } catch (LoaderException exception) {
                if (exception.getCause() instanceof NoSuchFileException) {
                    return defaultElement;
                }

                throw exception;
            }
        }
    }

    class Directory extends FilesystemDataSource {
        private static final FileVisitOption[] VISIT_SYMLINKS = new FileVisitOption[]{FileVisitOption.FOLLOW_LINKS};
        private static final FileVisitOption[] NO_VISIT_SYMLINKS = new FileVisitOption[0];

        private static final LinkOption[] SYMLINK = new LinkOption[0];
        private static final LinkOption[] NO_SYMLINK = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

        private final int depth;
        private final PathMatcher pathMatcher;
        private final boolean symlink;

        private Stream<Path> stream;
        private Iterator<Path> iterator;

        private Path cache;
        private Path last;

        private Directory(Path path, int depth, ConfigCodec codec, PathMatcher pathMatcher, boolean symlink) {
            super(path, codec);
            this.depth = depth;
            this.pathMatcher = pathMatcher;
            this.symlink = symlink;
        }

        private Iterator<Path> getIterator() throws LoaderException {
            if (iterator != null) {
                return iterator;
            }

            if (!Files.exists(path, symlink ? SYMLINK : NO_SYMLINK)) {
                return iterator = Iterators.iterator();
            }

            try {
                return iterator = (stream = Files.walk(path, depth, symlink ? VISIT_SYMLINKS : NO_VISIT_SYMLINKS)).iterator();
            } catch (IOException e) {
                throw LoaderException.builder()
                    .withCause(e)
                    .withMessage("failed to initialize data stream")
                    .build();
            }
        }

        private boolean hasNext0() throws LoaderException {
            Iterator<Path> itr = getIterator();
            try {
                return itr.hasNext();
            } catch (UncheckedIOException e) {
                throw LoaderException.builder()
                    .withCause(e)
                    .withMessage("failed to access file")
                    .build();
            }
        }

        private Path next0() throws LoaderException {
            Iterator<Path> itr = getIterator();
            try {
                return itr.next();
            } catch (UncheckedIOException e) {
                throw LoaderException.builder()
                    .withCause(e)
                    .withMessage("failed to access file")
                    .build();
            }
        }

        @Override
        public boolean hasNext() throws LoaderException {
            validateOpen();
            if (cache != null) {
                return true;
            }

            while (hasNext0()) {
                Path check = next0();
                if ((pathMatcher == null || pathMatcher.matches(check)) &&
                    Files.isRegularFile(check, symlink ? SYMLINK : NO_SYMLINK)) {
                    cache = check;
                    return true;
                }
            }

            return false;
        }

        @Override
        public @NotNull ConfigElement next() throws LoaderException {
            validateOpen();
            Path cache = this.cache;
            if (cache != null) {
                this.cache = null;
                this.last = cache;
                return load(cache);
            }

            Path path;
            do {
                path = next0();
            } while ((pathMatcher != null && !pathMatcher.matches(path)) ||
                !Files.isRegularFile(path, symlink ? SYMLINK : NO_SYMLINK));

            this.last = path;
            return load(path);
        }

        @Override
        public @NotNull DataLocation lastLocation() {
            Path last = this.last;
            if (last == null) {
                throw missingNextBeforeLastLocation();
            }

            return DataLocation.path(last);
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }

            super.close();

            Stream<Path> stream = this.stream;
            if (stream != null) {
                this.stream = null;
                this.iterator = null;
                this.cache = null;
                this.last = null;

                stream.close();
            }
        }
    }
}
