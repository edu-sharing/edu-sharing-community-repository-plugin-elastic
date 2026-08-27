package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * One indexable unit: the text that gets stored and shown, plus where it came from.
 * <p>
 * {@link #text()} is the bare chunk - that is what goes into the {@code text} field of the chunk
 * index and into the prompt. {@link #embeddingText()} is what gets sent to the embedding model: the
 * same text with a short, deterministically derived context header in front of it. Storing the bare
 * text but embedding the prefixed one is deliberate; an isolated paragraph from the middle of a
 * worksheet carries no subject reference of its own, and that is exactly what people search for.
 * <p>
 * {@code charStart}/{@code charEnd} index into the <em>normalized</em> full text (see
 * {@link TextNormalizer}), not into the original bytes - normalization removes and rewrites
 * characters, so original offsets cannot survive it. Consecutive content chunks overlap by design,
 * so their spans overlap too.
 */
public record Chunk(
        int ordinal,
        ChunkKind kind,
        String text,
        String contextPrefix,
        int charStart,
        int charEnd,
        String heading,
        Integer page,
        Double timeStart,
        Double timeEnd) {

    /** The text handed to the embedding model: context header, blank line, chunk. */
    public String embeddingText() {
        return contextPrefix == null || contextPrefix.isBlank()
                ? text
                : contextPrefix + "\n\n" + text;
    }

    public Optional<String> headingOptional() {
        return Optional.ofNullable(heading);
    }

    public OptionalInt pageOptional() {
        return page == null ? OptionalInt.empty() : OptionalInt.of(page);
    }

    public OptionalDouble timeStartOptional() {
        return timeStart == null ? OptionalDouble.empty() : OptionalDouble.of(timeStart);
    }

    Chunk withOrdinal(int newOrdinal) {
        return new Chunk(newOrdinal, kind, text, contextPrefix, charStart, charEnd, heading, page, timeStart, timeEnd);
    }

    Chunk withContextPrefix(String newPrefix) {
        return new Chunk(ordinal, kind, text, newPrefix, charStart, charEnd, heading, page, timeStart, timeEnd);
    }
}
