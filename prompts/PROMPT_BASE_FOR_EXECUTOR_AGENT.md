# Prompt dasar untuk agent pelaksana (frontend dan lainnya)

Tempel bagian "PROMPT" di awal pesan, isi bagian {…}, lalu tambahkan tugas spesifik di bawahnya.

## PROMPT

```

## Cara memakai
- Isi `{REPO_PATH}`, `{DAFTAR FILE/FOLDER}`, `{REPO_LAIN}` dan `{TUGAS}`. Makin spesifik kriteria selesainya, makin kecil kemungkinan agent melebar.
- Untuk agent frontend KedaiMbo, tambahkan di tugas: kontrak backend di `E:\Java\cashier-saas\prompts\PROMPT_FOR_FE_AGENT_FNB_API_INTEGRATION.md` (route, role, kontrak 429 final).
- Untuk agent backend (worker sonnet), orkestrator tetap memakai packet `tasks/TASK-XXX.md` dengan reserved writes. Prompt ini melengkapi, tidak menggantikannya.
