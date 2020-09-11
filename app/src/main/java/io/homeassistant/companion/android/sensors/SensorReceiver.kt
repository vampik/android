package io.homeassistant.companion.android.sensors

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import io.homeassistant.companion.android.common.dagger.GraphComponentAccessor
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.domain.integration.IntegrationUseCase
import io.homeassistant.companion.android.domain.integration.SensorRegistration
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SensorReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "SensorReceiver"
        val MANAGERS = listOf(
            ActivitySensorManager(),
            AudioSensorManager(),
            BatterySensorManager(),
            BluetoothSensorManager(),
            DNDSensorManager(),
            GeocodeSensorManager(),
            LastRebootSensorManager(),
            LightSensorManager(),
            NetworkSensorManager(),
            NextAlarmManager(),
            PhoneStateSensorManager(),
            PowerSensorManager(),
            PressureSensorManager(),
            ProximitySensorManager(),
            StepsSensorManager(),
            StorageSensorManager()
        )

        const val ACTION_REQUEST_SENSORS_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_SENSORS_UPDATE"
    }

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    @Inject
    lateinit var integrationUseCase: IntegrationUseCase

    private val chargingActions = listOf(
        Intent.ACTION_BATTERY_LOW,
        Intent.ACTION_BATTERY_OKAY,
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED
    )

    override fun onReceive(context: Context, intent: Intent) {

        DaggerSensorComponent.builder()
            .appComponent((context.applicationContext as GraphComponentAccessor).appComponent)
            .build()
            .inject(this)

        when (intent.action) {
            "android.app.action.NEXT_ALARM_CLOCK_CHANGED" -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(NextAlarmManager.nextAlarm.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Alarm Sensor disabled, skipping sensors update")
                    return
                }
            }
            "android.bluetooth.device.action.ACL_CONNECTED",
                "android.bluetooth.device.action.ACL_DISCONNECTED",
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(BluetoothSensorManager.bluetoothConnection.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Bluetooth Sensor disabled, skipping sensors update")
                    return
                }
            }
            Intent.ACTION_BATTERY_LOW,
                Intent.ACTION_BATTERY_OKAY,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(BatterySensorManager.batteryLevel.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Battery Sensor disabled, skipping sensors update")
                    return
                }
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(NetworkSensorManager.wifiConnection.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Wifi Connection Sensor disabled, skipping sensors update")
                    return
                }
            }
            Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_SCREEN_ON -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(PowerSensorManager.interactiveDevice.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Interactive Sensor disabled, skipping sensors update")
                    return
                }
            }
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(PowerSensorManager.doze.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Doze Mode Sensor disabled, skipping sensors update")
                    return
                }
            }
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(PowerSensorManager.powerSave.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Power Save Sensor disabled, skipping sensors update")
                    return
                }
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(PhoneStateSensorManager.phoneState.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Phone State Sensor disabled, skipping sensors update")
                    return
                }
            }
            AudioManager.ACTION_AUDIO_BECOMING_NOISY,
                AudioManager.ACTION_HEADSET_PLUG,
                AudioManager.RINGER_MODE_CHANGED_ACTION,
                AudioManager.ACTION_MICROPHONE_MUTE_CHANGED,
                AudioManager.ACTION_SPEAKERPHONE_STATE_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(AudioSensorManager.audioSensor.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Audio Sensor disabled, skipping sensors update")
                    return
                }
            }
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> {
                val sensorDao = AppDatabase.getInstance(context).sensorDao()
                val sensor = sensorDao.get(DNDSensorManager.dndSensor.id)
                if (sensor?.enabled != true) {
                    Log.d(TAG, "Do Not Disturb Sensor disabled, skipping sensors update")
                    return
                }
            }
        }

        ioScope.launch {
            updateSensors(context, integrationUseCase)
            if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
                intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
                intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {
                updateLocationSensor(context)
            }
            if (chargingActions.contains(intent.action)) {
                // Add a 5 second delay to perform another update so charging state updates completely.
                // This is necessary as the system needs a few seconds to verify the charger.
                delay(5000L)
                updateSensors(context, integrationUseCase)
            }
        }
    }

    suspend fun updateSensors(
        context: Context,
        integrationUseCase: IntegrationUseCase
    ) {
        val sensorDao = AppDatabase.getInstance(context).sensorDao()
        val enabledRegistrations = mutableListOf<SensorRegistration<Any>>()

        MANAGERS.forEach { manager ->
            try {
                manager.requestSensorUpdate(context)
            } catch (e: Exception) {
                Log.e(TAG, "Issue requesting updates for ${context.getString(manager.name)}", e)
            }
            manager.availableSensors.forEach { basicSensor ->
                val fullSensor = sensorDao.getFull(basicSensor.id)
                val sensor = fullSensor?.sensor

                // Register Sensors if needed
                if (sensor?.enabled == true && !sensor.registered && !sensor.type.isBlank()) {
                    val reg = fullSensor.toSensorRegistration()
                    reg.name = context.getString(basicSensor.name)
                    try {
                        integrationUseCase.registerSensor(reg)
                        sensor.registered = true
                        sensorDao.update(sensor)
                    } catch (e: Exception) {
                        Log.e(TAG, "Issue registering sensor: ${reg.uniqueId}", e)
                    }
                }
                if (sensor?.enabled == true && fullSensor != null && sensor?.registered) {
                    enabledRegistrations.add(fullSensor.toSensorRegistration())
                }
            }
        }

        if (enabledRegistrations.isNotEmpty()) {
            var success = false
            try {
                success = integrationUseCase.updateSensors(enabledRegistrations.toTypedArray())
            } catch (e: Exception) {
                Log.e(TAG, "Exception while updating sensors.", e)
            }

            // We failed to update a sensor, we should re register next time
            if (!success) {
                enabledRegistrations.forEach {
                    val sensor = sensorDao.get(it.uniqueId)
                    if (sensor != null) {
                        sensor.registered = false
                        sensorDao.update(sensor)
                    }
                }
            }
        } else Log.d(TAG, "Nothing to update")
    }

    suspend fun updateLocationSensor(
        context: Context
    ) {
        try {
            LocationSensorManager().requestSensorUpdate(context)
        } catch (e: Exception) {
            Log.e(TAG, "Issue requesting updates for ${context.getString(LocationSensorManager().name)}", e)
        }
    }
}
