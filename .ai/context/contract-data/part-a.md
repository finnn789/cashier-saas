# Contract Data — Part A: Wrapper, Auth/Outlet, Plans, Sales Report

> TEMPORARY working memory (2026-11-23). Source: FE contract TASK-CONTRACT-DATA v2.5.0. Delete after the DTO/controller audit is resolved.

Backend scope to check: `AuthController`, `OnboardingController`, `MerchantOutletController`, `OutletController`, `MembershipPlanCatalogController`, `FnbSalesReportController`, `exception/GlobalExceptionHandler`, and their DTOs.

## 4.1 Standard response wrapper

```typescript
export interface FieldErrorDetail { field: string; message: string }

export interface ApiResponse<T> {
  success: boolean
  code: number
  data: T
  message?: string
  validationErrors?: FieldErrorDetail[]
  timestamp?: string
}
```

Example 400:
```json
{ "success": false, "code": 400, "data": null, "message": "Validasi input gagal",
  "validationErrors": [ { "field": "name", "message": "Nama produk wajib diisi" } ],
  "timestamp": "2026-11-23T10:15:30.123Z" }
```

Claim: pagination is Spring 0-based index on cashier order list and sales report.

## 4.2 Auth & outlet-scoped session

```typescript
export type UserRole = 'SUPERADMIN' | 'OWNERMERCHANT' | 'CASHIER'

export interface User {
  id: string; name: string; email: string
  role: UserRole | null
  storeName: string
  storeId: string | null     // Tenant UUID
  outletId?: string | null   // Outlet UUID
  avatarUrl?: string
}

export interface AuthResponse {
  accessToken: string
  tokenType: string          // "Bearer"
  expiresAt: string          // ISO-8601 UTC
  role?: UserRole | null
  tenantId?: string | null
  outletId?: string | null
  nextStep?: 'COMPLETE_ONBOARDING' | 'SELECT_MERCHANT_OUTLET' | 'DASHBOARD' | string
}

export interface MerchantOutletChoice {
  tenantId: string; businessName: string; outletId: string; outletName: string; role: UserRole
}

export interface SelectMerchantInput {
  tenantId: string; outletId: string; outletName?: string; token?: string
}
```

Claims: login/refresh with refresh cookie; select merchant outlet yields scoped JWT (tenant_id, outlet_id, role).

## Plans

`src/types/plan.ts`: public SaaS plan catalog (Free, Pro, Premium). No field list given — report the backend shape.

## 4.6 Sales report (GET /fnb/reports/sales, plus CSV streaming export)

```typescript
export interface FnbSalesSummaryResponse {
  subtotal: number; netSales: number; total: number; amountCollected: number
  transactionCount: number; averageTransaction: number
  discountAmount: number; taxAmount: number; serviceChargeAmount: number
  cancellationCount: number
}

export interface FnbSalesPaymentBreakdownResponse {
  method: string             // "CASH", "MANUAL_NON_CASH", "QRIS"
  transactionCount: number; netSales: number; amountCollected: number
}

export interface FnbSalesProductResponse {
  productId: string; productName: string; quantity: number; grossSales: number
}

export interface FnbSalesReportResponse {
  startDate?: string; endDate?: string; timeZone?: string
  summary: FnbSalesSummaryResponse
  transactions?: {
    orderId: string; orderNumber: string; orderStatus: 'OPEN' | 'COMPLETED' | 'CANCELLED'
    paymentMethod: string; total: number; paidAt?: string
  }[]
  topProducts?: FnbSalesProductResponse[]
  paymentBreakdown?: FnbSalesPaymentBreakdownResponse[]
  page: number; size: number; hasNext: boolean
}
```
