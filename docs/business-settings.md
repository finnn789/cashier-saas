# Owner business settings API

`GET` and `PUT /api/v1/merchant/outlets/{outletId}/business-settings` are owner-only endpoints. The authenticated JWT principal supplies the tenant; the selected outlet must be accessible to that principal.

`PUT` replaces the full settings document:

```json
{
  "businessName": "Kedai Contoh",
  "logoUrl": "https://cdn.example.com/logo.png",
  "currency": "IDR",
  "address": "Jl. Contoh 1",
  "phoneNumber": "+6221123456",
  "taxRate": 11.00,
  "serviceChargeRate": 5.00,
  "outletName": "Outlet Utama",
  "outletAddress": "Jl. Contoh 1",
  "outletPhoneNumber": "+6221123456",
  "timeZone": "Asia/Jakarta",
  "receiptFooter": "Terima kasih, sampai jumpa lagi!"
}
```

Business and outlet names, currency, tax rate, service-charge rate, and time zone are required. Currency must be uppercase ISO-4217 with two fraction digits. Rates are `0.00` through `100.00` with at most two decimals. `logoUrl`, addresses, phone numbers and `receiptFooter` (max 200 characters, printed at the bottom of every receipt) are optional; absent, `null`, or blank optional values clear the stored value. A logo URL must be an absolute HTTPS URL with no user-info/credentials and a default port or an explicit port from `1` through `65535`; the service never fetches or uploads it.

The update locks the tenant before loading the owner-guarded outlet context. Tax and service-charge values are used only by future checkout calculations; order snapshots are not modified. Currency can change only until the tenant has any F&B order or legacy cashier sale, including unpaid or cancelled orders. Changed updates write one atomic `BUSINESS_SETTINGS_CHANGED` audit entry with structured before/after values; a no-op update writes no audit row. The selected outlet time zone is authoritative and is mirrored to the tenant default without changing other outlets.
