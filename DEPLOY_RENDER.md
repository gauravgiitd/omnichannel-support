# Deploy To Render

This repository includes a `render.yaml` blueprint that provisions:

- A Docker-based Spring Boot web service
- A managed PostgreSQL database

## Prerequisites

- Push this repository to GitHub, GitLab, or Bitbucket
- Create or sign in to a Render account

## Deploy

1. In Render, choose `New` -> `Blueprint`.
2. Connect the repository that contains this project.
3. Render will detect [`render.yaml`](/Users/gaurav.gupta/Code/omnichannel-support/render.yaml).
4. Review the resources:
   - Web service: `omnichannel-support`
   - Database: `omnichannel-support-db`
5. Approve the blueprint to create both resources.

## What Render Configures

The blueprint sets:

- `SPRING_PROFILES_ACTIVE=postgres`
- `DATABASE_HOST` from the managed Postgres instance
- `DATABASE_NAME` from the managed Postgres instance
- `DATABASE_USER` from the managed Postgres instance
- `DATABASE_PASSWORD` from the managed Postgres instance
- `DATABASE_PORT=5432`

The app uses Flyway, so schema migrations run automatically at startup.

## Verify After Deploy

Check these endpoints on your Render URL:

- `/`
- `/customer`
- `/agent`
- `/actuator/health`

## Notes

- The blueprint is set to the `singapore` region. Change this in [`render.yaml`](/Users/gaurav.gupta/Code/omnichannel-support/render.yaml) if you want a different region.
- The database plan is `basic-256mb`, which is fine for a prototype. Upgrade it before production traffic.
