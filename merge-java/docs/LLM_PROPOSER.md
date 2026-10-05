# A proposer behind the gate (plan step 4.4)

A conflict this module refuses to decide can be handed to somebody else — a model, a script, a person with a hunch —
whose answer appears as **one more fix path**. It is never the resolution, so it reaches a file only through the same
human decision every other fix path needs.

The boundary is [`DESIGN_NEVER_AUTO_RESOLVED.md`](../DESIGN_NEVER_AUTO_RESOLVED.md): structural, API and
overlapping-body conflicts are **substitutive** — resolving one discards somebody's intention or invents a third that
neither author wrote — so the decision belongs to a person. A proposer exists to inform that decision, not to take it.

## The shape

```java
@FunctionalInterface
public interface ConflictProposer {
    Optional<Proposal> propose(Conflict conflict);
    record Proposal(String resolvedCode, String explanation) { }
}
```

Wired in, optionally, where a resolver is built:

```java
MergeConflictResolver resolver = new MergeConflictResolver.Builder()
    .setBranchName("feature")
    .setHistoryPath(history)
    .setTypeContext(TypeContext.withRuntimeClasspath(sourceRoot))
    .setProposer(myProposer)                 // omit it and nothing changes
    .build();
```

## What happens to an answer

For every conflict the resolver escalates — a conflict no resolver claimed, and one a resolver escalated
(`STRUCTURAL_CHANGE` and `API_INCOMPATIBILITY` always do) — the proposal is:

1. **verified as if it were an automatic answer.** `ResolutionVerifier` only verifies automatic resolutions, because
   review, manual and replayed ones are already in front of a human; a proposal is therefore put through the gate in
   that form and the verdict is read back. This is the same gate every automatic answer passes, not a second one.
2. **attached as a fix path, and nowhere else.** The code path that attaches it cannot reach the resolution, so
   "cannot bypass the gate" is structural rather than a promise — and a test asserts exactly that
   (`ProposerBehindTheGateTest`).
3. **labelled with what the gate said.** A refused proposal is still shown — a reviewer may want to see what was
   suggested and why it was refused — but its description says it was refused and its impact begins `NOT verified`.
   Accepting a refused proposal stays possible *because* a human read it; it is never applied on its own.

A proposer that throws does not disturb the escalation: the fix path says the proposer failed and what it said. An
advisor's failure must not change a decision.

Automatic and already-replayed resolutions are never handed to a proposer — there is nothing to advise about.

## Plugging in a real endpoint

Nothing in this repository chooses an endpoint, credentials or a timeout, so no client ships here. The JDK's own
client is enough, and the shape is the obvious one:

```java
ConflictProposer http = conflict -> {
    String body = """
        {"model": "...", "input": {"base": %s, "ours": %s, "theirs": %s}}"""
        .formatted(json(conflict.getBaseCode()), json(conflict.getBranch1Code()),
            json(conflict.getBranch2Code()));
    HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
        .timeout(Duration.ofSeconds(20))
        .header("content-type", "application/json")
        .header("authorization", "Bearer " + token)     // from the environment, never from this file
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build();
    try (HttpClient client = HttpClient.newHttpClient()) {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return parseProposal(response.body());            // Optional.empty() when it declines to answer
    } catch (IOException | InterruptedException failure) {
        throw new IllegalStateException("proposer endpoint failed", failure);   // the resolver reports it
    }
};
```

**Two things to decide before pointing this at a model.** The code under conflict is sent *to that endpoint* — a data
disclosure decision about somebody's source, not a technical detail. And a proposal is untrusted text that will be
shown to a reviewer and copied into a file only by them; treat it as advice from a stranger, which is what the gate
and the fix-path boundary are for.

## What is not here yet

The page shows a proposal's reasoning and the gate's verdict, and the report now carries the proposed code
(`suggestedCode`), so accepting one is a matter of the page prefilling its editor with it rather than a reviewer
copying text. That last mile is deliberate follow-up work, not an oversight.
