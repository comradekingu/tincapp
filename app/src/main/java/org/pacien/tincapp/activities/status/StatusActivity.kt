/*
 * Tinc App, an Android binding and user interface for the tinc mesh VPN daemon
 * Copyright (C) 2017-2018 Pacien TRAN-GIRARD
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.pacien.tincapp.activities.status

import android.content.Intent
import android.os.Bundle
import android.support.v4.widget.SwipeRefreshLayout
import android.support.v7.app.AlertDialog
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import java8.util.concurrent.CompletableFuture
import kotlinx.android.synthetic.main.base.*
import kotlinx.android.synthetic.main.fragment_list_view.*
import kotlinx.android.synthetic.main.status_activity_list_header.*
import kotlinx.android.synthetic.main.status_node_info_dialog.view.*
import org.pacien.tincapp.R
import org.pacien.tincapp.activities.BaseActivity
import org.pacien.tincapp.activities.StartActivity
import org.pacien.tincapp.activities.common.ProgressModal
import org.pacien.tincapp.activities.viewlog.ViewLogActivity
import org.pacien.tincapp.commands.Executor
import org.pacien.tincapp.commands.Tinc
import org.pacien.tincapp.extensions.Android.setElements
import org.pacien.tincapp.intent.Actions
import org.pacien.tincapp.intent.BroadcastMapper
import org.pacien.tincapp.service.TincVpnService
import java.util.*
import kotlin.concurrent.timerTask

/**
 * @author pacien
 */
class StatusActivity : BaseActivity(), AdapterView.OnItemClickListener, SwipeRefreshLayout.OnRefreshListener {
  private val broadcastMapper = BroadcastMapper(mapOf(Actions.EVENT_DISCONNECTED to this::onVpnShutdown))
  private var shutdownDialog: AlertDialog? = null
  private var nodeListAdapter: ArrayAdapter<String>? = null
  private var refreshTimer: Timer? = null
  private var listNetworksAfterExit = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    nodeListAdapter = ArrayAdapter(this, R.layout.fragment_list_item)

    layoutInflater.inflate(R.layout.fragment_list_view, main_content)
    list_wrapper.setOnRefreshListener(this)
    list.addHeaderView(layoutInflater.inflate(R.layout.status_activity_list_header, list, false), null, false)
    list.addFooterView(View(this), null, false)
    list.onItemClickListener = this
    list.adapter = nodeListAdapter

    if (intent.action == Actions.ACTION_DISCONNECT) {
      listNetworksAfterExit = false
      stopVpn()
    } else {
      listNetworksAfterExit = true
    }
  }

  override fun onCreateOptionsMenu(m: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_status, m)
    return super.onCreateOptionsMenu(m)
  }

  override fun onDestroy() {
    super.onDestroy()
    nodeListAdapter = null
    refreshTimer = null
  }

  override fun onStart() {
    super.onStart()
    refreshTimer = Timer(true)
    refreshTimer?.schedule(timerTask { updateView() }, NOW, REFRESH_RATE)
  }

  override fun onStop() {
    refreshTimer?.cancel()
    super.onStop()
  }

  override fun onResume() {
    super.onResume()
    broadcastMapper.register()
    updateView()
    handleRecentCrash()
  }

  override fun onPause() {
    broadcastMapper.unregister()
    super.onPause()
  }

  override fun onRefresh() {
    refreshTimer?.schedule(timerTask { updateView() }, NOW)
  }

  override fun onItemClick(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = when (view) {
    is TextView -> showNodeInfo(view.text.toString())
    else -> Unit
  }

  private fun onVpnShutdown() {
    shutdownDialog?.dismiss()
    if (listNetworksAfterExit) openStartActivity()
    finish()
  }

  fun stopVpn(@Suppress("UNUSED_PARAMETER") i: MenuItem? = null) {
    refreshTimer?.cancel()
    list_wrapper.isRefreshing = false
    shutdownDialog = ProgressModal.show(this, getString(R.string.message_disconnecting_vpn))
    TincVpnService.disconnect()
  }

  fun openLogViewer(@Suppress("UNUSED_PARAMETER") i: MenuItem) =
    startActivity(Intent(this, ViewLogActivity::class.java))

  private fun writeNodeList(nodeList: List<String>) {
    nodeListAdapter?.setElements(nodeList)
    status_activity_node_list_placeholder.visibility = View.GONE
    list_wrapper.isRefreshing = false
  }

  private fun updateNodeList() {
    getNodeNames().thenAccept { nodeList -> runOnUiThread { writeNodeList(nodeList) } }
  }

  private fun showNodeInfo(nodeName: String) {
    val dialogTextView = layoutInflater.inflate(R.layout.status_node_info_dialog, main_content, false)

    runOnUiThread {
      AlertDialog.Builder(this)
        .setTitle(R.string.status_node_info_dialog_title)
        .setView(dialogTextView)
        .setPositiveButton(R.string.status_node_info_dialog_close_action) { _, _ -> Unit }
        .show()
    }

    TincVpnService.getCurrentNetName()?.let { netName ->
      Tinc.info(netName, nodeName).thenAccept { nodeInfo ->
        runOnUiThread { dialogTextView.dialog_node_details.text = nodeInfo }
      }
    }
  }

  private fun updateView() = when {
    TincVpnService.isConnected() -> updateNodeList()
    else -> openStartActivity()
  }

  private fun openStartActivity() {
    startActivity(Intent(this, StartActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    finish()
  }

  companion object {
    private const val REFRESH_RATE = 5000L
    private const val NOW = 0L

    fun getNodeNames(): CompletableFuture<List<String>> = TincVpnService.getCurrentNetName()?.let { netName ->
      Tinc.dumpNodes(netName).thenApply<List<String>> { list -> list.map { it.substringBefore(' ') } }
    } ?: Executor.supplyAsyncTask<List<String>> { emptyList() }
  }
}