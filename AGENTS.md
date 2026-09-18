# Repository Guidelines

## Project Structure & Module Organization

This is a Java 17 Spring Boot application for GEOSYNC data synchronization. Main code lives under `src/main/java/geosync`, organized by responsibility: `config`, `geo`, `mapper`, `model`, `repository`, `scheduler`, `service`, `web`, and `worker`. Thymeleaf templates are in `src/main/resources/templates`, static CSS is in `src/main/resources/static/css`, and runtime configuration is in `src/main/resources/application.yml`.

Repository-level configuration and deployment assets are separate from application code. Sync table XML and SQL scripts are under `conf/`, Windows service helpers are under `scripts/`, and packaged NSSM/runtime artifacts belong in `bin/` or generated `build/` output. Tests are under `src/test/java`.

## Build, Test, and Development Commands

- `gradlew.bat test` runs the JUnit 5 test suite.
- `gradlew.bat bootJar` builds `build/libs/geosync.jar`.
- `gradlew.bat jlinkZip` creates the distributable ZIP with the minimal JRE, scripts, `conf/`, and service assets.
- `gradlew.bat bootRun` starts the Spring Boot app locally using the current configuration.

Run commands from the repository root. `jlinkZip` expects either `jdk/` in this repository or a valid `JAVA_HOME`.

## Coding Style & Naming Conventions

Use standard Java conventions: 4-space indentation, PascalCase class names, camelCase methods and fields, and package names under `geosync`. Keep controllers, services, workers, and configuration classes in their existing package boundaries. Prefer constructor injection or established Spring patterns when adding dependencies.

Configuration files should keep environment-specific values in YAML or `conf/` assets, not hard-coded in Java. Avoid committing generated files from `build/`, `logs/`, `.gradle/`, or `workspace/`.

## Testing Guidelines

Tests use Spring Boot Test, JUnit Jupiter, and AssertJ. Name test classes with the `*Test` suffix and use descriptive method names such as `loadsKrasBaseTables`. Keep focused unit tests near the package being tested under `src/test/java`. Run `gradlew.bat test` before submitting changes, especially for mapper, coordinate transform, SQL generation, and configuration-loading behavior.

## Commit & Pull Request Guidelines

Recent history uses concise Conventional Commit-style subjects, for example `feat: KRAS 연결 테스트 및 스케줄 시간 선택 UI 추가`. Use a short type prefix such as `feat:`, `fix:`, `test:`, or `docs:` followed by a clear summary.

Pull requests should describe the behavior change, list verification commands, and call out configuration or deployment impacts. Include screenshots for UI changes under `templates/` or `static/`, and link related issues when available.

## Security & Configuration Tips

Do not commit real database passwords, KRAS credentials, or server-specific secrets. Review `application.yml`, `conf/`, and install instructions carefully before sharing deployment archives.
