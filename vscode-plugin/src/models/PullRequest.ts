/**
 * Core PR data models (provider-agnostic).
 * Mirrors the Bitbucket API shape and is used for both GitHub and Bitbucket responses.
 */

export interface PullRequest {
  id: number;
  title: string;
  state: string;              // "OPEN" | "MERGED" | "DECLINED" | "SUPERSEDED"
  author: Author;
  description: string;
  source: RefHolder;
  destination: RefHolder;
  links: Links;
  createdOn: string;
  updatedOn: string;
  commentCount: number;
  taskCount: number;
  fileCount?: number;         // populated after fetching diff stats
}

export interface Author {
  displayName: string;
  nickname: string;
}

export interface RefHolder {
  branch: Branch;
  repository: RepositoryRef;
}

export interface Branch {
  name: string;
}

export interface RepositoryRef {
  fullName: string;
}

export interface Links {
  html: HtmlLink;
}

export interface HtmlLink {
  href: string;
}

export interface DiffStatEntry {
  status: string;             // "ADDED" | "DELETED" | "MODIFIED" | "RENAMED"
  newFile?: FileRef;
  oldFile?: FileRef;
}

export interface FileRef {
  path: string;
}

export interface RepoInfo {
  provider: 'BITBUCKET' | 'GITHUB';
  owner: string;
  repoSlug: string;
}
