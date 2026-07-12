# jobmatchai-backend
Spring Boot backend for JobMatchAI platform

## Local OpenAI configuration

When running from the terminal, VS Code `launch.json` environment variables are not used.

Create a local `.env` file in the repository root or in `backend/`:

```properties
OPENAI_API_KEY=sk-your-openai-api-key-here
OPENAI_MODEL=gpt-4.1
```

Then start the backend from the `backend` directory:

```powershell
.\mvnw.cmd spring-boot:run
```

You can also set the variable for the current PowerShell session instead:

```powershell
$env:OPENAI_API_KEY="sk-your-openai-api-key-here"
.\mvnw.cmd spring-boot:run
```

## Self-hosted deployment (Docker Compose)

This backend has no dependency on any specific hosting provider - `docker-compose.yml` in
the repo root builds and runs it from the included `Dockerfile` on any server with Docker
installed. The database stays on Supabase (or any Postgres you point it at); only the
backend's compute needs a home.

1. Copy `.env.example` to `.env` in the repo root and fill in real values:
   - `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` -
     your Supabase (or other Postgres) connection string.
   - `JWT_SECRET` - a long random string (`openssl rand -base64 48`), not the insecure default.
   - `APP_CORS_ALLOWED_ORIGIN` - your deployed frontend's URL. Comma-separate multiple
     origins (e.g. `https://app.yourdomain.com,http://localhost:5173`) if you need both a
     production frontend and local dev to reach the same backend.
   - `APP_FRONTEND_URL` - your deployed frontend's URL (used for links in emails, etc.).
   - `OPENAI_API_KEY` and any of Stripe/mail/Jooble/RapidAPI keys you actually use.
   - Leave `APP_UPLOAD_DIR` unset - `docker-compose.yml` already points it at a persistent
     named volume (`cv-uploads`) so uploaded CVs survive container restarts/redeploys.

2. Build and start the container:

   ```bash
   docker compose up -d --build
   ```

   The backend is now reachable at `http://<your-server>:8080`. Put a reverse proxy (e.g.
   Caddy or nginx) with a TLS certificate in front of it for production HTTPS - this compose
   file intentionally only handles the application container, not ingress/TLS.

3. Point the frontend at it: set `VITE_API_BASE_URL` in the frontend's `.env` to your
   backend's public URL, then rebuild/redeploy the frontend (`npm run build`).

4. Decommission Railway: this repo has never contained any Railway-specific configuration
   (build settings, env vars, etc. were only ever set in Railway's dashboard), so there is
   nothing to remove from the code. Delete the Railway project/service from
   [Railway's dashboard](https://railway.app) once the self-hosted deployment above is live
   and verified.
