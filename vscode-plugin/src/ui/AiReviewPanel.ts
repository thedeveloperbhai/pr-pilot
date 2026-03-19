import * as vscode from 'vscode';
import { AiReviewResult, InlineComment } from '../models/InlineComment';
import { PullRequest } from '../models/PullRequest';
import { RepoInfo } from '../models/PullRequest';
import { PullRequestService } from '../services/PullRequestService';
import { JiraIntegrationService, ReviewOutcome, jiraSyncMessage } from '../jira/JiraIntegrationService';

/**
 * Webview panel for displaying AI review results.
 * Shows the Markdown summary and per-line inline comments with action buttons.
 */
export class AiReviewPanel {
  static currentPanel: AiReviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private _disposables: vscode.Disposable[] = [];

  private constructor(
    panel: vscode.WebviewPanel,
    private readonly pr: PullRequest,
    private readonly repoInfo: RepoInfo,
    private readonly reviewResult: AiReviewResult,
    private readonly service: PullRequestService,
    private readonly onRegenerate: () => Promise<void>
  ) {
    this._panel = panel;
    this._panel.webview.html = this.getHtml();
    this._panel.onDidDispose(() => this.dispose(), null, this._disposables);
    this._panel.webview.onDidReceiveMessage(
      (message) => this.handleMessage(message),
      null,
      this._disposables
    );
  }

  static show(
    extensionUri: vscode.Uri,
    pr: PullRequest,
    repoInfo: RepoInfo,
    reviewResult: AiReviewResult,
    service: PullRequestService,
    onRegenerate: () => Promise<void>
  ): AiReviewPanel {
    const column = vscode.window.activeTextEditor
      ? vscode.window.activeTextEditor.viewColumn
      : undefined;

    if (AiReviewPanel.currentPanel) {
      AiReviewPanel.currentPanel._panel.reveal(column);
      AiReviewPanel.currentPanel.update(pr, reviewResult, onRegenerate);
      return AiReviewPanel.currentPanel;
    }

    const panel = vscode.window.createWebviewPanel(
      'prPilotAiReview',
      `AI Review — PR #${pr.id}`,
      column ?? vscode.ViewColumn.One,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        localResourceRoots: [extensionUri],
      }
    );

    AiReviewPanel.currentPanel = new AiReviewPanel(panel, pr, repoInfo, reviewResult, service, onRegenerate);
    return AiReviewPanel.currentPanel;
  }

  update(pr: PullRequest, result: AiReviewResult, onRegenerate: () => Promise<void>): void {
    (this as any).pr = pr;
    (this as any).reviewResult = result;
    (this as any).onRegenerate = onRegenerate;
    this._panel.title = `AI Review — PR #${pr.id}`;
    this._panel.webview.html = this.getHtml();
  }

  private async handleMessage(message: { command: string; comments?: InlineComment[]; text?: string }): Promise<void> {
    const jira = new JiraIntegrationService();

    switch (message.command) {
      case 'regenerate':
        this._panel.webview.postMessage({ command: 'setLoading', loading: true });
        await this.onRegenerate();
        break;

      case 'copyMarkdown':
        await vscode.env.clipboard.writeText(this.reviewResult.summary);
        vscode.window.showInformationMessage('AI review copied to clipboard.');
        break;

      case 'postInlineComments': {
        const comments = message.comments ?? [];
        if (comments.length === 0) {
          vscode.window.showWarningMessage('No inline comments selected.');
          return;
        }
        try {
          await this.service.postInlineComments(
            this.repoInfo.owner,
            this.repoInfo.repoSlug,
            this.pr.id,
            comments
          );
          vscode.window.showInformationMessage(`Posted ${comments.length} inline comment(s) to PR #${this.pr.id}.`);
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to post comments: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'requestChanges': {
        const comments = message.comments ?? [];
        try {
          await this.service.requestChanges(
            this.repoInfo.owner,
            this.repoInfo.repoSlug,
            this.pr.id,
            this.reviewResult.summary,
            comments
          );
          const jiraResult = await jira.syncReviewOutcome(this.pr, 'CHANGES_REQUESTED', this.reviewResult.summary);
          const jiraMsg = jiraSyncMessage(jiraResult);
          vscode.window.showInformationMessage(
            `Changes requested on PR #${this.pr.id}.${jiraMsg ? ` ${jiraMsg}` : ''}`
          );
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to request changes: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'approve': {
        const confirmed = await vscode.window.showQuickPick(['Yes, Approve', 'Cancel'], {
          placeHolder: `Approve PR #${this.pr.id} — ${this.pr.title}?`,
        });
        if (confirmed !== 'Yes, Approve') return;
        try {
          await this.service.approvePullRequest(this.repoInfo.owner, this.repoInfo.repoSlug, this.pr.id);
          const jiraResult = await jira.syncReviewOutcome(this.pr, 'APPROVED');
          const jiraMsg = jiraSyncMessage(jiraResult);
          vscode.window.showInformationMessage(
            `PR #${this.pr.id} approved.${jiraMsg ? ` ${jiraMsg}` : ''}`
          );
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to approve: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'merge': {
        const confirmed = await vscode.window.showQuickPick(['Yes, Merge', 'Cancel'], {
          placeHolder: `Merge PR #${this.pr.id} — ${this.pr.title}?`,
        });
        if (confirmed !== 'Yes, Merge') return;
        try {
          await this.service.mergePullRequest(this.repoInfo.owner, this.repoInfo.repoSlug, this.pr.id);
          const jiraResult = await jira.syncReviewOutcome(this.pr, 'MERGED');
          const jiraMsg = jiraSyncMessage(jiraResult);
          vscode.window.showInformationMessage(
            `PR #${this.pr.id} merged.${jiraMsg ? ` ${jiraMsg}` : ''}`
          );
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to merge: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'decline': {
        const confirmed = await vscode.window.showQuickPick(['Yes, Decline', 'Cancel'], {
          placeHolder: `Decline/Close PR #${this.pr.id} — ${this.pr.title}?`,
        });
        if (confirmed !== 'Yes, Decline') return;
        try {
          await this.service.declinePullRequest(this.repoInfo.owner, this.repoInfo.repoSlug, this.pr.id);
          const jiraResult = await jira.syncReviewOutcome(this.pr, 'DECLINED', this.reviewResult.summary);
          const jiraMsg = jiraSyncMessage(jiraResult);
          vscode.window.showInformationMessage(
            `PR #${this.pr.id} declined.${jiraMsg ? ` ${jiraMsg}` : ''}`
          );
        } catch (err) {
          vscode.window.showErrorMessage(`Failed to decline: ${err instanceof Error ? err.message : String(err)}`);
        }
        break;
      }

      case 'openUrl':
        if (message.text) {
          vscode.env.openExternal(vscode.Uri.parse(message.text));
        }
        break;
    }
  }

  private getHtml(): string {
    const { pr, reviewResult } = this;
    const summaryHtml = markdownToHtml(reviewResult.summary);
    const commentsJson = JSON.stringify(reviewResult.inlineComments);
    const prUrl = pr.links.html.href;

    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline';">
  <title>AI Review — PR #${pr.id}</title>
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: var(--vscode-font-family);
      font-size: var(--vscode-font-size);
      color: var(--vscode-foreground);
      background: var(--vscode-editor-background);
      padding: 0;
      height: 100vh;
      display: flex;
      flex-direction: column;
    }
    .header {
      background: var(--vscode-titleBar-activeBackground);
      padding: 12px 16px;
      border-bottom: 1px solid var(--vscode-panel-border);
      flex-shrink: 0;
    }
    .header h2 {
      font-size: 14px;
      font-weight: 600;
      color: var(--vscode-titleBar-activeForeground);
      margin-bottom: 4px;
    }
    .header .meta {
      font-size: 12px;
      color: var(--vscode-descriptionForeground);
    }
    .header .meta a {
      color: var(--vscode-textLink-foreground);
      text-decoration: none;
      cursor: pointer;
    }
    .tabs {
      display: flex;
      background: var(--vscode-tab-inactiveBackground);
      border-bottom: 1px solid var(--vscode-panel-border);
      flex-shrink: 0;
    }
    .tab {
      padding: 8px 16px;
      cursor: pointer;
      font-size: 13px;
      color: var(--vscode-tab-inactiveForeground);
      border-bottom: 2px solid transparent;
      user-select: none;
    }
    .tab.active {
      background: var(--vscode-tab-activeBackground);
      color: var(--vscode-tab-activeForeground);
      border-bottom-color: var(--vscode-focusBorder);
    }
    .tab-content {
      flex: 1;
      overflow-y: auto;
      padding: 16px;
    }
    .tab-panel { display: none; }
    .tab-panel.active { display: block; }

    /* Markdown / summary styling */
    .summary h1, .summary h2, .summary h3 {
      color: var(--vscode-foreground);
      margin: 16px 0 8px;
    }
    .summary h1 { font-size: 1.3em; }
    .summary h2 { font-size: 1.15em; }
    .summary h3 { font-size: 1.05em; }
    .summary p { margin: 8px 0; line-height: 1.6; }
    .summary code {
      font-family: var(--vscode-editor-font-family, monospace);
      background: var(--vscode-textCodeBlock-background);
      padding: 1px 5px;
      border-radius: 3px;
      font-size: 0.9em;
    }
    .summary pre {
      background: var(--vscode-textCodeBlock-background);
      padding: 12px;
      border-radius: 4px;
      overflow-x: auto;
      margin: 8px 0;
    }
    .summary pre code { background: none; padding: 0; }
    .summary ul, .summary ol { margin: 8px 0 8px 24px; }
    .summary li { margin: 4px 0; line-height: 1.5; }
    .summary blockquote {
      border-left: 3px solid var(--vscode-focusBorder);
      padding-left: 12px;
      margin: 8px 0;
      color: var(--vscode-descriptionForeground);
    }
    .summary table { border-collapse: collapse; width: 100%; margin: 8px 0; }
    .summary th, .summary td {
      border: 1px solid var(--vscode-panel-border);
      padding: 6px 10px;
      text-align: left;
    }
    .summary th { background: var(--vscode-editor-selectionBackground); }
    .summary hr { border: none; border-top: 1px solid var(--vscode-panel-border); margin: 16px 0; }

    /* Inline comment cards */
    .comment-card {
      background: var(--vscode-editor-inactiveSelectionBackground);
      border: 1px solid var(--vscode-panel-border);
      border-radius: 6px;
      padding: 12px;
      margin-bottom: 10px;
      border-left: 4px solid var(--vscode-focusBorder);
    }
    .comment-card.critical { border-left-color: #f14c4c; }
    .comment-card.warning { border-left-color: #cca700; }
    .comment-card.suggestion { border-left-color: #4caf50; }
    .comment-header {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-bottom: 8px;
    }
    .severity-pill {
      font-size: 11px;
      padding: 2px 8px;
      border-radius: 10px;
      font-weight: 600;
      text-transform: uppercase;
    }
    .severity-pill.critical { background: #f14c4c22; color: #f14c4c; }
    .severity-pill.warning { background: #cca70022; color: #cca700; }
    .severity-pill.suggestion { background: #4caf5022; color: #4caf50; }
    .file-badge {
      font-family: var(--vscode-editor-font-family, monospace);
      font-size: 11px;
      background: var(--vscode-badge-background);
      color: var(--vscode-badge-foreground);
      padding: 1px 6px;
      border-radius: 3px;
    }
    .comment-text {
      font-size: 13px;
      line-height: 1.5;
      color: var(--vscode-foreground);
    }
    .comment-select {
      margin-left: auto;
    }
    .comment-select input[type="checkbox"] {
      transform: scale(1.2);
      cursor: pointer;
    }
    .no-comments {
      text-align: center;
      color: var(--vscode-descriptionForeground);
      padding: 40px;
    }

    /* Action bar */
    .action-bar {
      background: var(--vscode-panel-background);
      border-top: 1px solid var(--vscode-panel-border);
      padding: 10px 16px;
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
      flex-shrink: 0;
    }
    .action-bar .right {
      margin-left: auto;
      display: flex;
      gap: 8px;
    }
    button {
      padding: 5px 12px;
      border-radius: 3px;
      border: 1px solid var(--vscode-button-border, transparent);
      cursor: pointer;
      font-size: 12px;
      font-family: inherit;
    }
    button.primary {
      background: var(--vscode-button-background);
      color: var(--vscode-button-foreground);
    }
    button.primary:hover { background: var(--vscode-button-hoverBackground); }
    button.secondary {
      background: var(--vscode-button-secondaryBackground);
      color: var(--vscode-button-secondaryForeground);
    }
    button.secondary:hover { background: var(--vscode-button-secondaryHoverBackground); }
    button.danger {
      background: transparent;
      color: #f14c4c;
      border-color: #f14c4c44;
    }
    button.danger:hover { background: #f14c4c15; }
    button.approve {
      background: transparent;
      color: #4caf50;
      border-color: #4caf5044;
    }
    button.approve:hover { background: #4caf5015; }
    button:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }

    .loading-overlay {
      display: none;
      position: fixed;
      top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(0,0,0,0.5);
      align-items: center;
      justify-content: center;
      z-index: 100;
    }
    .loading-overlay.active { display: flex; }
    .spinner {
      color: var(--vscode-foreground);
      font-size: 16px;
    }
  </style>
</head>
<body>
  <div class="header">
    <h2>🤖 AI Code Review — PR #${pr.id}</h2>
    <div class="meta">
      <strong>${escapeHtml(pr.title)}</strong> &nbsp;|&nbsp;
      by ${escapeHtml(pr.author.displayName)} &nbsp;|&nbsp;
      <code>${escapeHtml(pr.source.branch.name)}</code> → <code>${escapeHtml(pr.destination.branch.name)}</code>
      ${prUrl ? `&nbsp;|&nbsp; <a href="#" onclick="openUrl('${escapeHtml(prUrl)}')">Open in browser ↗</a>` : ''}
    </div>
  </div>

  <div class="tabs">
    <div class="tab active" onclick="showTab('summary')">📋 Summary</div>
    <div class="tab" onclick="showTab('comments')">💬 Inline Comments (${reviewResult.inlineComments.length})</div>
  </div>

  <div class="tab-content">
    <div id="tab-summary" class="tab-panel active">
      <div class="summary">${summaryHtml}</div>
    </div>
    <div id="tab-comments" class="tab-panel">
      <div id="comments-container"></div>
    </div>
  </div>

  <div class="action-bar">
    <button class="secondary" onclick="regenerate()">🔄 Regenerate</button>
    <button class="secondary" onclick="copyMarkdown()">📋 Copy Markdown</button>
    <div class="right">
      <button class="secondary" onclick="postInlineComments()">💬 Post Comments</button>
      <button class="secondary" onclick="requestChanges()">⚠️ Request Changes</button>
      <button class="approve" onclick="approve()">✅ Approve</button>
      <button class="secondary" onclick="merge()">🔀 Merge</button>
      <button class="danger" onclick="decline()">❌ Decline</button>
    </div>
  </div>

  <div class="loading-overlay" id="loadingOverlay">
    <div class="spinner">⏳ Generating AI review…</div>
  </div>

  <script>
    const vscode = acquireVsCodeApi();
    const allComments = ${commentsJson};

    function showTab(name) {
      document.querySelectorAll('.tab').forEach((t, i) => {
        t.classList.toggle('active', ['summary', 'comments'][i] === name);
      });
      document.querySelectorAll('.tab-panel').forEach((p) => p.classList.remove('active'));
      document.getElementById('tab-' + name).classList.add('active');
    }

    function renderComments() {
      const container = document.getElementById('comments-container');
      if (allComments.length === 0) {
        container.innerHTML = '<div class="no-comments">✅ No inline comments generated. The code looks good!</div>';
        return;
      }
      container.innerHTML = allComments.map((c, i) => \`
        <div class="comment-card \${c.severity || 'suggestion'}">
          <div class="comment-header">
            <span class="severity-pill \${c.severity || 'suggestion'}">\${(c.severity || 'suggestion').toUpperCase()}</span>
            <span class="file-badge">\${escapeHtml(c.file || '')} : L\${c.line || 0}</span>
            <label class="comment-select">
              <input type="checkbox" class="comment-checkbox" data-index="\${i}" checked>
            </label>
          </div>
          <div class="comment-text">\${escapeHtml(c.comment || '')}</div>
        </div>
      \`).join('');
    }

    function escapeHtml(text) {
      return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
    }

    function getSelectedComments() {
      const checkboxes = document.querySelectorAll('.comment-checkbox:checked');
      return Array.from(checkboxes).map((cb) => allComments[parseInt(cb.dataset.index)]);
    }

    function regenerate() {
      vscode.postMessage({ command: 'regenerate' });
    }

    function copyMarkdown() {
      vscode.postMessage({ command: 'copyMarkdown' });
    }

    function postInlineComments() {
      vscode.postMessage({ command: 'postInlineComments', comments: getSelectedComments() });
    }

    function requestChanges() {
      vscode.postMessage({ command: 'requestChanges', comments: getSelectedComments() });
    }

    function approve() {
      vscode.postMessage({ command: 'approve' });
    }

    function merge() {
      vscode.postMessage({ command: 'merge' });
    }

    function decline() {
      vscode.postMessage({ command: 'decline' });
    }

    function openUrl(url) {
      vscode.postMessage({ command: 'openUrl', text: url });
    }

    window.addEventListener('message', (event) => {
      const msg = event.data;
      if (msg.command === 'setLoading') {
        document.getElementById('loadingOverlay').classList.toggle('active', msg.loading);
      }
    });

    renderComments();
  </script>
</body>
</html>`;
  }

  dispose(): void {
    AiReviewPanel.currentPanel = undefined;
    this._panel.dispose();
    while (this._disposables.length) {
      const d = this._disposables.pop();
      if (d) d.dispose();
    }
  }
}

// ── Lightweight Markdown → HTML converter ────────────────────────────────────

function markdownToHtml(md: string): string {
  let html = escapeHtml(md);

  // Fenced code blocks
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, (_m, lang, code) =>
    `<pre><code class="language-${lang}">${code}</code></pre>`
  );

  // Headings
  html = html.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  html = html.replace(/^## (.+)$/gm, '<h2>$1</h2>');
  html = html.replace(/^# (.+)$/gm, '<h1>$1</h1>');

  // Bold + italic
  html = html.replace(/\*\*\*(.+?)\*\*\*/g, '<strong><em>$1</em></strong>');
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  html = html.replace(/\*(.+?)\*/g, '<em>$1</em>');

  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>');

  // Horizontal rule
  html = html.replace(/^---$/gm, '<hr>');

  // Blockquote
  html = html.replace(/^&gt; (.+)$/gm, '<blockquote>$1</blockquote>');

  // Tables
  html = html.replace(
    /(\|.+\|\n)(\|[-| :]+\|\n)((\|.+\|\n)*)/g,
    (_, header, _sep, rows) => {
      const th = header.split('|').filter((c: string) => c.trim()).map((c: string) => `<th>${c.trim()}</th>`).join('');
      const trs = rows.split('\n').filter((r: string) => r.trim()).map((row: string) => {
        const tds = row.split('|').filter((c: string) => c.trim()).map((c: string) => `<td>${c.trim()}</td>`).join('');
        return `<tr>${tds}</tr>`;
      }).join('');
      return `<table><thead><tr>${th}</tr></thead><tbody>${trs}</tbody></table>`;
    }
  );

  // Unordered lists
  html = html.replace(/((?:^[*\-+] .+\n?)+)/gm, (block) => {
    const items = block.trim().split('\n').map((l: string) => `<li>${l.replace(/^[*\-+] /, '')}</li>`).join('');
    return `<ul>${items}</ul>`;
  });

  // Ordered lists
  html = html.replace(/((?:^\d+\. .+\n?)+)/gm, (block) => {
    const items = block.trim().split('\n').map((l: string) => `<li>${l.replace(/^\d+\. /, '')}</li>`).join('');
    return `<ol>${items}</ol>`;
  });

  // Paragraphs — wrap non-tag lines in <p>
  html = html.replace(/^(?!<[hou\d]|<pre|<block|<table|<ul|<ol|<li|<hr)(.+)$/gm, '<p>$1</p>');

  // Links
  html = html.replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank">$1</a>');

  return html;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
