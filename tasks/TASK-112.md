# TASK-112: Make the two footer actions visible buttons

Status: DONE (reviewed). Memory version: 104. UI repo `E:\Java\cashier-saas-ui` (not git). Backend untouched.

## User instruction (2026-11-25)
"Buatkan kedua itu seperti terlihat, jangan buatkan plain text, itu tombolnya seperti tidak terlihat" - the "Ganti peran" and "Keluar, lanjutkan nanti" actions (TASK-111 footer) look like plain text and are easy to miss.

## Global rules
- Report: `reports/TASK-112-F1.md` (backend repo).
- Not test-first: styling only, no behaviour change. Existing vitest suite must stay green.
- Reuse: `Button` from `@/components/common/Button` (variants `primary|secondary|outline|ghost|danger|success`, sizes `sm|md|lg`, `isLoading` prop), lucide icons.

## Verified facts
- `SecondaryActionsFooter` (`src/pages/onboarding/OnboardingPage.tsx:79-110`) renders two native `<button>` with `footerLinkClass` (text-only, `text-primary`, underline on hover) inside the `AuthShell` footer (`mt-6 text-center text-sm`, outside the card). Used by intent, profile and cashier steps and by `src/pages/auth/RegisterSuccessPage.tsx`.
- Logout has a local `loggingOut` pending flag; change-role is optional (`onChangeRole?`).
- `footerLinkClass` is also used by the intent-step "← Kembali" link (keep that one a link).

## Design decisions (reversible)
- D1: Render both actions with `Button` `variant="outline"` `size="md"`, each with a leading icon (`aria-hidden`): change role = lucide `ArrowLeftRight`, logout = lucide `LogOut`. Logout uses `isLoading={loggingOut}` instead of only `disabled`.
- D2: Layout: two buttons side by side with equal width (`grid grid-cols-2 gap-3`) when both exist; a single logout button is full-width. Stack to one column under ~360px (`grid-cols-1 sm:grid-cols-2` or `min-[360px]:` equivalent), so long Indonesian labels never truncate.
- D3: Keep them in the `AuthShell` footer (outside the card) so the primary card action stays dominant; outline style gives a visible border/background on the page surface. Logout text stays neutral (not danger red).
- D4: Remove `footerLinkClass` usage from the footer only; keep it for the Back link.

## F1 - UI (`fe-worker`)
Reserved:
- `src/pages/onboarding/OnboardingPage.tsx` (`SecondaryActionsFooter` only, plus imports)
Do: implement D1-D4, smallest diff, no new component file, no `AuthShell` change, no new language keys.
Acceptance: same handlers/navigation as before; `tsc -b`, lint (no new warnings), vitest green - run by orchestrator.
