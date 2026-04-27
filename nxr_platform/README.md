# NXR Platform

Parallel upgrade workspace for the new NXR stack.

## Directories

- `nxr-frontend-web`: Vue public frontend
- `nxr-frontend-admin`: Vue admin frontend
- `nxr-backend-java`: Spring Boot backend
- `nxr-docs`: upgrade documentation
- `nxr-scripts`: local run helpers

## Local Ports

- Public web: `3000`
- Admin web: `3001`
- Java backend: `8088`
- MySQL: `3306`

## Quick Start

1. `cd nxr_platform && docker compose up -d`
2. `./nxr-scripts/dev-backend.sh`
3. `./nxr-scripts/dev-web.sh`
4. `./nxr-scripts/dev-admin.sh`

If the scripts do not have execute permission in the local workspace, run them with `bash`:

- `bash ./nxr-scripts/dev-backend.sh`
- `bash ./nxr-scripts/dev-web.sh`
- `bash ./nxr-scripts/dev-admin.sh`

## Current Real Feature Slices

- Public web:
  - homepage metrics and featured cards from live API data
  - verify and card detail pages backed by published certificate records
- Admin workspace:
  - login, dashboard, entries list, detail, and pending submission creation
  - folder-based media import with exact cert-ID filename matching
  - staged media preview and explicit publish to live certificate media
- Backend:
  - Spring Boot + Flyway schema
  - local H2 profile for development
  - MySQL-oriented default configuration for target deployment

## Upload Naming Rules

- Folder import accepts image names like `NXR2026042602_A.jpg`, `VRA003_B.webp`, or `5703018202_B_1.png`
- `A` maps to `front`, `B` maps to `back`
- Matching is exact against existing `cert_id`
- Re-import replaces the staged side for the same cert
- Live published media is not replaced until the publish action is triggered
