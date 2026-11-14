package it.alberto.telecomando

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Collegamento al televisore Samsung (Tizen, modelli dal 2018) via WebSocket cifrato sulla porta 8002.
 */
class SamsungTv(
    context: Context,
    private val avvisa: (Stato) -> Unit,
    private val messaggio: (String) -> Unit,
) {

    enum class Stato { NON_COLLEGATO, IN_COLLEGAMENTO, ATTESA_AUTORIZZAZIONE, COLLEGATO, RIFIUTATO }

    /** Un'app del televisore: nome da cercare nell'elenco installate + codici noti di riserva. */
    class AppTv(val nome: String, val parole: List<String>, val codici: List<String>)

    companion object {
        const val TV_IP = "192.168.178.47"
        const val TV_MAC = "64:07:F6:AF:C1:A8"
        const val PORTA = 8002
        const val NOME_APP = "Telecomando"

        val NETFLIX = AppTv(
            "Netflix", listOf("netflix"),
            listOf("3201907018807", "11101200001", "org.tizen.netflix-app", "RN1MCdNq8t.Netflix"),
        )
        val PRIME_VIDEO = AppTv(
            "Prime Video", listOf("prime video", "amazon"),
            listOf("3201910019365", "3201512006785", "org.tizen.ignition", "evKhCgZelL.AmazonIgnitionLauncher2"),
        )
        val YOUTUBE = AppTv(
            "YouTube", listOf("youtube"),
            listOf("111299001912", "9Ur5IzDKqV.TizenYouTube"),
        )
    }

    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val principale = Handler(Looper.getMainLooper())
    private val inAttesa = mutableListOf<String>()

    /** Elenco delle app installate sul televisore: nome in minuscolo -> (codice, tipo). */
    @Volatile
    private var installate: Map<String, Pair<String, Int>> = emptyMap()

    private var ws: WebSocket? = null
    var stato = Stato.NON_COLLEGATO
        private set

    // Il televisore usa un certificato auto-firmato da Samsung: lo accettiamo
    // solo per l'indirizzo del televisore.
    private val client: OkHttpClient by lazy {
        val fiducia = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(fiducia), SecureRandom()) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, fiducia)
            .hostnameVerifier { host, _ -> host == TV_IP }
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    // Per le chiamate REST sulla porta 8001 (avvio app)
    private val rest: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun cambia(nuovo: Stato) {
        principale.post {
            stato = nuovo
            avvisa(nuovo)
        }
    }

    private fun scrivi(testo: String) = principale.post { messaggio(testo) }

    @Synchronized
    fun collega() {
        if (ws != null) return
        val nome = Base64.encodeToString(NOME_APP.toByteArray(), Base64.NO_WRAP)
        val token = prefs.getString("token", null)
        var url = "wss://$TV_IP:$PORTA/api/v2/channels/samsung.remote.control?name=$nome"
        if (!token.isNullOrEmpty()) url += "&token=$token"

        cambia(Stato.IN_COLLEGAMENTO)
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (prefs.getString("token", null).isNullOrEmpty()) cambia(Stato.ATTESA_AUTORIZZAZIONE)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = try { JSONObject(text) } catch (e: Exception) { return }
                when (msg.optString("event")) {
                    "ms.channel.connect" -> {
                        val nuovo = msg.optJSONObject("data")?.optString("token")
                        if (!nuovo.isNullOrEmpty()) prefs.edit().putString("token", nuovo).apply()
                        cambia(Stato.COLLEGATO)
                        svuotaCoda(webSocket)
                        chiediAppInstallate(webSocket)
                    }
                    "ms.channel.unauthorized" -> {
                        cambia(Stato.RIFIUTATO)
                        webSocket.close(1000, null)
                    }
                    "ed.installedApp.get" -> leggiAppInstallate(msg)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = chiuso(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = chiuso(webSocket)
        })
    }

    private fun chiediAppInstallate(webSocket: WebSocket) {
        val params = JSONObject().put("event", "ed.installedApp.get").put("to", "host")
        webSocket.send(JSONObject().put("method", "ms.channel.emit").put("params", params).toString())
    }

    private fun leggiAppInstallate(msg: JSONObject) {
        val elenco = msg.optJSONObject("data")?.optJSONArray("data") ?: return
        val mappa = mutableMapOf<String, Pair<String, Int>>()
        for (i in 0 until elenco.length()) {
            val app = elenco.optJSONObject(i) ?: continue
            val nome = app.optString("name").lowercase()
            val id = app.optString("appId")
            if (nome.isNotEmpty() && id.isNotEmpty()) mappa[nome] = id to app.optInt("app_type", 2)
        }
        installate = mappa
        prefs.edit().putString("app_installate", mappa.entries.joinToString("\n") { "${it.key}=${it.value.first}" }).apply()
    }

    @Synchronized
    private fun chiuso(webSocket: WebSocket) {
        if (ws === webSocket) {
            ws = null
            inAttesa.clear()
            if (stato != Stato.RIFIUTATO) cambia(Stato.NON_COLLEGATO)
        }
    }

    @Synchronized
    private fun svuotaCoda(webSocket: WebSocket) {
        inAttesa.forEach { webSocket.send(it) }
        inAttesa.clear()
    }

    @Synchronized
    fun scollega() {
        ws?.close(1000, null)
        ws = null
        inAttesa.clear()
        cambia(Stato.NON_COLLEGATO)
    }

    @Synchronized
    private fun invia(json: String) {
        val attuale = ws
        if (attuale != null && stato == Stato.COLLEGATO) {
            attuale.send(json)
        } else {
            inAttesa.add(json)
            collega()
        }
    }

    fun tasto(codice: String) {
        val params = JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", codice)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey")
        invia(JSONObject().put("method", "ms.remote.control").put("params", params).toString())
    }

    private fun avviaViaWebSocket(appId: String, tipo: Int) {
        val azione = if (tipo == 4) "NATIVE_LAUNCH" else "DEEP_LINK"
        val data = JSONObject().put("appId", appId).put("action_type", azione)
        val params = JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data)
        invia(JSONObject().put("method", "ms.channel.emit").put("params", params).toString())
    }

    /** Prova ad avviare via REST (porta 8001). Restituisce true se il televisore risponde OK. */
    private fun avviaViaRest(appId: String): Boolean = try {
        val richiesta = Request.Builder()
            .url("http://$TV_IP:8001/api/v2/applications/$appId")
            .post("".toRequestBody("application/json".toMediaType()))
            .build()
        rest.newCall(richiesta).execute().use { it.isSuccessful }
    } catch (_: Exception) {
        false
    }

    fun apriApp(app: AppTv) {
        // 1) codice preso dall'elenco delle app installate sul televisore
        val trovata = installate.entries.firstOrNull { (nome, _) -> app.parole.any { nome.contains(it) } }?.value
        if (trovata != null) avviaViaWebSocket(trovata.first, trovata.second)

        // 2) avvio via REST, provando il codice trovato e poi quelli noti
        Thread {
            val codici = (listOfNotNull(trovata?.first) + app.codici).distinct()
            val riuscito = codici.firstOrNull { avviaViaRest(it) }
            when {
                riuscito != null -> {
                    prefs.edit().putString("codice_${app.nome}", riuscito).apply()
                    scrivi("Apro ${app.nome}")
                }
                trovata != null -> scrivi("Apro ${app.nome}")
                else -> {
                    // ultimo tentativo: WebSocket con i codici noti
                    app.codici.forEach { avviaViaWebSocket(it, 2) }
                    val elenco = installate.keys.sorted().joinToString(", ")
                    scrivi(
                        if (elenco.isEmpty()) "${app.nome}: il televisore non ha risposto con l'elenco delle app"
                        else "${app.nome} non trovata. App sul TV: $elenco"
                    )
                }
            }
        }.start()
    }

    /** Elenco app installate (nome=codice), per diagnosi. */
    fun descriviAppInstallate(): String =
        prefs.getString("app_installate", null)?.takeIf { it.isNotBlank() } ?: "Elenco app non ancora ricevuto dal televisore."

    /** Accende il televisore spento (standby) con un "magic packet" Wake-on-LAN. */
    fun accendiDaSpento() {
        Thread {
            try {
                val mac = TV_MAC.split(":").map { it.toInt(16).toByte() }.toByteArray()
                val pacchetto = ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { mac.toList() }.toByteArray()
                DatagramSocket().use { s ->
                    s.broadcast = true
                    repeat(3) {
                        for (dest in listOf("255.255.255.255", "192.168.178.255")) {
                            s.send(DatagramPacket(pacchetto, pacchetto.size, InetAddress.getByName(dest), 9))
                        }
                        Thread.sleep(100)
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }
}
