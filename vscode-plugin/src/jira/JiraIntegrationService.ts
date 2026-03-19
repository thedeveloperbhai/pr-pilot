import { PullRequest } from '../models/PullRequest';
import { Settings } from '../settings/Settings';
import { JiraClient, JiraUser } from './JiraClient';

export type ReviewOutcome = 'APPROVED' | 'MERGED' | 'DECLINED' | 'CHANGES_REQUESTED';

export type JiraSyncStatus = 'UPDATED' | 'SKIPPED_NOT_CONFIGURED' | 'SKIPPED_NO_ISSUE';

export interface JiraSyncResult {
  status: JiraSyncStatus;
  issueKey?: string;
  reassigned?: boolean;
  assigneeLabel?: string;
}

export function jiraSyncMessage(result: JiraSyncResult): string | undefined {
  if (result.status !== 'UPDATED') return undefined;
  let msg = 'JIRA';
  if (result.issueKey) msg += ` ${result.issueKey}`;
  msg += ' updated';
  if (result.reassigned && result.assigneeLabel) {
    msg += ` and reassigned to ${result.assigneeLabel}`;
  }
  return msg + '.';
}

/**
 * Syncs PR review outcomes to JIRA Cloud.
 * Detects the issue key from the PR title, description, or branch name.
 */
export class JiraIntegrationService {
  async syncReviewOutcome(
    pr: PullRequest,
    outcome: ReviewOutcome,
    summary?: string
  ): Promise<JiraSyncResult> {
    const settings = Settings.instance;
    const baseUrl = settings.jiraBaseUrl;
    const email = settings.jiraEmail;
    const token = await settings.getJiraApiToken();

    if (!baseUrl || !email || !token) {
      return { status: 'SKIPPED_NOT_CONFIGURED' };
    }

    const issueKey = findAttachedIssueKey(pr, settings.jiraIssueKeyPattern);
    if (!issueKey) {
      return { status: 'SKIPPED_NO_ISSUE' };
    }

    const client = new JiraClient(baseUrl, email, token);

    // Verify issue exists
    await client.getIssue(issueKey);

    const commentBody = buildCommentBody(outcome, summary);
    await client.addPlainTextComment(issueKey, commentBody);

    if (outcome === 'APPROVED' || outcome === 'MERGED') {
      return { status: 'UPDATED', issueKey };
    }

    // Attempt to assign to PR author for DECLINED / CHANGES_REQUESTED
    const assignee = await resolvePrAuthor(pr, client);
    if (assignee) {
      await client.assignIssue(issueKey, assignee.accountId);
    }

    return {
      status: 'UPDATED',
      issueKey,
      reassigned: !!assignee,
      assigneeLabel: assignee?.displayName ?? pr.author.displayName,
    };
  }
}

function buildCommentBody(outcome: ReviewOutcome, summary?: string): string {
  switch (outcome) {
    case 'APPROVED':
    case 'MERGED':
      return 'Code Review Passed.';
    case 'DECLINED':
      return summary?.trim() || 'PR was declined. AI summary was unavailable.';
    case 'CHANGES_REQUESTED':
      return summary?.trim() || 'Changes requested on PR. AI summary was unavailable.';
  }
}

async function resolvePrAuthor(pr: PullRequest, client: JiraClient): Promise<JiraUser | undefined> {
  const searchTerms = [pr.author.nickname, pr.author.displayName]
    .map((t) => t.trim())
    .filter((t) => t.length > 0)
    .filter((t, i, arr) => arr.indexOf(t) === i);

  for (const term of searchTerms) {
    try {
      const matches = (await client.searchUsers(term)).filter((u) => u.active);

      const exact = matches.find(
        (u) =>
          u.displayName.toLowerCase() === term.toLowerCase() ||
          u.emailAddress?.toLowerCase() === term.toLowerCase()
      );
      if (exact) return exact;

      if (matches.length === 1) return matches[0];
    } catch {
      // ignore per-term search failures
    }
  }
  return undefined;
}

function findAttachedIssueKey(pr: PullRequest, configuredPattern: string): string | undefined {
  let pattern: RegExp;
  try {
    pattern = new RegExp(configuredPattern || '[A-Z][A-Z0-9]+-\\d+', 'i');
  } catch {
    pattern = /[A-Z][A-Z0-9]+-\d+/i;
  }

  const sources = [
    pr.title,
    pr.description,
    pr.source.branch.name,
    pr.destination.branch.name,
    pr.links.html.href,
  ];

  for (const src of sources) {
    const match = pattern.exec(src);
    if (match) return match[0].toUpperCase();
  }
  return undefined;
}
