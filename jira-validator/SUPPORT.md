# Support — Jira Validator small teams

Thanks for installing **Jira Validator small teams**. This page explains
how to get help, how to report bugs, and how to request features.

## Status

- Free tier: 5 enforced fields per project, unlimited projects, panel view,
  violations table.
- Pro tier: unlimited enforced fields, strict (blocking) mode, schema
  versioning + diff, Slack notifications on new violation types.

## How to get help

1. **Read the FAQ below** — covers the 5 questions we get every week.
2. **Open a GitHub issue** in this repository with the label `support`.
   Please include:
   - Jira Cloud site URL (or `*.atlassian.net` host).
   - App version (visible in the issue panel footer).
   - Steps to reproduce.
   - The schema text you pasted (if relevant).
3. **Email** — **tgg.apps.support@gmail.com**.

## FAQ

### 1. Why does my panel say "Schema: none loaded"?

The Admin Panel schema has not been saved yet for this project. Open the
Forge Admin Panel, pick your project, paste a JSON Schema, hit Save.

### 2. Can I have multiple schemas per project?

No — one schema per project in the free tier. Pro tier keeps the last
N versions and lets you diff or roll back, but only one is "active" at a
time.

### 3. Why is my `due_date` failing validation when Jira shows it correctly?

The App expects ISO 8601 dates (`YYYY-MM-DD`). Jira dates set via the
calendar picker are usually ISO; dates typed as text (e.g. "tomorrow")
fail the `format: date` check.

### 4. Does the App work with Jira Service Management?

Yes — the issue panel mounts on JSM issues too. The Admin Panel and
strict mode work the same way. No JSM-specific scope is requested.

### 5. How do I uninstall cleanly?

In your Jira site: **Settings → Apps → Manage apps → Jira Validator →
Uninstall**. This removes all stored schemas.

## Bug reports

Open an issue with:

- Reproduction steps
- Expected vs actual behaviour
- Browser + Jira Cloud version (visible in Jira footer)
- App version (in the issue panel footer)

## Feature requests

Open an issue with the label `enhancement`. We triage monthly. The most
upvoted ideas land in the next minor release.

## Security disclosures

Please email security issues rather than filing public issues:
**tgg.apps.support@gmail.com**.

## Service level

We aim for a first response within **5 business days** on weekdays.
Pro tier customers get priority response within **1 business day** after
launch.

## Changelog

See [`CHANGELOG.md`](./CHANGELOG.md) in this repository.
