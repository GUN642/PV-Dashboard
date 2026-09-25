package de.gun642.pvdashboard.senec.cloud

import java.net.URLDecoder

/** Liest das Anmeldeformular der SENEC-Anmeldeseite (Keycloak) aus. */
data class LoginForm(
    val action: String,
    val hasUsername: Boolean,
    val hasPassword: Boolean,
    val hasOtp: Boolean,
) {
    companion object {
        private val formRegex = Regex("""<form[^>]*action="([^"]+)"[^>]*>(.*?)</form>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val usernameRegex = Regex("""<input[^>]*(?:name|id)=["']?(?:username|user|email)["']?[^>]*>""", RegexOption.IGNORE_CASE)
        private val passwordRegex = Regex("""<input[^>]*(?:name|id)=["']?password["']?[^>]*>""", RegexOption.IGNORE_CASE)
        private val otpRegex = Regex("""<input[^>]*(?:name|id)=["']?otp["']?[^>]*>""", RegexOption.IGNORE_CASE)
        private val errorRegex = Regex(
            """<(?:span|div)[^>]*(?:id="input-error[^"]*"|class="[^"]*(?:kc-feedback-text|alert-error|pf-m-error)[^"]*")[^>]*>(.*?)</(?:span|div)>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun parse(html: String): LoginForm? {
            val match = formRegex.find(html) ?: return null
            val content = match.groupValues[2]
            return LoginForm(
                action = unescape(match.groupValues[1]),
                hasUsername = usernameRegex.containsMatchIn(content),
                hasPassword = passwordRegex.containsMatchIn(content),
                hasOtp = otpRegex.containsMatchIn(content),
            )
        }

        /** Fehlermeldung der Anmeldeseite, z. B. "Ungültiger Benutzername oder Passwort." */
        fun error(html: String): String? =
            errorRegex.find(html)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")
                ?.let { unescape(it).trim() }
                ?.takeIf { it.isNotEmpty() }

        /** Holt den Parameter `code` aus der Weiterleitung `senec-app-auth://…?code=…`. */
        fun codeFromRedirect(location: String): String? =
            location.substringAfter('?', "")
                .split('&')
                .map { it.split('=', limit = 2) }
                .firstOrNull { it.size == 2 && it[0] == "code" }
                ?.let { URLDecoder.decode(it[1], "UTF-8") }

        private fun unescape(s: String) = s
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}
