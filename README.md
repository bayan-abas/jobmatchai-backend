# JobMatchAI – Backend

JobMatchAI is an AI-powered recruitment platform designed to connect candidates with relevant job opportunities and help companies manage and evaluate applicants efficiently.

This repository contains the **backend** of the JobMatchAI system.

## Project Overview

The backend is responsible for:

* User authentication and authorization
* Candidate and company account management
* Job posting management
* Job application management
* CV upload and analysis
* AI-based job matching
* Candidate ranking
* Match score calculation
* Interview question generation
* Notifications and system data management
* Communication between the frontend, database, and AI services

## Technologies

The backend was developed using:

* **Java**
* **Spring Boot**
* **Spring Security**
* **JWT Authentication**
* **BCrypt**
* **Spring Data JPA**
* **Maven**
* **PostgreSQL**
* **Supabase**
* **OpenAI API**
* **Docker**
* **Render**

## System Architecture

The backend follows a layered architecture:

```text
Controller
    ↓
Service
    ↓
Repository
    ↓
Database
```

### Controller Layer

Handles HTTP requests from the frontend and exposes REST API endpoints.

### Service Layer

Contains the main business logic of the system, including AI processing, matching logic, validation, and application workflows.

### Repository Layer

Handles communication with the PostgreSQL database using Spring Data JPA.

### Database

The production database is hosted using **Supabase PostgreSQL**.

## Authentication & Security

The system uses **JWT-based authentication**.

Users are divided into two main roles:

* `CANDIDATE`
* `COMPANY`

Security mechanisms include:

* JWT authentication
* Role-based authorization
* Password hashing using BCrypt
* Protected API endpoints
* Login attempt protection
* Ownership validation
* File upload validation
* CORS configuration

## Main Features

### Candidate

Candidates can:

* Register and log in
* Upload a CV
* Analyze their CV using AI
* View available jobs
* Receive AI-generated match scores
* View strengths and missing skills
* Apply for jobs
* Track submitted applications

### Company

Companies can:

* Register and log in
* Create job postings
* Manage active and closed jobs
* View applicants for each job
* View candidate details
* View AI-generated match scores
* Rank candidates according to their suitability
* Generate interview questions
* Accept or reject applications

## AI Matching

JobMatchAI uses AI to analyze candidate CVs and compare them with job requirements.

The matching process includes:

```text
Candidate CV
     ↓
CV Analysis
     ↓
Job Requirements
     ↓
AI Matching Process
     ↓
Match Score
     ↓
Strengths / Missing Skills / Recommendation
```

The system also uses caching mechanisms to reduce unnecessary repeated AI requests.

## Job Status

Jobs can have the following statuses:

```text
ACTIVE
CLOSED
```

Active jobs are visible to candidates.

Closed jobs are no longer available for new applications, while candidates who already applied can still see the job status in their application history.

## API Structure

Main API groups include:

```text
/api/auth
/api/users
/api/jobs
/api/applications
/api/messages
```

Additional endpoints are used for CV analysis, AI matching, candidate ranking, and other system functionality.

## Running the Project Locally

### 1. Clone the repository

```bash
git clone <BACKEND_REPOSITORY_URL>
```

### 2. Enter the project directory

```bash
cd jobmatchai-backend
```

### 3. Configure environment variables

Use the provided:

```text
.env.example
```

as a reference for the required environment configuration.

Do not commit real passwords, API keys, database credentials, or other secrets to Git.

### 4. Build the project

```bash
mvn clean install
```

### 5. Run the backend

```bash
mvn spring-boot:run
```

By default, the backend runs locally on:

```text
http://localhost:8080
```

## Docker

The repository also contains:

```text
docker-compose.yml
```

which can be used for container-based deployment.

Additional deployment information is available in:

```text
DEPLOYMENT.md
```

## Production Deployment

The production architecture includes:

```text
Frontend
Firebase Hosting
      ↓
Backend
Render
      ↓
Database
Supabase PostgreSQL
      ↓
AI Services
OpenAI API
```

## Frontend Repository

The frontend is maintained in a separate GitHub repository:

```text
[<FRONTEND_REPOSITORY_URL>](https://github.com/bayan-abas/jobmatchai-frontend.git)
```

The frontend was developed using **React, TypeScript, and Vite**.

## User Guide

A complete User Guide explaining how to use the JobMatchAI system is included in this repository:

```text
JobMatchAI_User_Guide.docx
```

## Project Structure

A simplified project structure:

```text
jobmatchai-backend/
│
├── backend/
│   └── src/
│       └── main/
│           └── java/
│               └── com/jobmatchai/backend/
│                   ├── controller/
│                   ├── service/
│                   ├── repository/
│                   ├── model/
│                   └── security/
│
├── uploads/
├── .github/
├── .vscode/
├── .env.example
├── .gitignore
├── README.md
├── DEPLOYMENT.md
├── docker-compose.yml
└── JobMatchAI_User_Guide.docx
```

## Project Team

**JobMatchAI**

Developed as a final project in Information Systems.

## Notes

* The backend and frontend are maintained in separate repositories.
* Both repositories are required in order to review the complete system.
* Sensitive configuration values are not stored in Git.
* The User Guide is included in the repository for installation and system usage instructions.
