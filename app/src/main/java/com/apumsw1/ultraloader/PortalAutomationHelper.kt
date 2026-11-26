package com.apumsw1.ultraloader

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import org.json.JSONObject
import java.net.URI
import java.util.Locale

data class DetectedLogoutControl(
    val pageUrl: String = "",
    val tagName: String = "",
    val id: String = "",
    val name: String = "",
    val href: String = "",
    val text: String = "",
    val title: String = "",
    val ariaLabel: String = "",
    val onclick: String = "",
    val selector: String = "",
    val detectedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        return JSONObject()
            .put("pageUrl", pageUrl)
            .put("tagName", tagName)
            .put("id", id)
            .put("name", name)
            .put("href", href)
            .put("text", text)
            .put("title", title)
            .put("ariaLabel", ariaLabel)
            .put("onclick", onclick)
            .put("selector", selector)
            .put("detectedAt", detectedAt)
            .toString()
    }

    companion object {
        fun fromJson(raw: String?): DetectedLogoutControl? {
            if (raw.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(raw)
                DetectedLogoutControl(
                    pageUrl = obj.optString("pageUrl", ""),
                    tagName = obj.optString("tagName", ""),
                    id = obj.optString("id", ""),
                    name = obj.optString("name", ""),
                    href = obj.optString("href", ""),
                    text = obj.optString("text", ""),
                    title = obj.optString("title", ""),
                    ariaLabel = obj.optString("ariaLabel", ""),
                    onclick = obj.optString("onclick", ""),
                    selector = obj.optString("selector", ""),
                    detectedAt = obj.optLong("detectedAt", System.currentTimeMillis())
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

object VpnAutomationBridge {
    const val VPN_PACKAGE = "com.fast.free.unblock.thunder.vpn"
    const val VPN_DIALOG_PACKAGE = "com.android.vpndialogs"
    const val ACTION_RETURN_FROM_VPN = "com.apumsw1.ultraloader.RETURN_FROM_VPN"

    @Volatile
    var appInForeground: Boolean = false

    @Volatile
    private var armedUntilMs: Long = 0L

    @Volatile
    private var returnToAppClaimed: Boolean = false

    @Volatile
    private var clickAttempts: Int = 0

    @Volatile
    private var lastClickAtMs: Long = 0L

    fun arm(timeoutMs: Long = 60_000L) {
        armedUntilMs = System.currentTimeMillis() + timeoutMs
        returnToAppClaimed = false
        clickAttempts = 0
        lastClickAtMs = 0L
    }

    fun disarm() {
        armedUntilMs = 0L
        clickAttempts = 0
        lastClickAtMs = 0L
    }

    fun isArmed(): Boolean = System.currentTimeMillis() < armedUntilMs

    @Synchronized
    fun claimReturnToApp(): Boolean {
        if (returnToAppClaimed) return false
        returnToAppClaimed = true
        return true
    }

    fun canAttemptClick(minIntervalMs: Long = 1800L, maxAttempts: Int = 6): Boolean {
        if (!isArmed()) return false
        if (clickAttempts >= maxAttempts) return false
        return System.currentTimeMillis() - lastClickAtMs >= minIntervalMs
    }

    fun recordClickAttempt() {
        clickAttempts++
        lastClickAtMs = System.currentTimeMillis()
    }
}

object PortalNetworkHelper {

    fun isValidHttpUrl(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return try {
            val uri = URI(value.trim())
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun findWifiNetwork(manager: ConnectivityManager): Network? {
        return try {
            manager.allNetworks.firstOrNull { network ->
                val caps = manager.getNetworkCapabilities(network)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun isWifiNetworkActive(manager: ConnectivityManager, network: Network?): Boolean {
        if (network == null) return false
        return try {
            val caps = manager.getNetworkCapabilities(network)
            caps != null &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    fun isVpnConnected(manager: ConnectivityManager): Boolean {
        return try {
            manager.allNetworks.any { network ->
                manager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun bindToWifiNetwork(
        manager: ConnectivityManager,
        preferredWifi: Network?
    ): Network? {
        val target = if (isWifiNetworkActive(manager, preferredWifi)) {
            preferredWifi
        } else {
            findWifiNetwork(manager)
        } ?: return null

        return try {
            if (manager.bindProcessToNetwork(target)) target else null
        } catch (_: Exception) {
            null
        }
    }

    fun clearProcessNetworkBinding(manager: ConnectivityManager) {
        try {
            manager.bindProcessToNetwork(null)
        } catch (_: Exception) {
        }
    }

    fun getCaptivePortalUrls(
        manager: ConnectivityManager,
        network: Network?
    ): Pair<String?, String?> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || network == null) {
            return Pair(null, null)
        }
        return try {
            val linkProps = manager.getLinkProperties(network) ?: return Pair(null, null)
            val capData = linkProps.javaClass
                .getMethod("getCaptivePortalData")
                .invoke(linkProps) ?: return Pair(null, null)

            val userUrl = try {
                capData.javaClass.getMethod("getUserPortalUrl").invoke(capData)?.toString()
            } catch (_: Exception) {
                null
            }?.takeIf { isValidHttpUrl(it) }

            val venueUrl = try {
                capData.javaClass.getMethod("getVenueInfoUrl").invoke(capData)?.toString()
            } catch (_: Exception) {
                null
            }?.takeIf { isValidHttpUrl(it) }

            Pair(userUrl, venueUrl)
        } catch (_: Exception) {
            Pair(null, null)
        }
    }

    @Suppress("DEPRECATION")
    fun getCaptivePortalUrl(
        manager: ConnectivityManager,
        network: Network?,
        preferVenue: Boolean
    ): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val candidates = if (network != null) {
            listOf(network)
        } else {
            manager.allNetworks.filter { n ->
                manager.getNetworkCapabilities(n)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
        for (net in candidates) {
            val (userUrl, venueUrl) = getCaptivePortalUrls(manager, net)
            val picked = if (preferVenue) {
                venueUrl ?: userUrl
            } else {
                userUrl ?: venueUrl
            }
            if (isValidHttpUrl(picked)) {
                return picked
            }
        }
        return null
    }

    fun getWifiGatewayIp(
        context: Context,
        manager: ConnectivityManager,
        network: Network?
    ): String? {
        try {
            val target = network ?: findWifiNetwork(manager)
            if (target != null) {
                val lp = manager.getLinkProperties(target)
                val routeGateway = lp?.routes?.firstOrNull { route ->
                    route.isDefaultRoute && route.gateway is java.net.Inet4Address
                }?.gateway?.hostAddress
                if (!routeGateway.isNullOrBlank() && routeGateway != "0.0.0.0") {
                    return routeGateway
                }
            }
        } catch (_: Exception) {
        }
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val gw = wm.dhcpInfo?.gateway ?: 0
            if (gw != 0) {
                return String.format(
                    Locale.US,
                    "%d.%d.%d.%d",
                    gw and 0xff,
                    gw shr 8 and 0xff,
                    gw shr 16 and 0xff,
                    gw shr 24 and 0xff
                )
            }
        } catch (_: Exception) {
        }
        return null
    }
}

object PortalJsScripts {

    private const val SHARED_DOM_HELPERS = """
        function getAllDocs() {
          var docs = [document];
          try {
            var frames = document.querySelectorAll('iframe, frame');
            for (var i = 0; i < frames.length; i++) {
              try {
                var fd = frames[i].contentDocument || (frames[i].contentWindow && frames[i].contentWindow.document);
                if (fd) docs.push(fd);
              } catch (e) {}
            }
          } catch (e) {}
          return docs;
        }

        function getClassStr(el) {
          if (!el) return '';
          try {
            if (typeof el.className === 'string') return el.className;
            return el.getAttribute ? (el.getAttribute('class') || '') : '';
          } catch (e) {
            return '';
          }
        }

        function isElemVisible(el) {
          if (!el) return false;
          try {
            if (el.disabled) return false;
            if ((el.type || '').toLowerCase() === 'hidden') return false;
            var curr = el;
            var depth = 0;
            while (curr && curr.nodeType === 1 && depth < 10) {
              if (curr.hidden === true) return false;
              if (curr.getAttribute && curr.getAttribute('aria-hidden') === 'true') return false;
              var st = window.getComputedStyle ? window.getComputedStyle(curr) : null;
              if (st) {
                if (st.display === 'none' || st.visibility === 'hidden' || parseFloat(st.opacity || '1') === 0) {
                  return false;
                }
              }
              curr = curr.parentElement;
              depth++;
            }
            var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
            var txt = (el.innerText || el.textContent || el.value || '').trim();
            if (rect && rect.width === 0 && rect.height === 0 && !txt) {
              return false;
            }
            return true;
          } catch (e) {
            return true;
          }
        }

        function hasPeplinkLoggedInText(doc) {
          try {
            var d = doc || document;
            var t = (d.body ? (d.body.innerText || d.body.textContent || '') : '').toLowerCase();
            return /you\s+are\s+currently\s+logged\s+in|remaining\s+time|remaining\s+data\s+quota|successfully\s+connected|connected\s+successfully|internet\s+access\s+granted|authentication\s+successful/.test(t);
          } catch (e) {
            return false;
          }
        }

        function isLogoutElement(el) {
          if (!el || !isElemVisible(el)) return false;
          var tag = (el.tagName || '').toLowerCase();
          var inputType = (el.type || '').toLowerCase();
          if (tag === 'input' && ['hidden', 'text', 'password', 'email', 'tel', 'checkbox', 'radio'].includes(inputType)) {
            return false;
          }

          var rawText = (el.innerText || el.textContent || el.value || '').replace(/\s+/g, ' ').trim();
          if ((tag === 'div' || tag === 'span' || tag === 'p' || tag === 'td' || tag === 'li' || tag === 'label') && rawText.length > 55) {
            return false;
          }

          var altAttr = el.getAttribute ? (el.getAttribute('alt') || '') : '';
          var ariaAttr = el.getAttribute ? (el.getAttribute('aria-label') || '') : '';
          var hrefAttr = el.getAttribute ? (el.getAttribute('href') || '') : '';
          var onclickAttr = el.getAttribute ? (el.getAttribute('onclick') || '') : '';
          var dataAction = el.getAttribute ? (el.getAttribute('data-action') || '') : '';
          var dataHref = el.getAttribute ? (el.getAttribute('data-href') || el.getAttribute('data-url') || '') : '';
          var formAction = el.getAttribute ? (el.getAttribute('formaction') || '') : '';

          var textVal = (rawText + ' ' + (el.title || '') + ' ' + altAttr + ' ' + ariaAttr).toLowerCase();
          var attrVal = ((el.id || '') + ' ' + (el.name || '') + ' ' + getClassStr(el) + ' ' +
            hrefAttr + ' ' + onclickAttr + ' ' + dataAction + ' ' + dataHref + ' ' + formAction).toLowerCase();
          var combined = textVal + ' ' + attrVal;

          var logoutRx = /\b(sign\s*[-_]?\s*out|log\s*[-_]?\s*out|log\s*[-_]?\s*off|disconnect)\b|signout|logout|logoff/i;
          if (!logoutRx.test(combined)) return false;

          var loginOnlyRx = /\b(log\s*in|sign\s*in|sign\s*up|register)\b/i;
          if (loginOnlyRx.test(textVal) && !logoutRx.test(textVal)) return false;
          return true;
        }

        function findVisiblePasswordField() {
          var docs = getAllDocs();
          for (var d = 0; d < docs.length; d++) {
            var inputs = Array.from(docs[d].querySelectorAll('input[type=password]'));
            var found = inputs.find(function(x) { return isElemVisible(x); });
            if (found) return { doc: docs[d], field: found };
          }
          return null;
        }

        function findVisibleUsernameField(doc, pf) {
          var inputs = Array.from(doc.querySelectorAll('input'));
          return inputs.find(function(x) {
            if (x === pf || !isElemVisible(x)) return false;
            var t = (x.type || 'text').toLowerCase();
            if (['hidden', 'password', 'checkbox', 'radio', 'submit', 'button', 'reset', 'file', 'image'].includes(t)) {
              return false;
            }
            var n = ((x.name || '') + ' ' + (x.id || '') + ' ' + (x.placeholder || '') + ' ' + (x.getAttribute('aria-label') || '')).toLowerCase();
            return ['user', 'email', 'login', 'account', 'uid', 'name', 'id', 'phone', 'voucher', 'code'].some(function(k) {
              return n.includes(k);
            }) || ['text', 'email', 'tel'].includes(t);
          }) || null;
        }

        function findLogoutCandidate() {
          var docs = getAllDocs();
          var candidates = [];
          var seen = [];

          function pushCandidate(el, doc, bonus) {
            if (!el || seen.indexOf(el) !== -1) return;
            if (!isLogoutElement(el)) return;
            seen.push(el);

            var tag = (el.tagName || '').toLowerCase();
            var label = ((el.innerText || el.textContent || el.value || '') + ' ' +
              (el.getAttribute ? (el.getAttribute('aria-label') || '') : '') + ' ' +
              (el.title || '')).replace(/\s+/g, ' ').trim().toLowerCase();
            var idNameClass = ((el.id || '') + ' ' + (el.name || '') + ' ' + getClassStr(el)).toLowerCase();
            var href = (el.getAttribute ? (el.getAttribute('href') || '') : '').toLowerCase();
            var onclickStr = (el.getAttribute ? (el.getAttribute('onclick') || '') : '').toLowerCase();

            var score = bonus || 0;
            if (/^(sign\s*out|signout|log\s*out|logout|log\s*off)$/i.test(label)) score += 25;
            else if (/sign\s*[-_]?\s*out|log\s*[-_]?\s*out|signout|logout|log\s*[-_]?\s*off/i.test(label)) score += 15;
            if (/logout|signout|sign-out|log-out|logoff/i.test(idNameClass)) score += 10;
            if (/logout|signout|logoff/i.test(href) || /logout|signout|logoff/i.test(onclickStr)) score += 10;
            if (tag === 'button' || tag === 'a' || tag === 'input') score += 12;
            else if (el.getAttribute && (el.getAttribute('role') === 'button' || el.getAttribute('role') === 'link' || el.getAttribute('onclick'))) score += 8;

            candidates.push({ el: el, doc: doc, score: score });
          }

          for (var d = 0; d < docs.length; d++) {
            var doc = docs[d];
            var primary = Array.from(doc.querySelectorAll(
              'a,button,input[type=button],input[type=submit],input[type=reset],input[type=image],[role=button],[role=link],[onclick],[data-action],[data-href],[data-url]'
            ));
            for (var i = 0; i < primary.length; i++) {
              pushCandidate(primary[i], doc, 10);
            }

            // Forms explicitly posting/submitting to a logout/signout action
            var forms = Array.from(doc.querySelectorAll('form'));
            for (var f = 0; f < forms.length; f++) {
              var act = (forms[f].getAttribute('action') || '').toLowerCase();
              if (/logout|signout|sign-out|logoff/i.test(act)) {
                var fBtn = forms[f].querySelector('button,input[type=submit],input[type=button],a');
                if (fBtn && isElemVisible(fBtn)) {
                  seen.push(fBtn);
                  candidates.push({ el: fBtn, doc: doc, score: 35 });
                }
              }
            }

            // Clickable or JS-generated div/span/li/label controls (e.g. Peplink custom widgets)
            var secondary = Array.from(doc.querySelectorAll('div,span,li,td,p,label'));
            for (var s = 0; s < secondary.length; s++) {
              var sec = secondary[s];
              var st = window.getComputedStyle ? window.getComputedStyle(sec) : null;
              var cursorPointer = st && st.cursor === 'pointer';
              var hasHandler = typeof sec.onclick === 'function' || (sec.getAttribute && (sec.getAttribute('tabindex') !== null));
              var txt = (sec.innerText || sec.textContent || '').replace(/\s+/g, ' ').trim().toLowerCase();
              var exactText = /^(sign\s*out|signout|log\s*out|logout|log\s*off)$/i.test(txt);
              var classMatch = /logout|signout|sign-out|log-out/i.test((sec.id || '') + ' ' + getClassStr(sec));
              if (cursorPointer || hasHandler || exactText || classMatch) {
                // Prefer parent interactive element if nested inside <button> or <a>
                var parentInteractive = sec.closest ? sec.closest('a,button,[role=button],[role=link],[onclick]') : null;
                if (parentInteractive && isElemVisible(parentInteractive)) {
                  pushCandidate(parentInteractive, doc, 12);
                } else {
                  pushCandidate(sec, doc, exactText ? 8 : 2);
                }
              }
            }
          }
          if (candidates.length === 0) return null;
          candidates.sort(function(a, b) { return b.score - a.score; });
          return candidates[0];
        }

        function resolveUrlSafe(raw, baseHref) {
          if (!raw) return '';
          var trimmed = ('' + raw).trim();
          if (!trimmed || trimmed === '#' || trimmed.indexOf('javascript:') === 0) return '';
          try {
            return new URL(trimmed, baseHref || window.location.href).href;
          } catch (e) {
            return '';
          }
        }

        function extractOnclickUrl(onclickStr, baseHref) {
          if (!onclickStr) return '';
          var m = onclickStr.match(/(?:location(?:\.href)?\s*=\s*|location\.replace\(\s*|window\.open\(\s*)['"]([^'"]+)['"]/i);
          if (m && m[1]) {
            return resolveUrlSafe(m[1], baseHref);
          }
          return '';
        }

        function buildCssSelector(el) {
          if (!el) return '';
          if (el.id) return '#' + el.id.replace(/([^\w-])/g, '\\${'$'}1');
          var parts = [];
          var curr = el;
          while (curr && curr.nodeType === 1 && parts.length < 5) {
            var part = curr.tagName.toLowerCase();
            if (curr.name) {
              part += '[name="' + curr.name.replace(/"/g, '\\"') + '"]';
              parts.unshift(part);
              break;
            }
            var parent = curr.parentElement;
            if (parent) {
              var sameTag = Array.from(parent.children).filter(function(c) { return c.tagName === curr.tagName; });
              if (sameTag.length > 1) {
                part += ':nth-of-type(' + (sameTag.indexOf(curr) + 1) + ')';
              }
            }
            parts.unshift(part);
            curr = parent;
          }
          return parts.join(' > ');
        }
    """

    fun buildAutoLoginScript(
        encodedUser: String,
        encodedPass: String,
        hasCredentials: Boolean,
        allowClickNav: Boolean
    ): String {
        return """
            (function() {
              $SHARED_DOM_HELPERS

              function dec(s) {
                if (!s) return '';
                try { return decodeURIComponent(escape(atob(s))); }
                catch (e) { return atob(s); }
              }

              var logoutCandFirst = findLogoutCandidate();
              var loggedInBanner = hasPeplinkLoggedInText(document);
              var pfMatch = findVisiblePasswordField();

              if (logoutCandFirst && (loggedInBanner || !pfMatch)) {
                return 'ALREADY_LOGGED_IN';
              }

              var pf = pfMatch ? pfMatch.field : null;
              var formDoc = pfMatch ? pfMatch.doc : document;
              var uf = pf ? findVisibleUsernameField(formDoc, pf) : null;

              if (!pf) {
                if (logoutCandFirst || loggedInBanner) {
                  return 'ALREADY_LOGGED_IN';
                }

                var docs = getAllDocs();
                var nav = null;
                for (var d = 0; d < docs.length && !nav; d++) {
                  var btns = Array.from(docs[d].querySelectorAll('a,button,input[type=button],input[type=submit],[role=button]'));
                  nav = btns.find(function(b) {
                    if (!isElemVisible(b) || isLogoutElement(b)) return false;
                    var sig = ((b.innerText || '') + ' ' + (b.value || '') + ' ' + (b.id || '') + ' ' + (b.name || '') + ' ' + (b.getAttribute('aria-label') || ''));
                    return /\b(log\s*in|sign\s*in|connect|continue|enter|proceed|accept|agree|get\s*online|start)\b/i.test(sig);
                  }) || null;
                }
                if (nav && ${if (allowClickNav) "true" else "false"}) {
                  nav.click();
                  return 'NAVIGATED';
                }
                return 'FORM_NOT_FOUND';
              }

              if (${if (hasCredentials) "false" else "true"}) {
                return 'CREDENTIALS_REQUIRED';
              }

              var u = dec('$encodedUser');
              var p = dec('$encodedPass');

              if (uf) {
                uf.focus();
                uf.value = u;
                uf.dispatchEvent(new Event('input', { bubbles: true }));
                uf.dispatchEvent(new Event('change', { bubbles: true }));
              }

              pf.focus();
              pf.value = p;
              pf.dispatchEvent(new Event('input', { bubbles: true }));
              pf.dispatchEvent(new Event('change', { bubbles: true }));

              Array.from(formDoc.querySelectorAll('input[type=checkbox]')).forEach(function(c) {
                var label = ((c.name || '') + ' ' + (c.id || '') + ' ' + (c.parentElement ? c.parentElement.innerText : '')).toLowerCase();
                if (/terms?|conditions?|agreement|accept|policy|agree/.test(label) && !c.checked && !c.disabled) {
                  c.click();
                }
              });

              var form = pf.closest('form');
              if (form) {
                var submit = form.querySelector('button[type=submit],input[type=submit]');
                if (submit && isElemVisible(submit)) {
                  submit.click();
                  return 'SUBMITTED';
                }
                var formBtn = Array.from(form.querySelectorAll('button,input[type=button]')).find(function(b) {
                  return isElemVisible(b) && /log\s*in|sign\s*in|connect|continue|submit|enter/i.test(b.innerText || b.value || b.id || b.name || '');
                });
                if (formBtn) {
                  formBtn.click();
                  return 'SUBMITTED';
                }
                if (form.requestSubmit) form.requestSubmit();
                else form.submit();
                return 'SUBMITTED';
              }

              var buttons = Array.from(formDoc.querySelectorAll('button,input[type=button],input[type=submit],[role=button]'));
              var loginBtn = buttons.find(function(b) {
                return isElemVisible(b) && /log\s*in|sign\s*in|connect|continue|submit/i.test(b.innerText || b.value || b.id || b.name || '');
              });
              if (loginBtn) {
                loginBtn.click();
                return 'SUBMITTED';
              }

              return 'NO_SUBMIT_BUTTON';
            })();
        """.trimIndent()
    }

    fun buildCheckLoginScript(): String {
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var logoutCand = findLogoutCandidate();
              var loggedInText = hasPeplinkLoggedInText(document);
              if (logoutCand) return 'LOGIN_OK_LOGOUT_PRESENT';

              var pfMatch = findVisiblePasswordField();
              if (pfMatch && !loggedInText) return 'LOGIN_FORM_STILL_HERE';

              if (loggedInText) return 'LOGIN_OK_SUCCESS_TEXT';

              var t = (document.body ? (document.body.innerText || '') : '').toLowerCase();
              if (/\bwelcome\b/.test(t) && !pfMatch) {
                return 'LOGIN_OK_SUCCESS_TEXT';
              }
              return 'WAITING';
            })();
        """.trimIndent()
    }

    fun buildDetectLogoutScript(): String {
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var cand = findLogoutCandidate();
              var pfMatch = findVisiblePasswordField();
              var loggedInText = hasPeplinkLoggedInText(document);
              var curUrl = window.location ? (window.location.href || '') : '';

              if (!cand) {
                return JSON.stringify({
                  found: false,
                  reason: pfMatch && !loggedInText ? 'LOGIN_FORM_PRESENT' : 'NOT_FOUND',
                  pageUrl: curUrl,
                  loggedInText: loggedInText
                });
              }

              var el = cand.el;
              var doc = cand.doc || document;
              var baseHref = (doc.location && doc.location.href) ? doc.location.href : curUrl;
              var rawHref = el.getAttribute ? (el.getAttribute('href') || '') : '';
              var dataHref = el.getAttribute ? (el.getAttribute('data-href') || el.getAttribute('data-url') || '') : '';
              var onclickStr = el.getAttribute ? (el.getAttribute('onclick') || '') : '';
              var resolved = resolveUrlSafe(el.href || rawHref || dataHref, baseHref) ||
                             extractOnclickUrl(onclickStr, baseHref);

              var form = el.closest ? el.closest('form') : null;
              var formAction = '';
              var formMethod = '';
              var formParams = {};
              if (form) {
                formAction = resolveUrlSafe(form.getAttribute('action') || el.getAttribute('formaction') || baseHref, baseHref);
                formMethod = ((form.getAttribute('method') || 'GET') + '').toUpperCase();
                var fInputs = Array.from(form.querySelectorAll('input,select,textarea'));
                for (var fi = 0; fi < fInputs.length; fi++) {
                  var inp = fInputs[fi];
                  if (!inp.name || inp.disabled) continue;
                  var t = (inp.type || '').toLowerCase();
                  if ((t === 'checkbox' || t === 'radio') && !inp.checked) continue;
                  if ((t === 'submit' || t === 'button') && inp !== el) continue;
                  formParams[inp.name] = inp.value || '';
                }
                if (el.name && !formParams[el.name]) {
                  formParams[el.name] = el.value || '1';
                }
              }

              var control = {
                pageUrl: baseHref,
                tagName: (el.tagName || '').toLowerCase(),
                id: el.id || '',
                name: el.name || '',
                className: getClassStr(el),
                href: rawHref || (el.href || ''),
                resolvedUrl: resolved,
                formAction: formAction,
                formMethod: formMethod,
                formParamsJson: form ? JSON.stringify(formParams) : '',
                text: (el.innerText || el.textContent || el.value || '').replace(/\s+/g, ' ').trim(),
                title: el.title || '',
                ariaLabel: el.getAttribute ? (el.getAttribute('aria-label') || '') : '',
                onclick: onclickStr,
                selector: buildCssSelector(el),
                loggedInDetected: loggedInText
              };

              return JSON.stringify({ found: true, control: control });
            })();
        """.trimIndent()
    }

    fun buildClickLogoutScript(control: DetectedLogoutControl?): String {
        val controlJson = JSONObject.quote(control?.toJson() ?: "{}")
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var saved = {};
              try { saved = JSON.parse($controlJson || '{}'); } catch (e) { saved = {}; }

              function findSavedTarget() {
                var docs = getAllDocs();
                for (var d = 0; d < docs.length; d++) {
                  var doc = docs[d];
                  if (saved.id) {
                    var byId = doc.getElementById(saved.id);
                    if (byId && isElemVisible(byId)) return byId;
                  }
                  if (saved.selector) {
                    try {
                      var bySel = doc.querySelector(saved.selector);
                      if (bySel && isElemVisible(bySel) && isLogoutElement(bySel)) return bySel;
                    } catch (e) {}
                  }
                  var all = Array.from(doc.querySelectorAll('a,button,input[type=button],input[type=submit],input[type=reset],input[type=image],[role=button],[role=link],[onclick],div,span'));
                  if (saved.name) {
                    var byName = all.find(function(n) { return n.name === saved.name && isElemVisible(n) && isLogoutElement(n); });
                    if (byName) return byName;
                  }
                  if (saved.href) {
                    var byHref = all.find(function(n) {
                      var h = (n.getAttribute ? n.getAttribute('href') : '') || n.href || '';
                      return h === saved.href && isElemVisible(n);
                    });
                    if (byHref) return byHref;
                  }
                  if (saved.text) {
                    var targetText = saved.text.toLowerCase();
                    var byText = all.find(function(n) {
                      var txt = (n.innerText || n.textContent || n.value || '').replace(/\s+/g, ' ').trim().toLowerCase();
                      return txt === targetText && isElemVisible(n) && isLogoutElement(n);
                    });
                    if (byText) return byText;
                  }
                }
                return null;
              }

              var target = findSavedTarget();
              if (!target) {
                var fallback = findLogoutCandidate();
                target = fallback ? fallback.el : null;
              }

              if (!target) {
                return 'LOGOUT_NOT_FOUND';
              }

              try { target.scrollIntoView({ block: 'center' }); } catch (e) {}
              try { target.click(); } catch (e) {
                target.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
              }
              return 'LOGOUT_CLICKED';
            })();
        """.trimIndent()
    }

    fun buildVerifyLogoutScript(previousPageUrl: String): String {
        val prevQuoted = JSONObject.quote(previousPageUrl)
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var href = (window.location && window.location.href) ? window.location.href : '';
              if (!href || href.indexOf('chrome-error://') === 0 || href === 'about:blank') {
                return 'LOGOUT_ERROR_PAGE';
              }

              var logoutStillThere = findLogoutCandidate();
              var loggedInTextStillThere = hasPeplinkLoggedInText(document);

              var pfMatch = findVisiblePasswordField();
              if (pfMatch && !logoutStillThere) {
                return 'LOGOUT_VERIFIED_LOGIN_FORM';
              }

              var docs = getAllDocs();
              for (var d = 0; d < docs.length; d++) {
                var btns = Array.from(docs[d].querySelectorAll('a,button,input[type=button],input[type=submit],[role=button],[role=link]'));
                var loginBtn = btns.find(function(b) {
                  if (!isElemVisible(b) || isLogoutElement(b)) return false;
                  var sig = (b.innerText || '') + ' ' + (b.value || '') + ' ' + (b.id || '') + ' ' + (b.name || '') + ' ' + (b.title || '') + ' ' + (b.getAttribute('aria-label') || '') + ' ' + (b.getAttribute('href') || '');
                  return /\b(log\s*in|sign\s*in)\b|login|signin/i.test(sig);
                });
                if (loginBtn && !logoutStillThere && !loggedInTextStillThere) {
                  return 'LOGOUT_VERIFIED_LOGIN_BUTTON';
                }
              }

              var bodyTxt = (document.body ? (document.body.innerText || '') : '').toLowerCase();
              if (/logged\s*out|signed\s*out|session\s*(closed|ended|terminated|expired)|successfully\s*disconnected|you\s*have\s*been\s*(logged|signed)\s*out|please\s*(log|sign)\s*in|login\s*again/.test(bodyTxt) && !logoutStillThere) {
                return 'LOGOUT_VERIFIED_MESSAGE';
              }

              if (logoutStillThere) {
                return 'LOGOUT_STILL_PRESENT';
              }

              var prevUrl = $prevQuoted || '';
              if (href !== prevUrl && !loggedInTextStillThere && /logout|logoff|signout|login|signin|portal|status/i.test(href)) {
                return 'LOGOUT_VERIFIED_NAVIGATED';
              }

              return 'LOGOUT_PENDING';
            })();
        """.trimIndent()
    }

    fun buildSecondLoginAgentScript(): String {
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var pfMatch = findVisiblePasswordField();
              if (pfMatch) {
                var uf = findVisibleUsernameField(pfMatch.doc, pfMatch.field);
                if (uf) return 'LOGIN_AGENT_FORM_ALREADY_OPEN';
              }

              var docs = getAllDocs();
              for (var d = 0; d < docs.length; d++) {
                var a = Array.from(docs[d].querySelectorAll('a,button,input[type=button],input[type=submit],[role=button],[role=link]'));
                var x = a.find(function(n) {
                  if (!isElemVisible(n) || isLogoutElement(n)) return false;
                  var sig = (n.innerText || '') + ' ' + (n.value || '') + ' ' + (n.id || '') + ' ' + (n.name || '') + ' ' + (n.title || '') + ' ' + (n.getAttribute('aria-label') || '') + ' ' + (n.getAttribute('href') || '');
                  return /log.?in|sign.?in/i.test(sig);
                });
                if (x) {
                  x.click();
                  return 'LOGIN_AGENT_CLICKED';
                }
              }
              return 'LOGIN_AGENT_NOT_FOUND';
            })();
        """.trimIndent()
    }

    fun buildSecondLoginFormScript(): String {
        return """
            (function() {
              $SHARED_DOM_HELPERS

              var pfMatch = findVisiblePasswordField();
              if (!pfMatch) return 'WAITING';
              var uf = findVisibleUsernameField(pfMatch.doc, pfMatch.field);
              return uf ? 'SECOND_FORM_READY' : 'WAITING';
            })();
        """.trimIndent()
    }
}
