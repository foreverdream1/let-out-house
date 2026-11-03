import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import tailwindcss from "@tailwindcss/vite";
// @ts-expect-error type error without @types/node package
import process from "node:process";
// const host = process.env.TAURI_DEV_HOST;

// https://vite.dev/config/
export default defineConfig(() => ({
  plugins: [
    tailwindcss(),
    vue({
      template: {
        compilerOptions: {
          // 告诉 Vue 所有 mdui- 开头的标签都是原生自定义元素 (Web Components)
          isCustomElement: (tag) => tag.startsWith("mdui-"),
        },
      },
    }),
  ],

  // Vite options tailored for Tauri development and only applied in `tauri dev` or `tauri build`
  //
  // 1. prevent Vite from obscuring rust errors
  clearScreen: false,
  // 2. tauri expects a fixed port, fail if that port is not available
  server: {
    port: 1420,
    strictPort: true,
    host: "0.0.0.0",
    hmr: {
      protocol: "ws",

      port: 1421,
    },
    watch: {
      // 3. tell Vite to ignore watching `src-tauri`
      ignored: ["**/src-tauri/**"],
    },
    proxy: {
      "/api": {
        target: "http://localhost:8080", // 你的真实后端地址
        changeOrigin: true, // 允许跨域
        // 知识库文档上传是同步解析 + 切片 + 向量化，20MB 的 PDF 可能跑好几分钟。
        // vite 代理默认 120s 超时会先断开，前端看到"上传失败"但后端其实还在处理，
        // 所以这里放大到 5 分钟，与前端请求的 timeout 对齐。
        timeout: 300000,
        proxyTimeout: 300000,
        // 如果后端接口本身就带有 /api 前缀，则不需要 rewrite
        // 如果后端真实路径没有 /api，可以取消注释下面这行：
        // rewrite: (path) => path.replace(/^\/api/, ""),
      },
    },
  },
}));
