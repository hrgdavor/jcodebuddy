package hr.hrg.jcodebuddy.meta;

/**
 * A provider was asked to parse source bytes and has no source parser (DEC-W008).
 *
 * <h3>Why this type exists rather than a bare null or a generic exception</h3>
 *
 * <p>DEC-W008 makes {@code MetadataProvider.parse} part of the provider contract, and the honest
 * situation in this repository is that <strong>most providers cannot implement it</strong>: parsing Java
 * source is not free, and the module that owns the provider interface ({@code metadata-server}) has no
 * source reader on its classpath by design — the reader is OpenRewrite's LST, which lives in
 * {@code hipster-entity-tooling} (DEC-030). So the interface's default answers with this type, and a
 * provider that does have a reader overrides {@code parse}.</p>
 *
 * <p>The alternative shapes were both worse. Returning {@code null} would make "no parser here" and
 * "this source produced no metadata" the same answer, and a caller cannot tell a provider that cannot
 * help from one that found nothing. Letting the default throw
 * {@link UnsupportedOperationException} directly would be indistinguishable from any other unsupported
 * operation in a caller's {@code catch} block. This subclass is catchable on its own and still behaves
 * like what it is.</p>
 *
 * <p>See {@link MetadataProvider#parse(String, byte[])} for the contract, and DEC-W008 for the
 * decision's own record — including the amendment this class records: the decision's "the default
 * behaviour MUST work" sentence assumed the parser was available to every provider, which the module
 * graph does not allow.</p>
 */
public class MetadataParseUnsupportedException extends UnsupportedOperationException {

    private static final long serialVersionUID = 1L;

    /**
     * @param providerName the provider that was asked, so the message names the thing to change rather
     *                     than the thing that failed
     */
    public MetadataParseUnsupportedException(String providerName) {
        super(providerName + " has no source parser, so it cannot implement DEC-W008's parse(...): "
                + "the provider that has the source reader (OpenRewrite's LST, DEC-030) overrides it — "
                + "see project-automation's InMemoryMetadataCacheProvider for the reference implementation");
    }
}
