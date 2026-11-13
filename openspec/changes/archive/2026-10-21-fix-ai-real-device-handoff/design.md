# Design

Quantity detection must bind a plural/count expression to a device noun, not to
arbitrary measurements in a long integration request. Multiple proposed actions
still require clarification. A single-device plan retains explicit name/type,
optional location and ordinary confirmation. Model instructions identify supported
creation as the first step and never promise connector deployment from AI tools.
Rules may validate only explicitly requested nonempty YAML. Unsupported requests
produce no actions and explain how to select a model or clarify a supported task.
The real HSL handoff uses owner APIs and an explicit existing connector binding,
preserving current fleet entries and keeping tokens in ignored private config.

Acceptance additionally exposed two handoff defects: the drawer retained its
dirty flag after a persisted plan, and Movement filtered out all real sources.
Reset only the saved request's dirty state (guard new edits), and use authorized
REST source discovery in Movement with explicit ambiguity selection. No new
backend source authorization or ingestion path is introduced.
