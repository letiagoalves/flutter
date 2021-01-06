package com.mutablelogic.mdns_plugin

import android.app.Activity
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.EventChannel.StreamHandler
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.*
import kotlin.collections.HashMap
import kotlin.collections.set
import kotlin.concurrent.schedule


class MDNSPlugin : FlutterPlugin, MethodCallHandler, StreamHandler, ActivityAware {
  private var applicationContext: Context? = null
  private var methodChannel: MethodChannel? = null
  private var eventChannel: EventChannel? = null
  private var nsdManager: NsdManager? = null

  var discoveryListener: DiscoveryListener? = null
  var sink: EventSink? = null
  var activity: Activity? = null
  val services : HashMap<String, NsdServiceInfo> = HashMap()

  companion object {
    /*
    @JvmStatic
    fun registerWith(registrar: Registrar) {
      MethodChannel(registrar.messenger(), "mdns_plugin").setMethodCallHandler(MDNSPlugin(registrar))
    }
    */

    fun mapFromServiceInfo(method: String, serviceInfo: NsdServiceInfo?):HashMap<String, Any> {
      val map = HashMap<String, Any>()
      map["method"] = method
      serviceInfo?.let {
        map["name"] = serviceInfo.serviceName
        map["type"] = serviceInfo.serviceType.removePrefix(".").removeSuffix(".") + "." // cleanup
        map["hostName"] = serviceInfo.host?.hostName ?: ""
        map["port"] = serviceInfo.port
        map["txt"] = serviceInfo.attributes

        val addr = serviceInfo.host?.hostAddress
        addr?.let {
          map["addr"] = listOf(addr, serviceInfo.port)
        }
      }
      return map
    }
  }

  override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
    this.applicationContext = binding.applicationContext;
    this.methodChannel = MethodChannel(binding.binaryMessenger, "mdns_plugin")
    this.eventChannel = EventChannel(binding.binaryMessenger, "mdns_plugin_delegate")
    this.eventChannel!!.setStreamHandler(this);
    this.methodChannel!!.setMethodCallHandler(this)
  }

  override fun onDetachedFromEngine(p0: FlutterPlugin.FlutterPluginBinding) {
    this.applicationContext = null;
    this.methodChannel?.setMethodCallHandler(null);
    this.methodChannel = null;
    this.eventChannel?.setStreamHandler(null);
    this.eventChannel = null;
  }

  override fun onMethodCall(call: MethodCall, result: Result) {
    when (call.method) {
      "getPlatformVersion" -> {
        result.success("Android ${android.os.Build.VERSION.RELEASE}")
      }
      "startDiscovery" -> {
        startDiscovery(result, call.argument<String>("serviceType")
                ?: "", call.argument<Boolean>("enableUpdating") ?: false)
      }
      "stopDiscovery" -> {
        stopDiscovery(result)
      }
      "resolveService" -> {
        resolveService(result, call.argument<String>("name")
                ?: "", call.argument<Boolean>("resolve") ?: false)
      }
      else -> {
        result.notImplemented()
      }
    }
  }

  override fun onListen(p0: Any?, sink: EventSink) {
    this.sink = sink
  }

  override fun onCancel(p0: Any?) {
    this.sink = null
  }

  private fun startDiscovery(result: Result, serviceType: String, enableUpdating: Boolean) {
    if(enableUpdating) {
      Log.w("MDNSPlugin", "startDiscovery: enableUpdating is currently ignored on the Android platform")
    }
    discoveryListener?.let {
      nsdManager?.stopServiceDiscovery(discoveryListener)
    }
    discoveryListener = DiscoveryListener(this);
    services.clear()
    nsdManager?.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    result.success(null);
  }

  private fun stopDiscovery(result: Result) {
    nsdManager?.stopServiceDiscovery(discoveryListener)
    discoveryListener = null
    services.clear()
    result.success(null);
  }

  private fun resolveService(result: Result, name: String, resolve: Boolean) {
    if(services.containsKey(name)) {
      if(resolve) {
        nsdManager?.resolveService(services.get(name), ResolveListener(this));
      } else {
        services.remove(name)
      }
    } else {
      Log.w("MDNSPlugin", "resolveService: missing service with name $name")
    }
  }

  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.activity = binding.activity;
    this.nsdManager = binding.activity.getSystemService(Context.NSD_SERVICE) as NsdManager
  }

  override fun onDetachedFromActivity() {
    this.activity = null;
    this.nsdManager = null;
  }

  override fun onDetachedFromActivityForConfigChanges() {
    this.activity = null;
    this.nsdManager = null;
  }

  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
    this.activity = binding.activity;
    this.nsdManager = binding.activity.getSystemService(Context.NSD_SERVICE) as NsdManager
  }
}

class ResolveListener(private val plugin: MDNSPlugin) : NsdManager.ResolveListener {
    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
      val serviceMap = MDNSPlugin.mapFromServiceInfo("onServiceResolved", serviceInfo)
      plugin.activity?.runOnUiThread(java.lang.Runnable {
        plugin.sink?.success(serviceMap)
      })
    }  
    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
      when (errorCode) {
        NsdManager.FAILURE_ALREADY_ACTIVE -> {
          // Resolve again after a short delay
          Timer("resolve", false).schedule(20) {
            plugin.discoveryListener?.onServiceFound(serviceInfo)
          }
        }
        else ->
          Log.d("MDNSPlugin", "onResolveFailed: Error $errorCode: $serviceInfo")
      }
    }
}

class DiscoveryListener(private val plugin: MDNSPlugin) : NsdManager.DiscoveryListener {
  override fun onDiscoveryStarted(serviceType: String?) {
    plugin.activity?.runOnUiThread(java.lang.Runnable {
      plugin.sink?.success(mapOf("method" to "onDiscoveryStarted"))
    })
  }
  override fun onDiscoveryStopped(serviceType: String) {
    plugin.activity?.runOnUiThread(java.lang.Runnable {
      plugin.sink?.success(mapOf("method" to "onDiscoveryStopped"))
    })
  }
  override fun onServiceFound(serviceInfo: NsdServiceInfo) {
    val serviceMap = MDNSPlugin.mapFromServiceInfo("onServiceFound", serviceInfo)
    val name = serviceInfo.serviceName
    name?.let {
      plugin.services.put(name, serviceInfo)
    }
    plugin.activity?.runOnUiThread(java.lang.Runnable {
      plugin.sink?.success(serviceMap)
    })
  }
  override fun onServiceLost(serviceInfo: NsdServiceInfo) {
    val serviceMap = MDNSPlugin.mapFromServiceInfo("onServiceRemoved", serviceInfo)
    plugin.services.remove(serviceInfo.serviceName)
    plugin.activity?.runOnUiThread(java.lang.Runnable {
      plugin.sink?.success(serviceMap)
    })
  }
  override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
    Log.d("MDNSPlugin", "onStartDiscoveryFailed: $serviceType: $errorCode")
  }
  override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {
    Log.d("MDNSPlugin", "onStopDiscoveryFailed: $serviceType: $errorCode")
  }
}
