import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import express from 'express';
import path from 'node:path';
import request from 'supertest';

vi.mock('child_process', () => ({
  spawn: vi.fn(),
  spawnSync: vi.fn(),
}));

vi.mock('../package-manager.js', () => ({
  checkForUpdates: vi.fn(),
  getUpdateCommand: vi.fn(),
  detectPackageManagerDetails: vi.fn(),
}));

const childProcess = await import('child_process');
const packageManager = await import('../package-manager.js');
const { registerOrbitRoutes } = await import('./orbit-routes.js');

const createApp = ({ environment = {}, storedOptions = {} } = {}) => {
  const app = express();
  const dependencies = {
    fs: {
      existsSync: vi.fn(() => false),
      promises: {
        readFile: vi.fn(async () => JSON.stringify({
          launchMode: 'foreground',
          port: 7897,
          ...storedOptions,
        })),
      },
    },
    path,
    process: {
      env: environment,
      platform: 'linux',
      execPath: '/usr/bin/node',
    },
    server: {
      address: () => ({ port: 7897 }),
    },
    __dirname: '/opt/orbit/server',
    orbitDataDir: '/tmp/orbit',
    modelsDevApiUrl: 'https://models.example.test',
    modelsMetadataCacheTtl: 0,
    readSettingsFromDiskMigrated: vi.fn(),
    fetchFreeZenModels: vi.fn(),
    getCachedZenModels: vi.fn(),
  };

  registerOrbitRoutes(app, dependencies);
  return { app, dependencies };
};

beforeEach(() => {
  packageManager.checkForUpdates.mockResolvedValue({
    available: true,
    version: '1.17.1',
  });
  packageManager.detectPackageManagerDetails.mockReturnValue({
    packageManager: 'npm',
  });
  packageManager.getUpdateCommand.mockReturnValue('npm install -g @orbit/web@latest');
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

describe('Orbit foreground update route', () => {
  it('rejects a foreground update when the server is not owned by systemd', async () => {
    const { app } = createApp();

    await request(app)
      .post('/api/orbit/update-install')
      .expect(409, {
        error: 'Foreground servers must be updated by their service manager. Set ORBIT_SYSTEMD_UNIT when running under systemd, or run orbit update and restart the service.',
      });

    expect(childProcess.spawnSync).not.toHaveBeenCalled();
  });

  it('rejects an unsafe systemd unit override before starting an update job', async () => {
    const { app } = createApp({
      environment: {
        INVOCATION_ID: 'systemd-invocation',
        ORBIT_SYSTEMD_UNIT: 'orbit.service; rm -rf /',
      },
    });

    await request(app)
      .post('/api/orbit/update-install')
      .expect(409, {
        error: 'Foreground servers must be updated by their service manager. Set ORBIT_SYSTEMD_UNIT when running under systemd, or run orbit update and restart the service.',
      });

    expect(childProcess.spawnSync).not.toHaveBeenCalled();
  });

  it('queues the install in a transient systemd unit and returns its job identifier', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_000_000);
    childProcess.spawnSync.mockReturnValue({ status: 0, stdout: '', stderr: '' });
    const { app } = createApp({
      environment: {
        INVOCATION_ID: 'systemd-invocation',
        ORBIT_SYSTEMD_UNIT: 'orbit@wsl.service',
        PATH: '/home/syu/.npm-global/bin:/usr/bin:/bin',
      },
    });

    await request(app)
      .post('/api/orbit/update-install')
      .expect(200, {
        success: true,
        message: 'Update queued; Orbit will restart after installation completes',
        version: '1.17.1',
        packageManager: 'npm',
        autoRestart: true,
        restartManager: 'systemd',
        jobId: 'orbit-update-1700000000000',
        logPath: 'journalctl --user-unit orbit-update-1700000000000.service',
      });

    expect(childProcess.spawnSync).toHaveBeenCalledWith('systemd-run', [
      '--user',
      '--unit=orbit-update-1700000000000',
      '--collect',
      '--service-type=exec',
      '--setenv=PATH=/home/syu/.npm-global/bin:/usr/bin:/bin',
      '/bin/sh',
      '-c',
      "set -eu\nnpm install -g @orbit/web@latest\nsystemctl --user restart 'orbit@wsl.service'",
    ], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 5000,
    });
  });
});
