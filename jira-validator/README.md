# Jira Validator small teams

**A free Jira Cloud app that checks the fields of an issue against a JSON Schema
you define, and reports what is missing or wrong — without blocking anything.**

| | |
|---|---|
| **Runs on** | Atlassian Forge — inside your own Jira site, on Atlassian's infrastructure |
| **Price** | Free. No paid tier, no per-seat pricing, no usage metering |
| **Data leaves your site** | No. The app declares no egress at all |
| **App version** | 3 — the build currently served in production |

---

## What it does

Every time you open a Jira issue, the **Jira Validator** panel appears next to
it. The panel applies your project's JSON Schema to the issue and lists, field
by field, what does not conform: a missing required field, a number outside
its range, a value outside its enum, a date in the wrong format.

Two rules govern everything the app does:

- **It reports. It never blocks.** A violation is a signal, not a gate. You can
  still save, transition, comment on or close the issue — nothing is prevented.
- **It never modifies anything.** The app does not edit a field, add a comment,
  change a status or write an issue property. It reads and it tells you.

You define the rules. Paste a JSON Schema in the admin page and the panel
checks every issue of that project against it. One schema per project.

## Who it is for

Small teams (roughly 2 to 15 people) who need a **lightweight data-quality
check on their custom fields** but who do not want to install a heavyweight
Jira app or a validation engine:

- An ops team that wants every ticket to carry a `story_points`, a `region`
  and a `due_date`, and that wants to see at a glance which ones do not.
- A support team that must not let a ticket through without a `severity` and an
  `assignee_group` from a fixed list.
- Any team that has agreed on a field convention and would like it visible on
  every issue rather than enforced by a script.

It is **not** a workflow-automation tool, not a reporting dashboard, and not an
AI assistant. It does one thing: show you where an issue does not match the
rules you wrote.

## Install (2 steps)

**Step 1 — install the app.** In Jira Cloud, go to **Administration → Apps →
Get new apps**, search for *Jira Validator small teams*, press **Install**, and
approve the permissions listed below. (You can also press *Get started*
directly on the Marketplace listing.) The app adds one panel to the issue view
and one page to the project settings — it changes no existing Jira behaviour.

**Step 2 — save your schema.** Open one of your projects → **Project settings →
Apps → Jira Validator**. Pick the project, paste your JSON Schema in the
editor, and press **Save**. Then open any issue of that project: the panel
shows every field as OK, or lists the fields that fail and why.

Example of a schema you can paste:

```json
{
  "type": "object",
  "properties": {
    "story_points": { "type": "number", "minimum": 0, "maximum": 100 },
    "region":       { "type": "string", "enum": ["EMEA", "AMER", "APAC"] },
    "due_date":     { "type": "string", "format": "date" }
  },
  "required": ["story_points", "region"]
}
```

The editor checks the schema before saving it and tells you what is wrong with
it (it must be a JSON Schema object, must stay under 100 KB, and cannot use
external `$ref`s).

## The three permissions it requests, and why

The app declares exactly three scopes, visible in the app manifest:

| Scope | Why it is requested |
|---|---|
| `read:jira-work` | To read the issue's fields and the project list, so your schema can be applied to them. **Read-only.** The app makes three GET calls and no others. |
| `write:jira-work` | **Declared but not exercised by the current build:** the app contains no write path — it never writes to Jira. This scope was reserved for a "strict mode" that would block a transition, which is not built. **It is removed from the manifest before the Marketplace submission**, and it will be re-added only together with the feature that uses it. |
| `storage:app` | To store **one JSON Schema per project** in the Forge Key-Value Store, scoped to your site. It grants no access to Jira data. |

The app asks for **no user-identity scope**, **no admin scope**, **no
Confluence scope**, **no `llm:access` scope**, **no `read:cmdb-object`
scope**. It never calls the comment, worklog, attachment or user-profile
endpoints, so it reads no comment body, no attachment and no audit log.

One point stated plainly, because it is in the [Privacy Policy](PRIVACY.md):
the issue read carries **no field filter**, so Jira returns its standard field
set — which includes the issue's creator, reporter and assignee references.
The app then discards every field that your schema does not name, and stores
none of them.

## What it does **not** do

- It does not **block** anything: no transition is prevented, no field is
  made mandatory by the app.
- It does not **modify** anything: no edit, no comment, no status change, no
  issue property.
- It does not **send your data anywhere**: no external service, no analytics,
  no error reporter, no marketing tracker.
- It does not **use AI or a language model** at runtime. The validation is plain
  JSON Schema evaluation, locally, in the Forge runtime.
- It does not **store issue data**: the only thing written to storage is your
  schema text, its timestamp, and its last 10 versions.
- It does not **read your users**: no `/myself`, no user search, no account ids.

## How your data is handled

Everything stays inside your Atlassian site. The schema you paste is stored
by Atlassian in the Cloud region your site uses (EU, US, APAC — we do not
choose the region), encrypted at rest. Uninstalling the app deletes the
schemas it stored. See the [Privacy Policy](PRIVACY.md) for the full text.

## Support, privacy and security

- **Privacy Policy** — [PRIVACY.md](PRIVACY.md)
- **Support & FAQ** — [SUPPORT.md](SUPPORT.md)
- **Security policy** (how to report a vulnerability, what is in scope, the
  response SLA) — [SECURITY.md](SECURITY.md)
- **Email** — `tgg.apps.support@gmail.com`. Please include your Jira site URL,
  the steps to reproduce, and the exact message shown by the panel.
- **Source** — this app's public documents live in this repository; the
  repository is open for questions and bug reports.

## Version

**App version 3** is the build currently served in production. Material
changes are announced in the Marketplace listing changelog and in the
`Last updated` line of the [Privacy Policy](PRIVACY.md).
