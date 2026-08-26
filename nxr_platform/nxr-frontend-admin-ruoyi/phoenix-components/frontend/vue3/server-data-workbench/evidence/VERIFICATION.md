# Server Data Workbench verification

Candidate: `frontend.vue3.server-data-workbench@0.1.0` (`experimental`)

## Native paths

- entrypoint: `src/index.ts`
- example: `example/App.vue`
- test: `tests/server-data-workbench.test.ts`
- dependency lock: `package-lock.json`
- build report: `evidence/build.json`
- test report: `evidence/test.json`

## Compatibility claims

All claims are exact and are bound to `tests/server-data-workbench.test.ts`; broader ranges are not
claimed.

- `node`: `25.8.1`
- `vue`: `3.5.41`
- `typescript`: `6.0.3`
- `vite`: `8.2.1`
- `vitest`: `4.1.10`

## Commands and results

Run from `frontend/vue3/server-data-workbench/` on macOS arm64:

| Command | Result |
| --- | --- |
| `npm ci` | passed; 272 packages added, 273 audited, 0 vulnerabilities |
| `npm run lint` | passed |
| `npm run typecheck` | passed |
| `npm run test` | passed; 1 file, 8 tests |
| `npm run build` | passed; ESM 7.83 kB and CSS 3.64 kB before gzip |
| `npm run verify` | passed; lint, typecheck, 8 tests, and library build |
| `python3 ../../../src/pcl.py validate --strict frontend/vue3/server-data-workbench` | passed; 1 component validated |

The JSON reports are inert evidence. The repository manager does not execute commands from a
component manifest or evidence file.
