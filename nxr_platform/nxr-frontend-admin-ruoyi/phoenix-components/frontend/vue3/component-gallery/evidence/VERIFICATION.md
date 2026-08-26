# Verification

Version `0.11.1` is verified with locked Node dependencies.

- `npm run catalog:check`
- `npm run lint`
- `npm run typecheck`
- `npm run test`
- `npm run build`

The build produces both the reusable `dist` library and the deployable
`site-dist/index.html` gallery application from the same locked catalog snapshot.

The component test suite covers grouped direct rendering, all 163 reusable components and page patterns,
CRUD controls, overlays, upload and tree interactions, advanced display containers,
responsive layout, Chinese catalog content, and deterministic command copy.

Evidence is recorded in `evidence/build.json` and `evidence/test.json`; dependency
installation is locked by `package-lock.json`. The runnable example is
`example/App.vue`, and native verification lives in `tests/component-gallery.test.ts`
`tests/primitives.test.ts`, `tests/crud-primitives.test.ts`,
`tests/interaction-primitives.test.ts`, `tests/advanced-primitives.test.ts`,
`tests/form-primitives.test.ts`, `tests/platform-primitives.test.ts`,
`tests/business-primitives.test.ts`, `tests/page-patterns.test.ts`,
`tests/auth-primitives.test.ts`, `tests/marketing-primitives.test.ts`,
`tests/admin-primitives.test.ts`, `tests/commerce-primitives.test.ts`,
`tests/live-primitives.test.ts`, and `tests/management-patterns.test.ts`.
`tests/content-primitives.test.ts`, `tests/analytics-primitives.test.ts`, and
`tests/workspace-patterns.test.ts` cover content, messaging, visualization, and workspace pages.
`tests/solution-admin.test.ts`, `tests/solution-analytics.test.ts`, and
`tests/solution-content.test.ts` and `tests/solution-account.test.ts` cover the ready-to-use solution pages. The deterministic
efficiency comparison is exercised by `tests/development-efficiency.test.ts` and recorded
in `evidence/development-efficiency.json`. `tests/theme-provider.test.ts` verifies scoped theme
normalization, reactive theme context, and the absence of document-root mutation.
`tests/product-showcase.test.ts` and `tests/component-request-showcase.test.ts` verify the
Chinese product catalog, safe links, shared component request queue, filtering, and empty states.
`tests/anonymous-feedback-api.test.ts` and `tests/anonymous-feedback-ui.test.ts` verify
anonymous request and feedback submission, public readback, one-time edit credentials,
HTTPS-only credential updates, and the absence of browser credential storage.
Compatibility claims for node, vue, typescript, vite,
and vitest are exercised by those locked test and build routes.
