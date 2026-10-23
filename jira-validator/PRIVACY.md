# Privacy Policy — Jira Validator small teams

Last updated: 2026-10-14. Effective on first marketplace publish.

This Privacy Policy explains what data **Jira Validator small teams** (the
"App", a Forge app published on the Atlassian Marketplace) reads, stores,
and shares when an Atlassian Cloud administrator installs it on a Jira site.

The App is published by an individual partner (the "Publisher", identified
on the Marketplace listing). The App runs entirely on Atlassian Forge
infrastructure and does not operate any external server.

## 1. Data we read

The App reads the following Jira data, scoped by the Atlassian permissions
declared in the manifest:

- The **custom fields** and a small set of **system fields** (priority,
  story points, due date, labels, components, issuetype, project key) of
  every Jira issue on which the App is invoked via the issue panel.
- The **project metadata** (project id, project key, project lead) of every
  project the App is asked to validate against.

We do **not** read comments, attachments, worklogs, time tracking data,
user profiles, audit logs, or any other Jira data.

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
