import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the controller's REST API is proxied, so the app calls /api/*.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      "/api": process.env.EDGEFLEET_API ?? "http://localhost:8090",
    },
  },
});
