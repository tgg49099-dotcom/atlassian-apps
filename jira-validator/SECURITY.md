# Security — Schema Validator for Jira

Last updated: 2026-10-26.

Schema Validator for Jira runs entirely on the Atlassian Forge runtime, on
Atlassian's own infrastructure.

## What the app reads

- **The issue** the panel is attached to, fetched as the signed-in user.
- **Project metadata** (id, key, name) of the project selected in the admin page.

It never calls the comment, worklog, attachment, audit or user-profile
endpoints, so it reads no audit log. The single issue response does carry the
issue's comments (with their text), its worklog entries and its attachments'
metadata when the issue has them, and the creator, reporter and assignee
references Jira includes: the app applies them to the administrator's schema
in memory, stores none of them and sends none of them anywhere.

## What the app writes

- **One JSON Schema per project**, in Atlassian Forge storage (`storage:app`),
  under the key `schema:<projectId>`, with its version number, its last-edit
  timestamp and the Atlassian account ID of the administrator who saved it.
  Only the current version is kept - there is no history of previous
  versions, and no per-schema delete button: uninstalling the app is what
  removes the entry.

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
