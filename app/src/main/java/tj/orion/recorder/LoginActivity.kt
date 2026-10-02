package tj.orion.recorder

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import kotlin.concurrent.thread

/** One-time login. Shows a persistent diagnostic log so failures are never hidden. */
class LoginActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val email = findViewById<EditText>(R.id.email)
        val password = findViewById<EditText>(R.id.password)
        val status = findViewById<TextView>(R.id.status)
        val btn = findViewById<Button>(R.id.btnLogin)
        val showPass = findViewById<CheckBox>(R.id.showPass)

        // Show whatever happened last (survives recreation / crash)
        status.text = Diag.read(this)

        showPass.setOnCheckedChangeListener { _, checked ->
            password.inputType = if (checked)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            password.setSelection(password.text.length)
        }

        btn.setOnClickListener {
            val e = email.text.toString().trim()
            val p = password.text.toString()
            if (e.isEmpty() || p.isEmpty()) {
                status.text = "Введите email и пароль"
                return@setOnClickListener
            }
            btn.isEnabled = false
            Diag.log(this, "tap Войти")
            thread {
                val err = Auth.login(this, e, p)
                runOnUiThread {
                    btn.isEnabled = true
                    status.text = Diag.read(this)
                    if (err == null) {
                        Diag.log(this, "go to main")
                        SyncScheduler.schedulePeriodic(this)
                        SyncScheduler.kickNow(this)
                        finish()
                    }
                }
            }
        }
    }
}
