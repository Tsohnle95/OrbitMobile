import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.orbit.mobile',
  appName: 'Orbit',
  webDir: 'dist',
  server: {
    // Plain-http origin removes mixed-content blocking entirely: the WebView
    // serves the app from http://localhost, so fetches to plain-http tailnet
    // servers are same-scheme requests, not "insecure resource on HTTPS page".
    androidScheme: 'http',
    cleartext: true,
  },
  plugins: {
    Keyboard: {
      resize: 'none',
      resizeOnFullScreen: true,
      autoBackdropColor: 'dom',
    },
    StatusBar: {
      overlaysWebView: true,
      style: 'DEFAULT',
    },
    PushNotifications: {
      presentationOptions: [],
    },
  },
};

export default config;
