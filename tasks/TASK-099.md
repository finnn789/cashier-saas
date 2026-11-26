# TASK-099: Attach option groups to a product in the product form (frontend)

Status: DONE, REVIEWED, READY FOR TESTING (2026-11-25, memory v98).

## User instruction (2026-11-25)
"Jika ada task lagi silahkan buatkan dan delegasikan." Picked from the closed TASK-091 debt in `.ai/TASKS.md`: "Not offered: attach groups to product". Option groups are outlet-level and reusable (V5); the Option Groups tab can create them, but the owner cannot attach them to a product, so the POS never shows the choices.

## Verified facts (orchestrator, source read)
- Backend: `PUT /api/v1/merchant/outlets/{outletId}/fnb/products/{productId}/option-groups` (OWNER), body `{ groupIds: UUID[] }` (`@NotNull`, max 20), **full replace**: list order = display order; duplicates -> 400 "Grup pilihan tidak boleh duplikat."; unknown/other-outlet group -> 404; returns the attached groups. `GET /api/v1/cashier/outlets/{outletId}/fnb/products/{productId}/option-groups` returns the attached groups (`FnbCatalogService.replaceProductGroups` :149-164; `FnbCatalogController.java:102,107`).
- UI hooks already exist in `src/api/inventory.api.ts`: `useOutletOptionGroupsQuery()` (:367, all groups of the outlet with their options), `useOptionGroupsQuery(productId)` (:401, groups attached to a product, with options; used by the POS), `useAttachProductOptionGroupsMutation()` (:443, PUT `{ groupIds }`, invalidates `inventoryKeys.optionGroups(productId)`; its mock branch returns `[]` and does not persist).
- Mock adapter `src/api/mock/inventory.mock.ts` has `getOptionGroups(productId)` (:611) and `getOutletOptionGroups()` (:617).
- `src/pages/products/components/ProductFormPanel.tsx` has no option-group section today. `/products` is OWNER only (TASK-093).
- Reusable: `useLocale()`, `cn` from `@/utils/cn`, lucide icons, `useToast`, `getErrorMessage`, existing form controls in `ProductFormPanel`.

## Design decisions
- D1 In `ProductFormPanel`, for an EXISTING product (edit mode), add a "Grup pilihan" section: checkbox list of the outlet's groups (name, required/min-max if the type has it, option count), preselected from `useOptionGroupsQuery(productId)`; selected groups keep a visible order with simple up/down buttons (no drag and drop).
- D2 Save the attachment with `useAttachProductOptionGroupsMutation` as its own action inside the section ("Simpan grup pilihan"), not coupled to the product save, so a failure in one does not hide the other. Pending state, success toast, server message on error. For a NEW product show a hint "simpan produk dulu" instead of the list.
- D3 Make the mock attach persist (store the group ids per product in the mock adapter so `getOptionGroups(productId)` returns them), so mock mode shows the result.
- D4 Empty state when the outlet has no groups (point to the Option Groups tab). All copy in BOTH `id.ts` and `en.ts`; no literals.
- Out of scope: editing groups/options here, option price edit, product count per category, POS changes.

## Wave 1

### F1 (`fe-worker`)
Reserved (UI repo): `src/pages/products/components/ProductFormPanel.tsx`, a new `src/pages/products/components/ProductOptionGroupsSection.tsx` (optional, if the panel gets too long), `src/api/inventory.api.ts` (only the mock branch of `useAttachProductOptionGroupsMutation`), `src/api/mock/inventory.mock.ts` (attach persistence only), `src/language/id.ts`, `en.ts` (products block only).
Report: `reports/TASK-099-F1.md`.
Acceptance:
- Owner can attach, detach and reorder groups for an existing product; after refresh the same groups show, and the POS option picker for that product shows them (same query key).
- Server errors (400 duplicate, 404) show the server message.
- No literal copy; `tsc -b` and `oxlint` clean on touched files (the worker does not run them; the orchestrator does). Do not run tests.
