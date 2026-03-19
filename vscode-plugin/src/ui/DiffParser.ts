/**
 * Data class for a single file entry from a unified diff.
 */
export interface FileDiffEntry {
  displayLabel: string;   // e.g. "src/Foo.ts" or "src/Old.ts → src/New.ts"
  statusTag: 'ADDED' | 'DELETED' | 'MODIFIED' | 'RENAMED';
  oldPath: string;
  newPath: string;
  oldText: string;        // reconstructed old file content from diff
  newText: string;        // reconstructed new file content from diff
}

/**
 * Parses a raw unified diff string into an array of FileDiffEntry objects.
 */
export function parseToEntries(rawDiff: string): FileDiffEntry[] {
  const entries: FileDiffEntry[] = [];
  const filePatches = rawDiff.split(/(?=diff --git )/).filter((s) => s.trim().length > 0);

  for (const patch of filePatches) {
    const lines = patch.split('\n');

    const oldPathLine = lines.find((l) => l.startsWith('--- '));
    const newPathLine = lines.find((l) => l.startsWith('+++ '));

    const oldPath = oldPathLine
      ? oldPathLine.replace(/^--- /, '').replace(/^a\//, '').trim()
      : '';
    const newPath = newPathLine
      ? newPathLine.replace(/^\+\+\+ /, '').replace(/^b\//, '').trim()
      : '';

    const isAdded = oldPath === '/dev/null' || oldPath === 'dev/null' || oldPath.endsWith('/dev/null');
    const isDeleted = newPath === '/dev/null' || newPath === 'dev/null' || newPath.endsWith('/dev/null');
    const isRenamed = !isAdded && !isDeleted && oldPath.length > 0 && newPath.length > 0 && oldPath !== newPath;

    const oldLines: string[] = [];
    const newLines: string[] = [];

    for (const line of lines) {
      if (
        line.startsWith('--- ') ||
        line.startsWith('+++ ') ||
        line.startsWith('diff ') ||
        line.startsWith('index ') ||
        line.startsWith('new file') ||
        line.startsWith('deleted file') ||
        line.startsWith('@@') ||
        line.startsWith('\\')
      ) {
        continue;
      }
      if (line.startsWith('-')) {
        oldLines.push(line.slice(1));
      } else if (line.startsWith('+')) {
        newLines.push(line.slice(1));
      } else {
        const ctx = line.startsWith(' ') ? line.slice(1) : line;
        oldLines.push(ctx);
        newLines.push(ctx);
      }
    }

    const statusTag = isAdded ? 'ADDED' : isDeleted ? 'DELETED' : isRenamed ? 'RENAMED' : 'MODIFIED';
    const displayPath = isAdded
      ? (newPath || oldPath)
      : isDeleted
        ? (oldPath || newPath)
        : isRenamed
          ? `${oldPath} → ${newPath}`
          : (newPath || oldPath || '(unknown)');

    entries.push({
      displayLabel: displayPath,
      statusTag,
      oldPath,
      newPath,
      oldText: isAdded ? '' : oldLines.join('\n'),
      newText: isDeleted ? '' : newLines.join('\n'),
    });
  }

  return entries;
}

/**
 * Splits a raw unified diff into a map of filePath → patchText (keyed by new/b/ path).
 */
export function buildPatchMap(rawDiff: string): Map<string, string> {
  const map = new Map<string, string>();
  const sections = rawDiff.split(/(?=diff --git )/).filter((s) => s.trim().length > 0);

  for (const section of sections) {
    const newPathLine = section.split('\n').find((l) => l.startsWith('+++ '));
    if (!newPathLine) continue;
    const filePath = newPathLine.replace(/^\+\+\+ /, '').replace(/^b\//, '').trim();
    if (filePath && filePath !== '/dev/null') {
      map.set(filePath, section);
    }
  }

  return map;
}
