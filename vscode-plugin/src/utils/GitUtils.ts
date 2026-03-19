import * as vscode from 'vscode';
import * as path from 'path';
import * as fs from 'fs';
import { RepoInfo } from '../models/PullRequest';

/**
 * Detects the Git repository info from the current workspace.
 * Reads the .git/config file and parses the remote URL.
 * GitHub takes priority over Bitbucket when both remotes are present.
 */
export async function detectRepo(context: vscode.ExtensionContext): Promise<RepoInfo | undefined> {
  const workspaceFolders = vscode.workspace.workspaceFolders;
  if (!workspaceFolders || workspaceFolders.length === 0) {
    return undefined;
  }

  for (const folder of workspaceFolders) {
    const gitConfigPath = path.join(folder.uri.fsPath, '.git', 'config');
    if (!fs.existsSync(gitConfigPath)) {
      continue;
    }

    try {
      const configContent = fs.readFileSync(gitConfigPath, 'utf8');
      const remoteUrls = extractRemoteUrls(configContent);

      // Try GitHub first
      for (const url of remoteUrls) {
        const ghInfo = parseGitHubUrl(url);
        if (ghInfo) return ghInfo;
      }

      // Fall back to Bitbucket
      for (const url of remoteUrls) {
        const bbInfo = parseBitbucketUrl(url);
        if (bbInfo) return bbInfo;
      }
    } catch {
      // ignore parse errors, try next folder
    }
  }

  // Try using VS Code's built-in Git extension API
  return detectRepoFromGitExtension();
}

/**
 * Extracts all remote URLs from git config content.
 */
function extractRemoteUrls(configContent: string): string[] {
  const urls: string[] = [];
  const urlRegex = /^\s*url\s*=\s*(.+)$/gm;
  let match: RegExpExecArray | null;
  while ((match = urlRegex.exec(configContent)) !== null) {
    urls.push(match[1].trim());
  }
  return urls;
}

/**
 * Parses a GitHub remote URL into a RepoInfo.
 * Supports both HTTPS and SSH formats.
 */
export function parseGitHubUrl(url: string): RepoInfo | undefined {
  // HTTPS: https://github.com/owner/repo.git
  const httpsMatch = url.match(/https?:\/\/(?:.*@)?github\.com\/([^/]+)\/([^/.]+)(?:\.git)?/i);
  if (httpsMatch) {
    return { provider: 'GITHUB', owner: httpsMatch[1], repoSlug: httpsMatch[2] };
  }

  // SSH: git@github.com:owner/repo.git
  const sshMatch = url.match(/git@github\.com:([^/]+)\/([^/.]+)(?:\.git)?/i);
  if (sshMatch) {
    return { provider: 'GITHUB', owner: sshMatch[1], repoSlug: sshMatch[2] };
  }

  return undefined;
}

/**
 * Parses a Bitbucket Cloud remote URL into a RepoInfo.
 * Supports both HTTPS and SSH formats.
 */
export function parseBitbucketUrl(url: string): RepoInfo | undefined {
  // HTTPS: https://bitbucket.org/workspace/repo.git
  const httpsMatch = url.match(/https?:\/\/(?:.*@)?bitbucket\.org\/([^/]+)\/([^/.]+)(?:\.git)?/i);
  if (httpsMatch) {
    return { provider: 'BITBUCKET', owner: httpsMatch[1], repoSlug: httpsMatch[2] };
  }

  // SSH: git@bitbucket.org:workspace/repo.git
  const sshMatch = url.match(/git@bitbucket\.org:([^/]+)\/([^/.]+)(?:\.git)?/i);
  if (sshMatch) {
    return { provider: 'BITBUCKET', owner: sshMatch[1], repoSlug: sshMatch[2] };
  }

  return undefined;
}

/**
 * Falls back to VS Code's built-in Git extension to detect the repo.
 */
async function detectRepoFromGitExtension(): Promise<RepoInfo | undefined> {
  try {
    const gitExtension = vscode.extensions.getExtension('vscode.git');
    if (!gitExtension) return undefined;

    const git = gitExtension.isActive ? gitExtension.exports : await gitExtension.activate();
    const api = git.getAPI(1);

    if (!api || api.repositories.length === 0) return undefined;

    const repo = api.repositories[0];
    const remotes = repo.state.remotes;

    // Prefer 'origin' remote
    const origin = remotes.find((r: { name: string }) => r.name === 'origin') ?? remotes[0];
    if (!origin) return undefined;

    const remoteUrl = origin.fetchUrl ?? origin.pushUrl ?? '';
    if (!remoteUrl) return undefined;

    return parseGitHubUrl(remoteUrl) ?? parseBitbucketUrl(remoteUrl);
  } catch {
    return undefined;
  }
}

/**
 * Returns the current branch name using VS Code's Git extension.
 */
export async function currentBranch(): Promise<string | undefined> {
  try {
    const gitExtension = vscode.extensions.getExtension('vscode.git');
    if (!gitExtension) return undefined;

    const git = gitExtension.isActive ? gitExtension.exports : await gitExtension.activate();
    const api = git.getAPI(1);

    if (!api || api.repositories.length === 0) return undefined;
    return api.repositories[0].state.HEAD?.name;
  } catch {
    return undefined;
  }
}

/**
 * Returns the absolute path to the workspace root.
 */
export function getWorkspaceRoot(): string | undefined {
  const folders = vscode.workspace.workspaceFolders;
  if (!folders || folders.length === 0) return undefined;
  return folders[0].uri.fsPath;
}

/**
 * Reads a file relative to the workspace root.
 * Returns empty string if not found.
 */
export function readWorkspaceFile(relativePath: string): string {
  const root = getWorkspaceRoot();
  if (!root) return '';
  const fullPath = path.join(root, relativePath);
  try {
    return fs.existsSync(fullPath) ? fs.readFileSync(fullPath, 'utf8') : '';
  } catch {
    return '';
  }
}
