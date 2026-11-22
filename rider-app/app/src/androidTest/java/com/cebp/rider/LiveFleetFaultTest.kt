package com.cebp.rider

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cebp.rider.data.RiderLocation
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveFleetFaultTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun riderCanResumeAndReturnAFaultedTripAfterActivityRecreation() {
        assumeTrue("Requires an isolated fleet backend", BuildConfig.BACKEND_URL.isNotBlank())
        val label = "Rider CI " + UUID.randomUUID().toString().take(8)
        val position = RiderLocation.current
        val vehicle = post(
            "vehicles",
            JSONObject().put("label", label).put("type", "E_BIKE").put("batteryPercent", 80)
                .put("latitude", position.latitude).put("longitude", position.longitude),
        )
        compose.activityRule.scenario.recreate()
        click(label)
        click("Start rental")
        waitFor("Active ride")

        post(
            "vehicles/${vehicle.getString("id")}/maintenance",
            JSONObject().put("reason", "FAULT").put("faultCode", "BRAKE_SENSOR"),
        )
        Espresso.pressBack()
        waitFor("Available vehicles")
        compose.activityRule.scenario.recreate()
        click("Resume ride")
        click("Return vehicle")
        waitFor("Ride summary")
        compose.onNodeWithText("Vehicle: $label").assertIsDisplayed()
        click("Done")
        waitFor("Available vehicles")
        compose.onNodeWithText("Resume ride").assertDoesNotExist()
    }

    private fun waitFor(text: String) {
        compose.waitUntil(timeoutMillis = 15000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun click(text: String) {
        waitFor(text)
        compose.onNodeWithText(text).performClick()
    }

    private fun post(path: String, body: JSONObject): JSONObject {
        val connection = URL(BuildConfig.BACKEND_URL.trimEnd('/') + "/" + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            assertEquals(201, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
