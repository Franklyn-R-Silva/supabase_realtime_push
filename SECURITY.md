# Security Policy

## Supported Versions

Security fixes go to the latest release. Please upgrade before reporting.

| Version | Supported |
|---------|-----------|
| Latest release | ✅ |
| Older releases | ❌ |

## What is sensitive here

`supabase_realtime_push` keeps a per-device topic and secret on the phone and
uses them to read notifications from your Supabase project without the user's
session. A flaw that lets someone read another device's notifications, keep
receiving them after `stop(forgetDevice: true)` or after the device was
unregistered, or recover the secret from the database, is a vulnerability.
So is anything in how this repository builds and publishes the package.

## Reporting a Vulnerability

**Preferred:** GitHub's
[private vulnerability reporting](https://github.com/Franklyn-R-Silva/supabase_realtime_push/security/advisories/new)
("Report a vulnerability" under the Security tab).

**Or** email the maintainer at franklyn.dev.mobile@gmail.com. Either way, include:

- A description of the vulnerability and its impact
- Steps to reproduce (a minimal sample is ideal)
- The `supabase_realtime_push`, Flutter and Supabase (or Realtime image)
  versions affected

Please do not open a public issue for a vulnerability.

## Response Expectations

- **Acknowledgement:** within 5 business days
- **Assessment & fix timeline:** communicated after triage
- **Disclosure:** coordinated. Reporters are credited unless they prefer to
  stay anonymous.
