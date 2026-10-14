package hr.hrg.webview.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The resolution half of the location grammar: a fragment that names something must land on it, and a fragment that
 * names something the file does not have must resolve to nothing at all.
 *
 * <p>That second half is the point. A resolver that falls back to line 1 turns a broken link into a click that costs
 * the reader a search, and it is why {@link LocationResolver} returns {@code null} rather than a guess — the plan
 * step's rule, and the reason these tests assert the {@code null} cases as carefully as the found ones.</p>
 */
public class LocationResolverTest {

    private static final String JAVA = """
        package com.example;

        // #region wiring
        private final Set<String> entries = new TreeSet<>();
        // #endregion

        /** Adds one item. */
        @Override
        public boolean add(String item) {
            return entries.add(item);
        }

        public void caller() {
            add("x");
        }
        """;

    private static LocationFragment at(String fragment) {
        return LocationFragment.parse("src/main/java/com/example/Example.java", fragment);
    }

    private static LocationResolution resolve(String fragment, String text) {
        return LocationResolver.resolve(at(fragment), text);
    }

    @Test
    public void anExplicitPositionNeedsNoFileAtAll() {
        LocationResolution line = LocationResolver.resolve(at("L42"), null);
        assertEquals(42, line.line());
        assertEquals(42, line.endLine());
        assertFalse(line.isRange());

        LocationResolution range = LocationResolver.resolve(at("L42-L58"), null);
        assertEquals(42, range.line());
        assertEquals(58, range.endLine());
        assertTrue(range.isRange());
    }

    @Test
    public void aRegionDirectiveIsFoundAndSpansToItsEnd() {
        LocationResolution region = resolve("wiring", JAVA);

        assertNotNull(region);
        // The directive's own line is line 3, so the region's content starts at 4 and ends before '#endregion'.
        assertEquals(4, region.line());
        assertEquals(4, region.endLine());
        assertEquals(LocationFragment.Kind.REGION, region.kind());
        assertTrue("the answer says how it was found: " + region.how(),
            region.how().contains("inject-examples") && region.how().contains("wiring"));
    }

    @Test
    public void everyCommentPrefixInjectExamplesAcceptsIsAccepted() {
        String[] prefixes = {"#region", "// #region", "//region", "<!-- #region", "/* #region", "-- #region",
            "; #region", "% #region", "' #region", "REM #region"};
        for (String prefix : prefixes) {
            // A region is a *pair*: inject-examples pairs the directives, so an unclosed one is not a
            // section at all — the close is part of what makes this a directive rather than a comment.
            String text = "a\n" + prefix + " body\ncontent here\n#endregion\n";
            LocationResolution region = resolve("body", text);
            assertNotNull(prefix + " must be read as a directive", region);
            assertEquals(prefix, 3, region.line());
        }
    }

    @Test
    public void aDeclarationIsFoundByName() {
        LocationResolution declaration = resolve("add", JAVA);

        assertNotNull(declaration);
        assertEquals("the 'public boolean add(...)' line", 9, declaration.line());
        assertEquals(LocationFragment.Kind.MEMBER, declaration.kind());
        assertTrue(declaration.how().contains("\"add\""));
    }

    @Test
    public void aCallIsNotADeclaration() {
        // There is no declaration of 'missing' anywhere, and the only occurrence of a call shape would be a call -
        // which the brace rule rejects, so the answer is nothing rather than the call's line.
        assertNull(resolve("missing", JAVA));
        assertNull("a scheme-like fragment is not a location", resolve("mailto:dev@example.com", JAVA));
    }

    @Test
    public void aTypeIsFoundByNameAndAQualifiedNameUsesItsLastSegment() {
        String text = "package a;\n\npublic class SomeFile {\n}\n";
        LocationResolution type = resolve("SomeFile", text);
        assertNotNull(type);
        assertEquals(3, type.line());
        assertTrue(type.how().contains("\"SomeFile\""));

        LocationResolution qualified = resolve("a.SomeFile", text);
        assertNotNull("a qualified name resolves by its last segment", qualified);
        assertEquals(3, qualified.line());
    }

    @Test
    public void aDirectiveWinsOverADeclarationOfTheSameName() {
        // Both are named 'wiring'; inject-examples' rule is that the explicit directive always wins, and the answer
        // has to say which one it used.
        String text = "// #region wiring\nint a = 1;\n// #endregion\n\nvoid wiring() {\n}\n";
        LocationResolution region = resolve("wiring", text);

        assertNotNull(region);
        assertEquals(2, region.line());
        assertTrue("the directive wins and says so: " + region.how(),
            region.how().contains("inject-examples"));
    }

    @Test
    public void theBodyOnlyScopeLandsPastTheOpeningBrace() {
        // The modifier is a trailing segment in the reference grammar: 'add-', which this read before 2026-10-13, is
        // the name 'add-' now, and the resolver has to spell the body scope the way the reference reads it.
        LocationResolution body = resolve("add/-", JAVA);

        assertNotNull(body);
        // 'public boolean add(String item) {' is line 9, so the body starts on line 10.
        assertEquals(10, body.line());
    }

    @Test
    public void theWideningScopesLandOnTheDeclaration() {
        // + and ++ widen what an INCLUDE copies; a person following a link wants the code, so all three land alike.
        assertEquals(9, resolve("add", JAVA).line());
        assertEquals(9, resolve("add/+", JAVA).line());
        assertEquals(9, resolve("add/++", JAVA).line());
    }

    @Test
    public void aLocalVariableInsideAMethodIsATarget() {
        // The reported case of 2026-10-13: `#run/perOrg` must resolve to the local the maintainer declared inside
        // the method — a nested generic type (`Map<Long, List<Session>>`), which used to make the declaration
        // invisible to the matcher. webview asks the Java port for this; the port mirrors the reference, so the
        // answer here is the reference's answer.
        String source = "package a;\n"
                + "\n"
                + "public class Runner {\n"
                + "    public void run() {\n"
                + "        try { while (!Thread.interrupted()) {\n"
                + "            Map<Long, List<Session>> perOrg = new HashMap<>();\n"
                + "        } }\n"
                + "    }\n"
                + "}\n";

        LocationResolution byName = resolve("perOrg", source);
        assertNotNull("a local inside a block is a member", byName);
        assertEquals(6, byName.line());

        LocationResolution byPath = resolve("run/perOrg", source);
        assertNotNull("and the method reaches it by name", byPath);
        assertEquals(6, byPath.line());
        assertEquals(LocationFragment.Kind.MEMBER, byPath.kind());
    }

    @Test
    public void anEnumConstantIsATarget() {
        // The reported case of 2026-10-13: a link to an enum's constant must land on the constant, whether the
        // constants take a line each or share one line with the declaration. The rule is the reference
        // implementation's (matcher 4 reads a field *or constant*), so this asserts the wiring as much as the
        // answer: webview asks the Java port and does not carry a rule of its own.
        String lineByLine = "package a;\n\npublic enum OrgFeature {\n\tCALL,\n\tCHAT,\n\tCHAT_AUDIO\n}\n";
        String bunched = "package a;\n\npublic enum OrgFeature { CALL, CHAT, CHAT_AUDIO }\n";

        LocationResolution byPath = resolve("OrgFeature/CHAT", lineByLine);
        assertNotNull("a constant is a member of the enum that holds it", byPath);
        assertEquals(5, byPath.line());
        assertEquals(LocationFragment.Kind.MEMBER, byPath.kind());
        assertTrue("and the answer says what resolved it: " + byPath.how(),
            byPath.how().contains("inject-examples"));

        LocationResolution bare = resolve("CHAT_AUDIO", lineByLine);
        assertNotNull("and a constant is reachable by its own name", bare);
        assertEquals(6, bare.line());

        // A body written on one line is a container because its constants are its members, and the constant lands
        // on the line it is written on — the only line there is.
        LocationResolution inline = resolve("OrgFeature/CHAT", bunched);
        assertNotNull("one line of constants is still a member list", inline);
        assertEquals(3, inline.line());

        // A name the enum does not have is still nothing, not a guess at the line.
        assertNull(resolve("OrgFeature/NOPE", lineByLine));
    }

    @Test
    public void aJsonKeyPathLandsOnTheKey() {
        String json = """
            {
              "name": "example",
              "scripts": {
                "test": "node --test"
              }
            }
            """;

        LocationResolution key = LocationResolver.resolve(LocationFragment.parse("package.json", "scripts.test"),
            json);
        assertNotNull(key);
        assertEquals(4, key.line());
        assertEquals(LocationFragment.Kind.JSON, key.kind());
        assertTrue(key.how().contains("scripts.test"));

        // An absent key resolves to nothing.
        assertNull(LocationResolver.resolve(LocationFragment.parse("package.json", "scripts.missing"), json));
    }

    @Test
    public void nothingIsInvented() {
        assertNull("no fragment, no answer", LocationResolver.resolve(null, JAVA));
        assertNull("no text, no name resolution", LocationResolver.resolve(at("add"), null));
        assertNull("an empty file has no declaration", LocationResolver.resolve(at("add"), ""));
        assertNull("an empty fragment names nothing", resolve("", JAVA));
        assertNull(resolve("L42-", JAVA));
    }
}
