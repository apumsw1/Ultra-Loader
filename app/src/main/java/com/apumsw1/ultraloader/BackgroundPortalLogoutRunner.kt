package com.apumsw1.ultraloader

import android.net.Network
import android.webkit.CookieManager
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class BackgroundLogoutResult(
    val success: Boolean,
    val diagnostic: String,
    val finalUrl: String = "",
    val finalHtml: String = ""
)

object BackgroundPortalLogoutRunner {

    private val LOGOUT_HREF_REGEX = Regex(
        """<a\b[^>]*href\s*=\s*['"]([^'"]*(?:logout|signout|sign-out|log-out|logoff|action=logout)[^'"]*)['"][^>]*>""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val LOGOUT_ANCHOR_BY_TEXT_REGEX = Regex(
        """<a\b[^>]*href\s*=\s*['"]([^'"]+)['"][^>]*>\s*(?:<[^>]+>\s*)*(?:sign\s*out|signout|log\s*out|logout|log\s*off)\b""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val LOGOUT_FORM_REGEX = Regex(
        """<form\b([^>]*)>(.*?)</form>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private val HTML_REFRESH_REGEX = Regex(
        """(?:http-equiv\s*=\s*['"]refresh['"][^>]*content\s*=\s*['"][^'"]*url\s*=\s*|location(?:\.href)?\s*=\s*['"]|location\.replace\(\s*['"])([^'"\s>]+)['"]?""",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun executeLogoutOverWifi(
        wifiNetwork: Network,
        control: DetectedLogoutControl,
        userAgent: String
    ): BackgroundLogoutResult {
        return try {
            val referer = control.pageUrl.takeIf { PortalNetworkHelper.isValidHttpUrl(it) } ?: ""

            // 1. Direct URL (e.g. <a href="..."> or onclick="location.href='...'")
            if (PortalNetworkHelper.isValidHttpUrl(control.resolvedUrl)) {
                val resp = requestFollowingRedirects(
                    wifiNetwork = wifiNetwork,
                    initialUrl = control.resolvedUrl,
                    method = "GET",
                    postBody = null,
                    referer = referer,
                    userAgent = userAgent
                )
                val verified = verifyLogoutHtml(resp.first, resp.second, control.pageUrl)
                if (verified.success) return verified
            }

            // 2. Direct <form> submission (POST or GET with captured fields)
            if (PortalNetworkHelper.isValidHttpUrl(control.formAction)) {
                val method = if (control.formMethod.equals("POST", ignoreCase = true)) "POST" else "GET"
                val encodedParams = buildFormEncodedParams(control.formParamsJson)
                val targetUrl = if (method == "GET" && encodedParams.isNotEmpty()) {
                    val sep = if (control.formAction.contains("?")) "&" else "?"
                    "${control.formAction}$sep$encodedParams"
                } else {
                    control.formAction
                }
                val resp = requestFollowingRedirects(
                    wifiNetwork = wifiNetwork,
                    initialUrl = targetUrl,
                    method = method,
                    postBody = if (method == "POST") encodedParams else null,
                    referer = referer,
                    userAgent = userAgent
                )
                val verified = verifyLogoutHtml(resp.first, resp.second, control.pageUrl)
                if (verified.success) return verified
            }

            // 3. Re-fetch current portal page over Wi-Fi and inspect HTML for logout links/forms
            if (PortalNetworkHelper.isValidHttpUrl(control.pageUrl)) {
                val pageResp = requestFollowingRedirects(
                    wifiNetwork = wifiNetwork,
                    initialUrl = control.pageUrl,
                    method = "GET",
                    postBody = null,
                    referer = control.pageUrl,
                    userAgent = userAgent
                )

                // Maybe session already expired/logged out
                val alreadyCheck = verifyLogoutHtml(pageResp.first, pageResp.second, control.pageUrl)
                if (alreadyCheck.success) return alreadyCheck

                val extractedLink = extractLogoutLinkFromHtml(pageResp.second, pageResp.first)
                if (!extractedLink.isNullOrBlank()) {
                    val resp = requestFollowingRedirects(
                        wifiNetwork = wifiNetwork,
                        initialUrl = extractedLink,
                        method = "GET",
                        postBody = null,
                        referer = pageResp.first,
                        userAgent = userAgent
                    )
                    val verified = verifyLogoutHtml(resp.first, resp.second, pageResp.first)
                    if (verified.success) return verified
                }

                val extractedForm = extractLogoutFormFromHtml(pageResp.second, pageResp.first)
                if (extractedForm != null) {
                    val (actionUrl, method, body) = extractedForm
                    val targetUrl = if (method == "GET" && body.isNotEmpty()) {
                        val sep = if (actionUrl.contains("?")) "&" else "?"
                        "$actionUrl$sep$body"
                    } else {
                        actionUrl
                    }
                    val resp = requestFollowingRedirects(
                        wifiNetwork = wifiNetwork,
                        initialUrl = targetUrl,
                        method = method,
                        postBody = if (method == "POST") body else null,
                        referer = pageResp.first,
                        userAgent = userAgent
                    )
                    val verified = verifyLogoutHtml(resp.first, resp.second, pageResp.first)
                    if (verified.success) return verified
                }

                return BackgroundLogoutResult(
                    success = false,
                    diagnostic = "PORTAL_SESSION_STILL_ACTIVE_OR_JS_ONLY",
                    finalUrl = pageResp.first,
                    finalHtml = pageResp.second
                )
            }

            BackgroundLogoutResult(
                success = false,
                diagnostic = "INVALID_PORTAL_URL_FOR_HTTP_LOGOUT"
            )
        } catch (e: Exception) {
            BackgroundLogoutResult(
                success = false,
                diagnostic = "HTTP_LOGOUT_ERROR: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
            )
        }
    }

    fun followSecondLoginLinkIfPresent(
        wifiNetwork: Network,
        html: String,
        baseUrl: String,
        userAgent: String
    ) {
        if (html.isBlank() || !PortalNetworkHelper.isValidHttpUrl(baseUrl)) return
        try {
            val loginHrefRegex = Regex(
                """<a\b[^>]*href\s*=\s*['"]([^'"]+)['"][^>]*>\s*(?:<[^>]+>\s*)*(?:log\s*in|sign\s*in)\b""",
                setOf(RegexOption.IGNORE_CASE)
            )
            val rawHref = loginHrefRegex.find(html)?.groupValues?.getOrNull(1) ?: return
            val resolved = URL(URL(baseUrl), rawHref).toString()
            if (PortalNetworkHelper.isValidHttpUrl(resolved)) {
                requestFollowingRedirects(
                    wifiNetwork = wifiNetwork,
                    initialUrl = resolved,
                    method = "GET",
                    postBody = null,
                    referer = baseUrl,
                    userAgent = userAgent
                )
            }
        } catch (_: Exception) {
        }
    }

    private fun buildFormEncodedParams(formParamsJson: String): String {
        if (formParamsJson.isBlank()) return ""
        return try {
            val obj = JSONObject(formParamsJson)
            val keys = obj.keys()
            val parts = mutableListOf<String>()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                parts.add(
                    "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
                )
            }
            parts.joinToString("&")
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractLogoutLinkFromHtml(html: String, baseUrl: String): String? {
        val raw = LOGOUT_ANCHOR_BY_TEXT_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: LOGOUT_HREF_REGEX.find(html)?.groupValues?.getOrNull(1)
            ?: return null
        return try {
            val resolved = URL(URL(baseUrl), raw.trim()).toString()
            if (PortalNetworkHelper.isValidHttpUrl(resolved)) resolved else null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractLogoutFormFromHtml(
        html: String,
        baseUrl: String
    ): Triple<String, String, String>? {
        val formMatches = LOGOUT_FORM_REGEX.findAll(html)
        for (m in formMatches) {
            val attrs = m.groupValues[1]
            val body = m.groupValues[2]
            val isLogoutForm = Regex(
                """logout|signout|sign\s*out|log\s*out|logoff""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn("$attrs $body")
            if (!isLogoutForm) continue

            val rawAction = Regex("""action\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.getOrNull(1) ?: ""
            val actionUrl = try {
                URL(URL(baseUrl), rawAction.ifBlank { baseUrl }).toString()
            } catch (_: Exception) {
                baseUrl
            }
            if (!PortalNetworkHelper.isValidHttpUrl(actionUrl)) continue

            val method = Regex("""method\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(attrs)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT) ?: "GET"

            val inputRegex = Regex("""<input\b([^>]+)>""", RegexOption.IGNORE_CASE)
            val params = mutableListOf<String>()
            for (inp in inputRegex.findAll(body)) {
                val iAttrs = inp.groupValues[1]
                val name = Regex("""name\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(iAttrs)?.groupValues?.getOrNull(1) ?: continue
                val value = Regex("""value\s*=\s*['"]([^'"]*)['"]""", RegexOption.IGNORE_CASE)
                    .find(iAttrs)?.groupValues?.getOrNull(1) ?: ""
                params.add("${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}")
            }
            return Triple(actionUrl, if (method == "POST") "POST" else "GET", params.joinToString("&"))
        }
        return null
    }

    private fun requestFollowingRedirects(
        wifiNetwork: Network,
        initialUrl: String,
        method: String,
        postBody: String?,
        referer: String,
        userAgent: String
    ): Pair<String, String> {
        val cookieManager = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            null
        }

        var currentUrl = initialUrl
        var currentMethod = method
        var currentBody = postBody
        var currentReferer = referer
        var lastBody = ""

        for (hop in 0 until 6) {
            var conn: HttpURLConnection? = null
            try {
                conn = wifiNetwork.openConnection(URL(currentUrl)) as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = currentMethod
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                if (currentReferer.isNotBlank()) {
                    conn.setRequestProperty("Referer", currentReferer)
                }

                val cookieHeader = try {
                    cookieManager?.getCookie(currentUrl)
                } catch (_: Exception) {
                    null
                }
                if (!cookieHeader.isNullOrBlank()) {
                    conn.setRequestProperty("Cookie", cookieHeader)
                }

                if (currentMethod == "POST" && currentBody != null) {
                    conn.doOutput = true
                    conn.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                    OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                        writer.write(currentBody)
                        writer.flush()
                    }
                }

                val code = conn.responseCode

                // Save Set-Cookie headers back to WebView CookieManager
                try {
                    val headers = conn.headerFields
                    headers?.forEach { (key, values) ->
                        if (key != null && key.equals("Set-Cookie", ignoreCase = true) && values != null) {
                            for (cookie in values) {
                                cookieManager?.setCookie(currentUrl, cookie)
                            }
                        }
                    }
                    cookieManager?.flush()
                } catch (_: Exception) {
                }

                val location = conn.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    val nextUrl = URL(URL(currentUrl), location).toString()
                    if (!PortalNetworkHelper.isValidHttpUrl(nextUrl)) break
                    currentReferer = currentUrl
                    currentUrl = nextUrl
                    currentMethod = "GET"
                    currentBody = null
                    continue
                }

                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                lastBody = try {
                    stream?.bufferedReader()?.use { it.readText().take(65536) }.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                // Follow HTML meta-refresh or inline location redirect if present
                if (hop < 4 && lastBody.isNotBlank()) {
                    val redirectCandidate = HTML_REFRESH_REGEX.find(lastBody)?.groupValues?.getOrNull(1)
                    if (!redirectCandidate.isNullOrBlank()) {
                        val nextUrl = try {
                            URL(URL(currentUrl), redirectCandidate).toString()
                        } catch (_: Exception) {
                            ""
                        }
                        if (PortalNetworkHelper.isValidHttpUrl(nextUrl) && nextUrl != currentUrl) {
                            currentReferer = currentUrl
                            currentUrl = nextUrl
                            currentMethod = "GET"
                            currentBody = null
                            continue
                        }
                    }
                }

                return Pair(currentUrl, lastBody)
            } finally {
                conn?.disconnect()
            }
        }

        return Pair(currentUrl, lastBody)
    }

    fun verifyLogoutHtml(
        finalUrl: String,
        htmlBody: String,
        previousPageUrl: String
    ): BackgroundLogoutResult {
        if (htmlBody.isBlank()) {
            return BackgroundLogoutResult(
                success = false,
                diagnostic = "EMPTY_RESPONSE_OVER_WIFI",
                finalUrl = finalUrl
            )
        }

        val visibleText = htmlBody
            .replace(Regex("""<script\b[^>]*>.*?</script>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<style\b[^>]*>.*?</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .lowercase(Locale.ROOT)

        val hasLogoutControlStill = Regex(
            """\b(sign\s*out|log\s*out|log\s*off)\b|signout|logout""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(visibleText)

        val hasLoggedInBannerStill = Regex(
            """you\s+are\s+currently\s+logged\s+in|remaining\s+time|remaining\s+data\s+quota""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(visibleText)

        val hasPasswordField = Regex(
            """<input\b[^>]*type\s*=\s*['"]?password['"]?""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(htmlBody)

        val hasLogoutMessage = Regex(
            """logged\s*out|signed\s*out|session\s*(closed|ended|terminated|expired)|you\s*have\s*been\s*(logged|signed)\s*out|successfully\s*disconnected|please\s*(log|sign)\s*in|login\s*again""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(visibleText)

        val hasLoginButton = Regex(
            """<(?:button|a|input)\b[^>]*>?\s*(?:sign\s*in|log\s*in)\b|value\s*=\s*['"](?:sign\s*in|log\s*in)['"]""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(htmlBody)

        return when {
            hasPasswordField && !hasLogoutControlStill -> BackgroundLogoutResult(
                success = true,
                diagnostic = "PORTAL_LOGOUT_VERIFIED_LOGIN_FORM",
                finalUrl = finalUrl,
                finalHtml = htmlBody
            )

            hasLogoutMessage && !hasLogoutControlStill -> BackgroundLogoutResult(
                success = true,
                diagnostic = "PORTAL_LOGOUT_VERIFIED_MESSAGE",
                finalUrl = finalUrl,
                finalHtml = htmlBody
            )

            hasLoginButton && !hasLogoutControlStill && !hasLoggedInBannerStill -> BackgroundLogoutResult(
                success = true,
                diagnostic = "PORTAL_LOGOUT_VERIFIED_LOGIN_BUTTON",
                finalUrl = finalUrl,
                finalHtml = htmlBody
            )

            finalUrl != previousPageUrl &&
                !hasLogoutControlStill &&
                !hasLoggedInBannerStill &&
                Regex("""logout|logoff|signout|login|signin""", RegexOption.IGNORE_CASE).containsMatchIn(finalUrl) -> {
                BackgroundLogoutResult(
                    success = true,
                    diagnostic = "PORTAL_LOGOUT_VERIFIED_REDIRECT",
                    finalUrl = finalUrl,
                    finalHtml = htmlBody
                )
            }

            hasLogoutControlStill || hasLoggedInBannerStill -> BackgroundLogoutResult(
                success = false,
                diagnostic = "PORTAL_SESSION_STILL_LOGGED_IN",
                finalUrl = finalUrl,
                finalHtml = htmlBody
            )

            else -> BackgroundLogoutResult(
                success = false,
                diagnostic = "PORTAL_LOGOUT_UNCONFIRMED_RESPONSE",
                finalUrl = finalUrl,
                finalHtml = htmlBody
            )
        }
    }
}
