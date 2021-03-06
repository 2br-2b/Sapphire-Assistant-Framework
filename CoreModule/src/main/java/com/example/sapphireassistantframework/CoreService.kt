package com.example.sapphireassistantframework

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.componentframework.SAFService
import org.json.JSONObject
import java.util.*

class CoreService: SAFService(){
    private var connections: LinkedList<Pair<String, Connection>> = LinkedList()
    private lateinit var notificationManager: NotificationManager
    private val CHANNEL_ID = "SAF"
    private val NAME = "Sapphire Assistant Framework"
    private val SERVICE_TEXT = "Sapphire Assistant Framework"
    private lateinit var sapphire_apps: LinkedList<Pair<String, String>>

    /*
    These could be static, to be shared between this and CoreRegistrationService
     */
    private val CONFIG = "core.conf"
    private val DATABASE = "core.db"

    // These are table names
    private val DEFAULT_MODULES_TABLE = "defaultmodules.tbl"
    private val BACKGROUND_TABLE = "background.tbl"
    private val ROUTE_TABLE = "routetable.tbl"
    private val ALIAS_TABLE = "alias.tbl"

    // These are the config modules
    var jsonDefaultModules = JSONObject()
    var jsonBackgroundTable = JSONObject()
    var jsonRouteTable = JSONObject()
    var jsonAliasTable = JSONObject()
    // This is the outgoing postage
    var jsonPostageTable = JSONObject()

    var readyToGoSemaphore = false
    var SapphireModuleStack = LinkedList<Intent>()

    override fun onCreate() {
        // Do all startup tasks
        var configJSON = parseConfigFile(CONFIG)
        coreReadConfigJSON(configJSON)
        buildForegroundNotification()
        // This is where it is checking modules
        startRegistrationService()
        super.onCreate()
    }

    // Run through the registration process
    fun startRegistrationService(){
        var registrationIntent = Intent().setClassName(this.packageName,"${this.packageName}.CoreRegistrationService")
        Log.v(this.javaClass.name,"starting service ${"${this.packageName}.CoreRegistrationService"}")
        startService(registrationIntent)
    }


    fun coreReadConfigJSON(jsonConfig: JSONObject){
        // These should be global vars, so I really just need to load them.
        // I need to account for just directly loading these, not having them in a separate config
        jsonDefaultModules = loadJSONTable(DEFAULT_MODULES_TABLE)
        jsonBackgroundTable = loadJSONTable(BACKGROUND_TABLE)
        jsonRouteTable = loadJSONTable(ROUTE_TABLE)
        jsonAliasTable = loadJSONTable(ALIAS_TABLE)
    }

    fun buildForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, NAME, importance).apply {
                description = SERVICE_TEXT
            }

            notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // This is the notification for the foreground service. Maybe have it lead into other bound services
        var notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.assistant)
                .setContentTitle("Sapphire Assistant Framework")
                .setContentText("SAF is running")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

        startForeground(1337, notification)
    }

    // This is where the actual mail sorting happens
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(this.javaClass.name,"An CoreService intent was received")
        try {
            if((intent.action == ACTION_SAPPHIRE_CORE_BIND) or (intent.action == "INIT")) {
                // Do the binding
            }else if(intent.action == ACTION_SAPPHIRE_MODULE_REGISTER) {
                intent.setClassName(this,"${this.packageName}.CoreRegistrationService")
                // just forward the prior intent right along
                startService(intent)
            // This will be triggered until the stack is empty, at which point it will allow the rest of the init
            }else if(intent.action == ACTION_SAPPHIRE_CORE_REGISTRATION_COMPLETE && readyToGoSemaphore == false) {
                startBackgroundServicesConfigurable()
                // This could be the best place to do this, since it's not needed before, it's called only once
                // and it updates the configs after all modules are installed
                var configJSON = parseConfigFile(CONFIG)
                coreReadConfigJSON(configJSON)
                Log.i(this.javaClass.name, "All startup tasks finished")
                // This could be the static initializing var
                readyToGoSemaphore = true
                /*
                I need to wait for the initial install, because a env_variable is likely to be used
                in a route
                 */
            // This is not going to trigger when complete
            }else if(readyToGoSemaphore == false){
                // Don't do other stuff until it's initialized
                return super.onStartCommand(intent, flags, startId)
            }else if(intent.action == ACTION_SAPPHIRE_CORE_REQUEST_DATA){
                // I need to use the CoreService install process somehow, without duplicating code
                var queryIntent = Intent(ACTION_SAPPHIRE_CORE_REQUEST_DATA)
                var modulesWithData = packageManager.queryIntentServices(queryIntent, 0)
                Log.i(this.javaClass.name,"Query results ${modulesWithData}")
                var multiprocessRoute = "("
                // I"ll fix this later, but this will likely produce doubles, and I only have one w/ data right now
                for(dataModule in modulesWithData.take(1)) {
                    try{
                        var packageName = dataModule.serviceInfo.packageName
                        var className = dataModule.serviceInfo.name
                        // Should I check if it's registered?
                        // This is making a multiprocess route
                        multiprocessRoute+="${packageName};${className},"
                    }catch(exception: Exception){
                        continue
                    }
                }
                // Janky, but should do. I just made a (multiprocess route)
                multiprocessRoute = multiprocessRoute.subSequence(0,multiprocessRoute.length-1) as String
                multiprocessRoute+=")"
                Log.i(this.javaClass.name,"Multiprocess route: ${multiprocessRoute}")
                // Is there a reason to switch intents and not just use the original?
                var multiprocessIntent = Intent(intent).setClassName(this,"com.example.multiprocessmodule.MultiprocessService")
                Log.i(this.javaClass.name,"Tacking ${intent.getStringExtra(ROUTE)!!} on to ROUTE")
                multiprocessIntent.putExtra(ROUTE,"${multiprocessRoute},${intent.getStringExtra(ROUTE)}")
                Log.i(this.javaClass.name,"Requesting data keys ${multiprocessIntent.getStringArrayListExtra(DATA_KEYS)}" )
                startService(multiprocessIntent)
            }else {
                sortMail(intent)
            }
        }catch(exception: Exception){
            Log.e(this.javaClass.name,"Some intent error")
            Log.e(this.javaClass.name,exception.toString())
        }
        return super.onStartCommand(intent, flags, startId)
    }

    // It really just pulls the alias from Route.
    // I need to have a way to ensure it triggers from a module
    fun startBackgroundServicesConfigurable(){
        // This is to make sure the data is as upt to date as possible
        jsonBackgroundTable = loadJSONTable(BACKGROUND_TABLE)
        Log.v(this.javaClass.name,"Starting services in background service table...")
        for(recordName in jsonBackgroundTable.keys()){
            Log.v(this.javaClass.name,"Starting service ${recordName}")
            var startupRecordString = jsonBackgroundTable.getString(recordName)
            var startupRecord = JSONObject(startupRecordString)
            Log.v(this.javaClass.name,"JSONObject: ${startupRecord.toString()}")
            var startupIntent = Intent()
            startupIntent.putExtra(ROUTE, startupRecord.getString(STARTUP_ROUTE))
            Log.v(this.javaClass.name,"The route for this service is ${startupRecord.getString(STARTUP_ROUTE)}")
            startupIntent.putExtra(POSTAGE,jsonDefaultModules.toString())

            // This is ripped right out of sortMail, so I could probably make it a function
            //var routeData = getRoute(startupRecord.getString(ROUTE))
            var moduleList: List<String> = parseRoute(startupRecord.getString(STARTUP_ROUTE))
            var startingModule = moduleList.first().split(";")
            // This is ugly, but it'll do what I want
            Log.v(this.javaClass.name,"startup intent is for ${startingModule.get(0)};${startingModule.get(1)}")
            startupIntent.setClassName(startingModule.get(0),startingModule.get(1))

            // I can add in
            if(startupRecord.has("bound") and startupRecord.getBoolean("bound")) {
                Log.v(this.javaClass.name,"This service is a bound service")
                startBoundService(startupIntent)
            }else{
                Log.v(this.javaClass.name,"This service is not a bound service")
                startService(startupIntent)
            }
        }
    }

    fun stopBackgroundServices(sapphire_apps: List<Pair<String, String>>, connections: LinkedList<Pair<String, Connection>>) {
        for (connection in connections) {
            var service = connection.second
            unbindService(service)
        }
        for (info_pair: Pair<String, String> in sapphire_apps) {
            // Do I need to return a pair? Seems annoying.
            Log.i("CoreService", "launching ${info_pair.first};${info_pair.second}")
            var intent = Intent().setClassName(info_pair.first, info_pair.second)
            stopService(intent)
        }
    }

    // It's gonna work like this. Whatever is the LAST thing in the pipeline, core will read and upload pipeline data for.
    fun sortMail(intent: Intent){
        Log.v(this.javaClass.name,"sorting the incoming mail")
        var routeRequest = ""

        // It reads from the ROUTE, assuming it is the requested info! This is the only module that works different
        if(intent.hasExtra(ROUTE)){
            routeRequest = intent.getStringExtra(ROUTE)!!
            // This is just to let me know what is going on
            Log.i("PostOffice","pipelineRequest: ${routeRequest}")
        }else{
            Log.i("PostOffice","Nothing was found, sending it the default way")
            // currently, the default is to do nothing
            return
        }

        // This is *NOT* an ideal spot for this
        intent.putExtra(POSTAGE,addPostage())

        var routeData = getRoute(intent)
        Log.d(this.javaClass.name,"Route data loaded is ${routeData}")
        var route = parseRoute(routeData)
        // It's going to be the first in the pipeline, right?
        var packageClass = route.first().split(";")

        // the packageName, and the className
        intent.setClassName(packageClass.component1(),packageClass.component2())
        intent.putExtra(MESSAGE,intent.getStringExtra(MESSAGE))
        intent.putExtra(ROUTE,routeData)

        startService(intent)
    }

    fun addPostage():String{
        for(key in jsonDefaultModules.keys()){
            jsonPostageTable.put(key,jsonDefaultModules.getString(key))
        }
        Log.v(this.javaClass.name,"Postage is ${jsonPostageTable.toString()}")
        return jsonPostageTable.toString()
    }

    // THis needs to be fixed. StartBackgroundServices was sending a full route, not the name of a route
    fun getRoute(intent: Intent): String{
        var outgoingIntent = Intent(intent)

        // I need to update the incoming ROUTE with the route its supposed to take
        // This is confusing, and I don't like it
        outgoingIntent.putExtra(ROUTE,jsonRouteTable.getString(intent.getStringExtra(ROUTE)!!))
        // This could have taken the string, I think
        var checkedIntent = checkRouteForVariables(outgoingIntent)
        var routeData = checkedIntent.getStringExtra(ROUTE)!!
        return routeData
    }

    fun startBoundService(boundIntent: Intent){
        Log.i("CoreService", "binding ${boundIntent.component}")
        // This should probably append flags and the like
        var connection = Connection()

        if (packageManager.resolveService(boundIntent,0) != null) {
            bindService(boundIntent, connection, Context.BIND_AUTO_CREATE)
        } else {
            Log.e(this.javaClass.name, "PackageManager says the service doesn't exist")
        }
        // This appends it to the class variable, so I can shut it down later
        // I don't like how it's a pair
        connections.plus(Pair("${boundIntent.`package`};${boundIntent.component}",connection))
    }

    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    // The bound connection. The core attaches to each service as a client, tying them to cores lifecycle
    inner class Connection() : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(this.javaClass.name, "Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(this.javaClass.name, "Service disconnected")
        }
    }

    override fun onDestroy() {
        stopBackgroundServices(sapphire_apps, connections)
        notificationManager.cancel(1337)
        super.onDestroy()
    }
}