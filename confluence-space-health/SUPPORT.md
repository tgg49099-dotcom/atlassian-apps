# Support — Confluence Space Health

> Hosted at the GitHub URL Atlassian lists in the Marketplace Partner
> Profile as the support URL for the Confluence Space Health app.

Last updated: 2026-10-14.

Confluence Space Health is a small Forge app maintained by a single
Atlassian Marketplace Partner. This page documents how to get help.

## How to file an issue

Open a GitHub Issue at this repository (use the URL the Partner
Profile lists). Please include:

1. Your Confluence Cloud tenant size (number of spaces).
2. The exact error message or unexpected behavior (a screenshot
   helps).
3. The output of the admin page's "Last refresh" line.
4. Whether the issue reproduces after uninstall + reinstall.

You can also reach the publisher by email at **tgg.apps.support@gmail.com**.

## Response time

We commit to a first response within **2 business days** (UTC+1).
Bug fixes ship within 7 days of triage when no third-party
coordination is needed. Feature requests are reviewed monthly.

## Common questions

### The "Refresh" button doesn't change anything

The dashboard reads from the Confluence API on each click. If the
data is unchanged, the table stays the same. Wait ~30 s and click
"Refresh" again.

### The threshold edit modal rejects my values

Each threshold has hard bounds (stale: 7-365 days, orphan: 30-730
days). The meta-validator surfaces a red error banner explaining which
rule failed.

### The table shows "—" for label / orphan %

Either the space has zero pages (we display "—" for empty spaces) or
the API returned a page with malformed metadata (we skip those
silently). Open an issue if you see this for a space that has pages.

### The app does not appear in my admin page

You may not have the `confluence:admin-page` permission. The
marketplace install adds the App, but your Atlassian admin needs
to grant the admin-page permission to your user. Ask your Confluence
admin.

## Status & uptime

The App runs entirely on the Atlassian Forge runtime; the publisher
does not operate any external service. Outages are tracked at
status.atlassian.com.

## Data privacy

See the Privacy URL. The App is strictly read-only on Confluence
content and persists no PII.

## License

Source-available under the LICENSE file in this repository.
