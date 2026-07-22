# JobMatchAI — AWS Production Deployment Guide

This document covers what's required to deploy the existing application to AWS. It does not
describe any change in application behavior — matching/scoring, caching, queue processing, and UI
behavior are unchanged from local dev; only packaging, storage backend selection, and
configuration are new.

Architecture: Spring Boot backend on **Elastic Beanstalk**, React/Vite frontend on **Amplify
Hosting** (or S3+CloudFront), existing **Supabase Postgres** (Frankfurt) unchanged, CV files in a
private **S3** bucket, OpenAI API called only from the backend.

---

## 1. Required environment variables

### Backend (Elastic Beanstalk environment properties)

Every property below already has a safe fallback in `application.properties` — only the ones
marked **required** must actually be set for a real production deployment; the rest are optional
tuning knobs.

| Variable | Required | Purpose |
|---|---|---|
| `SPRING_DATASOURCE_URL` | **Yes** | Supabase Postgres JDBC URL. Must NOT be left unset — the app refuses to start in `prod` if it falls back to the in-memory H2 default (`ProductionConfigGuard`). |
| `SPRING_DATASOURCE_USERNAME` | **Yes** | Supabase DB user. |
| `SPRING_DATASOURCE_PASSWORD` | **Yes** | Supabase DB password. Store in Secrets Manager, not plain EB env config. |
| `JWT_SECRET` | **Yes** | Long random secret (`openssl rand -base64 48`). App refuses to start in `prod` if left at the dev default (`JwtService` guard). Store in Secrets Manager. |
| `OPENAI_API_KEY` | **Yes** | Backend-only; never sent to or read by the frontend. Store in Secrets Manager. |
| `APP_ENVIRONMENT` | **Yes** | Set to `prod` (also set by activating the `production` Spring profile below). Enables the H2/JWT-secret startup guards and switches password-reset links to email-only. |
| `APP_CORS_ALLOWED_ORIGIN` | **Yes** | Comma-separated list of allowed frontend origin(s), e.g. `https://app.yourdomain.com`. |
| `APP_FRONTEND_URL` | **Yes** | Production frontend URL — used to build password-reset links. Must be the real HTTPS domain, not `localhost`. |
| `APP_STORAGE_TYPE` | Yes (or via profile) | `s3` in production. Already set by `application-production.properties`; only needed here if not activating that profile. |
| `APP_S3_BUCKET_NAME` | **Yes** (if `APP_STORAGE_TYPE=s3`) | Name of the private S3 bucket for CVs. |
| `APP_S3_REGION` | Recommended | Defaults to `eu-central-1`; set explicitly to match your bucket's region. |
| `APP_PROXY_TRUST_X_FORWARDED_FOR` | **Yes** | Set to `true` — EB's ELB sits in front of the app; without this, IP-based rate limiting can be bypassed via a forged header. Already set by `application-production.properties`. |
| `SPRING_PROFILES_ACTIVE` | Recommended | Set to `production` to load `application-production.properties`. |
| `PORT` | No | EB injects this automatically on the Docker platform; the app already reads it with an `8080` fallback. |
| `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` | Recommended | `8` — see connection-pool sizing note below. |
| `SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE` | Recommended | `2` — see connection-pool sizing note below. |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` / `STRIPE_PREMIUM_PRICE_ID` | If payments are used | Store secret/webhook values in Secrets Manager. |
| `MAIL_HOST` / `MAIL_PORT` / `MAIL_USERNAME` / `MAIL_PASSWORD` | If email is used | Without these, verification/reset emails are only logged, not sent — fine for a soft launch, not for real users. Store `MAIL_PASSWORD` in Secrets Manager. |
| `INTERNAL_API_KEY` | If external-jobs import is used | Generate with `openssl rand -hex 32`. |
| `JOOBLE_API_KEY` / `RAPIDAPI_KEY` | If external-jobs import is used | Third-party job-board API keys. |
| `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` | **Fallback only** | Do NOT set these on the EB environment. Use an EB instance IAM role instead (see §4). Only relevant for local testing against a real S3 bucket outside of AWS. |

Everything else in `application.properties` (rate limits, matching/queue tuning, external-jobs
keywords, OpenAI model names) has sensible defaults and does not need to be touched for
deployment — changing any of it would be a behavioral change, which is explicitly out of scope
here.

### Frontend (Amplify Console build-time environment variable)

| Variable | Required | Purpose |
|---|---|---|
| `VITE_API_BASE_URL` | **Yes** | The backend's public HTTPS URL (e.g. the EB environment URL or a custom domain). Baked into the static build at build time — must be set in Amplify Console's environment variables (or your CI), not just locally. |

No other frontend env var exists; the codebase already has no hardcoded localhost URLs outside
this one documented dev fallback.

---

## 2. AWS resources to create (none of this has been done — nothing has been deployed)

1. **S3 bucket** for CV storage — private, all public access blocked, no bucket policy granting
   public read. Versioning optional. Note the bucket name/region for `APP_S3_BUCKET_NAME`/
   `APP_S3_REGION`.
2. **Elastic Beanstalk application + environment** — Docker platform (matches the existing
   `Dockerfile`), single-instance or load-balanced as desired.
3. **EB instance IAM role** (see §4) — scoped to `s3:GetObject`, `s3:PutObject`,
   `s3:DeleteObject`, `s3:HeadObject` on just the CV bucket (`arn:aws:s3:::<bucket-name>/*`).
4. **AWS Secrets Manager secrets** (recommended) for `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET`,
   `OPENAI_API_KEY`, `MAIL_PASSWORD`, `STRIPE_SECRET_KEY` — referenced from the EB environment
   configuration rather than stored as plain env vars.
5. **Amplify Hosting app** (or an S3 bucket + CloudFront distribution) for the frontend static
   build.

---

## 3. Build artifacts and deployment commands

### Backend

```bash
cd backend
./mvnw clean package -DskipTests
# Produces backend/target/backend-<version>.jar
```

Deploy via the existing `Dockerfile` (already aligned to Java 21 to match `pom.xml`) — EB's
Docker platform builds the image from this Dockerfile and runs the packaged jar. No changes
needed to the EB deployment mechanism beyond setting the environment variables in §1.

```bash
# From the backend directory, to build the image locally for a sanity check before deploying:
docker build -t jobmatchai-backend .
```

### Frontend

```bash
cd jobMatchAi-frontend
npm ci
npm run build
# Produces jobMatchAi-frontend/dist/ - the static site to publish
```

Amplify Hosting will run this automatically via the new `amplify.yml` at the frontend root once
connected to the repo, using `VITE_API_BASE_URL` from Amplify Console's environment variables. If
deploying to S3+CloudFront instead, upload the contents of `dist/` to the bucket and invalidate
the CloudFront distribution on each deploy.

---

## 4. IAM role setup for S3 (recommended primary approach)

The backend uses the AWS SDK's default credential provider chain (`S3Client.builder()` with no
explicit credentials) — it will automatically pick up whichever credential source is available,
in this order: environment variables, then an EC2/Elastic Beanstalk instance IAM role, then other
standard SDK sources. No code differs between these paths.

**Recommended for production**: attach an IAM role to the EB environment's EC2 instance profile,
scoped to only the CV bucket:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:HeadObject"],
      "Resource": "arn:aws:s3:::<bucket-name>/*"
    }
  ]
}
```

This needs no `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` env vars at all. Static access keys are
documented here only as a fallback for local testing against a real bucket outside AWS — do not
use them as the production credential source.

---

## 5. SPA routing (React Router deep-link/refresh support)

The frontend uses `BrowserRouter`, so the hosting layer must serve `index.html` for any unmatched
path (a direct visit or refresh on e.g. `/jobs/42` must not 404).

- **Amplify Hosting**: Console → App settings → Rewrites and redirects → add the built-in
  "Single Page App" rewrite (`</^[^.]+$|\.(?!(css|gif|ico|jpg|js|png|txt|svg|woff|woff2|ttf|map|json)$)([^.]+$)/>` → `/index.html`, 200).
- **S3+CloudFront**: add a CloudFront custom error response mapping both 403 and 404 →
  `/index.html` with an HTTP response code of 200.

---

## 6. Deployment checklist

1. Create the S3 bucket (§2.1) and confirm public access is fully blocked.
2. Create the EB application/environment and attach the scoped instance IAM role (§4).
3. Set all required backend env vars (§1) on the EB environment, using Secrets Manager for the
   sensitive ones.
4. Deploy the backend (`docker build` via EB's own pipeline, or `eb deploy`).
5. Confirm `GET /<eb-environment-url>/actuator/health` returns `200 {"status":"UP"}` — this is
   the endpoint EB's health checker uses and is now unauthenticated with no sensitive details
   exposed (`management.endpoint.health.show-details=never`).
6. Set `VITE_API_BASE_URL` in Amplify Console (or CI) to the EB environment's public URL.
7. Connect/deploy the frontend (Amplify auto-builds via `amplify.yml`, or push `dist/` to
   S3+CloudFront).
8. Configure the SPA rewrite rule (§5) on whichever frontend host was chosen.
9. Set `APP_CORS_ALLOWED_ORIGIN` and `APP_FRONTEND_URL` on the backend to the frontend's real
   domain, and redeploy the backend if they were placeholders until now.
10. Smoke test end-to-end against production: register/login, CV upload/download/delete, job
    matching, password reset email/link.
11. Confirm `S3FileStorageService` works against the real bucket (upload, download, delete) —
    this could not be verified in the development environment (no AWS credentials available
    there) and must be checked manually here.

---

## 7. Rollback plan

- **Backend**: Elastic Beanstalk retains previous application versions — roll back via
  `eb deploy --version <previous-label>` or the EB console's "Application versions" →
  "Deploy" on an earlier version.
- **Frontend**: Amplify Hosting keeps previous build deployments — roll back via the Amplify
  Console's deployment history ("Redeploy this version"). For S3+CloudFront, re-upload the
  previous `dist/` build and invalidate the distribution.
- **Database — known limitation, not fixed by this work**: this application has no migration
  framework (Flyway/Liquibase); schema changes are applied automatically by Hibernate
  (`spring.jpa.hibernate.ddl-auto=update`) the moment a new version of the backend starts up
  against the database. Rolling back the *application code* does **not** undo any schema change
  Hibernate already applied. Before deploying any backend version that adds/changes/removes an
  entity field, take a manual Supabase backup first — rolling back to the previous jar after such
  a deploy will not restore the previous schema, only the previous code.

---

## 8. Known limitations / not addressed by this work

These were identified during the audit but intentionally left unchanged, since fixing them would
be a real behavioral or architectural change beyond deployment packaging:

- No migration framework — see §7's database rollback caveat.
- `spring.jpa.hibernate.ddl-auto=update` stays as-is in production (switching to `validate` would
  require introducing a migration framework first).
- `S3FileStorageService` has not been exercised against a real AWS account — no AWS credentials
  were available in the development environment. Verify manually per checklist step 11.
