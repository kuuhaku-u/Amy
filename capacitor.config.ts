import type { CapacitorConfig } from "@capacitor/cli";

const config: CapacitorConfig = {
  appId: "app.monthlyspend.mobile",
  appName: "Monthly Spend",
  webDir: "dist",
  server: { androidScheme: "https" },
  android: { allowMixedContent: false }
};

export default config;
