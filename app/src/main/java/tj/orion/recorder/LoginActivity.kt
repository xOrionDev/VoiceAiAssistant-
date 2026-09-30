package tj.orion.recorder

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

/** One-time login. Session persists afterwards, so this shows only until signed in. */
class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val status = findViewById<TextView>(R.id.status)
        val btn = findViewById<Button>(R.id.btnLogin)

        btn.setOnClickListener {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) { status.text = "Введите email и пароль"; return@setOnClickListener }
            btn.isEnabled = false
            status.text = "Вход…"
            thread {
                val err = Auth.login(this, e, p)
                runOnUiThread {
                    btn.isEnabled = true
                    if (err == null) {
                        SyncScheduler.schedulePeriodic(this)
                        SyncScheduler.kickNow(this)
                        finish()
                    } else {
                        status.text = "Не удалось войти: $err"
                    }
                }
            }
        }
    }
}
