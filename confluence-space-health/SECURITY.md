# Security — Confluence Space Health

Last updated: 2026-10-24.

Confluence Space Health runs entirely on the Atlassian Forge runtime, on
Atlassian's own infrastructure. It has no server of the publisher's own.

## What the app reads

- **The spaces of the tenant** — key, name and last update, through the
  `read:space:confluence` scope.
- **The metadata of the pages** in those spaces — last update timestamp, label
  list, and owner account id — through the `read:page:confluence` scope.

It does not read page bodies, comments, attachments, user profiles, or audit
logs. It reads nothing outside Confluence.

## What the app writes

- **Nothing in Confluence.** No page, label, comment, permission or space is
  created, edited or deleted. The app declares **no write scope**.

## What the app stores

- **One small configuration object per tenant**, at the key
  `governance:<tenantId>` in the Atlassian Forge storage: the two thresholds
  (`staleDays`, `orphanDays`) and the optional list of space keys to include.
  No page metadata, no account id, no personal data is stored.

## Where the data lives

- Everything stays inside the customer's Atlassian site, encrypted at rest.
- Stored by Atlassian in the Cloud region of the site (EU, US, APAC).
- No outbound network call is made: the manifest declares no external
  permission, and the app calls no third-party API, no analytics and no AI
  service.
- Uninstalling the app from the site deletes the configuration it stored.

## Permissions requested

`read:space:confluence`, `read:page:confluence` (both read-only) and
`storage:app` (to store the configuration described above). No write scope, no
admin scope, no user-identity scope, no external fetch permission.

## Reporting a vulnerability

Email **tgg.apps.support@gmail.com** with the steps to reproduce, the app
version and the affected site. Please do not open a public GitHub issue for a
security problem: we confirm and fix it before anything is disclosed publicly.

We aim to acknowledge a report within **2 business days**.
