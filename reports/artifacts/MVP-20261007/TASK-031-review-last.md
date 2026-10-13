READY FOR TESTING.

Corrected the `LocalDate.MAX` audit-date overflow in [MerchantAuditService.java](E:/Java/cashier-saas/src/main/java/org/lite/project/cashiersaas/service/MerchantAuditService.java), returning the existing validation exception instead of a generic 500.

Updated evidence and revised signature in [reports/TASK-031.md](E:/Java/cashier-saas/reports/TASK-031.md). Static review only; no tests, build, startup, database, or network operations ran.