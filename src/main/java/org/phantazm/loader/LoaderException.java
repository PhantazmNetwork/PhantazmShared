package org.phantazm.loader;

import com.github.steanky.ethylene.core.ConfigElement;
import com.github.steanky.ethylene.core.path.ConfigPath;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Serial;

public class LoaderException extends IOException {
    @Serial
    private static final long serialVersionUID = 3716442204068749622L;

    private final DataLocation location;
    private final ConfigElement element;
    private final ConfigPath elementPath;
    private final String stage;

    private LoaderException(String message, Throwable cause, DataLocation location, ConfigElement element,
        ConfigPath elementPath, String stage) {
        super(message, cause);
        this.location = location;
        this.element = element;
        this.elementPath = elementPath;
        this.stage = stage;
    }

    public @NotNull Builder toBuilder() {
        return builder()
            .withMessage(getMessage())
            .withCause(getCause())
            .withDataLocation(location)
            .withElement(element)
            .withElementPath(elementPath)
            .withStage(stage);
    }

    private void appendInfo(StringBuilder builder, String name, Object data) {
        if (data == null) return;

        String sep = System.lineSeparator();
        builder.append(sep);
        builder.append(name);
        builder.append(": ");
        builder.append(data);
    }

    @Override
    public String getMessage() {
        String detail = super.getMessage();

        StringBuilder builder = new StringBuilder();
        builder.append(detail);

        appendInfo(builder, "stage", stage);
        appendInfo(builder, "location", location);
        appendInfo(builder, "element", element);
        appendInfo(builder, "path", elementPath);

        return builder.toString();
    }

    public static @NotNull Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private DataLocation location;
        private ConfigElement element;
        private ConfigPath elementPath;
        private String message;
        private String stage;
        private Throwable cause;

        private Builder() {
        }

        public @NotNull LoaderException build() {
            return new LoaderException(message, cause, location, element, elementPath, stage);
        }

        public @NotNull Builder withDataLocation(@Nullable DataLocation location) {
            this.location = location;
            return this;
        }

        public @NotNull Builder withElement(@Nullable ConfigElement element) {
            this.element = element;
            return this;
        }

        public @NotNull Builder withElementPath(@Nullable ConfigPath path) {
            this.elementPath = path;
            return this;
        }

        public @NotNull Builder withStage(@Nullable String stage) {
            this.stage = stage;
            return this;
        }

        public @NotNull Builder withMessage(@Nullable String message) {
            this.message = message;
            return this;
        }

        public @NotNull Builder withCause(@Nullable Throwable cause) {
            this.cause = cause;
            return this;
        }
    }
}
