package hr.hrg.watch2.server.metadata.transport;

import org.apache.fory.Fory;

/**
 * The one place a Fory codec for the metadata protocol is constructed.
 *
 * <h3>Why a factory rather than a builder call at each end</h3>
 * <p>Fory's wire format is <strong>not</strong> self-describing: {@code withNumberCompressed} and
 * {@code withRefTracking} change how bytes are written, and two ends that disagree do not fail with
 * "incompatible configuration" — they mis-parse each other. Measured on this protocol: the HTTP
 * transport built its codec with {@code withNumberCompressed(true).withRefTracking(false)} while every
 * in-tree client used {@code Fory.builder().build()}, and the server answered
 * {@code GET /api/fory} with an <strong>HTTP 500</strong> whose cause was
 * {@code SerializationException: java.lang.NullPointerException} from Fory's
 * {@code DeferedLazySerializer} — because the mis-parsed request graph reached the response with a value
 * whose runtime class Fory could not resolve a serializer for, and Fory's deferred lookup returns
 * {@code null} rather than reporting that. Nothing in the stack names the configuration, which is why
 * {@code MetadataServerTest.httpForyRoundTrip} is the test that catches it and why the flags have to live
 * in one place both ends can reach.</p>
 *
 * <p>That mismatch was one of <strong>two</strong> defects this endpoint had; the other is the null-field
 * one described below, and it is the one that produced the surviving 500. Both are invisible from the
 * exception the client sees, which is why both are written down here.</p>
 *
 * <p>The flags themselves are kept, because they are deliberate for a metadata channel: no reference
 * tracking (the payload is a tree, not a graph with shared nodes) and compressed numbers (the payload is
 * small integers and checksums). What changes is that a client can now obtain exactly the codec the
 * server speaks instead of guessing the defaults.</p>
 *
 * <h3>Contract for a client</h3>
 * <p>Build your codec with {@link #newFory()} — never with {@code Fory.builder()} directly — because the
 * builder's flags are part of the wire format.</p>
 *
 * <p><strong>And send maps, not the model objects.</strong> Measured against Fory 1.3.0 on JDK 25: it
 * cannot write a {@code null} into an object <em>field</em>.
 * {@code fory.serialize(new JsonRpcResponse())} — or any registered POJO with one null field — fails with
 * {@code SerializationException: java.lang.NullPointerException} from the field writer, under every
 * configuration tried ({@code withCompatible}, {@code withCodegen(false)}, {@code withAsyncCompilation},
 * {@code withRefTracking(true)}, {@code withMetaShare}) and with every combination of this transport's own
 * flags. A {@code Map} <em>value</em> of {@code null} serialises correctly, and so does a top-level
 * {@code null} and a POJO with all fields set, so the defect is exactly that one case — and JSON-RPC
 * cannot avoid it: a successful call has no {@code error}, a failed one no {@code result}, and
 * {@code getEntry} returns a null result for an unknown hash. The envelope therefore crosses as a map
 * ({@link hr.hrg.watch2.server.metadata.model.JsonRpcResponse#toWireMap()}), which is also the shape the
 * JSON transport speaks and the shape {@code MetadataServerTest} asserts.</p>
 *
 * <p>A <em>value</em> inside {@code result} is still an object, so it too must not carry null fields —
 * {@code MetadataProvider.CacheEntry} is the one that crosses this wire, and its {@code metadata} map must
 * be empty rather than null.</p>
 *
 * <p>Registering the model classes is still done by both transports: it costs nothing, and it keeps a
 * null-free POJO usable on this wire by a client that prefers to send one.</p>
 */
public final class ForyCodec {

    private ForyCodec() {
    }

    /**
     * A codec configured for this protocol.
     *
     * <p>A fresh instance per call rather than a shared static one: Fory instances are stateful (they
     * cache type ids and, with reference tracking on, the reference table), so a shared instance across
     * threads and requests is a correctness question this protocol has no reason to take on. Codec
     * construction is cheap next to the request it serves.</p>
     */
    public static Fory newFory() {
        return Fory.builder()
                .withNumberCompressed(true)
                .withRefTracking(false)
                .build();
    }
}
