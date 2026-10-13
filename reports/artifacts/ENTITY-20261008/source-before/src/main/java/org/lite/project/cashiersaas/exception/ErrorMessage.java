package org.lite.project.cashiersaas.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorMessage {
    INVALID_CREDENTIALS("Email atau kata sandi tidak valid."),
    AUTHENTICATION_REQUIRED("Autentikasi diperlukan. Silakan masuk terlebih dahulu."),
    AUTHENTICATION_INVALID("Autentikasi tidak lagi valid. Silakan masuk kembali."),
    AUTHORIZATION_HEADER_INVALID("Header Authorization tidak valid. Gunakan format Bearer diikuti access token."),
    SESSION_INVALID("Sesi tidak berlaku atau telah berakhir. Silakan masuk kembali."),
    ACCOUNT_FORBIDDEN("Akun tidak diizinkan melakukan operasi ini."),
    ACCESS_FORBIDDEN("Anda tidak memiliki izin untuk melakukan operasi ini."),
    PROFILE_INCOMPLETE("Selesaikan profil akun sebelum memilih merchant atau outlet."),
    ONBOARDING_STAGE_INVALID("Tahap akun tidak sesuai untuk memulai pendaftaran Freetier."),
    OWNER_INTENT_REQUIRED("Pilih jalur pemilik usaha sebelum memulai pendaftaran Freetier."),
    SUBSCRIPTION_INACTIVE("Langganan usaha tidak aktif. Hubungi pemilik usaha."),
    MERCHANT_INACTIVE("Usaha tidak aktif. Hubungi pemilik usaha."),
    RESOURCE_UNAVAILABLE("Data tidak ditemukan atau tidak dapat diakses."),
    CSRF_INVALID("Token CSRF tidak valid atau tidak tersedia. Ambil token CSRF lalu ulangi permintaan.");

    private final String message;
}
