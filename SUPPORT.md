# Getting help

ChainSentinel is maintained by volunteers. There is no paid support and no guaranteed response time, but every
well-described issue is read.

## Before you ask

1. Check the [README troubleshooting table](README.md#troubleshooting).
2. Read the relevant guide in the [documentation index](docs/README.md): installation, configuration variables,
   security hardening, writing rules, API.
3. Search [existing issues](https://github.com/alertmanager12-blip/openchain-intelligence/issues?q=is%3Aissue), open
   and closed: your question may already have an answer.

## Where to ask

| You have… | Go to |
|---|---|
| A bug: something does not work as documented | [Bug report](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=bug_report.yml) |
| An installation or upgrade problem, an incompatibility | [Bug report](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=bug_report.yml), with your versions, environment and logs (redacted) |
| Documentation that is wrong, missing or out of date | [Documentation issue](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=documentation_issue.yml) |
| An idea or a missing feature | [Feature request](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=feature_request.yml) |
| A detection rule to propose | [Rule contribution](https://github.com/alertmanager12-blip/openchain-intelligence/issues/new?template=rule_contribution.yml) |
| A usage question | A GitHub issue for now (GitHub Discussions are not enabled yet) |
| A security vulnerability | **Never in public.** [SECURITY.md](SECURITY.md) |

## What not to open as an issue

- **Security vulnerabilities**: report them privately ([SECURITY.md](SECURITY.md)).
- **Requests to investigate a specific wallet, person or incident**: ChainSentinel is a tool you run yourself; the
  maintainers do not investigate addresses for you.
- **Disputes about a risk signal or a label on an address**: use `POST /api/v1/labels/reports` on your instance, and
  contact the source of the label if it comes from an external list.
- **Anything containing secrets or personal data**: API keys, `.env` files, bot tokens, real customer data. Redact
  logs before you paste them.
- **Support for a third-party service** (TronGrid, an RPC provider, Telegram, Slack) unrelated to ChainSentinel.

## Writing a good report

Say what you did, what you expected, what happened instead, and include your ChainSentinel commit or version, how you
run it (Docker Compose or from source), your Java and Node versions if you build it, the network concerned and the
relevant logs (`docker compose logs app`), with secrets removed.
