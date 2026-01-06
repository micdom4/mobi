package pl.lodz.p.edu.mobi.hydrotrack

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import java.util.Calendar

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Apply a Material 3 Theme
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0077B6), // Ocean Blue
                    secondary = Color(0xFF90E0EF), // Light Cyan
                    background = Color(0xFFCAF0F8) // Pale Blue
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WaterTrackerScreen(context = applicationContext)
                }
            }
        }
    }
}

// --- Notification Scheduler ---
object NotificationScheduler {
    val notificationHours = listOf(9, 13, 16, 20)
    private const val CHANNEL_ID = "water_reminder_channel"
    private const val DELAYED_NOTIFICATION_REQUEST_CODE = 200

    fun scheduleNotifications(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        for (hour in notificationHours) {
            val calendar = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)

                if (before(Calendar.getInstance())) {
                    add(Calendar.DATE, 1)
                }
            }

            val intent = Intent(context, NotificationReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                hour,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                calendar.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pendingIntent
            )
        }
    }

    fun scheduleDelayedNotification(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, NotificationReceiver::class.java)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            DELAYED_NOTIFICATION_REQUEST_CODE, // Unique request code
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerAtMillis = System.currentTimeMillis() + 10 * 1000 // 10 seconds

        alarmManager.set(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent
        )
    }

    fun sendTestNotification(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Water Reminder",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(context, 100, launchIntent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("HydroTrack Reminder")
            .setContentText("Have a fresh cup of water twin!")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)
    }

    fun getNextNotificationText(context: Context): String {
        val now = Calendar.getInstance()

        val nextHour = notificationHours.find { it > now.get(Calendar.HOUR_OF_DAY) }

        val nextAlarmTime = Calendar.getInstance().apply {
            if (nextHour != null) {
                set(Calendar.HOUR_OF_DAY, nextHour)
                set(Calendar.MINUTE, 0)
            } else {
                add(Calendar.DATE, 1)
                set(Calendar.HOUR_OF_DAY, notificationHours.first())
                set(Calendar.MINUTE, 0)
            }
            set(Calendar.SECOND, 0)
        }

        val diffMinutes = (nextAlarmTime.timeInMillis - now.timeInMillis) / 60000
        val hours = diffMinutes / 60
        val minutes = diffMinutes % 60
        val timeString = String.format("%02d:00", nextAlarmTime.get(Calendar.HOUR_OF_DAY))

        return when {
            hours > 0 -> "Next water intake is in $hours hours and $minutes minutes on $timeString"
            else -> "Next water intake is in $minutes minutes on $timeString"
        }
    }
}

// --- 1. The ViewModel (Logic Layer) ---
class WaterViewModel(private val context: Context) : ViewModel() {
    private val sharedPref = context.getSharedPreferences("water_prefs", Context.MODE_PRIVATE)

    var currentIntake by mutableIntStateOf(sharedPref.getInt("intake", 0))
        private set
    var dailyGoal by mutableIntStateOf(sharedPref.getInt("daily_goal", 2500))
        private set

    fun addWater(amount: Int) {
        currentIntake += amount
        saveData()
    }

    fun resetWater() {
        currentIntake = 0
        saveData()
    }

    fun updateDailyGoal(newGoal: Int) {
        if (newGoal > 0) {
            dailyGoal = newGoal
            saveData()
        }
    }

    private fun saveData() {
        sharedPref.edit {
            putInt("intake", currentIntake)
            putInt("daily_goal", dailyGoal)
        }
    }

    fun getProgress(): Float {
        if (dailyGoal == 0) return 0f
        return (currentIntake.toFloat() / dailyGoal).coerceIn(0f, 1f)
    }
}

// --- 2. The UI (Presentation Layer) ---
@Composable
fun WaterTrackerScreen(context: Context) {
    val viewModel = remember { WaterViewModel(context) }
    var hasNotificationPermission by remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            )
        } else {
            mutableStateOf(true)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
            hasNotificationPermission = isGranted
            if (isGranted) {
                NotificationScheduler.scheduleNotifications(context)
            }
        }
    )

    LaunchedEffect(key1 = hasNotificationPermission) {
        if (hasNotificationPermission) {
            NotificationScheduler.scheduleNotifications(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var showGoalDialog by remember { mutableStateOf(false) }

    val animatedProgress by animateFloatAsState(
        targetValue = viewModel.getProgress(),
        animationSpec = tween(durationMillis = 1000), label = "progress"
    )

    if (showGoalDialog) {
        GoalInputDialog(
            currentGoal = viewModel.dailyGoal,
            onDismiss = { showGoalDialog = false },
            onGoalSet = { newGoal ->
                viewModel.updateDailyGoal(newGoal)
                showGoalDialog = false
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Hydration Goal",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(onClick = { showGoalDialog = true }) {
                Icon(Icons.Default.Edit, "Edit Goal", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(250.dp)) {
            CircularProgressIndicator(
                progress = { 1f },
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                strokeWidth = 20.dp,
            )
            CircularProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 20.dp,
                strokeCap = StrokeCap.Round
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${viewModel.currentIntake} ml",
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "of ${viewModel.dailyGoal} ml",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WaterButton(text = "250ml", onClick = { viewModel.addWater(250) })
                WaterButton(text = "500ml", onClick = { viewModel.addWater(500) })
            }

            Button(
                onClick = { viewModel.resetWater() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(0.5f)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset")
            }

            Button(
                onClick = { NotificationScheduler.sendTestNotification(context) },
            ) {
                Text("Send Test Notification")
            }

            Button(
                onClick = {
                    NotificationScheduler.scheduleDelayedNotification(context)
                    Toast.makeText(context, "Notification scheduled in 10 seconds!", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("Send Notification in 10s")
            }

            if (hasNotificationPermission) {
                Text(
                    text = NotificationScheduler.getNextNotificationText(context),
                    color = Color.DarkGray
                )
            }
        }
    }
}

@Composable
fun GoalInputDialog(currentGoal: Int, onDismiss: () -> Unit, onGoalSet: (Int) -> Unit) {
    var text by remember { mutableStateOf(currentGoal.toString()) }
    var isError by remember { mutableStateOf(false) }

    fun validate(input: String) {
        val value = input.toIntOrNull()
        isError = value == null || value < 1000
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Your Daily Goal") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    validate(it)
                },
                label = { Text("Goal in ml") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = isError,
                supportingText = {
                    if (isError) {
                        Text(
                            text = "Goal must be at least 1000 ml.",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val newGoal = text.toIntOrNull()
                    if (newGoal != null && newGoal >= 1000) {
                        onGoalSet(newGoal)
                    } else {
                        validate(text)
                    }
                },
                enabled = !isError
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun WaterButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.height(60.dp).width(150.dp)
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(text = text, fontSize = 16.sp)
    }
}
