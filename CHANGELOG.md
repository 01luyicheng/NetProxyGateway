# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Added CHANGELOG.md for tracking project changes
- Added GitHub Actions CI/CD workflow for automated testing
- Added Git hooks for pre-commit validation
- Added GitHub PR and Issue templates

### Changed
- Updated Git user email to 01luyicheng@gmail.com
- Established `dev` branch as the main development branch

## [0.1.0] - 2026-03-26

### Added
- Initial release of NetProxyGateway
- Android client with VPN, SOCKS5 proxy, and MQTT connection support
- Go server components: REST API, SOCKS5 proxy, WebSocket tunnel gateway
- Docker Compose configuration for server deployment
- Unit tests for core Android components
- AGENTS.md (CLAUDE.md) as AI agent execution contract

### Security
- JWT-based authentication for REST API
- MQTT TLS with certificate pinning support
- RFC1918 private address filtering in SOCKS5 proxy
- Rate limiting and login failure protection

[Unreleased]: https://github.com/01luyicheng/NetProxyGateway/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/01luyicheng/NetProxyGateway/releases/tag/v0.1.0
