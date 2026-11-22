# jcodebuddy-tool-hipster-entity

Proposal adapter over `hipster-entity`'s generator. It renders the generated Java into a temporary
source copy (the same reference pass used by `EntityMetadataGenerator.checkGeneratedSource`), returns
the differing generated files as proposals, and never writes the real source or metadata tree.
