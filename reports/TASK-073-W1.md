# TASK-073-W1 report

Status: DONE (nothing executed: no Maven, no git writes, no Postman run). Only the reserved paths were written.

Evidence source: `FnbCatalogController` lines 81-126 (routes), DTOs `FnbOptionGroupRequest`, `FnbOptionRequest`, `FnbProductOptionGroupsRequest`.

## Folder 08 (`postman/collections/KedaiMbo API/08 - F&B Option Groups/`)
| File | Old -> new URL | Body |
|---|---|---|
| Create Option Group (edit, 1000) | `POST .../merchant/outlets/{{outletId}}/fnb/products/{{productId}}/groups` -> `.../fnb/option-groups` | unchanged; matches `FnbOptionGroupRequest` (name @NotBlank @Size(120); required; minimumSelections @Min0 @Max20; maximumSelections @Min1 @Max20; displayOrder) |
| List Option Groups (edit, 2000) | `GET .../cashier/.../products/{{productId}}/groups` -> `.../cashier/outlets/{{outletId}}/fnb/option-groups` | none |
| Update Option Group (edit, 3000) | `PUT .../products/{{productId}}/groups/{{groupId}}` -> `.../merchant/.../fnb/option-groups/{{groupId}}` | unchanged, same DTO |
| Delete Option Group (NEW, 4000) | `DELETE {{baseUrl}}/api/v1/merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}` | none. Controller `archiveGroup` returns `ApiResponse<FnbOptionGroupResponse>`, message "Grup pilihan diarsipkan." (200, no `@ResponseStatus`) |
| Attach Option Groups To Product (NEW, 5000) | `PUT .../merchant/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups` | `{"groupIds": ["{{groupId}}"]}`; `FnbProductOptionGroupsRequest.groupIds` is `@NotNull @Size(max=20) List<UUID>` |
| List Product Option Groups (NEW, 6000) | `GET .../cashier/outlets/{{outletId}}/fnb/products/{{productId}}/option-groups` | none |

## Folder 09 (`.../09 - F&B Options/`)
| File | Old -> new URL | Body |
|---|---|---|
| Create Option (1000) | `.../products/{{productId}}/groups/{{groupId}}/options` -> `.../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options` | unchanged; matches `FnbOptionRequest` (name @NotBlank @Size(120); priceAdjustment @NotNull @DecimalMin(0.00) @Digits(17,2); available; displayOrder; priceChangeReason @Size(255), optional) |
| List Options (2000) | -> `.../cashier/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options` | none |
| Update Option (3000) | -> `.../merchant/outlets/{{outletId}}/fnb/option-groups/{{groupId}}/options/{{optionId}}` | unchanged (includes `priceChangeReason`, valid per DTO) |

No `{{productId}}` remains in folder 09 URLs (grep count 0). Old bodies had no obsolete fields, so none were removed.

## Notes / not verified
- Variables `productId`, `groupId`, `optionId` already exist in the collection `.resources/definition.yaml` (read only, not edited), and its test scripts set them from create responses.
- Delete Option Group / Attach bodies and statuses verified from controller source only; the collection was not run against a server.
- Whether the service requires `priceChangeReason` on price changes in Update Option was not checked (DTO marks it optional); the example already supplies one.
- Files were rewritten with LF endings; git reports "LF will be replaced by CRLF" warnings only (autocrlf), diffs are one line per edited file.
