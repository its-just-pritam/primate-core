# Platito backend

Kotlin and Spring Boot service that owns Platito's data in PostgreSQL and syncs Garmin Connect in the background. It replaces the Google Sheets "database" and copies the Sheet into Postgres once a day (see [Google Sheets sync](#google-sheets-sync)). The Next.js app's `/api/*` routes now forward to the same paths here.

- **Stack:** Kotlin 2.2, Spring Boot 3.5 (Web, JDBC `JdbcClient`), PostgreSQL 16 (local or [Supabase](#use-supabase)), Liquibase, ShedLock, Google Auth Library, JDK 21.
- **Auth:** verifies the Next.js session token (`Authorization: Bearer …`) with the shared `AUTH_SECRET`. Only `POST /api/auth/signup`, `POST /api/auth/login` and `GET /api/status` are public.

## Run locally

```bash
cd primate-core
cp .env.example .env              # set AUTH_SECRET (same as the web app) and GARMIN_API_KEY_ENCRYPTION_KEY
                                  # and POSTGRES_PORT=5434, the port bootRun connects to by default
docker compose up -d postgres
set -a; source .env; set +a
./gradlew bootRun                 # http://localhost:8080, migrations run on startup
```

Or run the whole stack with `docker compose up --build`. Then start the web app from the repository root with `BACKEND_URL=http://localhost:8080` in `.env.local`.

| Variable | Required | Description |
| --- | --- | --- |
| `AUTH_SECRET` | yes | Same value as the Next.js app; at least 32 characters |
| `GARMIN_API_KEY_ENCRYPTION_KEY` | yes | Base64 of 32 random bytes; encrypts stored Garmin API keys (AES-GCM). Changing it makes stored keys unreadable, and users must reconnect |
| `DATABASE_URL` / `DATABASE_USERNAME` / `DATABASE_PASSWORD` | no | JDBC URL and credentials (default `jdbc:postgresql://localhost:5434/platito`, `platito`/`platito`) |
| `DATABASE_SCHEMA` | no | Schema that holds the tables; created on startup if missing. Default `public` |
| `DATABASE_POOL_SIZE` | no | Maximum database connections, default 10 |
| `GARMIN_SYNC_ENABLED` | no | `false` disables the Garmin job |
| `GARMIN_SYNC_CRON` | no | Spring cron (UTC) for the Garmin job; default every 30 minutes |
| `GARMIN_API_BASE_URL` | no | Garmin Connect API, default `https://personal.connectapi.garmin.com` |
| `SHEETS_SYNC_ENABLED` | no | `true` turns on the Google Sheets job; default `false` |
| `SHEETS_SYNC_CRON` | no | Spring cron for the Sheets job; default `0 0 12 * * *` (daily at 12:00) |
| `SHEETS_SYNC_ZONE` | no | Time zone of `SHEETS_SYNC_CRON` and of Sheet timestamps without an offset; default `UTC` |
| `GOOGLE_SHEET_ID` | with Sheets sync | ID of the Sheet, from its URL (`docs.google.com/spreadsheets/d/<id>/…`) |
| `GOOGLE_SERVICE_ACCOUNT_EMAIL` / `GOOGLE_PRIVATE_KEY` | with Sheets sync | Service account that can read the Sheet; the key is the PEM `private_key` from its JSON key file (literal `\n` sequences are accepted) |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | no | Login for the [admin page](#admin-page); without both, `/admin` answers 404 |
| `PORT` | no | HTTP port, default 8080 |

Docker compose also reads `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_PORT` and `BACKEND_PORT` for the local stack (see `.env.example`).

Health check: `GET /actuator/health`.

## Use Supabase

Any PostgreSQL works; for a Supabase project, set these in `.env`:

```bash
DATABASE_URL=jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
DATABASE_USERNAME=postgres.<project-ref>
DATABASE_PASSWORD=<database password>
DATABASE_SCHEMA=platito
DATABASE_POOL_SIZE=5
```

- **Connection:** take the host and user from the project's **Connect** dialog, under **Session pooler**. It works over IPv4 and keeps session settings. The direct connection (`db.<project-ref>.supabase.co`, user `postgres`) also works, but only over IPv6 unless the project has the IPv4 add-on. Don't use the transaction pooler (port 6543): it doesn't keep the schema setting between transactions.
- **Schema:** keep the tables out of `public`. Supabase exposes `public` through its Data API, which would make tables such as `app_user` (with password hashes) readable with the project's public anon key. The backend creates `DATABASE_SCHEMA` and runs its migrations there on startup.
- **Pool size:** the session pooler allows only a few connections per client on smaller plans, so keep `DATABASE_POOL_SIZE` low.
- **Docker:** start only the backend, without the local Postgres: `docker compose up --build --no-deps backend`.

## Release plan

The database runs on Supabase and the backend on Render's free plan. A free Render instance sleeps after 15 minutes without requests and wakes on the next one, which takes about a minute. Scheduled jobs can't wake it, so the daily Sheets sync is turned off and you run it by hand from the admin page.

### 1. Create the Supabase database

1. Create a Supabase project. Pick a region close to the Render region you'll use (for example Frankfurt for both), because every query crosses between the two.
2. In the project's **Connect** dialog, under **Session pooler**, copy the host and user. Use the session pooler: Render connects over IPv4, and the direct `db.<project-ref>.supabase.co` host only works over IPv6 without Supabase's IPv4 add-on. Don't use the transaction pooler (port 6543), which loses the schema setting. See [Use Supabase](#use-supabase).
3. Optional but worth it: test against it from your machine first. Put the [Supabase settings](#use-supabase) in `.env` and run `./gradlew bootRun`. A clean startup means the connection works and the migrations ran in the `platito` schema. Then **Run now** on the admin page checks the Google credentials too.

Free Supabase projects are paused after a period of inactivity; resume a paused project from the Supabase dashboard.

### 2. Put the code in Git

Render deploys from GitHub, GitLab or Bitbucket. Push the code there without `.env` (it is in `.gitignore`). If `primate-core` is a folder in a larger repository, use it as the root directory in the next step.

### 3. Create the Render web service

In the Render dashboard, choose **New → Web Service**, pick the repository and set:

| Setting | Value |
| --- | --- |
| Language | Docker |
| Region | Close to the Supabase region |
| Root Directory | `primate-core` if the backend is a folder in the repository; otherwise empty |
| Dockerfile Path | `./Dockerfile` |
| Instance Type | Free |
| Health Check Path (under Advanced) | `/actuator/health` |

Under **Environment**, set:

| Variable | Value |
| --- | --- |
| `AUTH_SECRET` | Same value as the Next.js app |
| `GARMIN_API_KEY_ENCRYPTION_KEY` | Base64 of 32 random bytes (`openssl rand -base64 32`). Keep it: a new key makes stored Garmin keys unreadable |
| `DATABASE_URL` | `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require` |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | `postgres.<project-ref>` / the database password |
| `DATABASE_SCHEMA` | `platito`, which keeps the tables out of Supabase's Data API |
| `DATABASE_POOL_SIZE` | `5` |
| `SHEETS_SYNC_ENABLED` | `false`; you sync by hand (see [Run it](#run-it)) |
| `GOOGLE_SHEET_ID`, `GOOGLE_SERVICE_ACCOUNT_EMAIL`, `GOOGLE_PRIVATE_KEY` | Needed for the manual sync too. Paste the key as the multi-line PEM or on one line with `\n` |
| `ADMIN_USERNAME` / `ADMIN_PASSWORD` | Admin page login; use a long random password |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75`, which keeps the JVM inside the free instance's 512 MB |

Don't set `PORT`; Render sets it and the backend listens on it. Leave `GARMIN_SYNC_ENABLED` unset: the Garmin job then runs whenever the instance happens to be awake.

### 4. Deploy and check

Create the service. Render builds the image, and the backend runs the migrations on startup. It is live when the health check passes:

1. `https://<service>.onrender.com/actuator/health` answers `{"status":"UP"}`.
2. `https://<service>.onrender.com/admin` asks for the admin login and shows both jobs.

### 5. Load the data

On the admin page, press **Run now** on **Google Sheets sync** and wait for the run to show up under **Google Sheets sync runs** as succeeded. Read its warnings: they list rows that were skipped and why.

Data that exists only in a local Postgres and not in the Sheet, such as Garmin connections made through the app, isn't copied. Those users reconnect Garmin in the app.

### 6. Point the web app at it

Set `BACKEND_URL=https://<service>.onrender.com` in the Next.js app's environment and redeploy it.

### Run it

- **Waking up:** the first request after 15 quiet minutes waits about a minute while the backend starts. The web app gives up on a backend call after 60 seconds (`lib/backend.ts` in the web app), so that first request can fail and work on a retry. Cached pages aren't affected; logging in, saving and uncached pages are.
- **Syncing the Sheet:** open `/admin` (wait for it to wake up), then **Run now** on **Google Sheets sync**. The sync carries on in the background if you close the tab, and the backend stays up at least 15 minutes after your last request, which is plenty.
- **Garmin:** the 30-minute job only runs while the backend is awake. Users' summaries for today and yesterday are refreshed when they read them anyway, and **Sync Garmin** on the admin page syncs one user by hand.
- **Releasing changes:** every push to the deployed branch builds and deploys again. Migrations run on startup.
- **Logs:** the service's **Logs** tab in Render. Each Sheets sync logs a summary line, and failed runs show their error on the admin page.
- **Free hours:** Render gives 750 free instance hours a month per workspace. A backend that sleeps most of the day uses far fewer.

### When to move off the free plan

Switch the instance type to **Starter** when cold starts get in users' way or you want the Sheets sync to run on its own. Then set `SHEETS_SYNC_ENABLED=true`; the sync runs daily at 12:00 UTC (`SHEETS_SYNC_CRON`, `SHEETS_SYNC_ZONE`). Nothing else changes.

## Admin page

`http://localhost:8080/admin` is an operations page served by the backend. Set `ADMIN_USERNAME` and `ADMIN_PASSWORD` to turn it on; the browser asks for them. Without both, `/admin` answers 404.

The page has three tabs. The open tab is kept in the URL (`/admin#sheets`, `#garmin`, `#users`), and each tab's label flags a running job or a failure so it shows from any tab.

- **Google Sheets:** the Sheets job with its schedule, next run and whether it is running, plus **Run now**, and the last 20 sync runs with status, duration, totals, per-table counts and warnings.
- **Garmin:** the Garmin job with **Run now**, then sync health: connected users, last sync outcome counts, and users whose last sync failed with the error and a **Sync Garmin** button for that user.
- **Users:** searchable list with onboarding progress, meal and workout counts, time zone and Garmin status (up to 1,000 users).

**Run now** takes the same ShedLock lock as the scheduled run, so the two never overlap on any instance; it works even when the schedule is off and runs in the background.

The page refreshes every minute, or every 5 seconds while something is running. It never shows password hashes or Garmin keys.

Security: the login is HTTP Basic, so serve the backend over HTTPS anywhere but your own machine. Actions are `POST` requests that must carry an `X-Admin-Request` header, which a page on another site cannot add, so a malicious link can't trigger them with the browser's saved login.

| Endpoint | Description |
| --- | --- |
| `GET /admin` | The page |
| `GET /admin/api/overview` | Jobs, last 20 Sheets runs, users, as JSON |
| `POST /admin/api/jobs/{id}/run` | Start a job (`sheets-sync` or `garmin-sync`): 202, or 409 if it is already running |
| `POST /admin/api/users/{id}/garmin-sync` | Sync one user's Garmin data: 202, 404 for an unknown user, 409 if Garmin isn't connected |

## Tests

```bash
./gradlew test
```

The integration tests start PostgreSQL with Testcontainers, so Docker must be running. Garmin and the Google Sheet are replaced by in-memory fakes, so no credentials are needed.

| Test | Covers |
| --- | --- |
| `ApiIntegrationTest` | The HTTP API end to end, including the Garmin sync and the onboarding migration |
| `SheetsSyncIntegrationTest` | Sheets sync: first run, repeat run, updates, nothing deleted, skipped rows, a missing tab |
| `CustomSchemaTest` | Migrations and queries in a `DATABASE_SCHEMA` other than `public` |
| `AdminIntegrationTest`, `AdminAuthInterceptorTest` | Admin login, CSRF header, overview without secrets, manual job runs, 404 when not configured |
| `NodeCompatibilityTest` | Password hashes and session tokens created by the Node app |
| `BodyCompositionTest` | Plan body-composition math |

## Schema

Liquibase changelogs live in `src/main/resources/db/changelog/`. Add a new numbered SQL file and include it from `db.changelog-master.yaml`; never edit a changeset that has already run.

| Table | Contents |
| --- | --- |
| `app_user` | Account, scrypt password hash, body metrics, onboarding step, time zone, phone number |
| `ingredient` | Shared catalog; nutrients per `base_quantity` of `unit` |
| `recipe`, `recipe_ingredient` | A user's dishes; quantity is a multiple of the ingredient's base quantity |
| `packaged_food` | Per-user foods with nutrients for `quantity` servings |
| `meal_log` | Dish name, portion, time; nutrition is computed when read |
| `workout` | `DIRECT` (logged in the app) or `GARMIN` (synced, keyed by Garmin `activityId`) |
| `plan` | Target weight, resting calories, start; `deleted_at` soft-deletes |
| `garmin_integration` | Encrypted API key, device name, last sync outcome |
| `garmin_daily_metric` | Raw Garmin daily summary and sleep payloads per user and day |
| `shedlock` | Keeps each scheduled job to one instance at a time |
| `sheets_sync_run` | One row per Google Sheets sync: status, per-table counts, warnings, error |

The ingredient catalog has no UI. It is seeded from `ingredient-catalog.csv` on every deploy, and the Sheets sync adds the Sheet's ingredients. For an ingredient in both, the value written last wins: the Sheet's after a sync, the CSV's after a deploy.

## Garmin sync

- **Connect:** the key is verified with Garmin, stored encrypted, and the last 7 days are backfilled in the background.
- **Scheduled job:** for every connected user, syncs today and yesterday in the user's time zone. It reconciles activities into `GARMIN` workouts (add, update, and remove ones deleted in Garmin) and refreshes daily summaries, sleep, and the device name. The outcome is recorded on `garmin_integration`.
- **On demand:** the web app still calls `POST /api/garmin/activities` on load, which also records the browser's time zone. The dashboard's daily, weekly, and sleep views read from `garmin_daily_metric`. They only call Garmin for days that are missing, or for today and yesterday when the stored copy is older than 10 minutes. If a refresh fails, the stored copy is served instead.

Garmin access tokens never leave the backend.

## Google Sheets sync

A scheduled job copies the Google Sheet the app used to store its data in into Postgres every day at 12:00 noon UTC (`SHEETS_SYNC_CRON`, `SHEETS_SYNC_ZONE`). It runs on one instance only (ShedLock). The first run is the migration; later runs bring Postgres up to date. This replaces the one-off `scripts/export-sheets-to-sql.mjs` in the web app. Don't run both against the same database: the script reads timestamps differently, so its rows would not match the sync's and would be duplicated.

**Set it up**

1. In Google Cloud, create a service account, enable the Google Sheets API for its project, and download a JSON key.
2. Share the Sheet with the service account's email as a **Viewer**. The sync only reads.
3. Set `SHEETS_SYNC_ENABLED=true`, `GOOGLE_SHEET_ID`, `GOOGLE_SERVICE_ACCOUNT_EMAIL` and `GOOGLE_PRIVATE_KEY`, then restart the backend.

The Sheet needs the tabs `User`, `Ingredients`, `Recipe`, `Food`, `Workouts` and `Plan`; `Packaged` is optional. Columns are found by header name, ignoring case, spaces and punctuation, and the variants the app used are accepted (for example `Calories (kcal)` or `Created At (UTC)`).

**How it behaves**

- **One way, no deletes:** each Sheet row is inserted, or updates the row it matches. Nothing is ever deleted, so rows removed from the Sheet and rows created in the app stay. The one exception: when a recipe's ingredient lines change in the Sheet, its lines are replaced.
- **Sheet wins:** a changed Sheet row overwrites the matching database row, including edits made in the app. Password hashes and onboarding progress are only set when a user is first inserted.
- **Matching (natural keys):**

  | Table | Matched on |
  |---|---|
  | `app_user` | email (case-insensitive) |
  | `ingredient` | name |
  | `recipe`, `packaged_food` | user and name |
  | `meal_log` | user, dish and timestamp |
  | `workout` | user, name and timestamp, among `DIRECT` workouts |
  | `plan` | user, name and start date |

  Changing a key field in the Sheet (for example a meal's timestamp) adds a new row; the old one stays.
- **Skipped:** `Garmin Connect` workouts, silently, since the Garmin sync owns those. With a warning: rows that repeat an earlier row's key, rows for unknown users, rows with invalid values, and recipe lines with an unknown ingredient.
- **Timestamps:** date cells and ISO text without an offset are read in `SHEETS_SYNC_ZONE`.
- **All or nothing:** a run applies every change in one transaction. A missing tab or a Google error fails the run, and the database is left unchanged.
- **Garmin:** API keys in the Sheet are not copied (the backend stores them encrypted), so users who had Garmin connected reconnect it from their profile.

**Check a run**

Each run is logged, and recorded in `sheets_sync_run` with its status, per-table counts (inserted, updated, unchanged, skipped) and warnings:

```sql
SELECT started_at, status, counts, jsonb_array_length(warnings) AS warnings, error
FROM sheets_sync_run ORDER BY id DESC LIMIT 5;
```
