# JobMatchAI — Render + Firebase Hosting Deployment Guide

This document covers what's required to deploy the application to its current target platforms.
It does not describe any change in application behavior — matching/scoring, caching, queue
processing, and UI behavior are unchanged; only packaging, storage backend selection, and
configuration are covered here.

**Architecture:** Spring Boot backend on **Render** (Docker runtime), React/Vite frontend on
**Firebase Hosting**, existing **Supabase Postgres** database (unchanged), CV files in **Supabase
Storage**, OpenAI API called only from the backend. No AWS services are used anywhere.

---

## 0. What you need to fill in before this works (placeholders in the repo today)

Nothing below is guessed or invented for you — these are genuinely yours to provide:

| Placeholder | Where | Replace with |
|---|---|---|
| `REPLACE_WITH_YOUR_FIREBASE_PROJECT_ID` | `jobMatchAi-frontend/.firebaserc` | Your real Firebase project ID (create one at console.firebase.google.com if you haven't, or `firebase projects:list` if you have). |
| `REPLACE_WITH_YOUR_PRODUCTION_BACKEND_URL` | `jobMatchAi-frontend/.env.production` | Your Render service's public URL (e.g. `https://jobmatchai-backend.onrender.com`), known only after step 3 in §3. |
| Supabase pooler connection string | Set directly as `SPRING_DATASOURCE_URL` on Render (not stored in any file) | From Supabase dashboard → Project Settings → Database → Connection pooling → **Session mode**. Not the direct `db.<ref>.supabase.co` host — see §5. |
| All secrets (`JWT_SECRET`, `OPENAI_API_KEY`, `SUPABASE_SERVICE_ROLE_KEY`, `MAIL_PASSWORD`, DB password, etc.) | Set directly on Render's Environment tab (not stored in any file) | Your real values — see §1's table. |

Everything else below is already correct and doesn't need editing.

---

## 1. Required environment variables

### Backend (Render service → Environment tab)

Every property below already has a safe fallback in `application.properties` — only the ones
marked **Required** must actually be set for a real production deployment.

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **Yes** | Set to `production`. This is the single flag that activates `application-production.properties` — without it, storage silently stays on ephemeral local disk, `app.environment` never becomes `prod`, and X-Forwarded-For isn't trusted. Also transitively sets `app.environment=prod` for you — you do not need to separately set `APP_ENVIRONMENT`. |
| `SPRING_DATASOURCE_URL` | **Yes** | Supabase Postgres **connection pooler** URL, session mode: `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres` (get the exact host from Supabase dashboard → Project Settings → Database → Connection pooling). Do not use the direct `db.<ref>.supabase.co` host in production — see §5. |
| `SPRING_DATASOURCE_USERNAME` | **Yes** | Pooler username, formatted `postgres.<project-ref>` (not plain `postgres` — the pooler requires the project ref suffix). |
| `SPRING_DATASOURCE_PASSWORD` | **Yes** | Your Supabase database password. |
| `JWT_SECRET` | **Yes** | Long random secret (`openssl rand -base64 48`). The app refuses to start in `prod` if left at the insecure dev default (`JwtService`'s own guard). |
| `OPENAI_API_KEY` | **Yes** | Backend-only; never sent to or read by the frontend. |
| `APP_CORS_ALLOWED_ORIGIN` | **Yes** | Your Firebase Hosting URL, e.g. `https://<project-id>.web.app`. Comma-separate multiple origins if needed. |
| `APP_FRONTEND_URL` | **Yes** | Same Firebase Hosting URL — used to build password-reset email links. |
| `SUPABASE_URL` | **Yes** | Your Supabase project's HTTPS URL, e.g. `https://<project-ref>.supabase.co` (Project Settings → API). |
| `SUPABASE_SERVICE_ROLE_KEY` | **Yes** | Full-access storage key (Project Settings → API). Bypasses RLS — treat like a database password. |
| `SUPABASE_STORAGE_BUCKET` | **Yes** | Name of the Supabase Storage bucket for CVs (defaults to `cvs` if unset — only needed here if you used a different name). |
| `MAIL_HOST` / `MAIL_USERNAME` / `MAIL_PASSWORD` | **Yes** | Once `app.environment=prod`, password-reset/verification links are **only** emailed, never returned in the API response — without working mail, that flow is silently broken for real users. |
| `PORT` | No | Set automatically by Render; the app already reads it with an `8080` fallback. |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Recommended | `8`–`10` against the pooler (the default of `10` is fine; lower if you also run other services against the same Supabase project). |
| `INTERNAL_API_KEY` | Optional | Only needed to manually trigger `POST /api/external-jobs/import`; the scheduled cron runs regardless. Generate with `openssl rand -hex 32`. |
| `JOOBLE_API_KEY` / `RAPIDAPI_KEY` | Optional | Third-party external-job-board keys. Jooble alone is enough for external job imports to work; RapidAPI/JSearch is a second, optional source. |

**Deliberately not needed at all:** `STRIPE_*` (this app uses the demo/mock payment flow —
see `PaymentController`'s `/demo/*` routes, no real Stripe account required),
`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`APP_S3_*` (CV storage is Supabase Storage,
authenticated with `SUPABASE_SERVICE_ROLE_KEY` above, not AWS-style keys).

### Frontend (`jobMatchAi-frontend/.env.production`, baked in at build time)

| Variable | Required | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | **Yes** | Your Render backend's public HTTPS URL. Vite bakes this into the static build — it must be the real value **before** you run `npm run build`, not something you can change afterward without rebuilding. |

No other frontend env var exists. There is no build-time environment variable panel to configure
on Firebase Hosting's side — deployment is local (`npm run build && firebase deploy`), so this
file is the only place it's set.

---

## 2. Supabase resources (already created — reference only)

1. **Database**: the existing Supabase Postgres project, unchanged. For Render specifically, use
   the **connection pooler** (session mode) instead of the direct host — see §1 and §5.
2. **Storage bucket**: a private bucket (default name `cvs`) already exists in this project's
   Supabase Storage, verified reachable with the current `SUPABASE_SERVICE_ROLE_KEY`. No
   additional setup needed unless you want a differently-named or differently-configured bucket.

---

## 3. Render setup steps (backend)

1. Create a new **Web Service** on Render, connect this GitHub repository.
2. **Root Directory**: set this to `backend` — the Dockerfile lives at `backend/Dockerfile`, one
   level below the git repo root, not at the repo root itself.
3. **Runtime**: Docker (Render auto-detects the `Dockerfile` once Root Directory is set correctly).
4. Set every environment variable from §1's table.
5. **Health Check Path** (Render dashboard setting, not a file): `/actuator/health` — already
   unauthenticated and detail-free (`management.endpoint.health.show-details=never`), exactly for
   this purpose.
6. Deploy. Confirm the build logs show a successful `mvn package` inside the Docker build stage,
   then confirm `GET https://<your-service>.onrender.com/actuator/health` returns
   `{"status":"UP"}`.

**Note on Render's free tier:** it spins the service down after ~15 minutes of inactivity. This
app has several `@Scheduled` background jobs (the external-jobs import cron, the match-score
queue poller, a stale-job reaper) that simply don't run while the service is asleep, and the first
request after idle will be slow (cold start). If that matters for your use case, use a paid
always-on plan instead of the free tier.

---

## 4. Firebase Hosting setup steps (frontend)

1. Install the Firebase CLI if you haven't: `npm install -g firebase-tools`, then `firebase login`.
2. Replace the placeholder in `jobMatchAi-frontend/.firebaserc` with your real Firebase project ID.
3. Once your Render service is deployed and you have its URL, replace the placeholder in
   `jobMatchAi-frontend/.env.production` with that real URL.
4. From `jobMatchAi-frontend/`: `npm run build` (or `npm run deploy`, which runs build + deploy
   together).
5. `firebase deploy --only hosting` (skip if you used `npm run deploy` above).
6. SPA routing is already handled — `firebase.json`'s rewrite (`**` → `/index.html`) means a
   direct visit or refresh on a deep link (e.g. `/jobs/42`) will not 404. Nothing to configure
   here.

---

## 5. Closing the loop between the two deployments

The backend and frontend URLs depend on each other, so there's an unavoidable two-step:

1. Deploy the backend first (§3) to get its real Render URL.
2. Put that URL into the frontend's `.env.production` and deploy the frontend (§4).
3. Put the frontend's real Firebase Hosting URL into `APP_CORS_ALLOWED_ORIGIN` and
   `APP_FRONTEND_URL` on Render, then redeploy the backend so CORS actually allows the real
   frontend origin (it will reject requests from the frontend until this step is done, since
   those two vars default to `localhost:5173`).

### Why the Supabase connection pooler, not the direct host

Supabase's direct connection (`db.<ref>.supabase.co:5432`) has a low, fixed connection cap shared
across everything using your project (local dev, Render, the Supabase dashboard itself, etc.). The
pooler (`aws-0-<region>.pooler.supabase.com`) is built to absorb many concurrent short-lived
connections from a hosted platform like Render. **Session mode** (not transaction mode) is
required here specifically because this app uses normal JDBC/Hikari connections, not one-shot
pooled statements — transaction-mode pooling doesn't support the connection-level features Hikari
relies on. The pooler username is formatted `postgres.<project-ref>`, not plain `postgres`.

---

## 6. Smoke test after both are live

Register/login (candidate + company), upload/download/delete a CV (exercises Supabase Storage
end-to-end), run a job match, and trigger a password reset email (exercises real mail delivery,
which only activates once `app.environment=prod` — dev mode returns the link directly instead).

---

## 7. Rollback plan

- **Backend**: Render keeps prior deploys — roll back via the Render dashboard's deploy history
  ("Manual Deploy" → pick an earlier commit/deploy).
- **Frontend**: Firebase Hosting keeps release history — roll back via Firebase Console →
  Hosting → release history, or `firebase hosting:clone`.
- **Database — known limitation, not fixed by this work**: this application has no migration
  framework (Flyway/Liquibase); schema changes are applied automatically by Hibernate
  (`spring.jpa.hibernate.ddl-auto=update`) the moment a new backend version starts up against the
  database. Rolling back the *application code* does **not** undo any schema change Hibernate
  already applied. Take a manual Supabase backup before deploying any backend version that
  adds/changes/removes an entity field.

---

## 8. Alternative: self-hosting instead of Render

`docker-compose.yml` at the repo root remains a valid alternative if you ever want to run this
backend on your own server instead of Render — it builds from the same `Dockerfile`, persists CV
uploads to a named volume (`cv-uploads`) instead of Supabase Storage, and needs a reverse proxy
(Caddy/nginx) in front for TLS. See `README.md`'s "Self-hosted deployment" section. Render is the
documented, primary path in this guide; the two are independent choices — pick one, not both.

---

## 9. Known limitations / not addressed by this work

- No migration framework — see §7's database rollback caveat.
- `spring.jpa.hibernate.ddl-auto=update` stays as-is in production (switching to `validate` would
  require introducing a migration framework first).
