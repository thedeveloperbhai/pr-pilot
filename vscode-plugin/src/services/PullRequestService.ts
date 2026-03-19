import { BitbucketClient } from '../bitbucket/BitbucketClient';
import { GitHubClient } from '../github/GitHubClient';
import { DiffStatEntry, PullRequest } from '../models/PullRequest';
import { InlineComment } from '../models/InlineComment';
import { Settings } from '../settings/Settings';

/**
 * Provider-agnostic PR service. Delegates to BitbucketClient or GitHubClient
 * based on the configured GitProvider setting.
 */
export class PullRequestService {
  private bbClientCache = new Map<string, { token: string; client: BitbucketClient }>();

  private async getBitbucketClient(owner: string, repo: string): Promise<BitbucketClient> {
    const settings = Settings.instance;
    const key = `${owner.toLowerCase()}/${repo.toLowerCase()}`;
    const token = await settings.getBitbucketToken(owner, repo);

    if (!token) {
      throw new Error(
        `No Access Token configured for Bitbucket repo '${key}'.\n` +
        `Click the settings icon in the PR Pilot panel and add a token for this repo.\n` +
        `(Workspace: '${owner.toLowerCase()}', Repo Slug: '${repo.toLowerCase()}')`
      );
    }

    // Invalidate cached client if token changed
    const cached = this.bbClientCache.get(key);
    if (cached && cached.token === token) return cached.client;

    const client = new BitbucketClient(token);
    this.bbClientCache.set(key, { token, client });
    return client;
  }

  private async getGitHubClient(): Promise<GitHubClient> {
    const pat = await Settings.instance.getGitHubPat();
    if (!pat) {
      throw new Error('GitHub PAT is not configured. Open PR Pilot Settings and add your GitHub Personal Access Token.');
    }
    return new GitHubClient(pat);
  }

  private get isGitHub(): boolean {
    return Settings.instance.gitProvider === 'GITHUB';
  }

  // ── Pull Requests ─────────────────────────────────────────────────────────

  async getPullRequests(owner: string, repo: string, state = 'OPEN'): Promise<PullRequest[]> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).getPullRequests(owner, repo, state);
    }
    return (await this.getBitbucketClient(owner, repo)).getPullRequests(owner, repo, state);
  }

  async getPullRequestDetails(owner: string, repo: string, prId: number): Promise<PullRequest> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).getPullRequestDetails(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).getPullRequestDetails(owner, repo, prId);
  }

  async getPullRequestDiff(owner: string, repo: string, prId: number): Promise<string> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).getPullRequestDiff(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).getPullRequestDiff(owner, repo, prId);
  }

  async getDiffStat(owner: string, repo: string, prId: number): Promise<DiffStatEntry[]> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).getDiffStat(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).getDiffStat(owner, repo, prId);
  }

  // ── Actions ───────────────────────────────────────────────────────────────

  async approvePullRequest(owner: string, repo: string, prId: number): Promise<void> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).approvePullRequest(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).approvePullRequest(owner, repo, prId);
  }

  async declinePullRequest(owner: string, repo: string, prId: number): Promise<void> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).declinePullRequest(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).declinePullRequest(owner, repo, prId);
  }

  async mergePullRequest(owner: string, repo: string, prId: number): Promise<void> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).mergePullRequest(owner, repo, prId);
    }
    return (await this.getBitbucketClient(owner, repo)).mergePullRequest(owner, repo, prId);
  }

  async postComment(owner: string, repo: string, prId: number, body: string): Promise<void> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).postComment(owner, repo, prId, body);
    }
    return (await this.getBitbucketClient(owner, repo)).postComment(owner, repo, prId, body);
  }

  async postInlineComments(owner: string, repo: string, prId: number, comments: InlineComment[]): Promise<void> {
    if (comments.length === 0) return;
    if (this.isGitHub) {
      return (await this.getGitHubClient()).submitReview(owner, repo, prId, 'COMMENT', '', comments);
    }
    return (await this.getBitbucketClient(owner, repo)).postInlineComments(owner, repo, prId, comments);
  }

  async requestChanges(
    owner: string,
    repo: string,
    prId: number,
    body: string,
    comments: InlineComment[]
  ): Promise<void> {
    if (this.isGitHub) {
      return (await this.getGitHubClient()).submitReview(owner, repo, prId, 'REQUEST_CHANGES', body, comments);
    }
    return (await this.getBitbucketClient(owner, repo)).requestChanges(owner, repo, prId, body, comments);
  }
}
