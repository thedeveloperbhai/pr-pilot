# PR Pilot for VS Code

> AI-powered pull request reviews, right inside VS Code.

PR Pilot brings the power of AI code review to your VS Code workflow — supporting **Bitbucket Cloud** and **GitHub**, with **OpenAI**, OpenAI-compatible endpoints, and **Ollama** as AI providers.

---

## Features

### Browse Pull Requests
- See all open (or merged / declined / all) pull requests in the **PR Pilot** sidebar panel
- Filter by state: `OPEN`, `MERGED`, `DECLINED`, `ALL`
- Click a PR to select it; use the context menu for actions

### View Changed Files
- Run **View Files** on any PR to see all changed files in the **PR Files** panel
- Click a file to open a side-by-side diff view powered by VS Code's built-in diff editor

### AI Code Review
- Run **Generate AI Review** on any PR to get a full code review from an AI assistant
- The review panel shows:
  - An **Overview** + file-by-file analysis with severity labels (🔴 Critical, 🟡 Warning, 🟢 Suggestion)
  - **Inline comments** per file and line
  - Checkboxes to selectively post inline comments back to the PR
- Copy the full review as Markdown
- Regenerate the review at any time

### PR Actions
- **Approve**, **Merge**, or **Decline** pull requests directly from VS Code
- **Post a comment** to any PR

### JIRA Integration
- When configured, approving, merging, declining, or requesting changes will:
  - Detect the JIRA issue key in the PR title, description, or branch name
  - Add a comment to the JIRA issue summarising the review outcome

### Skills & Prompt Customisation
- Customise the AI's behaviour via three Markdown skill files stored in `.vscode/pr-pilot/skills/`:
  - `system_prompt.md` — AI role, tone, output format
  - `review_rules.md` — Review checklist (security, performance, testing, etc.)
  - `coding_standards.md` — Team coding conventions
- Edit skills directly in the **Settings → Skills & Prompts** tab or in your editor

---

## Requirements

- VS Code `1.85.0` or newer
- A Bitbucket Cloud or GitHub account with API access
- An AI provider: OpenAI account, an OpenAI-compatible server (e.g. vLLM, LM Studio, Together AI), or a local [Ollama](https://ollama.ai) installation

---

## Getting Started

1. Install the extension
2. Open a workspace that contains a Git repository with a Bitbucket or GitHub remote
3. Open **Settings** (click the gear icon in the PR Pilot sidebar or run **PR Pilot: Open Settings** from the command palette)
4. Configure your **Git Provider** and **AI Provider**
5. Click **Refresh** in the PR list to load pull requests

---

## Configuration

All settings are managed through the **PR Pilot Settings** panel (not `settings.json`) to protect sensitive tokens.

### Git Providers

**Bitbucket Cloud**
- Add one or more repository access tokens (per workspace/repo pair)
- Generate tokens at: Bitbucket → Repository Settings → Access tokens
- Required scopes: `pullrequest:read`, `pullrequest:write`

**GitHub**
- Enter a Personal Access Token (PAT)
- Generate at: GitHub → Settings → Developer Settings → Personal access tokens
- Required scopes: `repo` (includes `pull_requests`)

### AI Providers

| Provider | Required | Notes |
|---|---|---|
| OpenAI | API Key | Uses `gpt-4o` by default |
| OpenAI-compatible | Base URL (+ optional key) | For vLLM, LM Studio, Together AI, etc. |
| Ollama | Base URL | Default: `http://localhost:11434`. No API key needed. |

### JIRA

| Field | Description |
|---|---|
| Base URL | Your Atlassian cloud URL, e.g. `https://yourcompany.atlassian.net` |
| Email | Atlassian account email |
| API Token | Generate at [id.atlassian.com](https://id.atlassian.com) → Security → API tokens |
| Issue Key Pattern | Regex to detect issue keys in PR metadata (default: `[A-Z][A-Z0-9]+-\d+`) |

---

## Commands

| Command | Description |
|---|---|
| `PR Pilot: Refresh` | Reload the PR list |
| `PR Pilot: Filter by State` | Change the PR state filter |
| `PR Pilot: View Files` | Load changed files for selected PR |
| `PR Pilot: Generate AI Review` | Start an AI code review |
| `PR Pilot: Approve PR` | Approve the selected PR |
| `PR Pilot: Merge PR` | Merge the selected PR |
| `PR Pilot: Decline PR` | Decline the selected PR |
| `PR Pilot: Post Comment` | Post a comment on the selected PR |
| `PR Pilot: Open Settings` | Open the settings panel |

---

## Skills System

PR Pilot ships with default skill files that control how the AI reviews code. These are automatically seeded into `.vscode/pr-pilot/skills/` in your workspace the first time you run a review.

You can customise them per-repo and commit them to source control so your entire team shares the same review standards.

To edit skills:
1. Open **PR Pilot: Open Settings**
2. Navigate to the **Skills & Prompts** tab
3. Edit the content and click **Save**
4. Click **Reset to Default** to restore the bundled defaults

---

## Privacy & Security

- All tokens and API keys are stored in VS Code's encrypted `SecretStorage` — never in `settings.json` or on disk in plain text
- Skill files (`.vscode/pr-pilot/skills/`) contain only review instructions, not credentials
- PR diff content is sent to your configured AI provider for review. Review your AI provider's privacy policy if you work with sensitive code.
- No telemetry is collected by this extension

---

## License

MIT
