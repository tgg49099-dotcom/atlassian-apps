# Security — Jira Validator small teams

Last updated: 2026-10-23.

Jira Validator small teams runs entirely on the Atlassian Forge runtime, on
Atlassian's own infrastructure.

## What the app reads

- **The issue** the panel is attached to, fetched as the signed-in user.
- **Project metadata** (id, key, name) of the project selected in the admin page.

It reads no comments, attachments, worklogs, user profiles or audit logs.

## What the app writes

- **One JSON Schema per project**, in Atlassian Forge storage (`storage:app`),
  under the key `schema:<projectId>`.

The app never writes to Jira: it does not edit issues, add comments or change
statuses. The `write:jira-work` scope is declared but unused.

## Where the data lives

- Everything stays inside the customer's Atlassian site, encrypted at rest.
- Stored by Atlassian in the Cloud region of the site (EU, US, APAC).
- No server of the publisher's own, no external service, no secret stored.
- Uninstalling the app from the site deletes the schemas it stored.

## Permissions requested

`read:jira-work`, `write:jira-work` (declared, unused), `storage:app`. No
user-identity scope, no admin scope, no Confluence scope.

## Reporting a vulnerability

Email **tgg.apps.support@gmail.com** with the steps to reproduce, the app
version and the affected site. Please do not open a public GitHub issue for a
security problem: we confirm and fix it before anything is disclosed publicly.
We aim to acknowledge a report within **5 business days**.
