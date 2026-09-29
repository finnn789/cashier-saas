# Component tests

Folder ini untuk test batas komponen/subsistem yang menggunakan beberapa class
produksi nyata dengan dependency eksternal dimock atau diganti test double.
Contohnya Spring MVC slice yang memeriksa controller, validasi, serialisasi,
exception handler, dan security filter tanpa memuat persistence aplikasi penuh.

Gunakan package `org.lite.project.cashiersaas.component.<area>` dan
`@Tag("component")` pada setiap class test. Nama class tetap berakhiran `Test`
atau `Tests` agar ditemukan Maven Surefire.

Belum ada component test pada hasil audit 26 September 2026. Unit test untuk
class di package produksi `component` berada di `unit/component`; nama package
produksi tidak menentukan jenis test.

Lihat [panduan dan audit test](../../../../../../../../docs/testing/README.md).
