import * as vscode from 'vscode';
import { PullRequest } from '../models/PullRequest';
import { PullRequestService } from '../services/PullRequestService';
import { RepoInfo } from '../models/PullRequest';
import { detectRepo } from '../utils/GitUtils';

export type PRFilterState = 'OPEN' | 'MERGED' | 'DECLINED' | 'ALL';

export class PRItem extends vscode.TreeItem {
  constructor(
    public readonly pr: PullRequest,
    public readonly owner: string,
    public readonly repoSlug: string
  ) {
    super(`#${pr.id}  ${pr.title}`, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'pullRequest';
    this.description = `${pr.source.branch.name} → ${pr.destination.branch.name}`;
    this.tooltip = new vscode.MarkdownString(
      `**#${pr.id} — ${pr.title}**\n\n` +
      `**Author:** ${pr.author.displayName}\n\n` +
      `**State:** ${pr.state}\n\n` +
      `**Source:** \`${pr.source.branch.name}\` → \`${pr.destination.branch.name}\`\n\n` +
      (pr.commentCount > 0 ? `**Comments:** ${pr.commentCount}\n\n` : '') +
      (pr.updatedOn ? `**Updated:** ${formatDate(pr.updatedOn)}` : '')
    );
    this.iconPath = stateIcon(pr.state);
  }
}

export class PRFilterItem extends vscode.TreeItem {
  constructor(public readonly label: string) {
    super(label, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'filterItem';
    this.iconPath = new vscode.ThemeIcon('filter');
  }
}

export class PREmptyItem extends vscode.TreeItem {
  constructor(message: string) {
    super(message, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'empty';
    this.iconPath = new vscode.ThemeIcon('info');
  }
}

export class PRLoadingItem extends vscode.TreeItem {
  constructor() {
    super('Loading pull requests…', vscode.TreeItemCollapsibleState.None);
    this.iconPath = new vscode.ThemeIcon('loading~spin');
  }
}

export class PRErrorItem extends vscode.TreeItem {
  constructor(message: string) {
    super(message, vscode.TreeItemCollapsibleState.None);
    this.contextValue = 'error';
    this.iconPath = new vscode.ThemeIcon('error');
    this.tooltip = message;
  }
}

type PRTreeNode = PRItem | PRFilterItem | PREmptyItem | PRLoadingItem | PRErrorItem;

/**
 * TreeView data provider for the PR list.
 * Shows loading state while fetching, error state on failure, PR items on success.
 */
export class PRTreeProvider implements vscode.TreeDataProvider<PRTreeNode> {
  private _onDidChangeTreeData = new vscode.EventEmitter<PRTreeNode | undefined | null | void>();
  readonly onDidChangeTreeData = this._onDidChangeTreeData.event;

  private prs: PullRequest[] = [];
  private repoInfo: RepoInfo | undefined;
  private filterState: PRFilterState = 'OPEN';
  private filterText = '';
  private state: 'idle' | 'loading' | 'error' = 'idle';
  private errorMessage = '';
  private service = new PullRequestService();

  constructor(private readonly context: vscode.ExtensionContext) {}

  getTreeItem(element: PRTreeNode): vscode.TreeItem {
    return element;
  }

  getChildren(element?: PRTreeNode): vscode.ProviderResult<PRTreeNode[]> {
    if (element) return [];

    if (this.state === 'loading') {
      return [new PRLoadingItem()];
    }

    if (this.state === 'error') {
      return [new PRErrorItem(`Error: ${this.errorMessage}`)];
    }

    if (this.prs.length === 0 && this.state === 'idle') {
      return [new PREmptyItem('No pull requests found. Click refresh to load.')];
    }

    const filtered = this.applyFilter(this.prs);
    if (filtered.length === 0) {
      return [new PREmptyItem(`No ${this.filterState} pull requests found.`)];
    }

    return filtered.map((pr) => new PRItem(pr, this.repoInfo!.owner, this.repoInfo!.repoSlug));
  }

  // ── Public API ────────────────────────────────────────────────────────────

  async refresh(): Promise<void> {
    this.state = 'loading';
    this._onDidChangeTreeData.fire();

    try {
      this.repoInfo = await detectRepo(this.context);
      if (!this.repoInfo) {
        this.state = 'error';
        this.errorMessage = 'No Git repository detected. Open a workspace with a GitHub or Bitbucket remote.';
        this._onDidChangeTreeData.fire();
        return;
      }

      this.prs = await this.service.getPullRequests(
        this.repoInfo.owner,
        this.repoInfo.repoSlug,
        this.filterState
      );
      this.state = 'idle';
    } catch (err: unknown) {
      this.state = 'error';
      this.errorMessage = err instanceof Error ? err.message : String(err);
    }

    this._onDidChangeTreeData.fire();
  }

  setFilterState(state: PRFilterState): void {
    this.filterState = state;
    this.refresh();
  }

  setFilterText(text: string): void {
    this.filterText = text.toLowerCase();
    this._onDidChangeTreeData.fire();
  }

  getFilterState(): PRFilterState {
    return this.filterState;
  }

  getRepoInfo(): RepoInfo | undefined {
    return this.repoInfo;
  }

  getService(): PullRequestService {
    return this.service;
  }

  private applyFilter(items: PullRequest[]): PullRequest[] {
    if (!this.filterText) return items;
    return items.filter(
      (pr) =>
        pr.title.toLowerCase().includes(this.filterText) ||
        String(pr.id).includes(this.filterText) ||
        pr.author.displayName.toLowerCase().includes(this.filterText)
    );
  }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function stateIcon(state: string): vscode.ThemeIcon {
  switch (state.toUpperCase()) {
    case 'OPEN': return new vscode.ThemeIcon('git-pull-request', new vscode.ThemeColor('charts.green'));
    case 'MERGED': return new vscode.ThemeIcon('git-merge', new vscode.ThemeColor('charts.purple'));
    case 'DECLINED':
    case 'CLOSED': return new vscode.ThemeIcon('git-pull-request-closed', new vscode.ThemeColor('charts.red'));
    default: return new vscode.ThemeIcon('git-pull-request');
  }
}

function formatDate(isoDate: string): string {
  try {
    const d = new Date(isoDate);
    const now = new Date();
    const diffMs = now.getTime() - d.getTime();
    const diffDays = Math.floor(diffMs / 86400000);
    if (diffDays === 0) return 'today';
    if (diffDays === 1) return 'yesterday';
    if (diffDays < 7) return `${diffDays} days ago`;
    if (diffDays < 30) return `${Math.floor(diffDays / 7)} weeks ago`;
    return d.toLocaleDateString();
  } catch {
    return isoDate;
  }
}
