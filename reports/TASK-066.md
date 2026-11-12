# TASK-066 report (W6, Postman option-group requests)

Collection: `postman/collections/F&B Catalog Controller/`

## Changed (URL moved to outlet-level option-groups per TASK-064 contract)
- 03 - Option Groups: Create Option Group (POST .../fnb/option-groups), Update Option Group (PUT .../option-groups/{{groupId}}), List Option Groups (GET /cashier/.../option-groups). Bodies and test scripts unchanged (no productId was in the body; Create still saves data.id to groupId).
- 04 - Options: Create/Update/List now under `.../fnb/option-groups/{{groupId}}/options[/{{optionId}}]` (product segment dropped).
- 07 - Recipes: Set Option Recipe (Add-on) and Get Option Recipe (Add-on) now `.../fnb/option-groups/{{groupId}}/options/{{optionId}}/recipe`. Body unchanged. Product recipe requests untouched.

## Created (03 - Option Groups)
- Archive Option Group (DELETE merchant .../option-groups/{{groupId}}, order 4000; test 200 + success)
- Set Product Option Groups (PUT merchant .../products/{{productId}}/option-groups, body `{"groupIds": ["{{groupId}}"]}`, order 5000; test 200, data is array)
- Get Product Option Groups (GET cashier .../products/{{productId}}/option-groups, order 6000; test 200, data is array)

Folder `.resources/definition.yaml` files need no edit (they hold only `$kind`/`order`; requests are not listed, same as folders 05/06/07).

## Validation
Command (from the collection dir): `uv run --project E:/Java/cashier-saas/Ai-services python -I -c "import yaml,glob; ... yaml.safe_load each of 03/*.yaml, 04/*.yaml, 07/*Option*.yaml; assert $kind==http-request, url, method"`
Output: 11 lines `OK <file> <METHOD> <order>` then `11 files parsed`. Grep confirmed no remaining `/products/{{productId}}/groups` URL in the touched files.

## Deviations
None. Note: the DELETE/Archive request has no 409 test (happy path only). No other paths touched; no git/Maven.
