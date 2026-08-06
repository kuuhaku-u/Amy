# Monthly Spend PWA

The app also includes a free Insights screen with daily spending bars, monthly category analysis,
week-over-week comparisons, and deterministic observations calculated by the Java backend. It uses
no AI service or paid analytics API.

The optional private Android APK adds on-device payment-notification detection and receipt/payment-
screenshot OCR. Every match is an editable draft: nothing changes the sheet until the user confirms it.
Raw notification text and images are discarded after parsing.

An Android-installable expense form backed by Spring Boot and Google Sheets. The app loads or creates one row per full date in the first `Monthly Spend` worksheet, supports offline saves, and updates only fields changed by the user.

## What is included

- React + TypeScript mobile PWA with an IndexedDB offline queue and background sync.
- Persistent Light, Dark, and original Sanrio-inspired pastel themes.
- The private-use Sanrio theme displays unmodified official 2026 My Melody, Pochacco, Cinnamoroll, U*SA*HA*NA, My Sweet Piano, Lovelymocha, and Hanamaruobake wallpaper artwork in a swipeable gallery and soft background collage, with source attribution.
- Java 17 / Spring Boot API served from the same application.
- Service-account authentication for Google Sheets; credentials never reach the browser.
- Private bearer-link access, request validation, write rate limiting, and retry deduplication.
- Date lookup using the real year/month/day while displaying `MM-DD` in Sheets.
- Existing `NA` and `IDK` cells are treated as empty amounts and as zero in calculations; untouched cells remain unchanged.
- Backend calculations:
  - `Total`: Travel + Breakfast + Lunch + Eve Snack + Dinner.
  - `week Total`: full Monday–Sunday Total, repeated on each row in that week.
  - `Monthly Spend`: running monthly sum of all nine expense fields.

## Architecture overview

The project has three main parts:

```text
Android phone / browser
        │
        │ HTTP requests with private bearer token
        ▼
React + TypeScript PWA (port 5173 in development)
        │
        │ GET and PUT /api/monthly-spend
        ▼
Java Spring Boot backend (port 8080)
        │
        │ Google service-account authentication
        ▼
Google Sheets API ──► Monthy Spend worksheet
```

The browser never connects directly to Google Sheets and never receives `credentials.json`. Only the Java backend can read and update the workbook.

In development, Vite serves the frontend on port `5173` and proxies `/api` requests to Java on port `8080`. In production, React is compiled into static files and packaged inside the Spring Boot JAR, so one Java process serves both the website and API.

## Project structure

```text
eva/
├── credentials.json               Local Google service-account credentials
├── package.json                   Frontend dependencies and npm commands
├── pom.xml                        Java dependencies and Maven build
├── vite.config.ts                 Vite config and development API proxy
├── Dockerfile                     Production container build
├── scripts/
│   └── dev.sh                     Starts frontend and backend together
├── public/
│   ├── manifest.webmanifest       Android/PWA installation metadata
│   ├── sw.js                      Offline cache and background sync worker
│   └── icon.svg                   Application icon
├── src/
│   ├── App.tsx                    Main phone screen and form behavior
│   ├── api.ts                     Browser-to-Java API calls
│   ├── db.ts                      IndexedDB offline queue
│   ├── types.ts                   Shared frontend field definitions/types
│   ├── styles.css                 Mobile UI styling
│   ├── main.tsx                   React startup and service-worker registration
│   └── main/
│       ├── java/app/monthlyspend/
│       │   ├── MonthlySpendApplication.java     Java entry point
│       │   ├── api/                           HTTP controller and errors
│       │   ├── config/                        Settings, credentials and PWA serving
│       │   ├── security/                      Bearer-token filter and rate limit
│       │   └── sheet/                         Google Sheets and calculation logic
│       └── resources/application.yml          Backend configuration/defaults
├── dist/                           Generated frontend production files
└── target/                         Generated Java JAR
```

`dist`, `target`, `node_modules`, `.tools`, and `credentials.json` are generated, downloaded, or secret files and must not be committed.

## How one submission works

1. The user opens a private URL such as `/#access=dev-local-key`.
2. React saves the access key on that device and removes it from the visible URL.
3. Selecting a date calls `GET /api/monthly-spend?date=YYYY-MM-DD`.
4. Java checks the bearer token before the controller is allowed to run.
5. Java reads the worksheet, validates row 3, converts Google date serials into Java dates, and finds the unique matching row.
6. The user changes one or more expense inputs and presses Save.
7. The browser first puts the submission into IndexedDB. This prevents losing it if the network disappears.
8. The browser sends `PUT /api/monthly-spend` with a submission ID, date, and only the changed fields.
9. Java validates field names and amounts, updates or creates the date row, recalculates totals, and writes the changes with the Google Sheets API.
10. After Google confirms the write, the browser removes the submission from its offline queue and reloads the saved row.

Example request body:

```json
{
  "submissionId": "a-generated-unique-id",
  "date": "2026-07-24",
  "changes": {
    "travel": 120,
    "lunch": 85.5
  },
  "createdAt": 1784900000000
}
```

The backend ignores the extra `createdAt` property. It accepts only known expense keys, so a modified browser cannot write to arbitrary sheet columns.

## Offline behavior

- The service worker caches the application shell so the form can open without a connection.
- IndexedDB stores pending submissions locally on the phone.
- Submissions are processed oldest first when connectivity returns.
- Every submission has a unique ID, making retries safe inside the running backend.
- The date itself is also unique, so retrying a newly created row finds and updates the same date instead of appending another one.
- “Latest submission wins” applies when multiple queued edits change the same field.

## Google Sheets rules

The backend expects headers on row 3 and data beginning on row 4. The full date, including year, is stored internally; the date column is formatted to display `MM-DD`.

Editable fields:

```text
Travel, Breakfast, Lunch, Eve Snack, Dinner, Order, Others, Home, Rent
```

Calculated fields:

- `Total = Travel + Breakfast + Lunch + Eve Snack + Dinner`
- `week Total = sum of Total from Monday through Sunday`
- `Monthly Spend = running calendar-month sum of all nine editable fields`

Blank cells, `NA`, and `IDK` count as zero during calculations. Untouched input cells are not overwritten. If duplicate date rows exist, the backend refuses to choose one and returns a clear error.

## Java and Spring Boot guide

You do not need advanced Java knowledge to work on this project. The important ideas are:

### Java entry point

`MonthlySpendApplication.java` contains `main()`. This is the first Java method that runs:

```java
SpringApplication.run(MonthlySpendApplication.class, args);
```

Spring Boot then scans the project for classes marked with annotations such as `@RestController`, `@Service`, `@Component`, and `@Configuration`, creates them, and connects their dependencies through constructors.

### Controller: HTTP layer

`MonthlySpendController` defines the two API routes:

```java
@GetMapping
SpendResponse get(LocalDate date)

@PutMapping
SpendResponse update(UpdateRequest request)
```

The controller does not contain spreadsheet logic. It converts HTTP input into Java values and calls `MonthlySpendService`.

### Records: request and response data

Java `record` types are small immutable data containers. `UpdateRequest` represents incoming JSON, while `SpendResponse` becomes outgoing JSON. Spring/Jackson performs the JSON conversion automatically.

### Service: business logic

`MonthlySpendService` is the core of the backend. It:

- Validates the exact header layout.
- Converts sheet rows into Java `SheetRow` objects.
- Finds a row using `LocalDate`.
- Validates partial expense changes.
- Calculates daily, weekly, and monthly totals.
- Builds the Google Sheets batch update.
- Uses a lock so two requests in one Java process cannot modify the sheet simultaneously.

`BigDecimal` is used for money rather than `double`, avoiding common decimal rounding errors.

### Google connection

`AppConfig` loads `credentials.json`, just like `Amy/main.py`, and requests both Sheets and Drive scopes. `SheetsClient` obtains an OAuth access token from the service-account credentials and calls the Google Sheets REST API.

The equivalent Python idea is:

```python
creds = Credentials.from_service_account_file("credentials.json", scopes=SCOPES)
client = gspread.authorize(creds)
```

The Java implementation performs the same authentication without requiring Python at runtime.

### Security filter

`ApiSecurityFilter` runs before every `/api/*` request. It checks:

```text
Authorization: Bearer YOUR_ACCESS_TOKEN
```

Invalid tokens receive HTTP 401. Write requests are limited to 60 per minute per client address. This shared token is simple Phase 1 protection; it is not individual user authentication.

### Configuration

`application.yml` contains defaults. `${NAME:default}` means “read environment variable `NAME`; otherwise use `default`.” For example:

```yaml
port: ${PORT:8080}
```

### Maven

Maven is the Java build tool, similar to npm for the frontend:

```bash
mvn spring-boot:run       # Run Java during development
mvn -DskipTests package  # Build the executable JAR
```

Java dependencies are listed in `pom.xml`, while frontend dependencies are listed in `package.json`.

## Google Sheet setup

1. Enable the **Google Sheets API** and **Google Drive API** for the project named in `credentials.json`.
2. Obtain the service-account email without printing any private key:

   ```bash
   jq -r .client_email credentials.json
   ```

3. Share the spreadsheet with that email as **Editor**.
4. The live first worksheet is named `Monthy Spend ` (including its current spelling and trailing space). Row 3 contains these columns in this order:

   ```text
   Date | Travel | Breakfast | Lunch | Eve Snack | Dinner | Order | Others | Total | week Total | Home | Rent | Monthly Spend
   ```

The credentials, worksheet name, row-3 schema, and a real date read were verified successfully through the Java API. The verification was read-only.

## Run locally

Requirements: Node.js 22+, Java 17+, and Maven 3.9+.

```bash
npm install
npm run build
mvn -DskipTests package
APP_ACCESS_TOKEN=replace-with-a-long-random-secret java -jar target/monthly-spend-1.0.0.jar
```

The backend reads `credentials.json` from the project directory by default. Open the private activation link once on each phone:

```text
http://localhost:8080/#access=replace-with-a-long-random-secret
```

For separate frontend development, run `npm run dev`; Vite proxies `/api` to Spring Boot on port 8080.

### Start frontend and backend together

After installing Node.js and Java, run:

```bash
npm run dev:all
```

This starts Spring Boot on port `8080`, Vite on port `5173`, and prints the activation URL. If Maven is not installed, the script downloads Maven 3.9.11, verifies its SHA-512 checksum, and stores it under the git-ignored `.tools` directory. The default development URL is:

```text
http://localhost:5173
```

To choose a different development key:

```bash
APP_ACCESS_TOKEN=your-private-key npm run dev:all
```

Press `Ctrl+C` once to stop both processes.

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `APP_ACCESS_TOKEN` | insecure development value | Shared secret used by private activation links |
| `GOOGLE_APPLICATION_CREDENTIALS` | `credentials.json` | Local service-account JSON path |
| `GOOGLE_CREDENTIALS_JSON` | empty | Complete credentials JSON for hosted environments |
| `GOOGLE_DRIVE_RECEIPT_FOLDER_ID` | empty | Optional private Drive folder ID for receipt uploads; share the folder with the service account as Editor |
| `GOOGLE_DRIVE_FOOD_FOLDER_ID` | empty | Drive folder ID for food receipts |
| `GOOGLE_DRIVE_SPEND_FOLDER_ID` | empty | Drive folder ID for general spending receipts |
| `SPREADSHEET_ID` | supplied spreadsheet ID | Destination spreadsheet |
| `SHEET_NAME` | `Monthy Spend ` | Exact worksheet title, including its trailing space |
| `APP_CORS_ALLOWED_ORIGINS` | `https://localhost` | Exact comma-separated native WebView origins allowed to call the API |
| `PORT` | `8080` | HTTP port |

`credentials.json`, `.dev.vars`, compiled output, and dependency directories are git-ignored.

## Docker and Cloud Run

The multi-stage `Dockerfile` builds the frontend, packages it inside Spring Boot, and runs as a non-root user. A single container serves both the PWA and API.

Cloud Run is a good low-volume host for this Java service because it scales to zero and has a request-based free allowance. A billing account is still required, and usage beyond the allowance can cost money.

Recommended production settings:

- Region: `asia-south1` (Mumbai).
- Request-based billing with minimum instances set to `0`.
- Maximum instances set to `1` so sheet read/modify/write operations remain serialized.
- Store `GOOGLE_CREDENTIALS_JSON` and `APP_ACCESS_TOKEN` in Google Secret Manager.
- Allow public HTTP access to the static application; the `/api/*` routes still require the bearer secret.
- Configure a billing budget and alerts before deployment.

After deployment, activate a phone with:

```text
https://YOUR-SERVICE-URL/#access=YOUR-APP-ACCESS-TOKEN
```

The token is saved on that device and immediately removed from the visible URL. Rotate `APP_ACCESS_TOKEN` if the private link is exposed.

## Production checks

```bash
npm run build
mvn -DskipTests package
```

Before entering real expenses, use a copy of the worksheet to confirm its permissions, headers, date formatting, and calculations.

## Deploy on Render

The repository includes `render.yaml`, which defines one free Docker web service in Render's Singapore region. It builds the existing `Dockerfile` and serves the frontend and backend together.

1. Push the repository to GitHub.
2. In Render, select **New → Blueprint** and connect the repository.
3. Render reads `render.yaml` and asks for two secret values:
   - `APP_ACCESS_TOKEN`: a long random private key used in phone activation links.
   - `GOOGLE_CREDENTIALS_JSON`: the complete contents of `credentials.json`.
4. Create the service and wait for its health check to pass.
5. Open `https://YOUR-SERVICE.onrender.com/#access=YOUR-APP-ACCESS-TOKEN`.

Never put either secret directly in `render.yaml` or commit `credentials.json`.

## Private Android APK

The Android project is a Capacitor wrapper around the same React UI. Android-only code lives under
`android/` and adds:

- explicit system notification-listener access;
- a local encrypted-token store and private SQLite draft inbox;
- conservative debit/credit parsing with OTP rejection;
- gallery and Android image-share imports;
- bundled ML Kit OCR that runs on the phone; and
- merchant-to-category suggestions learned only on that device.

The listener can technically receive notifications from every app after approval, but it immediately
discards messages that do not contain a supported currency amount and transaction wording. Raw text is
never added to SQLite or sent to the server. Credits are logged in `Transaction Log` with status `LOGGED`
and do not reduce the daily summary. Confirmed debits are logged with status `APPLIED` and increment the
chosen daily category. Event IDs prevent retries from adding the same transaction twice.

### Local Android development

Install Android Studio with Android SDK 35 and set `ANDROID_HOME`, then build the bundled web UI and sync it:

```bash
VITE_API_BASE_URL=https://YOUR-SERVICE.onrender.com npm run android:sync
cd android
./gradlew assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Install it only on a
device you control. Open **Inbox**, approve Android notification access, and optionally approve this app's
own notification permission so it can alert you when a draft is waiting.

### One-time signing setup

Create one release key and keep it backed up securely. Losing it means later APKs cannot update the
installed app:

```bash
keytool -genkeypair -v -keystore monthly-spend-release.jks -alias monthly-spend \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 monthly-spend-release.jks
```

On macOS, use `base64 -i monthly-spend-release.jks` for the second command. Do not copy the keystore into
the repository.

In GitHub **Settings → Secrets and variables → Actions**, add repository variable:

- `RENDER_API_BASE_URL`: the Render origin only, such as `https://monthly-spend-pwa.onrender.com`.

Add repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Run the **Android APK** workflow manually for an artifact, or publish a private release with:

```bash
git tag android-v1.0.0
git push origin android-v1.0.0
```

Download `monthly-spend.apk` from the workflow artifact or tagged GitHub release. Both users must install
APKs signed by the same key for in-place updates.

### Transaction capture API

`POST /api/monthly-spend/transactions` uses the same bearer token as the rest of the API. The Android app
sends only the confirmed event ID, date/time, debit or credit type, amount, optional category and merchant,
source identifier, capture method, and random device ID. On first use, the backend creates the
`Transaction Log` worksheet and validates its headers on every write.
