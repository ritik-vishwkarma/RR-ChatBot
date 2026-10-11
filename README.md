# Chatbot — Spring Boot, PostgreSQL, Vanilla JavaScript

A local chatbot application built with Java 17, Spring Boot, PostgreSQL, and a vanilla HTML/CSS/JavaScript frontend. Authentication uses HttpOnly access and refresh cookies, CSRF protection, refresh-token rotation, and chat ownership checks.

> **Note:** This README describes the local development and QA setup. The included AI webhook is a deterministic test stub; it does not call a real AI provider.

## Application Screenshot

![Chatbot UI showing chat history and mock AI responses](image.png)

## Table of Contents

- [Requirements](#requirements)
- [Project Layout](#project-layout)
- [1. Install and Verify Prerequisites](#1-install-and-verify-prerequisites)
- [2. Create a PostgreSQL Database](#2-create-a-postgresql-database)
- [3. Configure Environment Variables](#3-configure-environment-variables)
- [4. Build and Test](#4-build-and-test)
- [5. Start the Mock AI Webhook](#5-start-the-mock-ai-webhook)
- [6. Start the Spring Boot Application](#6-start-the-spring-boot-application)
- [7. Open and Test the Frontend](#7-open-and-test-the-frontend)
- [8. Run API Regression Tests](#8-run-api-regression-tests)
- [9. Verify Database Persistence](#9-verify-database-persistence)
- [Troubleshooting](#troubleshooting)
- [Security Notes](#security-notes)

## Requirements

Install the following software before starting:

| Requirement | Version / Default |
|---|---|
| Java JDK | 17 or newer |
| Maven | 3.9+ recommended |
| PostgreSQL | Local instance, port `5432` |
| Python | 3.10+ for the local AI stub |
| Browser | Recent Chrome, Edge, or Firefox |
| Node.js | Optional, for JavaScript syntax checks |

Verify your installations in PowerShell:

```powershell
java -version
mvn -version
python --version
Test-NetConnection 127.0.0.1 -Port 5432
```

## Project Layout

Run commands from the project directory containing `pom.xml`.

```text
chatbot/
├── .env.example
├── .gitignore
├── pom.xml
├── scripts/
│   ├── Preflight.ps1
│   ├── Test-ChatbotApi.ps1
│   └── mock-ai-webhook.py
├── docs/
│   ├── BASELINE_REVIEW.md
│   ├── SAMPLE_TEST_OUTPUT.md
│   ├── TEST_CASE_MATRIX.csv
│   ├── TEST_REPORT_TEMPLATE.md
│   └── TESTING_GUIDE.md
└── src/
    ├── main/
    │   ├── java/com/example/chatbot/
    │   └── resources/
    │       ├── application.yml
    │       └── static/
    └── test/java/com/example/chatbot/
```

## 1. Install and Verify Prerequisites

Open PowerShell in the project root. For example:

```powershell
Set-Location "D:\Ritik\chatbot\src-token\chatbot-qa-test-pack\chatbot"
```

Use your actual extracted project path if it differs.

Run the preflight script:

```powershell
.\scripts\Preflight.ps1
```

The script checks Java, Maven, Python, the project root, and common ports. PostgreSQL should be running. Ports `8080` and `8090` should normally be available before starting the application and mock webhook.

## 2. Create a PostgreSQL Database

Open pgAdmin's Query Tool or connect through `psql` as a PostgreSQL administrator.

Create a dedicated local test role and database:

```sql
CREATE ROLE chatbot_test
LOGIN PASSWORD 'replace_with_a_local_test_password';

CREATE DATABASE chatbot_test
OWNER chatbot_test;
```

Replace the example password with a unique local password.

If the role and database already exist, do not run these statements again. Use your existing test database.

Verify the database:

```sql
SELECT datname
FROM pg_database
WHERE datname = 'chatbot_test';
```

Use a disposable database for automated API tests. Do not run regression tests against production or a database containing important personal data.

## 3. Configure Environment Variables

The application reads a `.env` file from the project root through `application.yml`.

Copy the template:

```powershell
Copy-Item .env.example .env
notepad .env
```

Configure the values to match your local PostgreSQL installation.

Example `.env`:

```properties
DB_URL=jdbc:postgresql://127.0.0.1:5432/chatbot_test
DB_USER=chatbot_test
DB_PASSWORD=replace_with_a_local_test_password

AI_WEBHOOK_URL=http://127.0.0.1:8090/ai
AI_WEBHOOK_SECRET=local-only-test-secret

JWT_SECRET=REPLACE_WITH_BASE64_ENCODED_32_RANDOM_BYTES
JWT_ISSUER=chatbot-api

COOKIE_SECURE=false
```

These are properties-style `KEY=value` lines. Do not add PowerShell `$env:` prefixes to the `.env` file.

### Generate a JWT Secret

Generate 32 random bytes and encode them in Base64 using PowerShell:

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($bytes)
$jwtSecret = [Convert]::ToBase64String($bytes)
$rng.Dispose()
$jwtSecret
```

Copy the generated value into `.env` as `JWT_SECRET`.

Do not commit `.env` to Git, share it, or include secret values in screenshots or bug reports. Confirm that `.env` is covered by `.gitignore`.

### Environment Variable Reference

| Variable | Purpose |
|---|---|
| `DB_URL` | JDBC URL for PostgreSQL |
| `DB_USER` | PostgreSQL username |
| `DB_PASSWORD` | Password for that database role |
| `AI_WEBHOOK_URL` | URL called by the Java backend |
| `AI_WEBHOOK_SECRET` | Shared secret sent to the webhook |
| `JWT_SECRET` | Base64-encoded JWT signing secret |
| `JWT_ISSUER` | JWT issuer claim |
| `COOKIE_SECURE` | `false` for local HTTP; `true` for HTTPS deployment |

The mock webhook defaults to `127.0.0.1:8090` and uses `local-only-test-secret`.

Keep `AI_WEBHOOK_SECRET` equal to the mock webhook's secret for local testing. If you change the mock's secret, set `MOCK_AI_WEBHOOK_SECRET` in the terminal used to start it and use the same value in `.env`.

## 4. Build and Test

Ensure PostgreSQL is running and `.env` is configured.

Run the preflight script:

```powershell
.\scripts\Preflight.ps1
```

Then compile the application and execute the automated Maven checks:

```powershell
mvn clean verify
```

A successful build ends with:

```text
BUILD SUCCESS
```

Resolve compilation errors or failed tests before continuing.

### Optional JavaScript Syntax Checks

If Node.js is installed, run:

```powershell
node --check .\src\main\resources\static\app.js
node --check .\src\main\resources\static\login.js
node --check .\src\main\resources\static\chat.js
```

No output and exit code `0` indicates a successful syntax check.

## 5. Start the Mock AI Webhook

Open **Terminal 1** in the project root. Start the local AI stub:

```powershell
python .\scripts\mock-ai-webhook.py
```

Leave this terminal running.

The expected startup message is similar to:

```text
Local AI test stub listening on http://127.0.0.1:8090/ai
```

Check its health endpoint from another terminal:

```powershell
Invoke-RestMethod http://127.0.0.1:8090/health
```

The response should indicate a healthy test stub.

The mock returns a deterministic reply similar to:

```text
TEST_STUB_REPLY: received user message
```

This verifies the application-to-webhook integration. It does not generate real AI responses.

## 6. Start the Spring Boot Application

Open **Terminal 2** in the project root. Keep the mock webhook running in Terminal 1.

Start Spring Boot:

```powershell
mvn spring-boot:run
```

Wait until Spring Boot reports that the application has started.

The application should be available at:

```text
http://127.0.0.1:8080
```

The current JPA configuration may use `ddl-auto: update`, allowing Hibernate to create or update tables during startup.

### Verify CSRF Bootstrap

Open **Terminal 3** and run:

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/csrf
```

The endpoint should return a successful response containing a non-empty CSRF token. Do not share the token value.

If startup fails, check the Spring Boot terminal and confirm:

- PostgreSQL is running.
- `DB_URL`, `DB_USER`, and `DB_PASSWORD` are correct.
- `JWT_SECRET` contains valid Base64-encoded random material.
- The AI webhook URL and secret are configured.
- Ports `8080` and `8090` are not occupied by other processes.

## 7. Open and Test the Frontend

Open the following URLs in your browser:

| Page | URL |
|---|---|
| Home | http://127.0.0.1:8080/ |
| Login | http://127.0.0.1:8080/login.html |
| Chat | http://127.0.0.1:8080/chat.html |

### First-Use Flow

1. Open the login page.
2. Register using an email address and a password of at least 12 characters, subject to the configured maximum.
3. Log in using the new account.
4. Create or open a chat.
5. Send a message.
6. Confirm the mock AI response appears.
7. Refresh the page and confirm the chat and messages remain available.

If the UI reports that the AI service is unavailable, verify that Terminal 1 is still running and that the webhook secret in `.env` matches the mock's secret.

### Verify Authentication Cookies

In browser DevTools:

1. Open **Application** or **Storage**.
2. Select **Cookies** for `http://127.0.0.1:8080`.
3. Confirm that the access and refresh authentication cookies are marked `HttpOnly`.

Do not copy cookie values into screenshots or reports.

The CSRF token is separate from authentication credentials. State-changing requests should send it in the `X-XSRF-TOKEN` request header.

For more detailed browser checks, refer to `docs/TESTING_GUIDE.md`.

## 8. Run API Regression Tests

Keep Spring Boot running. Open Terminal 3 in the project root.

Run:

```powershell
.\scripts\Test-ChatbotApi.ps1
```

If PowerShell blocks script execution, use a process-scoped execution policy bypass:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Test-ChatbotApi.ps1
```

The regression suite checks API behavior such as:

- Registration and login
- Authentication and CSRF
- Rate limiting
- Cookie behavior
- Chat and message persistence
- Cross-user chat ownership
- Refresh-token rotation and replay detection
- Logout behavior

The script writes Markdown and CSV reports under `test-results/`.

### Important Testing Notes

- Run tests only against the dedicated test database.
- The suite creates synthetic test accounts.
- Restart Spring Boot before a full run if the in-memory rate limiter has accumulated requests.
- Review every `FAIL`, `BLOCKED`, or `SKIP` result in the report.
- A passing test run only establishes the results of that particular run.

## 9. Verify Database Persistence

Connect to `chatbot_test` in pgAdmin.

Check the number of records:

```sql
SELECT COUNT(*) AS total_users FROM users;

SELECT COUNT(*) AS total_chats FROM chats;

SELECT COUNT(*) AS total_messages FROM messages;
```

### Check Messages Per Chat

```sql
SELECT
    c.id,
    c.owner,
    c.title,
    COUNT(m.id) AS message_count
FROM chats c
LEFT JOIN messages m ON m.chat_id = c.id
GROUP BY c.id, c.owner, c.title
ORDER BY c.created_at DESC;
```

A chat with `message_count = 0` has no persisted messages.

If sending a message fails because the AI webhook is unavailable, the backend may not save that exchange. Check the API response and Spring Boot logs to determine what happened.

## Troubleshooting

### Database Connection or Hibernate Dialect Error

If you see `Unable to determine Dialect` or a database connection error:

1. Confirm PostgreSQL is running on port `5432`.
2. Verify `DB_URL`, `DB_USER`, and `DB_PASSWORD`.
3. Confirm the database role can connect to the database.
4. Restart Spring Boot after changing `.env`.

### Missing or Invalid JWT Secret

Generate a new Base64-encoded secret using the instructions above. Update `.env` and restart the application.

Do not use a short, human-readable password as the signing secret.

### AI Service Unavailable / HTTP 502

Check the mock webhook terminal and run:

```powershell
Test-NetConnection 127.0.0.1 -Port 8090
```

Then:

```powershell
Invoke-RestMethod http://127.0.0.1:8090/health
```

Verify these `.env` values:

```properties
AI_WEBHOOK_URL=http://127.0.0.1:8090/ai
AI_WEBHOOK_SECRET=local-only-test-secret
```

Make sure the secret matches the mock webhook's configured secret. Inspect both the mock webhook and Spring Boot logs for errors.

### HTTP 401 Unauthorized

The access cookie may be missing, invalid, or expired.

- Log in again.
- Inspect the failing request in DevTools → Network.
- Check whether the authentication cookies are present.

### HTTP 403 Forbidden

For state-changing requests:

- Confirm the frontend fetched the CSRF token.
- Confirm the request includes `X-XSRF-TOKEN`.
- Check whether the authenticated user owns the requested chat.

### HTTP 429 Too Many Requests

The configured rate limiter may have reached its limit.

Wait for the configured rate-limit window to expire or restart the application for local testing. Do not disable rate limiting in production.

### Chat Appears but History Is Empty

Inspect these requests in DevTools → Network:

```text
GET /api/v1/chats
GET /api/v1/chats/{chatId}/messages
```

Then:

1. Check their HTTP status and response bodies.
2. Confirm the chat belongs to the authenticated user.
3. Run the SQL query in the database-persistence section.
4. Send a new message and confirm the exchange succeeds.

An empty message list is expected when the selected chat has no saved message rows.

## Security Notes and Known Limitations

- Keep `.env` local and never commit secrets.
- Rotate any secret that has been accidentally exposed.
- Use `COOKIE_SECURE=false` only for local HTTP development.
- Set `COOKIE_SECURE=true` when deploying over HTTPS.
- Do not expose PostgreSQL or the AI webhook directly to the public internet.
- The current `ddl-auto: update` setting is appropriate only for this prototype. Use reviewed, versioned database migrations for production.
- Rate limiting and in-flight chat guards are in-memory and do not coordinate across multiple application instances.
- The mock webhook tests the request/response contract and persistence path, not real AI provider quality, reliability, or latency.
- Perform separate expiry, concurrency, and production-like security testing before release.

Refer to `docs/TESTING_GUIDE.md` for additional testing instructions.

## Quick Start Summary

After installing prerequisites, creating the database, and configuring `.env`, use three terminals.

**Terminal 1 — Mock AI Webhook**

```powershell
python .\scripts\mock-ai-webhook.py
```

**Terminal 2 — Spring Boot**

```powershell
mvn spring-boot:run
```

**Browser — Frontend**

```text
http://127.0.0.1:8080/login.html
```

**Terminal 3 — API Regression Tests**

```powershell
.\scripts\Test-ChatbotApi.ps1
```

Keep the mock webhook and Spring Boot terminals running while using the application and running the API regression suite.
