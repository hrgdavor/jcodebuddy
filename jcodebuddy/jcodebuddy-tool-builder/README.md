# jcodebuddy-tool-builder

The generic Java builder as a proposal tool.

- parses the class with `jwa-builder`'s `ClassMemberProcessor` (OpenRewrite LST);
- generates the builder text through the repository's `SourceSplicer` path;
- returns a `FileProposal`; it never writes;
- has a standalone jsx6 page (`ui/builder` in the agent) and a catalog entry.

The agent's `WriteCoordinator` applies or rejects the proposal. The tool is registered explicitly in
`AgentTools`; there is no classpath scan.
