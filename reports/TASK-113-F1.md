# TASK-113 F1 report

Status: DONE (code written, nothing executed). Test status: NOT RUN (orchestrator runs GREEN).

## Changed files
New:
- E:\Java\cashier-saas-ui\src\api\media.api.ts
- E:\Java\cashier-saas-ui\src\components\common\ImageUploadField.tsx

Modified:
- E:\Java\cashier-saas-ui\src\pages\products\components\ProductFormPanel.tsx
- E:\Java\cashier-saas-ui\src\pages\settings\components\SettingsForm.tsx
- E:\Java\cashier-saas-ui\src\language\id.ts
- E:\Java\cashier-saas-ui\src\language\en.ts

## Packet items
- useUploadImage: done. FormData part `file`, no manual Content-Type, returns `data.url`, mock branch returns an object URL.
- ImageUploadField: done (preview, choose/replace, remove, uploading state, server error, sr-only file input, D11 compression: longest side 1024, webp 0.8, falls back to original if not WebP or not smaller, client-side > 2 MB rejected, type check).
- ProductFormPanel: done. NOTE the packet said the payload "already sends imageUrl"; it did not (form values had no imageUrl). Added local `imageUrl` state, sent in `data` (create + update; '' clears).
- SettingsForm: logoUrl text input replaced by the field; remove sets '' which `toRequest` turns into undefined (existing clear behaviour).
- Language: new keys `common.imageUpload.{choose,replace,remove,uploading,tooLarge,wrongType}` in both files; `logoUploadAlert` now "maks. 2 MB" / "max 2 MB". Reused `products.drawer.imageUrlLabel`/`imagePreviewDesc`, `profile.receiptLogoLabel`, `profile.logoUploadAlert` (hint instead of `uploadMonochromeLogo`, which is a button label).

## Deviations
- `useUploadImage()` takes no `outletId` and the component has no `outletId` prop: outlet comes from `user?.outletId || getLastOutletSelection()` inside the hook, per repo convention.
- Always uses `/cashier/outlets/{outletId}/media/images` (D10 allows owner and cashier there), same as product update does; no role switch.
- `imageUrlLabel` text still reads "URL Foto Produk" (existing key, per packet); consider renaming copy later.

## Callers outside paths that may break
None found; no tests reference ProductFormPanel/SettingsForm.

## Open questions
- Backend `settings.api` / mock logo validation (zod, if any) for non-HTTPS dev URLs not checked.
- Product `imageUrl` backend validation is "unchanged": a `http://localhost` media URL may be rejected for products in dev if the backend requires HTTPS; verify.

## Credentials/outlet
Outlet is required and checked in the hook (throws "Outlet aktif belum dipilih"). No tokens handled.
