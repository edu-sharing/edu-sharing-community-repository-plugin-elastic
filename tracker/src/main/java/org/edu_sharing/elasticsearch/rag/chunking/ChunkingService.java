package org.edu_sharing.elasticsearch.rag.chunking;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one node into the chunks that get embedded and indexed.
 * <p>
 * Deliberately free of any Alfresco, Spring or Elasticsearch type: chunking is the part of the RAG
 * pipeline with real behaviour to get wrong, and it is worth being able to test it without a
 * repository or a cluster in the loop.
 * <p>
 * <strong>Determinism is a contract, not a nicety.</strong> The tracker skips re-embedding when the
 * {@code contentHash} of a node's text is unchanged, which is only sound if the same text plus the
 * same {@link ChunkingOptions} always produce byte-identical chunks in the same order. Nothing here
 * may depend on iteration order of a hash container, on locale, or on wall-clock time.
 */
public class ChunkingService {

    /**
     * Longest context header put in front of a chunk. The header is charged on top of
     * {@link ChunkingOptions#maxTokens()}, so it needs its own ceiling - roughly 65 tokens, which is
     * comfortable against the 8192-token window of the models this is aimed at.
     */
    private static final int MAX_PREFIX_CHARS = 240;

    private final List<Chunker> chunkers;
    private final ChunkPacker packer;

    public ChunkingService() {
        // order matters: the structural chunker claims everything, so it goes last
        this(List.of(new TranscriptChunker(), new TableChunker(), new StructuralChunker()));
    }

    ChunkingService(List<Chunker> chunkers) {
        this.chunkers = List.copyOf(chunkers);
        this.packer = new ChunkPacker();
    }

    public ChunkingResult chunk(ChunkSource source, ChunkingOptions options) {
        String document = TextNormalizer.normalize(source.fullText());

        Chunk metadataChunk = buildMetadataChunk(source);
        List<Chunk> content = document.isBlank()
                ? List.of()
                : packer.pack(document, select(source.mimetype(), document).segment(document, options), options);

        int budget = options.maxChunksPerNode() - (metadataChunk == null ? 0 : 1);
        int kept = Math.max(0, Math.min(budget, content.size()));
        int dropped = content.size() - kept;

        String prefix = buildContextPrefix(source);
        List<Chunk> chunks = new ArrayList<>(kept + 1);
        if (metadataChunk != null) {
            chunks.add(metadataChunk.withOrdinal(chunks.size()));
        }
        for (int i = 0; i < kept; i++) {
            Chunk chunk = content.get(i);
            chunks.add(chunk
                    .withContextPrefix(headerFor(prefix, chunk.heading(), source.title()))
                    .withOrdinal(chunks.size()));
        }
        return new ChunkingResult(chunks, dropped);
    }

    private Chunker select(String mimetype, String document) {
        for (Chunker chunker : chunkers) {
            if (chunker.supports(mimetype, document)) {
                return chunker;
            }
        }
        throw new IllegalStateException("no chunker accepted mimetype " + mimetype
                + " - the fallback chunker must claim everything");
    }

    /**
     * Title, description, keywords and facets as running text.
     * <p>
     * For a large part of the WLO stock this is the only chunk there will ever be: linked resources,
     * images and videos without a transcript have no extractable text at all, and without this they
     * would be invisible to a semantic search that only ever sees full text.
     */
    private Chunk buildMetadataChunk(ChunkSource source) {
        StringBuilder text = new StringBuilder();
        appendLine(text, source.title());
        appendLine(text, source.description());
        appendLabelled(text, "Schlagworte", source.keywords());
        appendLabelled(text, "Fach", source.subject());
        appendLabelled(text, "Bildungsstufe", source.educationalContext());
        appendLabelled(text, "Materialart", source.learningResourceType());

        String body = text.toString().strip();
        if (body.isEmpty()) {
            return null;
        }
        return new Chunk(0, ChunkKind.METADATA, body, "", 0, 0, null, null, null, null);
    }

    /** Line 1 of the context header: what this material is, independent of the chunk. */
    private String buildContextPrefix(ChunkSource source) {
        List<String> parts = new ArrayList<>(3);
        addIfPresent(parts, source.title());
        addIfPresent(parts, String.join(", ", source.subject()));
        addIfPresent(parts, String.join(", ", source.educationalContext()));
        return String.join(" — ", parts);
    }

    /** Line 2 adds the section the chunk sits in, unless that merely repeats the title. */
    private String headerFor(String documentPrefix, String heading, String title) {
        StringBuilder header = new StringBuilder(documentPrefix);
        if (heading != null && !heading.isBlank() && !heading.equalsIgnoreCase(title)) {
            if (header.length() > 0) {
                header.append('\n');
            }
            header.append(heading.strip());
        }
        return truncate(header.toString(), MAX_PREFIX_CHARS);
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (value != null && !value.isBlank()) {
            parts.add(value.strip());
        }
    }

    private static void appendLine(StringBuilder text, String value) {
        if (value != null && !value.isBlank()) {
            text.append(value.strip()).append('\n');
        }
    }

    private static void appendLabelled(StringBuilder text, String label, List<String> values) {
        List<String> present = values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
        if (!present.isEmpty()) {
            text.append(label).append(": ").append(String.join(", ", present)).append('\n');
        }
    }

    /** Cuts at the last space before the limit so the header never ends mid-word. */
    private static String truncate(String value, int limit) {
        if (value.length() <= limit) {
            return value;
        }
        int space = value.lastIndexOf(' ', limit);
        return value.substring(0, space > limit / 2 ? space : limit).strip();
    }
}
