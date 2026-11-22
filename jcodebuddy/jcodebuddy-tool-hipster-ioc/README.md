# jcodebuddy-tool-hipster-ioc

Proposal adapter over `hipster-ioc`'s `IocGeneration.render` half. It reads the module's OpenRewrite
class index, renders context implementations and the dependency graph, and returns the differing files
as proposals. It does not write the real module; the agent's `WriteCoordinator` applies the review.
