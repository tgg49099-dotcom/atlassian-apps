# Privacy Policy — Confluence Space Health

> Hosted at the GitHub URL Atlassian lists in the Marketplace Partner
> Profile as the privacy URL for the Confluence Space Health app.

Last updated: 2026-10-14.

Confluence Space Health ("the App") is a read-only Confluence Cloud
admin dashboard published by an individual Atlassian Marketplace
Partner. This page documents what data the App accesses, how it
processes that data, and what it does **not** do.

## What the App accesses

The App declares two read-only Confluence scopes on
`developer.atlassian.com/platform/forge/manifest-permissions/confluence/`:

- `read:confluence-content.summary` — to read each page's timestamp,
  label list, and owner accountId.
- `read:confluence-space.summary` — to enumerate the spaces in the
  tenant.

The App reads no other Atlassian resource. The App does **not** read
the body of any page, any user profile, any comment, or any
permission. The App does **not** request any `write:*` scope.

## What the App does with the data

The App computes, per space:

- Page count.
- Days since the most recent page update.
- The % of pages that carry at least one label.
- The % of pages that have no owner accountId AND have not been
  edited in the configured orphan window.

The App then displays these numbers in a single table inside the
Confluence Cloud admin page. The page metadata is **only** used to
compute those four numbers. The metadata is never sent off the
Forge runtime.

## What the App persists

The App persists **one** piece of data per tenant: the admin's
threshold config (stale / orphan days, optional include-list) at
`governance:<tenantId>` in the Forge KVS. The persisted object is
~200 bytes. No page metadata is persisted. No accountId is
persisted. No PII of any kind is persisted.

## What the App does NOT do

- Does not write to any page, label, comment, or permission.
- Does not call any AI / LLM API. No `llm:access` scope is declared.
- Does not fetch any external URL. The single
  `permissions.external.fetch.backend` entry in `manifest.yml` is a
  placeholder for schema compliance; no fetch is ever made.
- Does not sell, share, or export any data to third parties.
- Does not log page metadata, accountIds, or space names anywhere
  outside the Forge runtime.

## On uninstall

When an Atlassian admin uninstalls the App, the Forge runtime
deletes all KVS entries scoped to the install. There is no other
persistence. Uninstall is clean.

## GDPR / data subject rights

Because the App persists no PII and reads only page metadata, there
is no personal data to export or delete on a per-user request. The
threshold config can be deleted by the admin via the App's "Reset"
button or by uninstalling the App.

## Contact

For privacy questions, open an issue at the support URL or email
**tgg.apps.support@gmail.com**.

## Changes to this policy

Any change will be published at this URL with a CHANGELOG entry.
The App does not push notifications.
