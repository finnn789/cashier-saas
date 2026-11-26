# Contract Data — Part B: POS Order, Payment, Receipt

> TEMPORARY working memory (2026-11-23). Source: FE contract TASK-CONTRACT-DATA v2.5.0. Delete after the DTO/controller audit is resolved.

Backend scope to check: `FnbStaffOrderController`, `FnbPaymentController`, `FnbReceiptController`, `FnbRefundController`, and their DTOs/enums.

Flow claim: POST order -> status OPEN + UNPAID -> POST payments -> COMPLETED + PAID -> GET receipt. Payment triggers recipe stock deduction (menu + option recipes) as SALE stock movements.

## 4.3 Types

```typescript
export type FnbServiceType = 'DINE_IN' | 'TAKEAWAY'
export type FnbOrderStatus = 'OPEN' | 'COMPLETED' | 'CANCELLED'
export type FnbPaymentStatus = 'UNPAID' | 'PAID' | 'REFUNDED'
export type FnbBackendPaymentMethod = 'CASH' | 'MANUAL_NON_CASH' | 'MOCK_GATEWAY'

// POST /cashier/outlets/{outletId}/fnb/orders
export interface FnbPosOrderRequest {
  serviceType: FnbServiceType
  customerLabel: string
  items: { productId: string; quantity: number; note?: string; optionIds: string[] }[]
}

export interface FnbStaffOrderResponse {
  orderId: string
  orderNumber: string         // e.g. "POS-788640"
  source: 'POS' | 'QR_ORDER' | string
  serviceType: FnbServiceType
  customerLabel: string
  status: FnbOrderStatus
  paymentStatus: FnbPaymentStatus
  createdAt: string
  subtotal: number; taxRate: number; taxAmount: number
  serviceChargeRate: number; serviceChargeAmount: number
  discountAmount: number; total: number
  items: {
    itemId: string; productName: string; unitPrice: number; quantity: number
    lineTotal: number; note?: string
    options?: { optionId: string; optionName: string; extraPrice: number }[]
  }[]
  paymentMethodName?: string
  cashierName?: string
  referenceId?: string
}

// POST /cashier/outlets/{outletId}/fnb/orders/{orderId}/payments
export interface FnbPaymentRequest {
  method: FnbBackendPaymentMethod
  amountTendered: number
  idempotencyKey: string      // 16-64 chars
}

export interface FnbPaymentResponse {
  orderId: string
  method: string
  amountPaid: number
  amountRequested: number
  changeAmount: number
  receiptNumber: string       // e.g. "RCP-20261123-001"
  paidAt: string
  stockWarnings?: string[]
}
```

Example order request:
```json
{ "serviceType": "DINE_IN", "customerLabel": "Meja 08 - Bpk Andi",
  "items": [ { "productId": "prd_latte_001", "quantity": 2, "note": "Less sugar, hot",
               "optionIds": ["opt_sugar_less", "opt_temp_hot"] } ] }
```

## Claims to verify
- Idempotency-Key TTL standardised at 24 hours (Redis or DB).
- Cashier order list uses 0-based pagination.
- Receipt endpoint path: GET .../receipt (exact path not given — report backend path).
