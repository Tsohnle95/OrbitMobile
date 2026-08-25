import { describe, expect, test } from 'bun:test';
import { resolveProjectForSessionDirectory } from './projectResolution';

const projects = [
  { id: 'orbit', path: '/workspace/orbit', label: 'Orbit' },
];

describe('resolveProjectForSessionDirectory', () => {
  test('resolves a sibling worktree to its registered project', () => {
    const worktrees = new Map([
      ['/workspace/orbit', [{
        path: '/workspace/orbit-feature',
        projectDirectory: '/workspace/orbit',
        branch: 'feature',
        label: 'feature',
      }]],
    ]);

    expect(resolveProjectForSessionDirectory(projects, worktrees, '/workspace/orbit-feature')).toEqual(projects[0]);
  });
});
