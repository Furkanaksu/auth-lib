# auth-lib

[![JitPack](https://jitpack.io/v/Furkanaksu/auth-lib.svg)](https://jitpack.io/#Furkanaksu/auth-lib)

Ktor + Exposed tabanlı, **yeniden kullanılabilir hesap ve token kütüphanesi**.
Kapsamı bilerek dar: kayıt, giriş, token yenileme ve token doğrulama. Başka hiçbir şey yok.

- Kullanıcının kendine dair bilgileri (oturum, cihaz, profil) → [user-me-lib](https://github.com/Furkanaksu/user-me-lib)
- Admin girişi ve admin panel uçları → projede kalır

Altyapı `furkan.api` ve diğer kütüphanelerle aynıdır: JDK 21, Kotlin 2.2.21, Ktor 3.3.2, Exposed 0.61.0.

## Kurulum

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

`build.gradle.kts`:

```kotlin
implementation("com.github.Furkanaksu:auth-lib:2.0.0")
```

## Kullanım

```kotlin
fun Application.module() {
    val db = Database.connect(/* projenin kendi DB'si */)
    install(ContentNegotiation) { json() }

    val auth = AuthConfig(
        database = db,
        jwtSecret = System.getenv("AUTH_JWT_SECRET"),  // en az 32 karakter
        tablePrefix = "prayapp_"                        // prayapp_accounts, prayapp_refresh_tokens
    )
    auth.migrate()

    routing {
        authRoutes(auth)

        // Kendi route'larını korumak:
        authenticate(AUTH_LIB) {
            get("/profil") { call.respond(call.currentAccount()!!) }
        }
    }
}
```

JWT provider'ı `authRoutes` kendisi kaydeder; ayrıca `install(Authentication)` gerekmez.
Kendi korumalı route'larını `authRoutes`'tan **önce** tanımlıyorsan en başta `installAuthLib(auth)` çağır.

## Uç noktalar

`basePath` varsayılanı `/auth`:

| Metot | Yol | Açıklama |
| --- | --- | --- |
| POST | `/auth/register` | Hesap aç → token çifti |
| POST | `/auth/login` | Giriş → token çifti |
| POST | `/auth/refresh` | Refresh token'la yeni çift (eskisi iptal) |

```json
POST /auth/register   { "email": "a@b.com", "password": "en-az-8-karakter", "displayName": "Ali" }
POST /auth/login      { "email": "a@b.com", "password": "..." }
POST /auth/refresh    { "refreshToken": "..." }
```

Cevap:

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "q9Z...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "account": { "id": 1, "email": "a@b.com", "displayName": "Ali", "createdAt": "..." }
}
```

İstemci akışı: token'ı `Authorization: Bearer <accessToken>` header'ında gönder; 401 alınca
`/auth/refresh` ile yenile; refresh de 401 dönerse kullanıcıyı giriş ekranına al.

Çıkış istemcide yapılır: uygulama token'ları siler. Sunucu tarafında çıkış ucu yoktur;
refresh token süresi (varsayılan 30 gün) dolana kadar geçerli kalır.

## Kod üzerinden kullanım

Token'dan gelen id ile hesabı okumak için:

```kotlin
val accounts = AuthAccounts(auth)
val account = call.currentAccount()?.let { accounts.find(it.accountId) }
```

`currentAccount()` gelen isteğin sahibini (`accountId`, `email`) verir; token yoksa null.

## Güvenlik

- Şifreler PBKDF2-HMAC-SHA256 (310.000 iterasyon, rastgele tuz) ile saklanır; karşılaştırma sabit zamanlı.
- Access token imzalı JWT (varsayılan 1 saat). Refresh token rastgele ve opak (varsayılan 30 gün); DB'de sadece SHA-256 özeti tutulur.
- Refresh her kullanımda döner. İptal edilmiş bir refresh token tekrar gelirse token çalınmış sayılır ve hesabın **tüm** refresh token'ları iptal edilir.
- Yanlış şifre ve kayıtlı olmayan email aynı hatayı ve aynı süreyi verir; email'in kayıtlı olup olmadığı anlaşılmaz.
- `AuthConfig.toString()` secret'ı yazdırmaz.

Kütüphanede giriş denemesi sınırı **yok**; kaba kuvvet koruması projenin rate limit'ine kalıyor.
Email doğrulama, şifre sıfırlama, şifre değiştirme, hesap silme ve sosyal giriş de yok.

## Ayarlar

| Alan | Varsayılan | Açıklama |
| --- | --- | --- |
| `database` | — | Projenin Exposed `Database`'i |
| `jwtSecret` | — | En az 32 karakter |
| `basePath` | `/auth` | |
| `tablePrefix` | `""` | Aynı DB'de birden fazla uygulama için |
| `authName` | `auth-lib` | `authenticate(...)` ile kullanılan provider adı |
| `accessTokenTtl` | 1 saat | |
| `refreshTokenTtl` | 30 gün | |
| `minPasswordLength` | 8 | |

## Tablolar

`migrate()` iki tablo oluşturur: `<prefix>accounts` ve `<prefix>refresh_tokens`.
Hesap silinirse refresh token'ları da silinir (`CASCADE`).

## Sürüm notu

`2.0.0` ile oturum/cihaz tarafı tamamen çıkarıldı (`POST /session`, `GET /me`, `AuthSessions`).
O işler artık [user-me-lib](https://github.com/Furkanaksu/user-me-lib)'de. `1.x` kullanıyorsan
oturum tablon aynı isimde ve aynı kolonlarla kalır; sadece hangi kütüphanenin yönettiği değişir,
veri taşımaya gerek yoktur.

## Yayınlama (JitPack)

```bash
git tag 2.0.0
git push origin 2.0.0
```

## Geliştirme

```bash
./gradlew build
```
