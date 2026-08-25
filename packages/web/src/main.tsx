import { createConfiguredWebAPIs, getDesktopRelayRestoreReady } from './runtimeConfig';
import { registerSW } from 'virtual:pwa-register';

import type { RuntimeAPIs } from '@orbit/ui/lib/api/types';
import { resolveHostedSurface, watchHostedSurfaceViewport, type HostedSurface } from '@orbit/ui/lib/runtimeSurface';
import {
  isEmbeddedSessionChat,
  requestEmbeddedSessionRuntimeBootstrap,
} from '@orbit/ui/components/layout/contextPanelEmbeddedChat';
import '@orbit/ui/index.css';
import '@orbit/ui/styles/fonts';

declare global {
  interface Window {
    __ORBIT_RUNTIME_APIS__?: RuntimeAPIs;
    __ORBIT_SURFACE__?: HostedSurface;
  }
}

const hostedSurface: HostedSurface = resolveHostedSurface();

type PrerenderingDocument = Document & {
  prerendering?: boolean;
};

const canUseServiceWorker = (): boolean => {
  if (!('serviceWorker' in navigator)) return false;
  if (!window.isSecureContext) return false;
  if (window.location.protocol !== 'http:' && window.location.protocol !== 'https:') return false;

  const documentState = document as PrerenderingDocument;
  if (documentState.prerendering || String(document.visibilityState) === 'prerender') {
    return false;
  }

  return true;
};

const runWhenDocumentCanRegisterServiceWorker = (task: () => void): void => {
  let completed = false;
  const run = () => {
    if (completed) return;
    if (canUseServiceWorker()) {
      completed = true;
      task();
    }
  };

  const afterLoad = () => {
    setTimeout(run, 0);
  };

  if (document.readyState === 'complete') {
    afterLoad();
  } else {
    window.addEventListener('load', afterLoad, { once: true });
  }

  const documentState = document as PrerenderingDocument;
  if (documentState.prerendering || String(document.visibilityState) === 'prerender') {
    document.addEventListener('visibilitychange', run, { once: true });
  }
};

const registerPwaServiceWorker = (): void => {
  runWhenDocumentCanRegisterServiceWorker(() => {
    try {
      registerSW({
        onRegisterError(error: unknown) {
          console.warn('[PWA] service worker registration skipped:', error);
        },
      });
    } catch (error) {
      console.warn('[PWA] service worker registration skipped:', error);
    }
  });
};

const unregisterDevelopmentServiceWorkers = (): void => {
  runWhenDocumentCanRegisterServiceWorker(() => {
    void navigator.serviceWorker.getRegistrations()
      .then((registrations) => Promise.all(registrations.map((registration) => registration.unregister())))
      .catch(() => {});
  });
};

const start = async (): Promise<void> => {
  const embeddedBootstrap = isEmbeddedSessionChat()
    ? await requestEmbeddedSessionRuntimeBootstrap()
    : null;
  window.__ORBIT_RUNTIME_APIS__ = createConfiguredWebAPIs(embeddedBootstrap);

  // Reload into the other app shell when the viewport crosses the phone
  // threshold after boot (no-op in fixed shells and with ?surface= overrides).
  watchHostedSurfaceViewport();

  if (hostedSurface === 'mobile') {
    const { renderMobileApp } = await import('@orbit/ui/apps/renderMobileApp');
    renderMobileApp(window.__ORBIT_RUNTIME_APIS__);
    return;
  }

  // Hold the render until a desktop relay-host restore has picked its transport.
  await getDesktopRelayRestoreReady();
  await import('@orbit/ui/main');
};

void start();

if (import.meta.hot) {
  import.meta.hot.on('orbit:theme-updated', (theme: unknown) => {
    window.dispatchEvent(new CustomEvent('orbit:theme-hmr', { detail: theme }));
  });
}

if (import.meta.env.PROD) {
  registerPwaServiceWorker();
} else {
  unregisterDevelopmentServiceWorkers();
}
