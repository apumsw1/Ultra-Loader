
# ULTRA LOADER Control Center

Cloudflare Worker backend and online activation system.

## Required Setup

### 1. D1 Database
Create a Cloudflare D1 database named:

ultra-loader-db

Add a D1 binding to the Worker:

Variable name: ULTRA_LOADER

Select the database you created.

### 2. Database Schema

Run the SQL from schema.sql in the D1 Console.

### 3. Worker Secrets

Open Worker Settings → Variables and Secrets.

Add these three secrets:

ADMIN_PASSWORD
Your private admin login password.

SESSION_SECRET
A random secret with at least 32 characters.

MASTER_KEY
Your permanent activation key.

Never publish these secrets in GitHub or inside the Android APK.

### 4. Deploy

Deploy the Worker and open its workers.dev URL.

### API

POST /api/admin/login

GET /api/admin/keys

POST /api/admin/keys/generate

POST /api/activate

Standard keys are valid for 30 days.

The Android application must verify activation with the API before starting protected services.
