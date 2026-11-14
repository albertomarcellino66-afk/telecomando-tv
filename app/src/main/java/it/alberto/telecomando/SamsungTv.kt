package it.alberto.telecomando

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
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
class SamsungTv(context: Context, private val avvisa: (Stato) -> Unit) {

    enum class Stato { NON_COLLEGATO, IN_COLLEGAMENTO, ATTESA_AUTORIZZAZIONE, COLLEGATO, RIFIUTATO }

    companion object {
        const val TV_IP = "192.168.178.47"
        const val TV_MAC = "64:07:F6:AF:C1:A8"
        const val PORTA = 8002
        const val NOME_APP = "Telecomando"

        const val NETFLIX = "3201907018807"
        const val PRIME_VIDEO = "3201910019365"
        const val YOUTUBE = "111299001912"
    }

    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    private val principale = Handler(Looper.getMainLooper())
    private val inAttesa = mutableListOf<String>()

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

    private fun cambia(nuovo: Stato) {
        principale.post {
            stato = nuovo
            avvisa(nuovo)
        }
    }

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
                    }
                    "ms.channel.unauthorized" -> {
                        cambia(Stato.RIFIUTATO)
                        webSocket.close(1000, null)
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = chiuso(webSocket)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = chiuso(webSocket)
        })
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

    fun apriApp(appId: String) {
        val data = JSONObject().put("appId", appId).put("action_type", "DEEP_LINK")
        val params = JSONObject().put("event", "ed.apps.launch").put("to", "host").put("data", data)
        invia(JSONObject().put("method", "ms.channel.emit").put("params", params).toString())
    }

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
