import { createConfiguredWebAPIs } from './runtimeConfig';
import type { RuntimeAPIs } from '@orbit/ui/lib/api/types';
import '@orbit/ui/index.css';
import '@orbit/ui/styles/fonts';

declare global {
  interface Window {
    __ORBIT_RUNTIME_APIS__?: RuntimeAPIs;
  }
}

window.__ORBIT_RUNTIME_APIS__ = createConfiguredWebAPIs();

void import('@orbit/ui/apps/renderMobileApp')
  .then(({ renderMobileApp }) => {
    renderMobileApp(window.__ORBIT_RUNTIME_APIS__ ?? createConfiguredWebAPIs());
  });
