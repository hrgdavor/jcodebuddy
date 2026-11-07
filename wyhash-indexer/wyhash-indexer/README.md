# wyhash-indexer

Fast, exact dependency pre-filter for Java codebases using WyHash + OpenRewrite.

Goal: answer "what files import/use X?" without full type resolution. Ignore overloads on purpose.

## Why this exists

Java LSPs (jdtls, IntelliJ) do precise binding resolution. That's expensive. If you only want to narrow the search for method usage and imports - what classes depend on which - you can do it with a pure symbol hash index.

This project does exactly that:

```
java.lang.Long.valueOf -> hash -> list of files
```

## Design

### Main index ignores overloads
Key is `package.Class.member` like `java.lang.Long.valueOf`, not `Long.valueOf(String)`. Enough to narrow candidates from 20k files to 20.

### Two indexes
- **Forward**: `File -> sorted long[]` (WyHash of symbols). ~3KB per file.
- **Inverted**: `long (WyHash) -> Set<File>`. Answers "who uses X?" in O(1).

### WyHash as identifier
- Uses WyHash final v4 with pinned secret `DEFAULT_SECRET` - stable forever.
- `WyHash.combine(h1, h2)` allows building `owner.member` hash without allocating a new string.
- Dictionary: `long hash -> String symbol`

### Fail-fast on collision (your requirement)
You said: "I am willing to accept rename if WyHash discovers a collision".

`SymbolDictionary.intern()` throws `CollisionException` with a very descriptive message if two different strings produce the same 64-bit hash.

- As long as no exception was thrown, `hash == symbol`. No false positives.
- `filesUsing("java.util.List")` returns EXACT files, not approximate.
- If collision ever happens (probability ~1e-7 for 1M symbols), you must rename one symbol by 1 char or change `WyHash.SECRET`.

This guarantee lets you say: "all these files import this class" reliably.

### Fine path
`FileIndex.fineSymbols` keeps overload details like `IMPORT:java.util.List` or `java.lang.Long.valueOf(Ljava/lang/String;)`. Not in main inverted index, but available for second-level filtering if needed.

## How it works

1. Parse with OpenRewrite `JavaParser.fromJavaVersion().classpath(runtimeClasspath())`
2. Visit with `SymbolCollector extends JavaIsoVisitor`:
   - `J.Import` -> `java.util.List`
   - `J.MethodInvocation` -> `owner.method` if declaring type known
   - `J.FieldAccess` -> `owner.field`
   - `J.NewClass`, `J.ParameterizedType` -> `owner`
3. Finalize: `dict.intern(symbol)` -> hash, check collision
4. Build inverted index

## Building

Requires JDK 17+ and Maven.

```bash
mvn clean package
java -jar target/wyhash-indexer-1.0.0-SNAPSHOT.jar /path/to/your/java/project
```

Example output:
```
Found 342 java files
Dictionary size: 1843 unique symbols

=== Top 20 most used symbols ===
  120 files : java.util.List [0x867e11779b3c2284]
   80 files : java.lang.Long.valueOf [0x9bd9fec888deeb81]

Query 'java.lang.Long.valueOf' -> 12 files
```

## API

```java
ProjectIndexer indexer = new ProjectIndexer();
indexer.index(Paths.get("/my/project"));

// Exact query, no collision bucket
Set<String> files = indexer.filesUsing("java.lang.Long.valueOf");
FileIndex fi = indexer.getFile("com/example/A.java");
boolean mayContain = fi.mayContain(WyHash.hash("java.util.List"));
```

## Performance

- Forward index: sorted `long[]`, binarySearch in L1 cache
- Inverted: HashMap<long, Set<String>> - replace with RoaringBitmap for 10x compression if you have 1M files
- Incremental: file changed -> re-parse one file, rewrite its `long[]`

Faster than Lucene for this use case because Lucene stores term strings, positions, norms. Here you store 8 bytes per symbol.

## When to use Lucene?

Use Lucene if you want fuzzy `Long.valu*` or full-text search. For exact `file contains symbol`, custom hash index wins.

## Collision handling options

1. **Current**: fail-fast, rename symbol (your choice)
2. Change secret in `WyHash.java` and re-index everything
3. Change `SymbolDictionary` to `Map<Long, List<String>>` and filter by exact string after hash lookup

## Files

- `WyHash.java` - final v4, pinned secret
- `CollisionException.java` - descriptive error you asked for
- `SymbolDictionary.java` - intern with collision check
- `FileIndex.java` - forward index per file
- `SymbolCollector.java` - OpenRewrite visitor
- `ProjectIndexer.java` - orchestration
- `Main.java` - CLI demo

## License

MIT - use for your own tools.
