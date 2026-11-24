# TS-SC — Smart Classroom & Timetable Scheduler

React + TypeScript frontend, Java 17 / Spring Boot backend, in-process Google
OR-Tools CP-SAT timetable solver, and Supabase (PostgreSQL/Auth) data layer.
The solver runs in the Java backend;

## Repository layout

| Directory | Contents |
|---|---|
| `frontend/` | React + TypeScript + Vite application |
| `backend/` | Spring Boot REST API and OR-Tools Java CP-SAT implementation |
| `supabase/` | PostgreSQL migrations, RLS policies, and seed data |
| `docs/` | Architecture and product documentation |

## Requirements

- Java 17 or newer
- Apache Maven 3.9 or newer
- A Supabase project for screens that read or write institutional data

## Run locally

Run the backend and frontend in separate terminals. Backend API: port `8000`.
Vite frontend: port `5173`.

### Windows PowerShell

Terminal 1 — Java API:

```powershell
cd backend
mvn spring-boot:run
```

Terminal 2 — React app:

```powershell
cd frontend
npm install
Copy-Item ..\.env.example .env
# Edit .env and set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.
npm run dev
```

### macOS / Linux

Terminal 1 — Java API:

```bash
cd backend
mvn spring-boot:run
```

Terminal 2 — React app:

```bash
cd frontend
npm install
cp ../.env.example .env
# Edit .env and set VITE_SUPABASE_URL and VITE_SUPABASE_ANON_KEY.
npm run dev
```

Open `http://localhost:5173`. The frontend uses
`VITE_API_URL=http://localhost:8000` by default. Check
`http://localhost:8000/health` for backend health.

### Supabase setup

Create a Supabase project, then run `supabase/migrations/*.sql` in the SQL
editor followed by `supabase/seed.sql`. The browser receives only the project
URL and anon key. Keep the Supabase service-role key on the server; the current
Java API does not need it for solver requests.

### Optional email settings

Without `SMTP_HOST`, the backend logs a development email dispatch and returns
success. To send real email, set `SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`,
`SMTP_PASS`, `SMTP_AUTH`, `SMTP_STARTTLS`, and `FROM_EMAIL` in the backend
environment. Do not put these values in `frontend/.env`.

## Verify

```powershell
cd backend
mvn test
mvn package
```

```bash
cd backend
mvn test
mvn package
```

For production, build the frontend with `VITE_API_URL` set to the deployed
Spring Boot API origin. Set backend `CORS_ALLOWED_ORIGINS` to the frontend
origin. The Vercel configuration only hosts the static frontend; deploy the
Java backend separately (or use `backend/Dockerfile`).

See [backend/README.md](backend/README.md) and
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for API and architecture details.
