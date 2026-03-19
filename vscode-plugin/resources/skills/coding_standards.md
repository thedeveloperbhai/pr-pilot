# PR Pilot — Coding Standards

These are the coding standards the AI will validate code against during review.

---

## Naming Conventions
- **Variables & functions:** camelCase (e.g. `getUserById`)
- **Classes & interfaces:** PascalCase (e.g. `UserRepository`)
- **Constants:** UPPER_SNAKE_CASE (e.g. `MAX_RETRY_COUNT`)
- **Files:** match the primary class/object name they contain
- Package / module names must be **lowercase with no underscores** (e.g. `com.company.project.service`)
- Avoid abbreviations unless they are universally understood (e.g. `id`, `url`)
- Prefer descriptive names over short names, even if slightly longer
- Boolean variables and methods should read clearly as predicates (e.g. `isEnabled`, `hasPermission`)
- Avoid Hungarian notation or encoding type information in variable names

---

## Code Style
- Maximum line length: **100–120 characters** (prefer 100 where possible)
- Indentation: **4 spaces** (no tabs)
- No trailing whitespace
- One blank line between top-level declarations
- Braces on the same line as the statement (K&R style)
- Use explicit visibility modifiers where supported (`public`, `private`, `internal`)
- Avoid wildcard imports — import only what is used
- Prefer early returns instead of deeply nested conditionals
- Limit method length to maintain readability (ideally under ~40 lines)
- Prefer immutability (`final`, `val`) unless mutation is required

---

## Documentation
- All `public` and `internal` functions must have a KDoc/Javadoc comment
- Public classes, interfaces, and modules must include documentation explaining their purpose
- Complex logic blocks must have inline comments explaining **why**, not **what**
- TODO comments must include a ticket reference (e.g. `// TODO(PROJ-123): refactor this`)
- Avoid redundant comments that restate the code
- Document assumptions, invariants, and side effects
- API documentation must describe parameters, return values, and possible errors

---

## Architecture
- No business logic in UI/View layer — delegate to ViewModels or Services
- All repository / data-access calls must be **asynchronous** (suspend / coroutine / RxJava)
- Avoid direct dependency on concrete implementations — program to interfaces
- Follow **single-responsibility principle**: one class, one job
- Follow **dependency inversion principle** for core business logic
- Separate domain logic, infrastructure, and presentation layers
- Avoid large "god classes" that combine multiple responsibilities
- Favor composition over inheritance when designing components
- Keep modules loosely coupled and highly cohesive

---

## Dependencies
- Do not add new third-party dependencies without team approval
- Prefer standard library functions over external utilities for trivial tasks
- All new dependencies must be pinned to an exact version in the build file
- Avoid dependencies that introduce large transitive dependency graphs
- Ensure dependencies are actively maintained and have no known security vulnerabilities
- Avoid duplicate libraries providing similar functionality
- Prefer lightweight and well-established libraries when external dependencies are required

---

## Testing
- Minimum **80% unit test coverage** for new business logic
- Tests must follow the **Arrange / Act / Assert** pattern
- Test class names: `<ClassUnderTest>Test` (e.g. `UserRepositoryTest`)
- No `Thread.sleep()` in tests — use test coroutine dispatchers or mocks
- Tests must be deterministic and not rely on execution order
- Prefer unit tests over integration tests when testing isolated logic
- Each test should validate a single behavior
- Avoid overly complex test setups — use fixtures or builders where necessary
- Mock external services and IO operations

---

## Version Control
- Commits must reference a ticket (e.g. `feat(PROJ-123): add user login`)
- PR title format: `<type>(<scope>): <short description>`
  - Types: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`
- Each PR should address a single concern
- Commit messages should be concise and written in imperative form (e.g. `add validation for email input`)
- Avoid large commits that mix refactoring and functional changes
- Rebase or squash commits when appropriate to maintain clean history
