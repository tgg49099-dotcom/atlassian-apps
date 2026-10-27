# Schema Validator for Jira

**A free Jira Cloud app that checks the fields of an issue against a JSON Schema
you define, and reports what is missing or wrong — without blocking anything.**

| | |
|---|---|
| **Runs on** | Atlassian Forge — inside your own Jira site, on Atlassian's infrastructure |
| **Price** | Free. No paid tier, no per-seat pricing, no usage metering |
| **Data leaves your site** | No. The app declares no egress at all |
| **App version** | 4.0.0 — the build currently served in production |

## What it does

Every time you open a Jira issue, the **Schema Validator for Jira** panel appears next to
it. The panel applies your project's JSON Schema to the issue and lists, field
by field, what does not conform: a missing required field, a number outside
its range, a value outside its enum, a date in the wrong format.

Two rules govern everything the app does:

- **It reports. It never blocks.** A violation is a signal, not a gate: you can
  still save, transition, comment on or close the issue.
- **It never modifies anything.** It does not edit a field, add a comment,
  change a status or write an issue property. It reads, and it tells you.

You define the rules: paste a JSON Schema in the admin page and the panel
checks every issue of that project against it. One schema per project.

## Who it is for

Small teams (roughly 2 to 15 people) who want a lightweight data-quality check on their custom fields without installing a heavyweight Jira app:

- An ops team that wants every ticket to carry a `story_points`, a `region`
  and a `due_date`, and to see at a glance which ones do not.
- A support team that must not let a ticket through without a `severity` and
  an `assignee_group` from a fixed list.

## Install (4 steps)

**Step 1 — install the app.** In Jira Cloud, go to **Administration → Apps →
Get new apps**, search for *Schema Validator for Jira*, press **Install**, and
approve the permissions listed below. (You can also press *Get started*
directly on the Marketplace listing.) The app adds one panel to the issue view
and one page to the project settings — it changes no existing Jira behaviour.

**Step 2 — open the app.** Open one of your projects → **Project settings →
Apps → Schema Validator for Jira**. The first time you open it, the app asks
Atlassian for permission to read your Jira data and shows an **Allow access**
button instead of the project list.

**Step 3 — accept Atlassian's consent.** Press **Allow access**, approve on
Atlassian's own consent screen, then reload the page. Until you do, the app
reads nothing — no field, no issue, no project. This is Atlassian's consent,
not ours: the app only ever asks for the reading permission listed below.

**Step 4 — save your schema.** Back on the same page (**Project settings →
Apps → Schema Validator for Jira**), pick the project, paste your JSON Schema,
and press **Save**. Then open any issue of that project: the panel shows every
field as OK, or lists the fields that fail and why.

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

The editor checks the schema before saving it (it must be a JSON Schema object,
under 100 KB, and cannot use external `$ref`s).

## The two permissions it requests, and why

The app declares exactly two scopes, visible in the app manifest:

| Scope | Why it is requested |
|---|---|
| `read:jira-work` | To read the issue's fields and the project list, so your schema can be applied to them. **Read-only.** The app calls only three Jira GET endpoints - the project list, one project's details, one issue - and nothing else. |
| `storage:app` | To store **one JSON Schema per project** in the Forge Key-Value Store, scoped to your site. It grants no access to Jira data. Uninstalling the app deletes the schemas it stored. |

The app asks for **no user-identity scope**, **no admin scope**, **no
Confluence scope**, **no `llm:access` scope**, **no `read:cmdb-object`
scope**. It never calls the comment, worklog, attachment, audit or
user-profile endpoints, so it reads no audit log.

Two points stated plainly, because they are in the [Privacy Policy](PRIVACY.md):

- The issue read carries **no field filter**, so the app reads the **whole
  issue — comments and attachments included**: Jira's standard field set comes
  back, with creator, reporter and assignee references, all discarded unless
  your schema names them.
- That same response also carries the issue's **comments (with their text),
  its worklog entries and its attachments' metadata** (file name, size, type,
  author) when the issue has them. The app hands them to your schema like any
  other field: it never calls those endpoints, never stores them and never
  sends them anywhere. The content of an attached file is never read.

## What it does **not** do

- It does not **block** anything: no transition is prevented, no field is
  made mandatory by the app.
- It does not **modify** anything: no edit, no comment, no status change, no
  issue property.
- It does not **send your data anywhere**: no external service, no analytics,
  no error reporter, no marketing tracker.
- It does not **use AI or a language model** at runtime. Validation is plain
  JSON Schema evaluation, locally, in the Forge runtime.
- It does not **store issue data**: the only thing written to storage is your
  schema (text and parsed form), its version number, its timestamp, and the
  Atlassian account ID of the admin who saved it. No history of previous
  versions is kept.
- It does not **call any user endpoint**: no `/myself`, no user search, no
  profile lookup. The user references Jira includes in the issue response
  are passed to your schema like any other field.

## Support, privacy and security

Everything stays inside your Atlassian site. The schema you paste is stored by
Atlassian in the Cloud region your site uses (EU, US, APAC — we do not choose
the region), encrypted at rest — see the [Privacy Policy](PRIVACY.md) for details.

- **Privacy Policy** — [PRIVACY.md](PRIVACY.md)
- **Support & FAQ** — [SUPPORT.md](SUPPORT.md)
- **Security policy** (reporting a vulnerability, what is in scope, the
  response SLA) — [SECURITY.md](SECURITY.md)
- **Email** — `tgg.apps.support@gmail.com`. Please include your Jira site URL,
  the steps to reproduce, and the exact message shown by the panel.
- **Source** — this app's public documents live in this repository, open for
  questions and bug reports.

## Version

**App version 4.0.0** is the build currently served in production. Material changes
are announced in the Marketplace listing changelog and in the
`Last updated` line of the [Privacy Policy](PRIVACY.md).
