# auth-lib

[![JitPack](https://jitpack.io/v/Furkanaksu/auth-lib.svg)](https://jitpack.io/#Furkanaksu/auth-lib)

Ktor + Exposed tabanlı, **yeniden kullanılabilir hesap ve token kütüphanesi**.
Kapsamı bilerek dar: kayıt, giriş (email/şifre ve Google / Apple / Facebook), token yenileme
ve token doğrulama. Başka hiçbir şey yok.

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
implementation("com.github.Furkanaksu:auth-lib:3.1.0")
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
| POST | `/auth/social/{provider}` | `google` / `apple` / `facebook` ile giriş |
| POST | `/auth/device` | Kullanıcı adı/şifre olmadan, cihaz kimliğiyle giriş |

```json
POST /auth/register   { "email": "a@b.com", "password": "en-az-8-karakter", "displayName": "Ali" }
POST /auth/login      { "email": "a@b.com", "password": "..." }
POST /auth/refresh    { "refreshToken": "..." }
POST /auth/device     { "deviceId": "a1b2c3", "profile": { "appVersion": "2.3.0", "language": "tr" } }
```

### Cihaz girişi

Uygulama, kullanıcı hiç kayıt olmadan da bir hesaba sahip olsun istiyorsan: ilk açılışta
`POST /auth/device` çağrılır, e-postasız ve şifresiz bir hesap açılır, cihaz kimliği
`account_identities` tablosuna `DEVICE` sağlayıcısıyla yazılır. Aynı `deviceId` her zaman
aynı hesaba düşer. Sonraki açılışlarda bu uç **çağrılmaz**: elde token varsa doğrudan
kullanılır, süresi dolmuşsa `/auth/refresh` ile yenilenir.

İstersen `deviceSecret` gönder: ilk kayıtta saklanır (hash'lenerek) ve sonraki girişlerde
zorunlu olur, yanlışsa 401. Göndermezsen cihaz kimliği tek başına yeterlidir.

`profile` serbest bir JSON nesnesidir; kütüphane içeriğine karışmaz, `onLogin` geri
çağrısıyla olduğu gibi projeye iletir:

```kotlin
AuthConfig(
    database = db,
    jwtSecret = secret,
    onLogin = { olay ->
        if (olay.method == LoginMethod.DEVICE && olay.deviceId != null) {
            // orn. user-me-lib'e oturum yaz — bagimlilik kutuphanede degil, projede
        }
    }
)
```

`LoginEvent` her giriş yolunda tetiklenir: `method` alanı `DEVICE`, `PASSWORD`, `SOCIAL`
ya da `REGISTER` olur.

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

## Sosyal giriş

Uygulama sağlayıcının SDK'sıyla token'ı alır, bize gönderir; biz doğrulayıp kendi token'ımızı döneriz.

```kotlin
val auth = AuthConfig(
    database = db,
    jwtSecret = System.getenv("AUTH_JWT_SECRET"),
    social = SocialConfig(
        googleClientIds = setOf(androidClientId, iosClientId, webClientId),
        appleAudiences = setOf("com.furkan.prayapp"),
        facebookAppId = System.getenv("FB_APP_ID"),
        facebookAppSecret = System.getenv("FB_APP_SECRET")
    )
)
```

Tanımlanmayan sağlayıcı kapalıdır; istek gelirse `501` döner.

```json
POST /auth/social/google   { "token": "<ID token>" }
POST /auth/social/apple    { "token": "<ID token>", "displayName": "Ali" }
POST /auth/social/facebook { "token": "<access token>" }
```

Cevap `login` ile aynıdır: access + refresh token ve hesap.

**Doğrulama:** Google ve Apple ID token'ının imzası sağlayıcının JWKS'i ile kontrol edilir, `iss`
ve `aud` doğrulanır (`aud` senin verdiğin client id listesinde olmalı). Facebook access token'ı
Graph `debug_token` ucuna sorulur ve uygulamanın kendi token'ı olduğu doğrulanır.

**Hesap eşleştirme sırası:**

1. `(sağlayıcı, sağlayıcı kullanıcı id)` daha önce bağlandıysa o hesap kullanılır — sağlayıcıdaki
   email değişse bile aynı hesaba düşer.
2. Değilse ve sağlayıcı email'i **doğruladıysa**, aynı email'li hesap varsa ona bağlanır
   (`linkByVerifiedEmail`, varsayılan açık).
3. Hiçbiri değilse yeni hesap açılır (şifresiz).

Doğrulanmamış email ile asla birleştirme yapılmaz: o email'e sahipmiş gibi davranan biri mevcut
hesabı ele geçirebilirdi. Bu durumda yeni hesap açılır ve email yalnızca kimlik satırında tutulur.

**Sağlayıcıya özel notlar:**

- **Apple** kullanıcının adını sadece ilk yetkilendirmede gönderir ve token'da hiç göndermez;
  istemci o an yakalayıp `displayName` alanında iletmeli. "Email'imi gizle" seçilirse email
  `@privaterelay.appleid.com` olur ya da hiç gelmez.
- **Facebook** email için izin ister; vermezse email null gelir.
- **Google** için Android, iOS ve web ayrı client id kullanır; hepsi `googleClientIds` içinde olmalı.

Sosyal girişle açılmış hesabın şifresi yoktur; `POST /auth/login` denemesi `401` döner.

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
Email doğrulama, şifre sıfırlama, şifre değiştirme ve hesap silme de yok.

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
| `social` | kapalı | `SocialConfig`: client id'ler, `linkByVerifiedEmail`, test için `verifier` |

## Tablolar

`migrate()` üç tablo oluşturur: `<prefix>accounts`, `<prefix>account_identities` ve
`<prefix>refresh_tokens`. Hesap silinirse kimlikleri ve refresh token'ları da silinir (`CASCADE`).

`accounts.email` ve `accounts.password_hash` **null olabilir**: sosyal girişte sağlayıcı email
vermeyebilir, sosyal hesabın şifresi yoktur. 2.x'ten gelen bir veritabanında bu kolonlar NOT NULL
tanımlıdır; Exposed kısıtı kendiliğinden gevşetemezse tek seferlik:

```sql
ALTER TABLE <prefix>accounts ALTER COLUMN email DROP NOT NULL;
ALTER TABLE <prefix>accounts ALTER COLUMN password_hash DROP NOT NULL;
```

## Sürüm notu

`3.1.0` cihaz girişini (`POST /auth/device`) ve `onLogin` geri çağrısını ekledi. Kırıcı
değişiklik yok; `account_identities` tablosuna nullable bir `secret_hash` kolonu eklenir,
şema kendiliğinden güncellenir.

`3.0.0` sosyal girişi ekledi. Kırıcı değişiklik: `AccountResponse.email` ve `AccountPrincipal.email`
artık null olabilir (sağlayıcı email vermeyebilir). Şema için yukarıdaki nullable notuna bak.

`2.0.0` ile oturum/cihaz tarafı tamamen çıkarıldı (`POST /session`, `GET /me`, `AuthSessions`).
O işler artık [user-me-lib](https://github.com/Furkanaksu/user-me-lib)'de. `1.x` kullanıyorsan
oturum tablon aynı isimde ve aynı kolonlarla kalır; sadece hangi kütüphanenin yönettiği değişir,
veri taşımaya gerek yoktur.

## Yayınlama (JitPack)

```bash
git tag 3.1.0
git push origin 3.1.0
```

## Geliştirme

```bash
./gradlew build
```
