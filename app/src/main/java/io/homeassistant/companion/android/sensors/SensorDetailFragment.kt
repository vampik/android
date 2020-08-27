package io.homeassistant.companion.android.sensors

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import androidx.core.os.postDelayed
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.preference.contains
import io.homeassistant.companion.android.R
import io.homeassistant.companion.android.common.dagger.GraphComponentAccessor
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.sensor.SensorDao

class SensorDetailFragment(
    private val sensorManager: SensorManager,
    private val sensorId: String
) :
    PreferenceFragmentCompat() {

    companion object {
        fun newInstance(
            sensorManager: SensorManager,
            sensorId: String
        ): SensorDetailFragment {
            return SensorDetailFragment(sensorManager, sensorId)
        }
    }

    private lateinit var sensorDao: SensorDao
    private val handler = Handler()
    private val refresh = object : Runnable {
        override fun run() {
            refreshSensorData()
            handler.postDelayed(this, 10000)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        DaggerSensorComponent
            .builder()
            .appComponent((activity?.application as GraphComponentAccessor).appComponent)
            .build()
            .inject(this)
        sensorDao = AppDatabase.getInstance(requireContext()).sensorDao()

        addPreferencesFromResource(R.xml.sensor_detail)

        findPreference<SwitchPreference>("enabled")?.let {
            val dao = sensorDao.get(sensorId)
            val perm = sensorManager.checkPermission(requireContext())
            if (dao == null) {
                it.isChecked = perm
            } else {
                it.isChecked = dao.enabled && perm
            }
            updateSensorEntity(it.isChecked)

            it.setOnPreferenceChangeListener { _, newState ->
                val isEnabled = newState as Boolean

                if (isEnabled && !sensorManager.checkPermission(requireContext())) {
                    requestPermissions(sensorManager.requiredPermissions(), 0)
                    return@setOnPreferenceChangeListener false
                }

                updateSensorEntity(isEnabled)
                this@SensorDetailFragment.refreshSensorData()

                return@setOnPreferenceChangeListener true
            }
        }
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed(refresh, 0)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    private fun refreshSensorData() {
        SensorWorker.start(requireContext())
        LocationWorker.start(requireContext())

        val sensorDao = AppDatabase.getInstance(requireContext()).sensorDao()
        val fullData = sensorDao.getFull(sensorId)
        if (fullData?.sensor == null)
            return
        val sensorData = fullData.sensor
        val attributes = fullData.attributes

        findPreference<Preference>("unique_id")?.let {
            it.isCopyingEnabled = true
            it.summary = sensorId
        }
        findPreference<Preference>("state")?.let {
            it.isCopyingEnabled = true
            when {
                !sensorData.enabled ->
                    it.summary = "Disabled"
                sensorData.unitOfMeasurement.isNullOrBlank() ->
                    it.summary = sensorData.state
                else ->
                    it.summary = sensorData.state + " " + sensorData.unitOfMeasurement
            }
        }
        findPreference<Preference>("device_class")?.let {
            it.isCopyingEnabled = true
            it.summary = sensorData.deviceClass
        }
        findPreference<Preference>("icon")?.let {
            it.isCopyingEnabled = true
            it.summary = sensorData.icon
        }

        findPreference<PreferenceCategory>("attributes")?.let {
            if (attributes.isNullOrEmpty())
                it.isVisible = false
            else {
                attributes.forEach { attribute ->
                    val key = "attribute_${attribute.name}"
                    val pref = findPreference(key) ?: Preference(requireContext())
                    pref.isCopyingEnabled = true
                    pref.key = key
                    pref.title = attribute.name
                    pref.summary = attribute.value
                    pref.isIconSpaceReserved = false

                    if (!it.contains(pref))
                        it.addPreference(pref)
                }
            }
        }
    }

    private fun updateSensorEntity(
        isEnabled: Boolean
    ) {
        val sensorEntity = sensorDao.get(sensorId)
        if (sensorEntity != null) {
            sensorEntity.enabled = isEnabled
            sensorDao.update(sensorEntity)
        }
        refreshSensorData()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        findPreference<SwitchPreference>("enabled")?.run {
            isChecked = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            updateSensorEntity(isChecked)
        }
    }
}
