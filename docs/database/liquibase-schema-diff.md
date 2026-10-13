# Manual entity/schema diff

The optional `schema-diff` Maven profile compares Hibernate entity metadata (the
reference) with a PostgreSQL database (the target). It is a diagnostic only: a plain
`liquibase:diff` prints a console report and does not run an update.

Flyway remains responsible for migrations. Liquibase auto-configuration is disabled at
runtime. SQL migration files cannot be compared directly to entity metadata; the target
must be a PostgreSQL database already populated by Flyway. To compare against migration
source, provision a separate disposable PostgreSQL database and migrate it outside this
workflow. Do not migrate or repair Flyway history as part of this command; the known
authentic V11 conflict remains unresolved.

## Prerequisites

Use a deliberately selected, non-production target containing the Flyway schema. Compile
the current entity classes immediately before the diff—do not assume `target/classes` is
fresh. The schema-diff plugin reads its target connection from
`src/main/resources/liquibase-diff.properties`; it does not use Spring datasource
placeholders or automatically load an application's `.env` file.

Use the existing user-managed connection in that properties file. Do not replace it with
environment-only requirements or copy its credentials into documentation, reports, or a
new configuration file.

## Run manually

```powershell
.\mvnw.cmd -Pschema-diff -DskipTests compile liquibase:diff
```

This command compiles entities and then runs only the requested diff goal. It does not
start Spring, run Flyway migrations, or apply Liquibase changes. The inactive profile
explicitly includes the project artifact and excludes test output, so the main compiled
artifact/classpath supplies `target/classes` and application dependencies. The plugin has
its own tool classpath: it explicitly provides the Hibernate extension, Boot-managed
Hibernate/Spring dependencies, Jakarta Validation API and Hibernate Validator, plus the
Tomcat EL implementation required by Hibernate Validator's default interpolation, and the
PostgreSQL JDBC driver required by the target URL. This is separate from the application
runtime classpath. Its `changeLogFile` is only a target-path plugin setting for the manual
diff command; it does not configure changelog generation or an update goal.

The reference URL uses `HibernateDriver`, Hibernate's
`CamelCaseToUnderscoresNamingStrategy`, and Boot 4's
`org.springframework.boot.hibernate.SpringImplicitNamingStrategy`. The two schema settings
remain `public`; the target URL, username, and password remain in the existing local
properties configuration.

No build, runtime, or database execution was performed when this setup was added, so
classpath resolution and metadata compatibility remain to be verified when an authorized
operator runs the command against a disposable target.

## Reading the report

- `Missing` means entity metadata contains an object absent from the target database.
- `Unexpected` means the target database contains an object absent from entity metadata.
- `Changed` means both sides define metadata differently.

Database-only indexes, checks, and legacy tables can be intentional. Review every result
against the intended migration history; do not treat the diff as an instruction to update
or delete database objects.
