# NXR Platform Development Plan

## Purpose

This document defines the upgrade path from the current Flask + SQLite implementation to a new parallel system built under the repository root with:

- `nxr-frontend-web`: Vue user-facing site
- `nxr-frontend-admin`: Vue admin/upload backend UI
- `nxr-backend-java`: Java backend service
- `nxr-sql`: MySQL schema and migration scripts
- `nxr-scripts`: local run, build, and test helpers

This new system must be developed in parallel with the current production codebase. The existing Flask system remains the live reference system until cutover is explicitly approved.

## Phase Status Snapshot

### Completed real slices

- Phase 1:
  - public overview
  - verify lookup
  - public card detail
  - admin login
  - dashboard
  - entry list/detail/create
- Phase 2:
  - folder import by exact cert ID with `A/B` side parsing
  - staged media persistence in `submission_media`
  - explicit publish action that promotes staged media to live published media
  - public card rendering now consumes newly published local media records without legacy Flask dependencies

### Local startup memory

- Backend:
  - `bash ./nxr-scripts/dev-backend.sh`
  - local profile, H2-backed, port `8088`
- Public frontend:
  - `bash ./nxr-scripts/dev-web.sh`
  - port `3000`
- Admin frontend:
  - `bash ./nxr-scripts/dev-admin.sh`
  - port `3001`

## Non-Negotiable Project Memory

- Public admin URL is fixed at `https://nxrgrading.com/x7k9m2q4r8v6c3p1`
- Public `https://nxrgrading.com/admin` is intentionally not the admin entry
- Production main site and admin are separate processes
- Database files under `Data/` must not be synced, replaced, restored, or overwritten without explicit authorization in the current session
- Normal code deploys must use Git-first workflow and exclude runtime data
- Backup retention rule: keep only 2 retained backup copies unless explicitly instructed otherwise

## Current System Analysis

## Runtime Layout

- Root entrypoint: `app.py` -> imports `nxr_site.app`
- User site source: `nxr_site/`
- Admin source: `nxr_admin/`
- Main database: `Data/cards.db`
- Review/workflow database: `Data/temp_cards.db`
- Admin local upload directory: `nxr_admin/uploads/`
- Published site image directory: `nxr_site/static/`

## Current Service Split

### User site

- Flask app in `nxr_site/app.py`
- Production port: `8080`
- Main public domain proxies here

### Admin backend

- Flask app in `nxr_admin/app_updated.py`
- Production port: `8081`
- Nginx rewrites hidden public path to internal `/admin...` routes

## Current User-Facing Features

### Site pages

- `/`
- `/services`
- `/submit`
- `/about`
- `/verify`
- `/query`
- `/upload`
- `/faq`
- `/card/<cert_id>`

### Site APIs

- `GET /api/waitlist_count`
- `POST /api/waitlist`
- `POST /api/ai_character_info`

## Current Admin Features

### Auth and session

- `/admin/login`
- `/admin/logout`
- `/admin`
- `/admin/dashboard`

### Entry workflow

- New entry create
- Entry list with filters, sort, pagination
- Entry detail
- Entry edit
- Single approve
- Batch approve

### Upload workflow

- Upload manager
- Batch ZIP image import by exact 10-digit cert ID
- Single upload to main DB
- Batch upload to main DB
- Mark uploaded entry as client pushed
- Upload stats

### Export and admin operations

- Export approved entries into main DB
- Generate Excel export
- Export history list and delete
- Admin user management

## Current Database Model

## Main DB: `cards`

Primary role: user-facing published card records.

Key fields observed:

- `cert_id` primary key
- `card_name`
- `grade`
- `final_grade`
- `final_grade_text`
- `year`
- `brand`
- `player`
- `variety`
- `pop`
- `image`
- `front_image`
- `back_image`
- `qr_url`
- `centering`
- `edges`
- `corners`
- `surface`
- `language`
- `set_name`
- `card_number`
- `grading_phase`
- `data_version`
- `created_at`
- `updated_at`
- AI-related fields such as `ai_grade`, `ai_confidence`, `ai_model_version`, `ai_front_analysis`, `ai_back_analysis`
- `decision_method`
- `decision_notes`

## Workflow DB: `temp_cards`

Primary role: admin review and upload queue.

Key fields observed:

- `id` primary key
- `cert_id` unique
- `card_name`
- `year`
- `brand`
- `variety`
- `pop`
- `language`
- `set_name`
- `card_number`
- `centering`
- `edges`
- `corners`
- `surface`
- `final_grade`
- `final_grade_text`
- `front_image`
- `back_image`
- `published_front_image`
- `published_back_image`
- `entry_notes`
- `entry_by`
- `entry_date`
- `approved_at`
- `approval_sequence`
- `status`
- `created_at`
- `updated_at`
- `upload_status`
- `upload_started`
- `upload_completed`
- `upload_error`
- `server_response`

## Supporting Data in Main DB

The current site also writes these tables into `cards.db`:

- `waitlist`
- `ai_character_cache`
- `admin_users`

This means the current main DB mixes site content, auth, and operational data. The new system should split these concerns explicitly in MySQL.

## Current Business Flow

## 1. Entry creation

Admin creates a record in `temp_cards` with:

- 10-digit `cert_id`
- card identity fields
- sub-scores
- calculated final grade
- calculated population
- optional front/back images

## 2. Approval

Pending records are approved inside `temp_cards`.

Approval writes:

- `status = approved`
- `approved_at`
- `approval_sequence`
- `updated_at`

## 3. Image queueing

Images may arrive in two ways:

- manual image upload during entry create/edit
- ZIP batch import in `/admin/upload/import-images`

Batch ZIP import rules already in production logic:

- filename must contain exact 10-digit cert ID
- `A` means front
- `B` means back
- matching is exact cert ID equality, not prefix match
- only approved `temp_cards` rows are updated
- published site images are not replaced until the normal upload step runs

## 4. Publish/upload

Admin upload action does all of the following:

- writes record into `cards`
- copies local queued images into `nxr_site/static/`
- updates main DB public image paths
- clears queued local image references in `temp_cards`
- marks upload status progression

## 5. User verification

User enters `cert_id` on `/verify`.

The site reads from `cards` using case-insensitive exact lookup:

- success -> redirect to `/card/<cert_id>`
- miss -> show not found

## 6. Excel export

Approved `temp_cards` data can be exported as Excel with filters and summary sheets.

## Current Technical Risks and Constraints

## Tight coupling

The current system couples these concerns together:

- public site rendering
- admin auth
- workflow queue
- publish pipeline
- operational exports
- AI cache
- waitlist

## Data model duplication

`temp_cards` and `cards` overlap heavily. The new system should preserve the business meaning while making state transitions explicit rather than copying semi-overlapping payloads between two SQLite databases.

## File-based image lifecycle

Images are tracked by filename and copied between folders. This is workable but fragile. The new system should move to object storage-oriented metadata even if local disk remains during development.

## Current local data is not authoritative

Current local SQLite counts are small:

- `cards`: 12
- `temp_cards`: 14

This local workspace should be treated as a development snapshot only, not as the canonical production data baseline.

## Deployment detail that must not be forgotten

- Restarting only `app.py` does not refresh admin code/templates
- Admin backend restart must target `nxr_admin/app_updated.py`
- Hidden admin entry path must remain stable at the public URL above

## Target Upgrade Architecture

## Top-level directory

```text
nxr_platform/
├── nxr-frontend-web/
├── nxr-frontend-admin/
├── nxr-backend-java/
├── nxr-sql/
├── nxr-docs/
├── nxr-scripts/
└── docker-compose.yml
```

## Target system split

### `nxr-frontend-web`

Purpose:

- official site
- verify flow
- card detail page
- submission/waitlist UI

Suggested stack:

- Vue 3
- Vite
- Vue Router
- Pinia
- Element Plus only where useful, not for full marketing-site styling

### `nxr-frontend-admin`

Purpose:

- login
- dashboard
- entry management
- approval
- upload queue
- ZIP batch import
- export operations
- admin user management

Suggested stack:

- Vue 3
- Vite
- Vue Router
- Pinia
- Element Plus

### `nxr-backend-java`

Purpose:

- unified API backend
- auth and permission control
- workflow services
- image metadata services
- export services
- future integration layer for object storage and AI services

Suggested stack:

- Java 21
- Spring Boot 3
- Spring Security
- MyBatis-Plus or MyBatis
- Flyway
- Validation

### `nxr-sql`

Purpose:

- MySQL base schema
- migration scripts
- seed data
- mapping notes from SQLite

## Target Domain Modules

The Java backend should be separated by business domain, not by page.

### Auth

- admin login
- session or token auth
- role and permission enforcement

### Card Catalog

- published cards
- verify lookup
- card detail read APIs

### Review Workflow

- draft/pending/approved status
- score calculation
- population calculation
- review notes

### Upload and Media

- local dev file storage adapter
- future cloud storage adapter
- ZIP import
- front/back image tracking

### Export

- export tasks
- downloadable export files
- operation history

### Waitlist and site forms

- waitlist submit
- optional future submission application forms

### AI content

- character info cache
- provider abstraction

## Proposed MySQL Model

The new schema should not blindly copy the two-DB SQLite shape. It should model states explicitly.

## Core tables

### `admin_user`

- login account
- role
- active state
- last login

### `card_submission`

Represents the admin workflow record currently held in `temp_cards`.

Key columns:

- `id`
- `cert_id`
- `card_name`
- `year`
- `brand`
- `variety`
- `language_code`
- `set_name`
- `card_number`
- `centering_score`
- `edges_score`
- `corners_score`
- `surface_score`
- `final_grade_value`
- `final_grade_label`
- `population_value`
- `entry_notes`
- `entry_by_user_id`
- `status`
- `approved_at`
- `approval_sequence`
- `created_at`
- `updated_at`

### `card_media`

Separate media records from submission payload.

Suggested columns:

- `id`
- `submission_id`
- `cert_id`
- `side` (`front` or `back`)
- `storage_type` (`local`, `object_storage`)
- `storage_key`
- `public_url`
- `source_stage` (`queue`, `published`)
- `checksum`
- `created_at`
- `updated_at`

### `published_card`

Represents the final public card record now stored in `cards`.

Suggested columns:

- `id`
- `cert_id`
- `submission_id`
- `card_name`
- `brand`
- `year`
- `variety`
- `language_code`
- `set_name`
- `card_number`
- `population_value`
- `final_grade_value`
- `final_grade_label`
- `front_image_url`
- `back_image_url`
- `qr_url`
- `published_at`
- `updated_at`

### `upload_job`

Track publish operations explicitly instead of only storing status fields on the submission row.

Suggested columns:

- `id`
- `submission_id`
- `status`
- `started_at`
- `completed_at`
- `error_message`
- `response_payload`
- `triggered_by_user_id`

### `waitlist_email`

- `id`
- `email`
- `status`
- `created_at`

### `ai_character_cache`

- `id`
- `cert_id`
- `language_code`
- `prompt_hash`
- `content_json`
- `rendered_html`
- `model_name`
- `created_at`

### `export_job`

- `id`
- `type`
- `filters_json`
- `record_count`
- `file_path`
- `file_size`
- `created_by_user_id`
- `created_at`

## Status Model

The new backend should standardize workflow statuses.

### Submission status

- `draft`
- `pending`
- `approved`
- `published`
- `archived`

### Upload job status

- `not_started`
- `queued`
- `uploading`
- `uploaded`
- `failed`
- `client_pushed`

## API Design Direction

## Public APIs

- `GET /api/public/cards/{certId}`
- `GET /api/public/verify/{certId}`
- `POST /api/public/waitlist`
- `POST /api/public/ai-character-info`

## Admin APIs

- `POST /api/admin/auth/login`
- `POST /api/admin/auth/logout`
- `GET /api/admin/dashboard`
- `GET /api/admin/submissions`
- `POST /api/admin/submissions`
- `GET /api/admin/submissions/{id}`
- `PUT /api/admin/submissions/{id}`
- `POST /api/admin/submissions/{id}/approve`
- `POST /api/admin/submissions/batch-approve`
- `POST /api/admin/uploads/import-zip`
- `POST /api/admin/uploads/{submissionId}`
- `POST /api/admin/uploads/batch`
- `POST /api/admin/uploads/{submissionId}/client-pushed`
- `POST /api/admin/exports/excel`
- `GET /api/admin/exports`
- `GET /api/admin/admin-users`

## Frontend Route Direction

## `nxr-frontend-web`

- `/`
- `/services`
- `/submit`
- `/about`
- `/verify`
- `/card/:certId`
- `/faq`

## `nxr-frontend-admin`

Public entry requirement remains:

- public route exposed by Nginx: `/x7k9m2q4r8v6c3p1`

Internally the Vue admin app should handle:

- `/login`
- `/dashboard`
- `/entries`
- `/entries/new`
- `/entries/:id`
- `/entries/:id/edit`
- `/upload`
- `/exports`
- `/users`

## Development Phases

## Phase 1

- create `nxr_platform/` structure
- scaffold Vue web app
- scaffold Vue admin app
- scaffold Java backend
- add Docker Compose for MySQL
- add Flyway base migration

## Phase 2

- implement admin auth
- implement admin layout and navigation
- implement submission list API and page
- implement submission create/edit APIs

## Phase 3

- implement approval workflow
- implement population calculation
- implement verify API
- implement public card detail API

## Phase 4

- implement upload manager
- implement ZIP exact-match image import
- implement local dev media adapter
- implement publish pipeline to `published_card`

## Phase 5

- implement Excel export
- implement waitlist API
- implement AI character info integration abstraction

## Phase 6

- migration scripts from SQLite to MySQL
- staging verification
- Nginx cutover plan
- production launch checklist

## Development Rules for the Upgrade Project

- New development happens locally first
- Do not mutate production SQLite data while building the new platform
- Do not wire the new platform directly to current production DBs during initial development
- Use MySQL only inside the new local development environment
- Keep the old Flask system runnable until the new platform is validated
- Preserve the hidden admin public URL during eventual cutover

## Immediate Next Step

After this document is approved, start implementation by creating the base project skeleton under `nxr_platform/` and bringing up a local development environment with:

- Vue web app
- Vue admin app
- Spring Boot backend
- MySQL container
- base migration and health checks
