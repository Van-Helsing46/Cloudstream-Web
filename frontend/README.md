# Cloudstream Web frontend (React + Vite + TS)

Web-first app; potentially reusable later with React Native (mobile) or Tauri (desktop).

## Requirements
- Node.js 20+

## Run
```bash
npm install
npm run dev      # http://localhost:5173 (proxies /api → backend :8080)
```

## Layout
```
src/
├── main.tsx              # bootstrap + React Query
├── App.tsx               # shell, nav and routes
├── types.ts              # types aligned with the backend domain
├── api/client.ts         # typed REST client
├── pages/                # Home, Search, Detail, Library, Extensions, gates
└── components/Player.tsx # hls.js player (HLS) + native fallback
```

## Notes
- In dev the backend must run on `:8080` (Vite proxies `/api` and `/health`).
- Streaming URLs go through the backend proxy, which injects the custom headers.
