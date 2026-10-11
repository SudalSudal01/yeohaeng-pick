import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// 브라우저는 같은 출처의 /api만 호출합니다. 개발 프록시가 Java 서버로 전달하므로
// 세션 쿠키와 CSRF 보호를 유지하면서 프론트/백엔드를 별도로 실행할 수 있습니다.
export default defineConfig(({ mode }) => {
  // 루트 .env의 API 포트를 개발 프록시에도 적용합니다. 키를 클라이언트 define에 넣지 않습니다.
  const env = loadEnv(mode, "..", "");
  const apiTarget = `http://127.0.0.1:${env.SERVER_PORT || "18081"}`;
  return {
    plugins: [react()],
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        "/api": apiTarget,
      },
    },
  };
});
