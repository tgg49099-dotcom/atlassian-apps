# Privacy Policy — Jira Validator small teams

Last updated: 2026-10-24. Effective on first marketplace publish.

This Privacy Policy explains what data **Jira Validator small teams** (the
"App", a Forge app published on the Atlassian Marketplace) reads, stores,
and shares when an Atlassian Cloud administrator installs it on a Jira site.

The App is published by an individual partner (the "Publisher", identified
on the Marketplace listing). The App runs entirely on Atlassian Forge
infrastructure and does not operate any external server.

## 1. Data we read

When the validation panel is opened on an issue, the App makes **one** read
request for that issue, as the signed-in user:

    GET /rest/api/3/issue/<issueKey>

The request carries **no field filter**, so Atlassian returns the issue's
standard field set. The App receives, in memory and for the duration of that
single request: the issue's system fields (summary, description, labels,
components, priority, dates, time tracking, status, links, subtasks, ...), its
custom fields, and the issue's creator, reporter and assignee references
(account id, display name, email address) as Jira returns them.

The App then applies the project's JSON Schema to that object. **Only the
fields named in the schema are looked at**; every other field is discarded
immediately. In particular, the App:

- does not call the comment, worklog or attachment endpoints, and receives no
  comment body;
- stores no issue field, no user identifier, no email address and no personal
  data - the only thing written to storage is the schema (section 2);
- sends nothing to any external service (section 3).

Because the schema is yours, **a rule you write can target any field of the
issue**, including the creator's or the reporter's email address. Do not write
such a rule on a project where that would be inappropriate.

The App also reads the **project metadata** (project id, key, name) of every
project the App is asked to validate against.

## 2. Data we store

The App stores **one JSON Schema document per project** in the Atlassian
Forge storage API. The schema contains:

- The project key or project id it applies to.
- The JSON Schema text the administrator pasted in the Admin Panel.
- A timestamp of the last edit.

The schema is the only piece of data the App writes. The App does not write
any Jira issue data, user data, or attachment data.

Storage is scoped to the Forge installation. Data lives in the Atlassian
Cloud region the customer has selected (EU, US, APAC…) — we do not choose
the region. Data is encrypted at rest by Atlassian.

## 3. Data we share

The App **does not share data with any external party**. There is no
egress network call declared in the manifest. The App does not call
analytics services, error reporting services, marketing trackers, or
third-party APIs.

## 4. Data retention

A schema is kept until the administrator deletes it via the Admin Panel
or until the App is uninstalled from the Jira site. Uninstalling the App
removes all storage entries (per Atlassian Forge uninstall behavior).

## 5. Permissions used

The App requests only the following Atlassian API scopes:

- `read:jira-work` — to read issue fields for validation.
- `write:jira-work` — declared in the manifest but not used by the app: it
  reads and reports, it never writes to Jira.
- `storage:app` — to store one JSON Schema per project.

No user-identity scope, no admin scope, no Confluence scope, no
`llm:access` scope, no `read:cmdb-object` scope, no `read:jira-user` scope.

## 6. Children

The App is not directed at children and does not knowingly process data
of minors.

## 7. Changes to this Policy

Material changes are announced on the Marketplace listing changelog and
on this page with a new "Last updated" date.

## 8. Contact

Publisher contact email: **tgg.apps.support@gmail.com**. For data
deletion requests, uninstalling the App removes all stored schemas.

## 9. Hosting and compliance

The App is hosted on Atlassian Forge, which is SOC 2 Type II and ISO 27001
certified and offers GDPR-compliant data residency. See the Atlassian
Trust Center for details.
