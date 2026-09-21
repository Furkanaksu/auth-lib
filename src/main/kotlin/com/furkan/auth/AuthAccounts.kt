package com.furkan.auth

/**
 * Hesap bilgisine HTTP olmadan, kod uzerinden erisim.
 *
 * Kullanicinin kendi bilgilerini donen uclar (bkz. user-me-lib) ya da projenin kendi
 * kodu, token'dan gelen id ile hesabi buradan okur:
 *
 * ```
 * val accounts = AuthAccounts(authConfig)
 * val account = call.currentAccount()?.let { accounts.find(it.accountId) }
 * ```
 */
class AuthAccounts(config: AuthConfig) {

    private val repository = AccountRepository(config.database, config.accounts)

    fun find(accountId: Int): AccountResponse? = repository.findById(accountId)

    fun findByEmail(email: String): AccountResponse? =
        repository.findByEmail(email.trim().lowercase())?.response
}
