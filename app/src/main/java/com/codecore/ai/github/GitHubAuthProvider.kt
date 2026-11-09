package com.codecore.ai.github

interface GitHubAuthProvider {
    fun getToken(): String
    fun isAuthenticated(): Boolean
}

class TokenAuthProvider(private val tokenSupplier: () -> String) : GitHubAuthProvider {
    override fun getToken(): String = tokenSupplier()
    override fun isAuthenticated(): Boolean = getToken().isNotBlank()
}
