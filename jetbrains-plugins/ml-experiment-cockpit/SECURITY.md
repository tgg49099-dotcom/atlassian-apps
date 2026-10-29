# Security Policy — ML Experiment Cockpit

## Reporting a vulnerability

**Please do not open a public GitHub issue for a security problem.**

Report it by email to **tgg.apps.support@gmail.com** with:

- the plugin version and your IDE build;
- what you did, what you expected, and what happened;
- the steps to reproduce, and any proof-of-concept you are comfortable sharing
  by email.

We acknowledge reports as soon as we can (normally within a few working days)
and keep you informed while we investigate. We will tell you when a fix is
published, and we will credit you in the release notes if you want us to.

Please give us a reasonable window to ship a fix before disclosing publicly.

## What is in scope

- The plugin reading or writing data it should not.
- The plugin sending a request to a host other than the one you configured, or
  leaking your W&B API key, your MLflow/Optuna URIs or experiment data to a
  third party.
- The plugin exposing credentials stored by it (for example the W&B API key
  that lives in the IDE's PasswordSafe).
- Injection or unsafe handling of data returned by an MLflow, Optuna or W&B
  server that leads to code execution in the IDE.

## What is out of scope

- Issues that require an attacker to already control your MLflow, Optuna or
  W&B server, or your local account.
- Vulnerabilities in the IDE itself, in the JetBrains Marketplace, or in the
  MLflow / Optuna / W&B servers — please report those to their own maintainers
  (the plugin displays what those servers return, it does not validate them).
- Denial of service caused by pointing the plugin at an unreachable or
  enormous server.
- Missing hardening that has no practical impact in the plugin's usage.

## Supported versions

Security fixes are applied to the latest published version. The plugin is
published as a free plugin; older versions are not maintained.

| Version | Supported |
|---|---|
| 0.0.17 (current) | yes |
| ≤ 0.0.16 | no — please update |
