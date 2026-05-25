import groovy.json.JsonSlurper
import groovy.json.JsonOutput

metadata {
    definition(name: "Fusion Energy Main", namespace: "your_namespace", author: "Tim") {
        capability "Refresh"
        capability "Initialize"

        attribute "rawData", "string"
        attribute "ctList", "string"
        attribute "wattsSummary", "string"
        attribute "driverVersion", "string"
		attribute "counter","Number"
    }

    preferences {
        input("brokerUrl", "text", title: "MQTT Broker URL", defaultValue: "tcp://192.168.1.62:1883")
        input("mqttTopic", "text", title: "MQTT Topic", defaultValue: "hubitat/sem_meter/#")
        input("debugEnable", "bool", title: "Enable debug logging?", defaultValue: true)
    }
}

def getDriverVersion() {
    return "v1.0.1"  // Update this string as needed
    // only send burst of anyShown
}

def installed() {
    initialize()
    sendEvent(name: "driverVersion", value: getDriverVersion())
}

def updated() { 
    unschedule()
    disconnectMqtt()
    initialize()
    sendEvent(name: "driverVersion", value: getDriverVersion())
}

def initialize() {
    log.info "Initializing MQTT connection to ${brokerUrl} topic ${mqttTopic}"
    if (!brokerUrl || !mqttTopic) return

    connectToMqtt()
    unschedule()
    schedule("0 0 0 * * ?", midnightReset)
    runEvery1Hour(hourlyDeltaUpdate)
    runEvery5Minutes("mqttWatchdog")                /*ADDED TO RESTART */

    if (debugEnable) runIn(1500, disableDebug)
}

/* -------------------- MQTT MANAGEMENT -------------------- */

def connectToMqtt() {
    try {
        interfaces.mqtt.connect(brokerUrl, "hubitat_${device.id}", null, null)
        log.info "Connected to MQTT broker at ${brokerUrl}"
        interfaces.mqtt.subscribe(mqttTopic)
    } catch (e) {
        log.warn "MQTT connect failed: ${e.message}. Retrying in 60s."
        runIn(60, reconnectMqtt)
    }
}

def disconnectMqtt() {
    try { interfaces.mqtt.disconnect() } catch (e) { log.warn e.message }
}

def mqttClientStatus(String status) {
    if (debugEnable) log.debug "MQTT Status: ${status}"
    if (status.startsWith("Error")) runIn(30, reconnectMqtt)
}

/* old version - new below)
def reconnectMqtt() {
    disconnectMqtt()
    pauseExecution(2000)
    connectToMqtt()
}
*/

def reconnectMqtt() {
    log.warn "MQTT: Starting safe reconnect"

    // 1. Try to disconnect cleanly
    try {
        interfaces.mqtt.disconnect()
        log.info "MQTT: Disconnect OK"
    } catch(e) {
        log.warn "MQTT: Disconnect error: ${e}"
    }

    // 2. Give Hubitat time to release the socket
    pauseExecution(500)

    // 3. Attempt reconnect
    try {
        connectToMqtt()   // your existing connect logic
        log.info "MQTT: Reconnect successful"
        state.reconnectFailures = 0
    } catch(e) {
        log.error "MQTT: Reconnect FAILED: ${e}"
        state.reconnectFailures = (state.reconnectFailures ?: 0) + 1

        if(state.reconnectFailures >= 3) {
            notifyFailure()
        }
    }
}

def mqttWatchdog() {
    if(!state.lastMsg) {
        // No messages yet — nothing to check
        return
    }

    long age = now() - state.lastMsg
    long threshold = 1000 * 60 * 5  // 5 minutes of silence

    if(age > threshold) {
        log.warn "MQTT watchdog: No messages for ${age/1000} seconds. Attempting reconnect."
        reconnectMqtt()
    }
}

def notifyFailure() {
    def msg = "MQTT reconnect failed after 3 attempts on ${device.displayName}"
    log.error msg
}


/* -------------------- MQTT PARSE -------------------- */
import groovy.json.JsonSlurper

def safeDecode(String input) {
    try {
        return new String(input.decodeBase64(), "UTF-8")
    } catch (Exception e) {
        return input  // fallback to plain text
    }
}

def parse(String message) {
    if (!message) {
        log.warn "Message false -exiting parse immediately"
        return
    } else {
        log.info "Message received is ${message}"
    }
           
    /* TRACK LAST MSG TIMESTAMP */
    state.lastMsg = now()
    
    def rawTopic = null
    def rawPayload = null

    if (message.contains("topic:") && message.contains("payload:")) {
        def topicStart = message.indexOf("topic:")
        def payloadStart = message.indexOf("payload:")
        rawTopic = message.substring(topicStart + 6, payloadStart).trim()
        rawPayload = message.substring(payloadStart + 8).trim()
    } else {
        log.warn "Message format unexpected: ${message}"
        return
    }

    def decodedTopic = safeDecode(rawTopic)
    def decodedPayload = safeDecode(rawPayload)

    if (debugEnable) {
 //       log.debug "Decoded topic: ${decodedTopic}"
        log.debug "Decoded payload: ${decodedPayload}"
    }

    def json = null
    try {
        json = new JsonSlurper().parseText(decodedPayload)
    } catch (Exception e) {
        log.warn "JSON parsing failed: ${e.message}"
        return
    }

    def ctName = json.ct?.toString()
    def power  = (json.power instanceof Number) ? json.power as BigDecimal : null
    def energy = (json.energy_in instanceof Number) ? json.energy_in as BigDecimal : null
    def counter = (json.counter instanceof Number) ? json.counter as BigDecimal : null
//    if (!ctName || power == null || energy == null || counter == null) {
//        log.warn "Issue with ctname,power,energy or counter"
//		return
//	}
    if (!ctName) {
        log.warn "Issue with ctname"
		return
    } else {
        if (power == null) {
            log.warn "Issue with power for ${ctName}"
        }
        if (energy == null ) {
            log.warn "Issue with energy for ${ctName}"
        }
        if (counter == null ) {
            log.warn "Issue with counter for ${ctName}"
        }
    }
    
    def nowMs = now()
    state.lastUpdateMap = state.lastUpdateMap ?: [:]
    def lastCtUpdate = state.lastUpdateMap[ctName] ?: 0
    if (nowMs - lastCtUpdate < 1000) {
        log.warn "Skipping ${ctName} update — too soon after last (${nowMs - lastCtUpdate} ms)"
        return
    }
    state.lastUpdateMap[ctName] = nowMs

    def safeDNI   = "fusion_ct_" + ctName.replaceAll(/[^A-Za-z0-9_]/, "_")
    def safeLabel = "CT " + ctName.replaceAll(/[^A-Za-z0-9_ ]/, "_")

    def child = getChildDevice(safeDNI)

    if (!child) {
        try {
            log.debug "Trying to create child: ${safeDNI}"
            child = addChildDevice(
                "your_namespace",
                "Fusion CT Child",
                safeDNI,
                [label: safeLabel,isComponent:false]
            )
            if (child) {
                def childIndex = getChildDevices().size()
                log.debug "✅ Created the ${childIndex}${ordinalSuffix(childIndex)} child device: ${safeLabel} (${safeDNI})"
            } else {
                log.warn "❌ addChildDevice returned null for ${safeDNI}"
            }

        } catch (Exception e) {
            log.warn "Child already exists or creation failed for ${safeDNI}: ${e.message}"
            child = getChildDevice(safeDNI)  // fallback in case it was just slow to register
            if (!child) return  // skip update if still not found
        }
    }
 
    if (child.hasCommand("updated")) {
        child.updateSetting("debugEnable", [value: debugEnable, type: "bool"])
    }

    child.updatePower(power)
    child.updateEnergy(energy)
    child.updateCounter(counter)


    def ctList = state.ctList ?: []
    if (!(ctName in ctList)) {
        ctList << ctName
        state.ctList = ctList
        sendEvent(name: "ctList", value: JsonOutput.toJson(ctList))
    }

    state.ctLastUpdated = state.ctLastUpdated ?: [:]
    state.ctLastUpdated[ctName] = now()

    updateWattsSummary()
    updateBurstSummary()
}
/* -------------------- WATTS SUMMARY -------------------- */

def updateWattsSummary() {
    def summary = "<b>Watts by Circuit</b><br>"
    def ctList = state.ctList ?: []
    def nowMs = now()

    // Collect CTs with power for sorting
    def ctPowerList = ctList.collect { ctName ->
        def safeDNI = "fusion_ct_" + ctName.replaceAll(/[^A-Za-z0-9_]/, "_")
        def child = getChildDevice(safeDNI)
        def power  = (child?.currentValue("power") != null) ? child.currentValue("power") : 0
        def energy = (child?.currentValue("energy") != null) ? child.currentValue("energy") : 0
        def burst = (child?.currentValue("burstCounter") != null) ? child.currentValue("burstCounter") : 0
        [ctName: ctName, power: power, energy: energy]
    }

    // Sort descending by power
    ctPowerList.sort { -it.power }

    // Build summary table
    ctPowerList.each { entry ->
        def ctName = entry.ctName
        def power  = entry.power
        def energy = entry.energy
        def lastTs = state.ctLastUpdated?.get(ctName) ?: 0
        def stale = (nowMs - lastTs) > 300000
        def displayName = stale ? "${ctName} ⚠️" : ctName

        summary += "${displayName}: ${String.format('%.1f', power.toDouble())} W / ${String.format('%.2f', energy.toDouble())} kWh<br>"
    }

    sendEvent(name: "wattsSummary", value: summary)
}

/* -------------------- BURST SUMMARY -------------------- */


/* -------------------- BURST SUMMARY (skips Main & ProjectMeter, only > 4) -------------------- */

def updateBurstSummary() {
    def summary = "<b>Burst Counter by Circuit</b><br>"
    def ctList = state.ctList ?: []
    def nowMs = now()

    // Exact-name skips (case-insensitive version shown below in "Optional tweaks")
    def skipNames = ["Main", "Main2","ProjectedMeter"] as Set

    // Build entries safely
    def ctBurstList = ctList.collect { ctName ->
        // Skip unwanted names early
        if (skipNames.contains(ctName)) return null

        // Use a plain string pattern to avoid regex literal issues
        def safeDNI = "fusion_ct_" + ctName.replaceAll("[^A-Za-z0-9_]", "_")
        def child = getChildDevice(safeDNI)

        // Read raw attributes (default to 0 if missing)
        def rawBurst  = (child?.currentValue("burstCounter") != null) ? child.currentValue("burstCounter") : 0
        def rawPower  = (child?.currentValue("power")        != null) ? child.currentValue("power")        : 0
        def rawEnergy = (child?.currentValue("energy")       != null) ? child.currentValue("energy")       : 0

        // Normalize to numbers safely (avoid complex expressions inside map literals)
        def burstVal  = rawBurst?.toString()?.isNumber()  ? rawBurst.toString().toBigDecimal()  : 0G
        def powerVal  = rawPower?.toString()?.isNumber()  ? rawPower.toString().toBigDecimal()  : 0G
        def energyVal = rawEnergy?.toString()?.isNumber() ? rawEnergy.toString().toBigDecimal() : 0G

        // Return a simple map
        [
            ctName: ctName,
            burst : burstVal,
            power : powerVal,
            energy: energyVal
        ]
    }.findAll { it != null }  // drop skipped entries

    // Sort descending by burst
    ctBurstList.sort { -it.burst }

    def anyShown  = false
    def threshold = 4  // only show if burstCounter > 4

    // Build summary rows
    ctBurstList.each { entry ->
        if (entry.burst > threshold) {
            def ctName = entry.ctName

            // Stale marker based on last update timestamp (5 minutes)
            def lastTs = state.ctLastUpdated?.get(ctName) ?: 0
            def stale  = (nowMs - lastTs) > 300000
            def displayName = stale ? "${ctName} [STALE]" : ctName   // use ASCII to avoid emoji parser issues

            // Precompute formatted values to keep GStrings simple
            def burstCount = (entry.burst as long)
            def powerW     = String.format('%.1f', entry.power.toDouble())
            def energyKWh  = String.format('%.2f', entry.energy.toDouble())

            summary += "${displayName}: ${burstCount} bursts / ${powerW} W / ${energyKWh} kWh<br>"
            anyShown = true
        }
    }

    // Optional: message when no circuits meet the threshold
    //if (!anyShown) {
    //    summary += "<i>No circuits exceeded burst count > 4</i><br>"
     //  }

    if (anyShown) {
        // Publish to attribute for dashboard/tile use
        sendEvent(name: "burstSummary", value: summary)
    }
}

/* -------------------- SUPPORT -------------------- */

def refresh() { if (debugEnable) log.debug "Refresh called" }
def midnightReset() { }
def hourlyDeltaUpdate() { }
def disableDebug() {
    device.updateSetting("debugEnable", [value: false, type: "bool"])
    log.info "Parent debug logging disabled"
}

String ordinalSuffix(int number) {
    if (number % 100 in [11, 12, 13]) return "th"
    switch (number % 10) {
        case 1: return "st"
        case 2: return "nd"
        case 3: return "rd"
        default: return "th"
    }
}
