# TASK-031 review correction

Memory baseline5. Same original allowlist. After original worker completes, correct date boundary handling in MerchantAuditService: LocalDate.MAX with start=end passes range check but endDate.plusDays(1) throws DateTimeException -> generic500. Catch DateTimeException around start/end instant construction and return existing InvalidOperationException, consistent with existing report service. Preserve scope/count/stable ordering. Retain existing controller validation and service filter length checks.

Update reports/TASK-031.md with correction and static evidence plus recommended extreme-date case; update docs if needed. No tests/build/startup/database/network, other task files/code, canonical writes or nested agents.
