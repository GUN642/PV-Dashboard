package de.gun642.pvdashboard.senec.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SenecCloudParsingTest {

    private val loginPage = """
        <html><body>
        <form id="kc-form-login" action="https://sso.senec.com/realms/senec/login-actions/authenticate?session_code=abc&amp;execution=123&amp;client_id=endcustomer-app-frontend" method="post">
          <input tabindex="1" id="username" name="username" type="text" autofocus autocomplete="off" />
          <input tabindex="2" id="password" name="password" type="password" autocomplete="off" />
          <input type="submit" value="Anmelden" />
        </form>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesLoginFormAndUnescapesAction() {
        val form = LoginForm.parse(loginPage)!!
        assertEquals(
            "https://sso.senec.com/realms/senec/login-actions/authenticate?session_code=abc&execution=123&client_id=endcustomer-app-frontend",
            form.action,
        )
        assertTrue(form.hasUsername)
        assertTrue(form.hasPassword)
        assertFalse(form.hasOtp)
    }

    @Test
    fun detectsUsernameOnlyStepAndOtp() {
        val userOnly = LoginForm.parse("""<form action="https://x/step"><input name="username"></form>""")!!
        assertTrue(userOnly.hasUsername)
        assertFalse(userOnly.hasPassword)
        val otp = LoginForm.parse("""<form action="https://x/otp"><input id="otp" name="otp"></form>""")!!
        assertTrue(otp.hasOtp)
        assertNull(LoginForm.parse("<html>kein Formular</html>"))
    }

    @Test
    fun readsErrorMessage() {
        val html = """<span id="input-error" class="kc-feedback-text">Ungültiger Benutzername oder Passwort.</span>"""
        assertEquals("Ungültiger Benutzername oder Passwort.", LoginForm.error(html))
    }

    @Test
    fun extractsCodeFromRedirect() {
        assertEquals(
            "a.b-c",
            LoginForm.codeFromRedirect("senec-app-auth://keycloak.prod?state=x&session_state=y&iss=https%3A%2F%2Fsso&code=a.b-c"),
        )
        assertNull(LoginForm.codeFromRedirect("senec-app-auth://keycloak.prod?error=access_denied"))
    }

    @Test
    fun parsesMeasurements() {
        val body = """
            {"timeSeries":[
              {"date":"2025-04-30T22:00:00Z","measurements":{"durationInSeconds":2678400,"values":[453.7,275.7,41.7,218.0,54.2,49.5,87.2,84.88,0]}},
              {"date":"2025-05-31T22:00:00Z","measurements":{"durationInSeconds":2592000,"values":[2043.3,840.2,35.7,1234.9,146.4,138.5,87.8,95.75,12.5]}}
            ],
            "measurements":["POWER_GENERATION","POWER_CONSUMPTION","GRID_IMPORT","GRID_EXPORT","BATTERY_IMPORT","BATTERY_EXPORT","BATTERY_LEVEL_IN_PERCENT","AUTARKY_IN_PERCENT","WALLBOX_CONSUMPTION"]}
        """.trimIndent()
        val points = SenecCloud.parseMeasurements(body)
        assertEquals(2, points.size)
        assertEquals(Instant.parse("2025-04-30T22:00:00Z"), points[0].start)
        assertEquals(453.7, points[0].values.getValue("POWER_GENERATION"), 1e-9)
        assertEquals(12.5, points[1].values.getValue("WALLBOX_CONSUMPTION"), 1e-9)
    }
}
