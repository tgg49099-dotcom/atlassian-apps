# Support — Jira Validator small teams

If you use **Jira Validator small teams**, this page explains what the app
does, how to get help, and how to report a problem.

## What the app does

Jira Validator is a **free** Jira Cloud app. It checks the fields of a Jira
issue against a **JSON Schema** that a project admin has saved for the
project, and **reports** the violations it finds in the issue panel.

- The app **reports**, it never **blocks**. A violation is a signal, not a
  gate: nothing prevents you from saving, transitioning or closing an issue.
- The app **never modifies** anything in Jira — it does not edit fields, add
  comments or change statuses.
- Your data stays inside your Atlassian site. The schema is stored in
  Atlassian's Forge storage, on Atlassian's infrastructure, in the region of
  your site. The app calls no external service.
- One schema per project. The schema saved in the admin page applies to
  every issue of that project.

## How to get help

1. **Read the FAQ below** — it covers the questions we are asked most.
2. **Email** — **tgg.apps.support@gmail.com**. Please include:
   - Your Jira Cloud site URL (or `*.atlassian.net` host).
   - The steps to reproduce.
   - The exact message shown by the panel.
   - The schema text you pasted (if relevant).
3. **GitHub issue** — the repository of this app is public
   (`tgg49099-dotcom/atlassian-apps`). Use the label `question` for a
   question, `bug` for a defect, `enhancement` for an idea. Do not paste
   confidential issue data in a public issue.

## FAQ

### 1. Why does my panel say "No schema is set for this project yet"?

The project has no schema saved yet, so there is nothing to validate
against. Open the admin page (**Project settings → Apps → Jira Validator**),
pick your project in the list, paste a JSON Schema, and press **Save**. The
issue panel then checks the issues of that project against it.

If the panel says instead that the stored schema could not be parsed, the
schema saved for the project is not valid JSON — open the admin page and
save it again.

### 2. Can I have several schemas per project?

No — one schema per project. Saving again replaces the schema saved for
that project.

### 3. Why is my `due_date` failing validation when Jira shows it correctly?

When the schema asks for the `date` format, the app expects an ISO 8601
date (`YYYY-MM-DD`). Dates typed as free text (for example "tomorrow") fail
the `format: date` check; the calendar picker produces a date Jira itself
also accepts.

### 4. Does the app work with Jira Service Management?

Yes — the issue panel also appears on JSM issues. No JSM-specific
permission is requested: the app uses the same `read:jira-work` scope.

### 5. How do I uninstall cleanly?

In your Jira site: **Settings → Apps → Manage apps → Jira Validator →
Uninstall**. Atlassian deletes the schemas the app had stored for the site
when the app is uninstalled.

## Bug reports

Email **tgg.apps.support@gmail.com** with:

- The steps to reproduce
- Expected vs actual behaviour
- The exact message shown by the panel
- Your browser and your Jira Cloud version

## Feature requests

Open an issue with the label `enhancement`, or email us. Every request is
read.

## Security disclosures

Please email security issues rather than filing public issues:
**tgg.apps.support@gmail.com**. See [`SECURITY.md`](./SECURITY.md) for what
the app reads and writes.

## Service level

We aim for a first response within **5 business days** on weekdays.
