package it.alberto.telecomando

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tv: SamsungTv
    private lateinit var statoTesto: TextView
    private lateinit var frecce: View
    private lateinit var tastierino: View
    private lateinit var bottone123: Button
    private val ripeti = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statoTesto = findViewById(R.id.stato)
        frecce = findViewById(R.id.frecce)
        tastierino = findViewById(R.id.tastierino)
        bottone123 = findViewById(R.id.b_123)

        tv = SamsungTv(this, { mostraStato(it) }, { Toast.makeText(this, it, Toast.LENGTH_LONG).show() })

        // Tasti semplici
        mapOf(
            R.id.b_su to "KEY_UP",
            R.id.b_giu to "KEY_DOWN",
            R.id.b_sinistra to "KEY_LEFT",
            R.id.b_destra to "KEY_RIGHT",
            R.id.b_ok to "KEY_ENTER",
            R.id.b_indietro to "KEY_RETURN",
            R.id.b_home to "KEY_HOME",
            R.id.b_muto to "KEY_MUTE",
            R.id.b_0 to "KEY_0", R.id.b_1 to "KEY_1", R.id.b_2 to "KEY_2",
            R.id.b_3 to "KEY_3", R.id.b_4 to "KEY_4", R.id.b_5 to "KEY_5",
            R.id.b_6 to "KEY_6", R.id.b_7 to "KEY_7", R.id.b_8 to "KEY_8",
            R.id.b_9 to "KEY_9",
        ).forEach { (id, codice) ->
            findViewById<View>(id).setOnClickListener { v -> vibra(v); tv.tasto(codice) }
        }

        // Volume e canali: tenendo premuto il comando si ripete
        mapOf(
            R.id.b_vol_su to "KEY_VOLUP",
            R.id.b_vol_giu to "KEY_VOLDOWN",
            R.id.b_can_su to "KEY_CHUP",
            R.id.b_can_giu to "KEY_CHDOWN",
        ).forEach { (id, codice) -> conRipetizione(findViewById(id), codice) }

        // App
        findViewById<View>(R.id.b_netflix).setOnClickListener { vibra(it); tv.apriApp(SamsungTv.NETFLIX) }
        findViewById<View>(R.id.b_prime).setOnClickListener { vibra(it); tv.apriApp(SamsungTv.PRIME_VIDEO) }
        findViewById<View>(R.id.b_youtube).setOnClickListener { vibra(it); tv.apriApp(SamsungTv.YOUTUBE) }

        // Tenendo premuto un tasto app si vede l'elenco delle app del televisore (per diagnosi)
        listOf(R.id.b_netflix, R.id.b_prime, R.id.b_youtube).forEach { id ->
            findViewById<View>(id).setOnLongClickListener {
                AlertDialog.Builder(this)
                    .setTitle("App sul televisore")
                    .setMessage(tv.descriviAppInstallate())
                    .setPositiveButton("OK", null)
                    .show()
                true
            }
        }

        // Accensione: se il televisore risponde lo spegne, se è spento lo risveglia
        findViewById<View>(R.id.b_power).setOnClickListener {
            vibra(it)
            if (tv.stato == SamsungTv.Stato.COLLEGATO) {
                tv.tasto("KEY_POWER")
            } else {
                tv.accendiDaSpento()
                ripeti.postDelayed({ tv.collega() }, 4000)
            }
        }

        // Tastierino numerico al posto delle frecce
        bottone123.setOnClickListener {
            vibra(it)
            val mostra = tastierino.visibility != View.VISIBLE
            tastierino.visibility = if (mostra) View.VISIBLE else View.GONE
            frecce.visibility = if (mostra) View.GONE else View.VISIBLE
            bottone123.text = if (mostra) "Frecce" else "123"
        }

        statoTesto.setOnClickListener { tv.collega() }
    }

    override fun onResume() {
        super.onResume()
        tv.collega()
    }

    override fun onPause() {
        super.onPause()
        ripeti.removeCallbacksAndMessages(null)
        tv.scollega()
    }

    private fun vibra(v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun conRipetizione(bottone: View, codice: String) {
        val azione = object : Runnable {
            override fun run() {
                tv.tasto(codice)
                ripeti.postDelayed(this, 180)
            }
        }
        bottone.setOnTouchListener { v, evento ->
            when (evento.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    vibra(v)
                    tv.tasto(codice)
                    ripeti.postDelayed(azione, 500)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    ripeti.removeCallbacks(azione)
                    if (evento.action == MotionEvent.ACTION_UP) v.performClick()
                }
            }
            true
        }
    }

    private fun mostraStato(stato: SamsungTv.Stato) {
        val (testo, colore) = when (stato) {
            SamsungTv.Stato.COLLEGATO -> "● Collegato a Samsung Salotto" to 0xFF4CAF50.toInt()
            SamsungTv.Stato.IN_COLLEGAMENTO -> "● Collegamento in corso…" to 0xFFFFC107.toInt()
            SamsungTv.Stato.ATTESA_AUTORIZZAZIONE -> "● Accetta la richiesta sul televisore" to 0xFFFFC107.toInt()
            SamsungTv.Stato.RIFIUTATO -> "● Il televisore ha rifiutato l'accesso" to 0xFFF44336.toInt()
            SamsungTv.Stato.NON_COLLEGATO -> "● Televisore non raggiungibile (tocca per riprovare)" to 0xFF9E9E9E.toInt()
        }
        statoTesto.text = testo
        statoTesto.setTextColor(colore)
    }
}
