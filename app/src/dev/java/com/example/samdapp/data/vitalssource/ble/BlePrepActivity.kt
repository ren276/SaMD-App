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
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * The transparent dev-only activity that hosts one preparation step for the BLE source (B1,
 * addendum 3.3 to 3.6): the Nearby devices permission, or the companion-device association. It reads
 * its request from [BlePrepBridge], answers it once, and finishes. A request the source already
 * abandoned (Stop, screen exit, the ceiling, process death) is not answered. One thing is still done
 * for it: an association the system creates after the abandonment has its ID stored (the companion
 * object's `associationCreated`), because the system holds that association either way and the next
 * Start should find it rather than ask the human again.
 *
 * Reached only on API 33 and up: the source refuses below it before asking for anything (Q1).
 */
@AndroidEntryPoint
class BlePrepActivity : ComponentActivity() {

    @Inject lateinit var bridge: BlePrepBridge

    @Inject lateinit var associations: HubAssociations

    private var requestId = NO_REQUEST

    private var hubId = ""

    /** This instance, as the bridge routes the companion-device callbacks to it. */
    private val host = object : BlePrepBridge.Host {
        override fun showDialog(dialog: Any) {
            associationDialog.launch(IntentSenderRequest.Builder(dialog as IntentSender).build())
        }

        override fun finish() = this@BlePrepActivity.finish()
    }

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        answer(if (granted) BlePrepBridge.Answer.GRANTED else BlePrepBridge.Answer.REFUSED)
    }

    /** The secondary signal (3.3). RESULT_OK carries the created association, which completes the
     *  request through the same path as `onAssociationCreated`, whichever of the two arrives first. */
    private val associationDialog = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != RESULT_OK) {
            answer(BlePrepBridge.Answer.REFUSED)
            return@registerForActivityResult
        }
        val data = result.data
        if (data != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            createdAssociation(data)?.let { associationCreated(bridge, associations, requestId, hubId, it) }
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = intent.getLongExtra(EXTRA_REQUEST_ID, NO_REQUEST)
        val request = bridge.pending(requestId)
        if (request == null) {
            finish()
            return
        }
        hubId = request.hubId
        bridge.attach(requestId, host)
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

    override fun onDestroy() {
        bridge.detach(requestId, host)
        super.onDestroy()
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
        // The callback outlives this instance, so it holds no state of it: it reaches the live instance,
        // the recreated one after a rotation, only through the bridge.
        val bridge = bridge
        val associations = associations
        val id = requestId
        try {
            cdm.associate(request, ContextCompat.getMainExecutor(this), object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = bridge.dialogReady(id, intentSender)

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    associationCreated(bridge, associations, id, hubId, associationInfo)
                }

                override fun onFailure(error: CharSequence?) = bridge.associationFailed(id)
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

        /** The one path for both signals of a created association; the bridge lets only the first
         *  store the ID and answer. */
        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun associationCreated(
            bridge: BlePrepBridge,
            associations: HubAssociations,
            id: Long,
            hubId: String,
            info: AssociationInfo,
        ) = bridge.associationCreated(id) { associations.onCreated(hubId, info.id) }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun createdAssociation(data: Intent): AssociationInfo? =
            IntentCompat.getParcelableExtra(data, CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo::class.java)
    }
}
