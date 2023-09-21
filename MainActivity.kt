package alcorp.com.billingtutorial

import android.app.Application
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView

import kotlinx.coroutines.GlobalScope

class MainActivity : AppCompatActivity(){
    private lateinit var Texte:TextView
    private lateinit var Subscribe:Button
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Texte=findViewById(R.id.Etat)
        Subscribe=findViewById(R.id.abonnement)
        val billingManager = BillingManager.getInstance(application= Application(), defaultScope = GlobalScope,arrayOf(),arrayOf("subscribetest"),
            arrayOf())
        Subscribe.setOnClickListener {
            billingManager.launchBillingFlow(this,"subscribetest",0)


        }
            }

}
