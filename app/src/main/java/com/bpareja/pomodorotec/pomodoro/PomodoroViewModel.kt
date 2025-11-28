package com.bpareja.pomodorotec.pomodoro

import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.os.CountDownTimer
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.bpareja.pomodorotec.MainActivity
import com.bpareja.pomodorotec.PomodoroReceiver
import com.bpareja.pomodorotec.R
import com.bpareja.pomodorotec.utils.DataSyncManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName

enum class Phase {
    FOCUS, BREAK
}

class PomodoroViewModel(application: Application) : AndroidViewModel(application) {
    init {
        instance = this
    }
    // Singleton para acceder al ViewModel desde el BroadcastReceiver
    companion object {
        internal var instance: PomodoroViewModel? = null
        fun skipBreak() {
            instance?.startFocusSession()  // Saltar el descanso y comenzar sesión de concentración
        }
    }

    private val context = getApplication<Application>().applicationContext

    // Estados observables (LiveData)
    private val _timeLeft = MutableLiveData("25:00") // Tiempo inicial correcto
    val timeLeft: LiveData<String> = _timeLeft

    private val _isRunning = MutableLiveData(false) // Estado del timer
    val isRunning: LiveData<Boolean> = _isRunning

    private val _currentPhase = MutableLiveData(Phase.FOCUS)// Fase actual
    val currentPhase: LiveData<Phase> = _currentPhase

    private val _isSkipBreakButtonVisible = MutableLiveData(false)// Visibilidad botón saltar
    val isSkipBreakButtonVisible: LiveData<Boolean> = _isSkipBreakButtonVisible

    private val _progress = MutableLiveData(0f) // Progreso (0-1)
    val progress: LiveData<Float> = _progress

    // Variables de control del timer
    private var countDownTimer: CountDownTimer? = null

    // Variable de control de tiempo de prueba
    private var testingStartTimeSeconds: Int = 0

    // Declaración inicial con valores reales
    private var totalTimeInMillis: Long = 25 * 60 * 1000L // Tiempo total (25 min)
    private var timeRemainingInMillis: Long = 25 * 60 * 1000L // Tiempo inicial para FOCUS (25 min)

    // Función pública para cambiar el tiempo de inicio de prueba
    /**
     * Establece el tiempo restante en segundos para simular el inicio de la sesión.
     * Si se establece en 0, usa la duración completa.
     */
    fun setTestingStartTime(seconds: Int) {
        testingStartTimeSeconds = seconds
        // Es buena práctica reiniciar el contador al cambiar el tiempo de prueba
        resetTimer()
    }

    // ----------- FUNCIONES PRINCIPALES ------------

    fun startFocusSession() {
        countDownTimer?.cancel()
        _currentPhase.value = Phase.FOCUS

        val focusDuration = 25 * 60 * 1000L

        // Lógica de tiempo para Concentración
        timeRemainingInMillis = if (testingStartTimeSeconds > 0) {
            testingStartTimeSeconds.toLong() * 1000L
        } else {
            focusDuration
        }
        totalTimeInMillis = focusDuration // Siempre 25 min para el progreso

        // Ajustar el tiempo mostrado al tiempo restante (10 segundos si está en modo prueba)
        val initialMinutes = (timeRemainingInMillis / 1000) / 60
        val initialSeconds = (timeRemainingInMillis / 1000) % 60
        _timeLeft.value = String.format("%02d:%02d", initialMinutes, initialSeconds)

        // Simular progreso si se usa tiempo de prueba
        _progress.value = if (testingStartTimeSeconds > 0) {
            1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())
        } else {
            0f
        }

        _isSkipBreakButtonVisible.value = false
        showNotification("Inicio de Concentración", "La sesión de concentración ha comenzado.")
        startTimer()
    }

    private fun startBreakSession() {
        _currentPhase.value = Phase.BREAK

        val breakDuration = 5 * 60 * 1000L

        // Lógica de tiempo para Descanso
        timeRemainingInMillis = if (testingStartTimeSeconds > 0) {
            testingStartTimeSeconds.toLong() * 1000L
        } else {
            breakDuration
        }
        totalTimeInMillis = breakDuration // Siempre 5 min para el progreso

        // Ajustar el tiempo mostrado al tiempo restante (10 segundos si está en modo prueba)
        val initialMinutes = (timeRemainingInMillis / 1000) / 60
        val initialSeconds = (timeRemainingInMillis / 1000) % 60
        _timeLeft.value = String.format("%02d:%02d", initialMinutes, initialSeconds)

        // Simular progreso si se usa tiempo de prueba
        _progress.value = if (testingStartTimeSeconds > 0) {
            1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())
        } else {
            0f
        }

        _isSkipBreakButtonVisible.value = true
        showNotification("Inicio de Descanso", "La sesión de descanso ha comenzado.")
        startTimer()
    }

    fun startTimer() {
        countDownTimer?.cancel()
        _isRunning.value = true

        countDownTimer = object : CountDownTimer(timeRemainingInMillis, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                timeRemainingInMillis = millisUntilFinished
                val minutes = (millisUntilFinished / 1000) / 60
                val seconds = (millisUntilFinished / 1000) % 60
                _timeLeft.value = String.format("%02d:%02d", minutes, seconds)
                val progress = 1f - (millisUntilFinished.toFloat() / totalTimeInMillis.toFloat())
                _progress.value = progress

                // ----------- GUARDAR DATOS PARA EL WIDGET -------------
                updateWidgetData()
            }
            override fun onFinish() {
                _isRunning.value = false
                _progress.value = 1f
                // Reiniciar la variable de prueba al finalizar para evitar bucles de 10s
                setTestingStartTime(0)
                when (_currentPhase.value) {
                    Phase.FOCUS -> startBreakSession()
                    Phase.BREAK -> startFocusSession()
                    null -> {}
                }
            }
        }.start()
    }

    fun updateDurations(sessionDuration: Int, breakDuration: Int) {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = sessionDuration,
            breakDuration = breakDuration
        )
    }

    fun updateTimerData() {
        DataSyncManager.sendPomodoroData(
            context = getApplication(),
            sessionDuration = 25,
            breakDuration = 5
        )
    }

    fun pauseTimer() {
        countDownTimer?.cancel()
        _isRunning.value = false
        // Actualizar notificación si quieres aquí
    }

    fun resetTimer() {
        countDownTimer?.cancel()
        _isRunning.value = false
        _currentPhase.value = Phase.FOCUS
        // Reinicio a valores reales
        timeRemainingInMillis = 25 * 60 * 1000L
        totalTimeInMillis = 25 * 60 * 1000L
        _timeLeft.value = "25:00"
        _progress.value = 0f
        _isSkipBreakButtonVisible.value = false
        // Actualizar widget aquí también si quieres
        updateWidgetData()
    }

    // -------------- ACTUALIZACIÓN DE WIDGET -----------------

    private fun updateWidgetData() {
        // Guarda datos en SharedPreferences
        val prefs = context.getSharedPreferences("pomodoro_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("phase", _currentPhase.value?.let { if (it == Phase.FOCUS) "Concentración" else "Descanso" } ?: "Concentración")
            // Mostrar tiempo restante real en el widget
            val minutes = (timeRemainingInMillis / 1000) / 60
            val seconds = (timeRemainingInMillis / 1000) % 60
            putString("timeLeft", String.format("%02d:%02d", minutes, seconds))
            putInt("progress", ((1f - (timeRemainingInMillis.toFloat() / totalTimeInMillis.toFloat())) * 100).toInt())
            apply()
        }
        // Fuerza actualización de widget
        val intent = Intent(context, com.bpareja.pomodorotec.PomodoroWidgetProvider::class.java)
        intent.action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        val ids = AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, com.bpareja.pomodorotec.PomodoroWidgetProvider::class.java))
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        context.sendBroadcast(intent)
    }

    // ----------------- NOTIFICACIÓN AVANZADA ------------------------

    private fun showNotification(title: String, message: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        val customTitle = when (_currentPhase.value) {
            Phase.FOCUS -> "🎯 ¡Tiempo de Concentración!"
            Phase.BREAK -> "☕ ¡Momento de Descanso!"
            else -> title
        }
        val formattedTime = _timeLeft.value?.let { if (it != "00:00") it else "Finalizado" } ?: "25:00"
        val customMessage = when (_currentPhase.value) {
            Phase.FOCUS -> "⏰ Restan $formattedTime\n💪 ¡Mantén el enfoque!"
            Phase.BREAK -> "⏰ Restan $formattedTime\n🧘‍♂️ ¡Relájate unos minutos!"
            else -> message
        }

        // 4. Estilo de Imagen Expandida (BigPictureStyle)
        val bigImage = BitmapFactory.decodeResource(
            context.resources,
            if (_currentPhase.value == Phase.FOCUS) R.drawable.focus_image
            else R.drawable.break_image
        )
        val style = NotificationCompat.BigPictureStyle().bigPicture(bigImage)

        // 2. Colores y Luces de notificación
        val notificationColor = if (_currentPhase.value == Phase.FOCUS) Color.rgb(178, 34, 34) else Color.rgb(46, 139, 87)

        // 6. Patrón de Vibración
        val vibrationPattern = if (_currentPhase.value == Phase.FOCUS)
            longArrayOf(0, 100, 100, 100)
        else
            longArrayOf(0, 500, 500)

        // Intents para acciones (3. Interacción Directa)
        val pauseIntent = Intent(context, PomodoroReceiver::class.java).apply { action = "PAUSE_TIMER" }
        val pausePendingIntent = PendingIntent.getBroadcast(
            context, 1, pauseIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(context, PomodoroReceiver::class.java).apply { action = "RESUME_TIMER" }
        val resumePendingIntent = PendingIntent.getBroadcast(
            context, 2, resumeIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val skipIntent = Intent(context, PomodoroReceiver::class.java).apply { action = "SKIP_BREAK" }
        val skipPendingIntent = PendingIntent.getBroadcast(
            context, 3, skipIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = Intent(context, PomodoroReceiver::class.java).apply { action = "END_TIMER" }
        val endPendingIntent = PendingIntent.getBroadcast(
            context, 4, endIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val progress = ((timeRemainingInMillis * 100) / totalTimeInMillis).toInt()

        val builder = NotificationCompat.Builder(context, MainActivity.CHANNEL_ID)
            // 1. Icono Pequeño de fase
            .setSmallIcon(
                if (_currentPhase.value == Phase.FOCUS) R.drawable.baseline_center_focus_strong_24
                else R.drawable.baseline_free_breakfast_24
            )
            .setContentTitle(customTitle)
            .setContentText(customMessage)
            .setStyle(style) // Aplica el estilo de imagen
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setColor(notificationColor) // Aplica el color
            .setColorized(true)
            .setLights(notificationColor, 1000, 1000)
            .setVibrate(vibrationPattern) // Aplica vibración
            .setProgress(100, progress, false) // 5. Barra de progreso
            .setSound( // Aplica sonido
                RingtoneManager.getDefaultUri(
                    if (_currentPhase.value == Phase.FOCUS) RingtoneManager.TYPE_RINGTONE
                    else RingtoneManager.TYPE_NOTIFICATION
                )
            )
            // Botones de acción (3. Interacción Directa)
            .addAction(R.drawable.baseline_pause_circle_24, "Pausar", pausePendingIntent)
            .addAction(R.drawable.ic_resume, "Reanudar", resumePendingIntent)
            .addAction(R.drawable.ic_stop, "Terminar", endPendingIntent)

        if (_currentPhase.value == Phase.BREAK) {
            builder.addAction(
                R.drawable.ic_skip,
                "Saltar Descanso",
                skipPendingIntent
            )
        }

        with(NotificationManagerCompat.from(context)) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                notify(MainActivity.NOTIFICATION_ID, builder.build())
            }
        }
    }
}