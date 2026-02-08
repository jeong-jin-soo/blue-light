# Blue Light - Electrical Installation Licence Platform

Singapore EMA (Energy Market Authority) Electrical Installation Licence application and management platform.

## Overview

Blue Light streamlines the process of applying for electrical installation licences in Singapore. Applicants submit licence applications online with auto-calculated kVA-based pricing, upload required documents (SLD drawings), and track their application status. Administrators review applications, confirm payments, and issue licences.

### Key Features

**Applicant Portal**
- Multi-step licence application form (Address > kVA Selection & Pricing > Review)
- Automatic price calculation based on selected kVA tier
- SLD document upload (PDF/JPG/PNG, max 10MB)
- Application status tracking with step progress indicator
- Dashboard with application summary statistics
- Profile management and password change

**Admin Portal**
- Application monitoring with status filter and search
- Payment confirmation (offline PayNow/bank transfer)
- Status workflow management (PENDING_PAYMENT > PAID > IN_PROGRESS > COMPLETED)
- Licence issuance with licence number and expiry date
- Admin file upload (licence PDF, reports)
- User management

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | React 19, TypeScript, Vite 7, Tailwind CSS v4.1, Zustand 5 |
| Backend | Java 17, Spring Boot 4.0.2, Spring Security, JPA (Hibernate 7.2) |
| Database | MySQL 8.0 |
| Auth | JWT (HS512, 24h expiry) |
| File Storage | Local disk (interface-based, S3-ready) |

## Prerequisites

- Java 17+
- Node.js 20+
- Docker & Docker Compose (for MySQL)

## Getting Started

### 1. Start MySQL

```bash
cd blue-light-backend
docker compose up -d
```

MySQL runs on port **3307** with database `bluelight`.

### 2. Start Backend

```bash
cd blue-light-backend
./gradlew bootRun
```

Backend starts on **http://localhost:8090**. Schema and seed data (kVA pricing tiers) are auto-initialized on startup.

### 3. Start Frontend

```bash
cd blue-light-frontend
npm install
npm run dev
```

Frontend starts on **http://localhost:5174**.

## Project Structure

```
blue-light/
  blue-light-backend/          # Spring Boot API server
    src/main/java/.../
      api/                     # REST controllers
        admin/                 # Admin endpoints
        application/           # Application CRUD
        auth/                  # Signup/Login
        file/                  # File upload/download
        price/                 # kVA pricing
        user/                  # Profile management
      domain/                  # JPA entities & repositories
      config/                  # Security, CORS config
      security/                # JWT filter & provider
    src/main/resources/
      schema.sql               # DDL
      data.sql                 # Seed data (kVA prices)
      application.yaml         # App config

  blue-light-frontend/         # React SPA
    src/
      api/                     # Typed API clients (axios)
      components/
        ui/                    # Design primitives (Button, Input, Modal, etc.)
        data/                  # DataTable, Pagination
        domain/                # StatusBadge, DashboardCard, FileUpload, StepTracker
        common/                # Layout, AuthLayout, ProtectedRoute
      pages/
        auth/                  # Login, Signup
        applicant/             # Dashboard, ApplicationList, NewApplication, ApplicationDetail, Profile
        admin/                 # AdminDashboard, AdminApplicationList, AdminApplicationDetail, AdminUserList
      stores/                  # Zustand (auth, toast)
      router/                  # React Router routes
      types/                   # TypeScript interfaces
```

## API Endpoints

### Public
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/auth/signup` | Register new user |
| POST | `/api/auth/login` | Login (returns JWT) |
| GET | `/api/prices` | List kVA pricing tiers |
| GET | `/api/prices/calculate?kva=100` | Calculate price for kVA |

### Applicant (authenticated)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/applications` | Create new application |
| GET | `/api/applications` | List my applications |
| GET | `/api/applications/:id` | Get application detail |
| GET | `/api/applications/summary` | Dashboard summary counts |
| POST | `/api/applications/:id/files` | Upload SLD file |
| GET | `/api/applications/:id/files` | List application files |
| GET | `/api/files/:id/download` | Download a file |
| DELETE | `/api/files/:id` | Delete a file |
| GET | `/api/applications/:id/payments` | Payment history |
| GET | `/api/users/me` | Get my profile |
| PUT | `/api/users/me` | Update profile |
| PUT | `/api/users/me/password` | Change password |

### Admin (ADMIN role required)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/admin/applications` | List all applications (paginated) |
| GET | `/api/admin/applications/:id` | Get any application detail |
| PATCH | `/api/admin/applications/:id/status` | Update application status |
| POST | `/api/admin/applications/:id/complete` | Complete & issue licence |
| POST | `/api/admin/applications/:id/payments/confirm` | Confirm payment |
| GET | `/api/admin/applications/:id/payments` | Payment history |
| POST | `/api/admin/applications/:id/files` | Upload admin file |
| GET | `/api/admin/dashboard` | Dashboard statistics |
| GET | `/api/admin/users` | List all users |

## Application Status Flow

```
PENDING_PAYMENT  -->  PAID  -->  IN_PROGRESS  -->  COMPLETED
                                                       |
                                                   (licence number +
                                                    expiry date assigned)
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `DB_USERNAME` | `user` | MySQL username |
| `DB_PASSWORD` | `password` | MySQL password |
| `JWT_SECRET` | dev default | JWT signing key (change in production) |
| `FILE_UPLOAD_DIR` | `./uploads` | File storage directory |

## Security

- JWT authentication with role-based access control (APPLICANT / ADMIN)
- URL-level admin route protection (`/api/admin/**`)
- Method-level `@PreAuthorize` on admin endpoints
- Ownership verification on all file operations (IDOR-protected)
- Ownership verification on application access
- BCrypt password hashing
- CORS restricted to frontend origins

## Design System

Custom component library built with Tailwind CSS v4.1:

- **Primitives**: Button, Input, Select, Textarea, Card, Badge, Modal, Toast, LoadingSpinner, EmptyState
- **Data**: DataTable (sortable, responsive columns, loading/empty states), Pagination
- **Domain**: StatusBadge, DashboardCard, FileUpload (drag & drop), StepTracker

Theme tokens defined in `src/index.css` via `@theme` directive (brand color `#1a3a5c`).

## License

Proprietary - All rights reserved.
