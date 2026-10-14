package com.example.samdapp.data.vitalssource.ble

import android.Manifest
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * The transparent dev-only activity that hosts one preparation step for the BLE source (B1,
 * addendum 3.3 to 3.6): the Nearby devices permission, or the companion-device association. It reads
 * its request from [BlePrepBridge], answers it once, and finishes. A request the source already
 * abandoned (Stop, screen exit, the ceiling, process death) is not acted on.
 *
 * Reached only on API 33 and up: the source refuses below it before asking for anything (Q1).
 */
@AndroidEntryPoint
class BlePrepActivity : ComponentActivity() {

    @Inject lateinit var bridge: BlePrepBridge

    @Inject lateinit var associations: HubAssociations

    private var requestId = NO_REQUEST

    /** Set once the system dialog was shown: a failure after it is the human's refusal, before it
     *  the system's inability to run the step (Bluetooth or Location off, a CDM error). */
    private var dialogShown = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer(if (granted) BlePrepBridge.Answer.GRANTED else BlePrepBridge.Answer.REFUSED)
    }

    /** The secondary signal (3.3): only a cancel answers here; RESULT_OK is answered by
     *  `onAssociationCreated`, which also stores the ID. */
    private val associationDialog = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == RESULT_OK) finish() else answer(BlePrepBridge.Answer.REFUSED)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST)
        val request = bridge.pending(requestId)
        if (request == null) {
            finish()
            return
        }
        // Recreated with the dialog already up: its result comes back through the launchers.
        if (savedInstanceState != null) return
        when (request.kind) {
            BlePrepBridge.Kind.CONNECT_PERMISSION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) permission.launch(Manifest.permission.BLUETOOTH_CONNECT) else answer(BlePrepBridge.Answer.UNAVAILABLE)
            BlePrepBridge.Kind.ASSOCIATION ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) associate(request.hubId) else answer(BlePrepBridge.Answer.UNAVAILABLE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (bridge.pending(requestId) == null) finish()
    }

    /** Addendum 3.1 and 3.2: the vendor service UUID and the hub's local name filter the dialog;
     *  identity is proved later by the DIS serial. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun associate(hubId: String) {
        val cdm: CompanionDeviceManager? = getSystemService(CompanionDeviceManager::class.java)
        if (cdm == null || !associations.canAssociate()) {
            answer(BlePrepBridge.Answer.UNAVAILABLE)
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(ScanFilter.Builder().setServiceUuid(ParcelUuid(ControlPoint.VENDOR_SERVICE)).build())
            .setNamePattern(Pattern.compile("^SaMD-${Pattern.quote(hubId)}$"))
            .build()
        val request = AssociationRequest.Builder().addDeviceFilter(filter).setSingleDevice(true).build()
        // Neither callback needs this activity alive: a recreated instance finishes through its launcher.
        val bridge = bridge
        val associations = associations
        val id = requestId
        try {
            cdm.associate(request, ContextCompat.getMainExecutor(this), object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    dialogShown = true
                    associationDialog.launch(IntentSenderRequest.Builder(intentSender).build())
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    associations.onCreated(hubId, associationInfo.id)
                    bridge.complete(id, BlePrepBridge.Answer.GRANTED)
                    finish()
                }

                override fun onFailure(error: CharSequence?) {
                    answer(if (dialogShown) BlePrepBridge.Answer.REFUSED else BlePrepBridge.Answer.UNAVAILABLE)
                }
            })
        } catch (e: RuntimeException) {
            answer(BlePrepBridge.Answer.UNAVAILABLE)
        }
    }

    private fun answer(answer: BlePrepBridge.Answer) {
        bridge.complete(requestId, answer)
        finish()
    }

    companion object {
        private const val EXTRA_REQUEST_ID = "com.example.samdapp.ble.PREP_REQUEST_ID"
        private const val NO_REQUEST = -1L

        /** From the application context while CompounderScreen is visible, so not a background start
         *  (3.6); outside an activity a new-task flag is required. */
        fun intent(context: Context, request: BlePrepBridge.Request): Intent =
            Intent(context, BlePrepActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, request.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
