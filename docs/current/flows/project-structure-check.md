# Project Structure Check

`scripts/check-project-structure.ps1` is the repeatable guardrail for repository
layout and code-organization drift.

Run it from the repository root:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/check-project-structure.ps1
```

The check fails fast for structural violations that should not enter the tree:

- Java package declarations that do not match source paths.
- Spring controllers, JPA entities, repositories, `@Autowired`, or direct
  `@Value` usage outside their expected packages.
- Empty production source package directories and `.gitkeep` placeholders under
  production Java source.
- New generic `*ConfigService` or `*SettingsService` classes in backend
  `service` packages.

The check currently reports these risks as warnings so existing large units can
be decomposed by active OpenSpec changes without blocking unrelated validation:

- Java production `@Service` classes over 350 lines.
- Java service constructors with more than 8 injected collaborators.
- Frontend `*Page.tsx` files over 600 lines.
- Python modules under `ml-service/app/services/` over 350 lines.
- Wildcard imports in production Java code.

Manual fixture check:

1. Temporarily create a backend production service named
   `ExampleConfigService.java` under a `service` package and verify the script
   exits non-zero.
2. Temporarily lower one warning threshold in the script and verify the warning
   block prints while the script still exits zero.
3. Revert the temporary fixture and threshold edits before committing.
