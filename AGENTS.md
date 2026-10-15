# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Java 25 Spring Boot application built with Maven. Production code lives under `src/main/java/com/svi/channelconnect/` and is organized by feature. Keep WhatsApp HTTP, orchestration, Meta integration, DTOs, models, security, and configuration under `whatsapp/`. Keep the channel-neutral AI boundary and adapters under `ai/`; that package must not depend on WhatsApp. Put only genuinely cross-feature types under `common/`. Keep `ChannelConnectApplication` and the shared `MessagingAppHandler` directly under the root package, with channel-specific implementations in sibling `whatsapp/` and `messenger/` folders. Tests mirror the main package tree, with shared test factories under `com.svi.channelconnect.support` and webhook fixtures in `src/test/resources/fixtures/whatsapp/`.

## Build and Development Commands

Use the checked-in Maven wrapper so contributors share the same Maven version.

- `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` (Windows: `.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`) starts the local in-memory service.
- `./mvnw compile` compiles production code without running tests.
- `./mvnw -Dmaven.test.skip=true package` creates the executable JAR in `target/` without compiling or running tests.

Run commands from the repository root. Do not commit generated `target/` content.

## Coding Style & Naming Conventions

Follow standard Java naming: `PascalCase` for classes and interfaces, `camelCase` for methods and variables, and uppercase snake case for constants. Retain the root package `com.svi.channelconnect` and place new channel connectors directly beneath it without a `features` wrapper. Match the existing Spring-generated style: tabs for indentation, opening braces on the declaration line, and one public top-level type per file. Prefer constructor injection for Spring dependencies and small classes with a single responsibility. No formatter or linter is currently configured; use IDE formatting and remove unused imports before committing.

## Testing Guidelines

Do not create, add, or modify tests, and do not run any tests, unless the user explicitly requests that work. This applies to bug fixes, features, refactors, and verification. Do not run Maven goals or other commands that execute tests indirectly; when packaging, use `-Dmaven.test.skip=true`. Verify changes through code review and production compilation when appropriate, and state that tests were not run under this repository policy. This policy takes precedence over testing recommendations in skills and other repository guidance.

## Commit & Pull Request Guidelines

The short history does not establish a formal convention. Use concise, imperative subjects such as `Add webhook signature validation` and keep commits focused. Pull requests should explain the change, list verification commands, link relevant issues, and call out configuration or API-contract changes. Include sample requests or responses when endpoint behavior changes.

## Security & Configuration

Never commit access tokens, webhook secrets, customer phone numbers, message bodies, or private API URLs. Supply secrets through environment variables; `.env.example` is an inventory and is not loaded automatically. The nondurable dispatcher must remain limited to `local` and `test`; production requires a durable implementation.

The outbound REST endpoint must also remain limited to `local` and `test`. It requires bearer authentication, recipient/template allowlists, and an `Idempotency-Key`. Preserve the distinction between Meta accepting a submission and confirming delivery through a later webhook. The process-local outbound idempotency store is bounded and nondurable; do not enable it as a production delivery mechanism.
