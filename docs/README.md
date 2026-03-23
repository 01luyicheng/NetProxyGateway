# Documentation Policy (AI-First)

This repository maintains **minimal documentation** on purpose.

## What We Keep

- **[CLAUDE.md](CLAUDE.md)**: Single authoritative source for execution contracts, constraints, and next actions
- **This file**: Documentation retention policy and pointer to code-as-truth

## What We Delete

We proactively delete these types of documents to reduce AI context noise:

| Document Type | Example | Reason |
|---|---|---|
| Historical plans | `.sisyphus/plans/*.md` (outdated) | Past work and assumptions, not current reality |
| Architecture blueprints | `ARCHITECTURE.md` | Code is the architecture; docs lag reality |
| Unscoped duplicate README | Multiple README files with overlapping purpose | Redundancy causes sync problems |
| API documentation | `docs/api/...` | Code interfaces are self-documenting |
| "Aspirational" designs | "Future: support X" | Risk of treating fiction as fact |
| Change logs | `CHANGELOG.md` | Git history suffices; not a decision driver |
| Contributor guides | `CONTRIBUTING.md` | Not applicable for AI-driven development |

## Retention Rule

**Keep only documents that directly change AI execution decisions.**

When in doubt:
1. Does this document change what an AI agent does next? → **Keep it**
2. Is it also documented accurately in code or tests? → **Delete it and reference code**
3. Is it a record of past decisions already in git? → **Delete it**
4. Does it reflect aspirational (not actual) state? → **Delete it**

## Update Discipline

Whenever implementation reality changes:
1. Code changes first (source of truth)
2. Update this repository's tests
3. Then update [CLAUDE.md](CLAUDE.md) to reflect new constraints
4. Never keep docs describing "what we plan to do someday"

## Annual Review  

See [CLAUDE.md section 11](CLAUDE.md#11-annual-documentation-audit-maintenance-protocol): 
Every 12 months, audit this file and CLAUDE.md. Delete anything older than 6 months that is already resolved.
