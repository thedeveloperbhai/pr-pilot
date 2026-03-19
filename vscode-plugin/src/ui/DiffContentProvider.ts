import * as vscode from 'vscode';

export interface DiffContent {
  oldText: string;
  newText: string;
  title: string;
}

/**
 * VS Code TextDocumentContentProvider for serving diff content.
 * Scheme: pr-pilot
 * URI format: pr-pilot://diff/<prId>/<side>/<encoded-filename>
 * where side = "old" | "new"
 */
export class DiffContentProvider implements vscode.TextDocumentContentProvider {
  static readonly scheme = 'pr-pilot';
  private readonly _store = new Map<string, string>();
  private readonly _onDidChange = new vscode.EventEmitter<vscode.Uri>();

  readonly onDidChange = this._onDidChange.event;

  /**
   * Register both sides of a diff and return the URIs to pass to vscode.diff.
   */
  registerDiff(prId: string | number, filename: string, oldText: string, newText: string): { oldUri: vscode.Uri; newUri: vscode.Uri } {
    const encoded = encodeURIComponent(filename);
    const oldUri = vscode.Uri.parse(`${DiffContentProvider.scheme}://diff/${prId}/old/${encoded}`);
    const newUri = vscode.Uri.parse(`${DiffContentProvider.scheme}://diff/${prId}/new/${encoded}`);
    this._store.set(oldUri.toString(), oldText);
    this._store.set(newUri.toString(), newText);
    return { oldUri, newUri };
  }

  /**
   * Clear all cached diff content for a PR when it is no longer needed.
   */
  clearPr(prId: string | number): void {
    const prefix = `${DiffContentProvider.scheme}://diff/${prId}/`;
    for (const key of this._store.keys()) {
      if (key.startsWith(prefix)) {
        this._store.delete(key);
      }
    }
  }

  provideTextDocumentContent(uri: vscode.Uri): string {
    return this._store.get(uri.toString()) ?? '';
  }
}
