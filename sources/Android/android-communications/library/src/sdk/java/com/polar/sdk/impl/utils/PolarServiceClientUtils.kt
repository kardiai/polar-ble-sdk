package com.polar.sdk.impl.utils

import com.polar.androidcommunications.api.ble.BleDeviceListener
import com.polar.androidcommunications.api.ble.model.BleDeviceSession
import com.polar.androidcommunications.api.ble.model.BleDeviceSession.DeviceSessionState
import com.polar.androidcommunications.api.ble.model.gatt.BleGattBase
import com.polar.androidcommunications.api.ble.model.gatt.client.BleHrClient
import com.polar.androidcommunications.api.ble.model.gatt.client.BleHrClient.Companion.HR_MEASUREMENT
import com.polar.androidcommunications.api.ble.model.gatt.client.BleHrClient.Companion.HR_SERVICE
import com.polar.androidcommunications.api.ble.model.gatt.client.BleMdsClient
import com.polar.androidcommunications.api.ble.model.gatt.client.BlePfcClient
import com.polar.androidcommunications.api.ble.model.gatt.client.BlePfcClient.Companion.PFC_SERVICE
import com.polar.androidcommunications.api.ble.model.gatt.client.pmd.BlePMDClient
import com.polar.androidcommunications.api.ble.model.gatt.client.psftp.BlePsFtpClient
import com.polar.androidcommunications.api.ble.model.gatt.client.psftp.BlePsFtpUtils
import com.polar.sdk.api.errors.PolarDeviceDisconnected
import com.polar.sdk.api.errors.PolarDeviceNotFound
import com.polar.sdk.api.errors.PolarInvalidArgument
import com.polar.sdk.api.errors.PolarNotificationNotEnabled
import com.polar.sdk.api.errors.PolarServiceNotAvailable
import java.util.UUID

internal object PolarServiceClientUtils {

    @Throws(Throwable::class)
    internal fun sessionHrClientReady(identifier: String, listener: BleDeviceListener?): BleDeviceSession {
        val session = sessionServiceReady(identifier, HR_SERVICE, listener)
        val client = session.fetchClient(HR_SERVICE) as BleHrClient? ?: throw PolarServiceNotAvailable()
        val hrMeasurementChr = client.getNotificationAtomicInteger(HR_MEASUREMENT)
        if (hrMeasurementChr != null && hrMeasurementChr.get() == BleGattBase.ATT_SUCCESS) {
            return session
        }
        throw PolarNotificationNotEnabled()
    }

    @Throws(Throwable::class)
    internal fun sessionPmdClientReady(identifier: String, listener: BleDeviceListener?): BleDeviceSession {
        val session = sessionServiceReady(identifier, BlePMDClient.PMD_SERVICE, listener)
        val client = session.fetchClient(BlePMDClient.PMD_SERVICE) as BlePMDClient? ?: throw PolarServiceNotAvailable()
        val pair = client.getNotificationAtomicInteger(BlePMDClient.PMD_CP)
        val pairData = client.getNotificationAtomicInteger(BlePMDClient.PMD_DATA)
        if (pair != null && pairData != null && pair.get() == BleGattBase.ATT_SUCCESS && pairData.get() == BleGattBase.ATT_SUCCESS) {
            return session
        }
        throw PolarNotificationNotEnabled()
    }

    @Throws(Throwable::class)
    internal fun sessionPsFtpClientReady(identifier: String, listener: BleDeviceListener?): BleDeviceSession {
        val session = sessionServiceReady(identifier, BlePsFtpUtils.RFC77_PFTP_SERVICE, listener)
        val client = session.fetchClient(BlePsFtpUtils.RFC77_PFTP_SERVICE) as BlePsFtpClient? ?: throw PolarServiceNotAvailable()
        val pair = client.getNotificationAtomicInteger(BlePsFtpUtils.RFC77_PFTP_MTU_CHARACTERISTIC)
        if (pair != null && pair.get() == BleGattBase.ATT_SUCCESS) {
            return session
        }
        throw PolarNotificationNotEnabled()
    }

    @Throws(Throwable::class)
    internal fun sessionPsPfcClientReady(identifier: String, listener: BleDeviceListener?): BleDeviceSession {
        val session = sessionServiceReady(identifier, PFC_SERVICE, listener)
        val client = session.fetchClient(PFC_SERVICE) as BlePfcClient? ?: throw PolarServiceNotAvailable()
        if (client.isServiceDiscovered) {
            return session
        }
        throw PolarNotificationNotEnabled()
    }

    @Throws(Throwable::class)
    internal fun sessionMdsClientReady(identifier: String, listener: BleDeviceListener?): BleDeviceSession {
        val session =  sessionServiceReady(identifier, BleMdsClient.MDS_SERVICE, listener)
        val client = session.fetchClient(BleMdsClient.MDS_SERVICE) as BleMdsClient? ?: throw PolarServiceNotAvailable()
        if (client.isServiceDiscovered) {
            return session
        }
        throw PolarNotificationNotEnabled()
    }

    @Throws(Throwable::class)
    internal fun sessionServiceReady(identifier: String, service: UUID, listener: BleDeviceListener?): BleDeviceSession {
        val session = fetchSession(identifier, listener)
            ?: throw PolarDeviceNotFound()

        if (session.sessionState != DeviceSessionState.SESSION_OPEN) {
            throw PolarDeviceDisconnected()
        }

        val client = session.fetchClient(service)
            ?: throw PolarServiceNotAvailable()

        val timeoutMillis = 10 * 1000L
        if (!client.isServiceDiscovered) {
            if (!waitForServiceDiscovery(client, timeoutMillis)) {
                throw PolarServiceNotAvailable()
            }
        }

        return session
    }

    @Throws(PolarInvalidArgument::class)
    internal fun fetchSession(identifier: String, listener: BleDeviceListener?): BleDeviceSession? {
        if (identifier.matches(Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$"))) {
            return sessionByAddress(identifier, listener)
        } else if (identifier.matches(Regex("([0-9a-fA-F]){6,8}"))) {
            return sessionByDeviceId(identifier, listener)
        }
        throw PolarInvalidArgument()
    }

    internal fun getRSSIValue(deviceId: String, listener: BleDeviceListener?): Int {
        return sessionByDeviceId(deviceId, listener)?.rssi ?: -1
    }

    /**
     * A device id does not always name one session. Sessions are also created from the phone's
     * bonded devices, with the device id taken from the bonded name, and a phone can hold several
     * bonds for one device under different addresses (the address of an H10 changes, the bond
     * stays). The first match used to be answered, in the order of a HashSet: a connect dialled
     * an address nobody was on, and a stream or a disconnect could land on a session other than
     * the one that was open.
     *
     * The open session is answered before any other, then one the device has advertised from,
     * and a bond that was never heard only when there is nothing else.
     */
    private fun sessionByDeviceId(deviceId: String, listener: BleDeviceListener?): BleDeviceSession? {
        val matching = listener?.deviceSessions()
            ?.filterNotNull()
            ?.filter { session -> session.advertisementContent.polarDeviceId == deviceId }
            .orEmpty()
        return matching.singleOrNull() ?: matching.maxByOrNull { session -> preference(session) }
    }

    private fun preference(session: BleDeviceSession): Int {
        var preference = 0
        if (session.sessionState == DeviceSessionState.SESSION_OPEN) preference += 4
        if (session.advertisementContent.hasAdvertised) preference += 2
        if (session.sessionState != DeviceSessionState.SESSION_CLOSED) preference += 1
        return preference
    }

    private fun sessionByAddress(address: String, listener: BleDeviceListener?): BleDeviceSession? {
        listener?.let {
            val sessions = it.deviceSessions()
            if (sessions != null) {
                for (session in sessions) {
                    if (session != null) {
                        if (session.address == address) {
                            return session
                        }
                    }
                }
            }
        }
        return null
    }

    private fun waitForServiceDiscovery(client: BleGattBase, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (!client.isServiceDiscovered) {
            if (System.currentTimeMillis() - start > timeoutMs) {
                return false
            }
            try {
                Thread.sleep(100)
            } catch (ie: InterruptedException) {
                return false
            }
        }
        return true
    }
}