# TS-SC Java backend

Spring Boot REST API and timetable solver. Google OR-Tools CP-SAT runs in this
Java process. The backend does not proxy to or launch a Python solver.

## Requirements

- Java 17 or newer
- Apache Maven 3.9 or newer

## Run locally

Run from the repository root, in its own terminal.

Windows PowerShell:

```powershell
cd backend
mvn spring-boot:run
```

macOS / Linux:

```bash
cd backend
mvn spring-boot:run
```

The API listens on `http://localhost:8000`. Health endpoint:
`GET http://localhost:8000/health`.

## Test and package

```shell
mvn test
mvn package
java -jar target/ts-sc-backend-0.1.0.jar
```

## API

- `GET /health` — liveness response.
- `POST /generate` — timetable CP-SAT generation and infeasibility diagnosis.
- `POST /substitutions/rank` — rank available replacement faculty.
- `POST /substitutions/resolve` — select an accepted candidate and report penalties.
- `POST /notifications/email` — send a notification email or log a dev dispatch.

The same paths are also available under `/api` for deployments that retain an
`/api/*` frontend route. JSON property names match the established snake_case JSON request
and response contract (snake_case).

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | Spring Boot HTTP port |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://127.0.0.1:5173` | Allowed browser origins |
| `SMTP_HOST` | empty | When empty, email dispatch is logged locally |
| `SMTP_PORT` | `587` | SMTP server port |
| `SMTP_USER`, `SMTP_PASS` | empty | SMTP credentials, server environment only |
| `SMTP_AUTH` | `false` | SMTP authentication toggle |
| `SMTP_STARTTLS` | `true` | Enable SMTP STARTTLS |
| `FROM_EMAIL` | `notifications@ts-sc.local` | Sender address |

Supabase URL, anon key, and optional service-role settings belong in the
frontend and server environments respectively. The service-role key must
never be exposed through Vite or delivered to the browser. Solver requests do
not require a service-role key.

## Frontend

Run the unchanged React UI separately on `http://localhost:5173`; it calls the
backend at `VITE_API_URL` (default `http://localhost:8000`). See the repository
root README for complete Windows and macOS/Linux commands and Supabase setup.
