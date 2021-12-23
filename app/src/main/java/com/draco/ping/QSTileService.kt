package com.draco.ping

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.WindowManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QSTileService : TileService() {
    companion object {
        const val PING_TIMEOUT = 200
    }

    private var running = false

    private val executorService: ExecutorService = Executors.newFixedThreadPool(32)

    private lateinit var clipboardManager: ClipboardManager
    private lateinit var noneDialog: AlertDialog

    override fun onBind(intent: Intent?): IBinder? {
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        noneDialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_title)
            .setMessage(R.string.dialog_none)
            .setPositiveButton(R.string.dialog_dismiss) { _, _ -> }
            .create()

        return super.onBind(intent)
    }

    private fun getExternalAddresses(): List<String> {
        val addresses = mutableListOf<String>()

        for (inter in NetworkInterface.getNetworkInterfaces()) {
            for (address in inter.inetAddresses) {
                val hostAddress = address.hostAddress ?: continue
                if (address !is Inet4Address || address.isLoopbackAddress)
                    continue
                addresses.add(hostAddress)
            }
        }

        return addresses
    }

    private fun scrapeAddresses(startAddress: String): List<String> {
        val reachableAddresses = Array<String?>(255) { null }
        val prefix: String = startAddress.substring(0, startAddress.lastIndexOf(".") + 1)

        val callables = mutableListOf<Callable<Unit>>()
        for (i in 0 until 256) {
            val testIp = prefix + i.toString()
            val address = InetAddress.getByName(testIp)
            val hostAddress = address.hostAddress ?: continue

            val callable = Callable {
                if (address.isReachable(PING_TIMEOUT)) {
                    val name = if (hostAddress == startAddress)
                        "*$hostAddress"
                    else
                        hostAddress
                    reachableAddresses[i] = name
                }
            }
            callables.add(callable)
        }

        executorService.invokeAll(callables)

        return reachableAddresses.mapNotNull { it }
    }

    private fun setRunning(status: Boolean) {
        when (status) {
            true -> {
                running = true
                qsTile.state = Tile.STATE_ACTIVE
                qsTile.updateTile()
            }
            false -> {
                running = false
                qsTile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    qsTile.subtitle = ""
                qsTile.updateTile()
            }
        }
    }

    override fun onClick() {
        super.onClick()

        if (running)
            return

        setRunning(true)

        val externalAddresses = getExternalAddresses()
        val scrapedAddresses = externalAddresses.map {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                qsTile.subtitle = it
                qsTile.updateTile()
            }
            scrapeAddresses(it)
        }

        val addresses = scrapedAddresses
            .flatten()
            .joinToString("\n")

        try {
            if (addresses.isEmpty()) {
                showDialog(noneDialog)
            } else {
                val dialog = AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_title)
                    .setMessage(addresses)
                    .setPositiveButton(R.string.dialog_dismiss) { _, _ -> }
                    .setNeutralButton(R.string.dialog_copy) { _, _ ->
                        val clipData = ClipData.newPlainText(
                            getString(R.string.dialog_title),
                            addresses
                        )
                        clipboardManager.setPrimaryClip(clipData)
                    }
                    .create()

                showDialog(dialog)
            }
        } catch (_: WindowManager.BadTokenException) {}

        setRunning(false)
    }
}